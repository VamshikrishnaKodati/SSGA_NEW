package SSGA_Backend.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;

/**
 * Role and permission administration: the permission matrix, per role.
 *
 * <p>Two invariants are enforced here. The System Administrator role is fixed,
 * because editing it is the fastest way to remove the ability to administer the
 * system at all. And a role cannot be granted a permission that does not exist
 * in the catalogue, so the matrix cannot drift from the API's authority checks.
 */
@Service
public class AdminRoleService {

    private static final String SYSTEM_ROLE = "SYS_ADMIN";

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public AdminRoleService(JdbcTemplate jdbcTemplate, RlsContext rlsContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(readOnly = true)
    public List<AdminDtos.RoleResponse> list() {
        return jdbcTemplate.query(
                """
                SELECT r.id, r.code, r.name, r.max_scope, r.is_active,
                       COALESCE(
                           (SELECT string_agg(p.code, ',' ORDER BY p.code)
                              FROM public.role_permissions rp
                              JOIN public.permissions p ON p.id = rp.permission_id
                             WHERE rp.role_id = r.id),
                           ''
                       ) AS permissions
                  FROM public.roles r
                 WHERE r.is_active
                 ORDER BY r.code
                """,
                (rs, rowNum) -> new AdminDtos.RoleResponse(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("max_scope"),
                        rs.getBoolean("is_active"),
                        SYSTEM_ROLE.equals(rs.getString("code")),
                        splitPermissions(rs.getString("permissions"))
                )
        );
    }

    @Transactional(readOnly = true)
    public List<AdminDtos.ModuleResponse> permissionCatalogue() {
        List<AdminDtos.PermissionResponse> permissions = jdbcTemplate.query(
                """
                SELECT p.id, p.code, p.module, p.description,
                       COALESCE(
                           (SELECT string_agg(r.code, ',' ORDER BY r.code)
                              FROM public.role_permissions rp
                              JOIN public.roles r ON r.id = rp.role_id
                             WHERE rp.permission_id = p.id
                               AND r.is_active),
                           ''
                       ) AS roles
                   FROM public.permissions p
                  ORDER BY p.module, p.code
                """,
                (rs, rowNum) -> new AdminDtos.PermissionResponse(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("module"),
                        rs.getString("description"),
                        splitPermissions(rs.getString("roles"))
                )
        );

        Map<String, List<AdminDtos.PermissionResponse>> byModule = new LinkedHashMap<>();

        for (AdminDtos.PermissionResponse permission : permissions) {
            byModule.computeIfAbsent(permission.module(), key -> new ArrayList<>())
                    .add(permission);
        }

        List<AdminDtos.ModuleResponse> modules = new ArrayList<>();

        for (Map.Entry<String, List<AdminDtos.PermissionResponse>> entry : byModule.entrySet()) {
            modules.add(new AdminDtos.ModuleResponse(entry.getKey(), entry.getValue()));
        }

        return modules;
    }

    @Transactional
    public AdminDtos.RoleResponse replacePermissions(long roleId, List<String> requested) {
        String code = jdbcTemplate.query(
                "SELECT code FROM public.roles WHERE id = ? AND is_active",
                (rs, rowNum) -> rs.getString("code"),
                roleId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "role not found"
        ));

        if (SYSTEM_ROLE.equals(code)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The System Administrator role cannot be modified; assign another "
                            + "role instead"
            );
        }

        Set<String> codes = new LinkedHashSet<>(requested == null ? List.of() : requested);

        if (codes.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "a role must keep at least one permission, or it can no longer be used"
            );
        }

        List<Long> permissionIds = jdbcTemplate.query(
                """
                SELECT id FROM public.permissions
                 WHERE code = ANY (?)
                 ORDER BY code
                """,
                (rs, rowNum) -> rs.getLong("id"),
                (Object) codes.toArray(new String[0])
        );

        if (permissionIds.size() != codes.size()) {
            Set<String> known = new LinkedHashSet<>(jdbcTemplate.queryForList(
                    "SELECT code FROM public.permissions", String.class
            ));

            List<String> unknown = new ArrayList<>(codes);
            unknown.removeAll(known);

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "unknown permission: " + String.join(", ", unknown)
            );
        }

        List<Object[]> rows = new ArrayList<>();

        for (Long permissionId : permissionIds) {
            rows.add(new Object[]{roleId, permissionId});
        }

        jdbcTemplate.update("DELETE FROM public.role_permissions WHERE role_id = ?", roleId);
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO public.role_permissions (role_id, permission_id)
                VALUES (?, ?)
                ON CONFLICT (role_id, permission_id) DO NOTHING
                """,
                rows
        );

        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    'ROLE_PERMISSION_UPDATE', 'ROLE', ?::varchar,
                    NULL::bigint, NULL::bigint,
                    NULL::jsonb,
                    ?::jsonb,
                    NULL::text, NULL::uuid
                )
                """,
                Long.class,
                String.valueOf(roleId),
                "{\"permissions\": " + jsonArray(codes) + "}"
        );

        return list().stream()
                .filter(role -> role.id() == roleId)
                .findFirst()
                .orElseThrow();
    }

    @Transactional(readOnly = true)
    public AdminDtos.ScopeCatalogue scopeCatalogue() {
        rlsContext.setCurrentUser();

        List<AdminDtos.ScopeOption> wings = jdbcTemplate.query(
                """
                SELECT id, code, name
                  FROM public.wings
                 WHERE status = 'ACTIVE'
                 ORDER BY code
                """,
                (rs, rowNum) -> new AdminDtos.ScopeOption(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        null,
                        null
                )
        );

        List<AdminDtos.ScopeOption> divisions = jdbcTemplate.query(
                """
                SELECT d.id, d.code, d.name, d.wing_id, w.code AS wing_code
                  FROM public.divisions d
                  JOIN public.wings w ON w.id = d.wing_id
                 WHERE d.status = 'ACTIVE'
                 ORDER BY w.code, d.code
                """,
                (rs, rowNum) -> new AdminDtos.ScopeOption(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        (Long) rs.getObject("wing_id"),
                        rs.getString("wing_code")
                )
        );

        return new AdminDtos.ScopeCatalogue(wings, divisions);
    }

    private static List<String> splitPermissions(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return List.of(value.split(","));
    }

    private static String jsonArray(Set<String> values) {
        List<String> parts = new ArrayList<>();

        for (String value : values) {
            parts.add('"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
        }

        return "[" + String.join(", ", parts) + "]";
    }
}