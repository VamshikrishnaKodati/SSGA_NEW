package SSGA_Backend.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;

/**
 * User administration: create, edit, deactivate, and assign roles and
 * organizational scope.
 * <p>Three rules are enforced here rather than left to the caller, because each
 * one is a way to escape the permission model:
 * <ul>
 *   <li>a user may never edit their own assignments, so nobody grants
 *       themselves privileges;</li>
 *   <li>the last active System Administrator cannot be demoted or deactivated,
 *       so the system cannot be locked out of administration;</li>
 *   <li>a caller may only assign a role whose scope they could hold themselves
 *       at the target scope, so a division administrator cannot hand out an
 *       organization-wide role.</li>
 * </ul>
 */
@Service
public class AdminUserService {

    private static final String SYSTEM_ROLE = "SYS_ADMIN";

    private static final int MAX_USERNAME = 60;
    private static final int MAX_DISPLAY_NAME = 120;
    private static final int MAX_EMAIL = 160;
    private static final int MAX_EMPLOYEE_CODE = 40;
    private static final int MIN_PASSWORD = 10;

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public AdminUserService(JdbcTemplate jdbcTemplate, RlsContext rlsContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminDtos.UserPageResponse list(
            String query,
            String roleCode,
            Boolean active,
            int limit,
            int offset
    ) {
        rlsContext.setCurrentUser();
        if (limit < 1 || limit > 200) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "limit must be between 1 and 200"
            );
        }
        if (offset < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "offset must not be negative"
            );
        }

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");

        String normalizedQuery = trimToNull(query);
        if (normalizedQuery != null) {
            where.append(" AND (u.username ILIKE ? OR u.display_name ILIKE ?)");
            args.add("%" + normalizedQuery + "%");
            args.add("%" + normalizedQuery + "%");
        }

        String normalizedRole = trimToNull(roleCode);
        if (normalizedRole != null) {
            where.append(
                    " AND EXISTS (SELECT 1 FROM public.user_role_scopes s"
                            + " JOIN public.roles r ON r.id = s.role_id"
                            + " WHERE s.user_id = u.id AND s.is_active AND r.code = ?)"
            );
            args.add(normalizedRole);
        }

        if (active != null) {
            where.append(" AND u.is_active = ?");
            args.add(active);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.users u" + where,
                Long.class,
                args.toArray()
        );

        if (total == null || total == 0) {
            return new AdminDtos.UserPageResponse(List.of(), 0);
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);

        List<Long> ids = jdbcTemplate.query(
                "SELECT u.id FROM public.users u" + where
                        + " ORDER BY u.username LIMIT ? OFFSET ?",
                (rs, rowNum) -> rs.getLong("id"),
                pageArgs.toArray()
        );

        if (ids.isEmpty()) {
            return new AdminDtos.UserPageResponse(List.of(), total);
        }

        // Every lookup below is one statement for the whole page, so the query
        // count no longer grows with the number of users returned. It was three
        // statements per user before, which is 150 for a full page of 50.
        String idsClause = inPlaceholders(ids.size());
        Object[] idArgs = ids.toArray();

        Map<Long, UserRow> rows = new LinkedHashMap<>();

        jdbcTemplate.query(
                """
                SELECT u.id, u.username, u.display_name, u.email, u.employee_code,
                       u.is_active, u.must_change_password, u.mfa_enabled
                  FROM public.users u
                 WHERE u.id IN (""" + idsClause + ")",
                (rs, rowNum) -> {
                    rows.put(rs.getLong("id"), new UserRow(
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("email"),
                            rs.getString("employee_code"),
                            rs.getBoolean("is_active"),
                            rs.getBoolean("must_change_password"),
                            rs.getBoolean("mfa_enabled")
                    ));

                    return null;
                },
                idArgs
        );

        Map<Long, List<AdminDtos.Assignment>> assignments = new HashMap<>();

        jdbcTemplate.query(
                """
                SELECT s.user_id, s.id, r.code, r.name, r.max_scope,
                       s.wing_id, w.code AS wing_code, s.division_id, d.code AS division_code
                  FROM public.user_role_scopes s
                  JOIN public.roles r ON r.id = s.role_id
                  LEFT JOIN public.wings w ON w.id = s.wing_id
                  LEFT JOIN public.divisions d ON d.id = s.division_id
                 WHERE s.user_id IN (""" + idsClause + ") AND s.is_active"
                        + " ORDER BY r.code",
                (rs, rowNum) -> {
                    assignments
                            .computeIfAbsent(rs.getLong("user_id"), key -> new ArrayList<>())
                            .add(new AdminDtos.Assignment(
                                    rs.getLong("id"),
                                    rs.getString("code"),
                                    rs.getString("name"),
                                    rs.getString("max_scope"),
                                    (Long) rs.getObject("wing_id"),
                                    rs.getString("wing_code"),
                                    (Long) rs.getObject("division_id"),
                                    rs.getString("division_code")
                            ));

                    return null;
                },
                idArgs
        );

        Map<Long, List<String>> authorities = new HashMap<>();

        jdbcTemplate.query(
                """
                SELECT u.id, x.authority
                  FROM public.users u
                  CROSS JOIN LATERAL app.find_user_authorities(u.id) x
                 WHERE u.id IN (""" + idsClause + ") ORDER BY u.id, x.authority",
                (rs, rowNum) -> {
                    authorities
                            .computeIfAbsent(rs.getLong("id"), key -> new ArrayList<>())
                            .add(rs.getString("authority"));

                    return null;
                },
                idArgs
        );

        Map<Long, List<AdminDtos.PermissionOverride>> overrides = new HashMap<>();

        jdbcTemplate.query(
                """
                SELECT up.user_id, up.permission_id, p.code, up.granted, up.reason
                  FROM public.user_permissions up
                  JOIN public.permissions p ON p.id = up.permission_id
                 WHERE up.user_id IN (""" + idsClause + ") ORDER BY up.user_id, p.code",
                (rs, rowNum) -> {
                    overrides
                            .computeIfAbsent(rs.getLong("user_id"), key -> new ArrayList<>())
                            .add(new AdminDtos.PermissionOverride(
                                    rs.getLong("permission_id"),
                                    rs.getString("code"),
                                    rs.getBoolean("granted"),
                                    rs.getString("reason")
                            ));

                    return null;
                },
                idArgs
        );

        List<AdminDtos.UserResponse> users = new ArrayList<>(ids.size());

        for (Long id : ids) {
            UserRow row = rows.get(id);

            if (row == null) {
                continue;
            }

            List<AdminDtos.Assignment> userAssignments =
                    assignments.getOrDefault(id, List.of());

            List<String> effective = authorities
                    .getOrDefault(id, List.of())
                    .stream()
                    .filter(code -> !code.startsWith("ROLE_"))
                    .toList();

            users.add(new AdminDtos.UserResponse(
                    id,
                    row.username(),
                    row.displayName(),
                    row.email(),
                    row.employeeCode(),
                    row.active(),
                    row.mustChangePassword(),
                    row.mfaEnabled(),
                    userAssignments.stream().anyMatch(a -> SYSTEM_ROLE.equals(a.roleCode())),
                    userAssignments,
                    effective,
                    overrides.getOrDefault(id, List.of())
            ));
        }

        return new AdminDtos.UserPageResponse(users, total);
    }

    @Transactional(readOnly = true)
    public AdminDtos.UserResponse get(long userId) {
        return load(userId);
    }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    @Transactional
    public AdminDtos.UserResponse create(AdminDtos.UserCreateRequest request) {
        String username = requireText(request.username(), "username", MAX_USERNAME);
        String displayName = requireText(request.displayName(), "displayName", MAX_DISPLAY_NAME);
        String password = requireText(request.password(), "password", null);

        if (password.length() < MIN_PASSWORD) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "password must be at least " + MIN_PASSWORD + " characters"
            );
        }

        username = username.toLowerCase();

        if (existsByUsername(username)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "username already exists"
            );
        }

        List<AdminDtos.UserScope> scopes = requireScopes(request.scopes());

        long actorId = currentUserId();
        assertMayAssign(actorId, scopes, null);

        Long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO public.users (
                    username, display_name, email, employee_code,
                    auth_provider, password_hash, must_change_password, is_active
                )
                VALUES (
                    ?, ?, ?, ?, 'LOCAL',
                    '{bcrypt}' || crypt(?, gen_salt('bf', 10)),
                    true, true
                )
                RETURNING id
                """,
                Long.class,
                username,
                displayName,
                trimToNull(request.email()),
                trimToNull(request.employeeCode()),
                password
        );

        replaceScopes(userId, scopes, actorId);

        audit(
                "USER_CREATE",
                userId,
                null,
                "{\"username\": " + quote(username)
                        + ", \"display_name\": " + quote(displayName)
                        + ", \"role_count\": " + scopes.size() + "}"
        );

        return load(userId);
    }

    @Transactional
    public AdminDtos.UserResponse update(long userId, AdminDtos.UserUpdateRequest request) {
        AdminDtos.UserResponse before = load(userId);

        String displayName = requireText(
                request.displayName(), "displayName", MAX_DISPLAY_NAME
        );

        jdbcTemplate.update(
                """
                UPDATE public.users
                   SET display_name = ?,
                       email = ?,
                       employee_code = ?,
                       updated_at = now()
                 WHERE id = ?
                """,
                displayName,
                trimToNull(request.email()),
                trimToNull(request.employeeCode()),
                userId
        );

        audit(
                "USER_UPDATE",
                userId,
                null,
                "{\"before\": " + jsonOf(before.displayName(), before.email(), before.employeeCode())
                        + ", \"after\": " + jsonOf(displayName, request.email(), request.employeeCode())
                        + "}"
        );

        return load(userId);
    }

    @Transactional
    public AdminDtos.UserResponse replaceScopes(long userId, List<AdminDtos.UserScope> scopes) {
        return replaceScopes(userId, scopes, currentUserId());
    }

    private AdminDtos.UserResponse replaceScopes(
            long userId,
            List<AdminDtos.UserScope> requested,
            long actorId
    ) {
        AdminDtos.UserResponse before = load(userId);

        List<AdminDtos.UserScope> scopes = requireScopes(requested);

        if (userId == actorId) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You cannot change your own roles or scope; ask another administrator"
            );
        }

        assertMayAssign(actorId, scopes, userId);
        assertNotLastSystemAdministrator(userId, scopes);

        jdbcTemplate.update(
                "DELETE FROM public.user_role_scopes WHERE user_id = ?",
                userId
        );

        if (!scopes.isEmpty()) {
            List<Object[]> rows = new ArrayList<>();

            for (AdminDtos.UserScope scope : scopes) {
                Long wingId = null;
                Long divisionId = null;

                if (scope.divisionId() != null) {
                    divisionId = requireDivision(scope.divisionId());
                    wingId = jdbcTemplate.queryForObject(
                            "SELECT wing_id FROM public.divisions WHERE id = ?",
                            Long.class,
                            divisionId
                    );
                } else if (scope.wingId() != null) {
                    wingId = requireWing(scope.wingId());
                } else if (!isOrgScoped(scope.roleId())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "A wing or division role needs a wing or division scope"
                    );
                }

                rows.add(new Object[]{
                        userId,
                        scope.roleId(),
                        wingId,
                        divisionId,
                        java.time.LocalDate.now(),
                        true,
                        actorId
                });
            }

            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO public.user_role_scopes (
                        user_id, role_id, wing_id, division_id,
                        valid_from, is_active, granted_by
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    rows
            );
        }

        audit(
                "USER_SCOPE_ASSIGN",
                userId,
                null,
                "{\"before\": " + jsonOfAssignments(before.assignments())
                        + ", \"after\": " + jsonOfScopeIds(scopes) + "}"
        );

        return load(userId);
    }

    @Transactional
    public AdminDtos.UserResponse setActive(long userId, boolean active) {
        AdminDtos.UserResponse before = load(userId);

        if (userId == currentUserId()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "You cannot deactivate your own account"
            );
        }

        if (!active) {
            assertNotLastSystemAdministrator(userId, List.of());
        }

        jdbcTemplate.update(
                "UPDATE public.users SET is_active = ?, updated_at = now() WHERE id = ?",
                active, userId
        );

        audit(
                active ? "USER_ACTIVATE" : "USER_DEACTIVATE",
                userId,
                null,
                "{\"active\": " + active + "}"
        );

        return load(userId);
    }

    @Transactional
    public void resetPassword(long userId, String password) {
        String value = requireText(password, "password", null);

        if (value.length() < MIN_PASSWORD) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "password must be at least " + MIN_PASSWORD + " characters"
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE public.users
                   SET password_hash = '{bcrypt}' || crypt(?, gen_salt('bf', 10)),
                       must_change_password = true,
                       failed_attempts = 0,
                       locked_until = NULL,
                       updated_at = now()
                 WHERE id = ?
                """,
                value, userId
        );

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }

        // The password itself is never written to the audit trail.
        audit("USER_PASSWORD_RESET", userId, null, null);
    }

    /** Grants or removes a single permission for one user, with a reason. */
    @Transactional
    public AdminDtos.UserResponse setOverride(long userId, AdminDtos.OverrideRequest request) {
        AdminDtos.UserResponse before = load(userId);

        if (userId == currentUserId()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You cannot change your own permissions"
            );
        }

        if (!request.granted() && trimToNull(request.reason()) == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "a permission removal needs a reason"
            );
        }

        jdbcTemplate.update(
                """
                INSERT INTO public.user_permissions (
                    user_id, permission_id, granted, reason, granted_by
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, permission_id) DO UPDATE
                   SET granted = EXCLUDED.granted,
                       reason = EXCLUDED.reason,
                       granted_by = EXCLUDED.granted_by
                """,
                userId,
                requirePermission(request.permissionId()),
                request.granted(),
                trimToNull(request.reason()),
                currentUserId()
        );

        audit(
                "USER_PERMISSION_OVERRIDE",
                userId,
                null,
                "{\"before\": " + jsonOfPermissions(before.effectivePermissions())
                        + ", \"after\": "
                        + quote(request.granted() ? "granted" : "denied") + "}"
        );

        return load(userId);
    }

    // -----------------------------------------------------------------------
    // Guards
    // -----------------------------------------------------------------------

    /**
     * A caller may not hand out a scope they could not hold themselves. A
     * division administrator cannot create an organization-wide administrator.
     */
    private void assertMayAssign(
            long actorId,
            List<AdminDtos.UserScope> scopes,
            Long targetUserId
    ) {
        if (isSystemAdministrator(actorId)) {
            return;
        }

        for (AdminDtos.UserScope scope : scopes) {
            if (isSystemRole(scope.roleId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Only a System Administrator may assign the System Administrator role"
                );
            }

            String maxScope = maxScopeOf(scope.roleId());

            if (maxScope == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "unknown role"
                );
            }

            if ("ORG".equals(maxScope)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Only a System Administrator may assign an organization-wide role"
                );
            }

            if ("WING".equals(maxScope) && !canAssignWing(actorId, scope.wingId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "You may only assign roles inside a wing you administer"
                );
            }

            if ("DIVISION".equals(maxScope) && scope.divisionId() != null
                    && !canAccessDivision(actorId, scope.divisionId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "You may only assign roles inside a division you can access"
                );
            }
        }

        if (targetUserId != null && targetUserId == actorId) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "You cannot change your own assignments"
            );
        }
    }

    /** Refuses to remove or deactivate the final active System Administrator. */
    private void assertNotLastSystemAdministrator(long userId, List<AdminDtos.UserScope> scopes) {
        boolean keepsSystemRole = scopes.stream().anyMatch(scope -> isSystemRole(scope.roleId()));

        if (!keepsSystemRole) {
            boolean wasSystemAdministrator = jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                          FROM public.user_role_scopes s
                          JOIN public.roles r ON r.id = s.role_id
                         WHERE s.user_id = ?
                           AND s.is_active
                           AND r.code = ?
                    )
                    """,
                    Boolean.class,
                    userId,
                    SYSTEM_ROLE
            );

            if (!Boolean.TRUE.equals(wasSystemAdministrator)) {
                return;
            }
        }

        long remaining = countActiveSystemAdministrators(userId);

        if (remaining <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This is the last active System Administrator; assign the role to "
                            + "another account first"
            );
        }
    }

    private long countActiveSystemAdministrators(long excludeUserId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(DISTINCT u.id)
                  FROM public.users u
                  JOIN public.user_role_scopes s ON s.user_id = u.id
                  JOIN public.roles r ON r.id = s.role_id
                 WHERE u.is_active
                   AND s.is_active
                   AND r.code = ?
                   AND u.id <> ?
                """,
                Long.class,
                SYSTEM_ROLE,
                excludeUserId
        );

        return count == null ? 0 : count;
    }

    // -----------------------------------------------------------------------
    // Lookups
    // -----------------------------------------------------------------------

    private AdminDtos.UserResponse load(long userId) {
        rlsContext.setCurrentUser();
        try {
            List<AdminDtos.Assignment> assignments = jdbcTemplate.query(
                    """
                    SELECT s.id, r.code, r.name, r.max_scope,
                           s.wing_id, w.code AS wing_code, s.division_id, d.code AS division_code
                      FROM public.user_role_scopes s
                      JOIN public.roles r ON r.id = s.role_id
                      LEFT JOIN public.wings w ON w.id = s.wing_id
                      LEFT JOIN public.divisions d ON d.id = s.division_id
                     WHERE s.user_id = ?
                       AND s.is_active
                     ORDER BY r.code
                    """,
                    (rs, rowNum) -> new AdminDtos.Assignment(
                            rs.getLong("id"),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("max_scope"),
                            (Long) rs.getObject("wing_id"),
                            rs.getString("wing_code"),
                            (Long) rs.getObject("division_id"),
                            rs.getString("division_code")
                    ),
                    userId
            );

            return jdbcTemplate.queryForObject(
                    """
                    SELECT u.id, u.username, u.display_name, u.email, u.employee_code,
                           u.is_active, u.must_change_password, u.mfa_enabled
                      FROM public.users u
                     WHERE u.id = ?
                    """,
                    (rs, rowNum) -> new AdminDtos.UserResponse(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("email"),
                            rs.getString("employee_code"),
                            rs.getBoolean("is_active"),
                            rs.getBoolean("must_change_password"),
                            rs.getBoolean("mfa_enabled"),
                            assignments.stream()
                                    .anyMatch(a -> SYSTEM_ROLE.equals(a.roleCode())),
                            assignments,
                            effectivePermissions(rs.getLong("id")),
                            overrides(rs.getLong("id"))
                    ),
                    userId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
    }

    private List<String> effectivePermissions(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT authority FROM app.find_user_authorities(?) ORDER BY authority",
                String.class,
                userId
        ).stream().filter(code -> !code.startsWith("ROLE_")).toList();
    }

    private List<AdminDtos.PermissionOverride> overrides(long userId) {
        return jdbcTemplate.query(
                """
                SELECT up.permission_id, p.code, up.granted, up.reason
                  FROM public.user_permissions up
                  JOIN public.permissions p ON p.id = up.permission_id
                 WHERE up.user_id = ?
                 ORDER BY p.code
                """,
                (rs, rowNum) -> new AdminDtos.PermissionOverride(
                        rs.getLong("permission_id"),
                        rs.getString("code"),
                        rs.getBoolean("granted"),
                        rs.getString("reason")
                ),
                userId
        );
    }

    private boolean existsByUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.users WHERE username = ?",
                Long.class,
                username
        );

        return count != null && count > 0;
    }

    private boolean isSystemAdministrator(long userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM public.user_role_scopes s
                      JOIN public.roles r ON r.id = s.role_id
                     WHERE s.user_id = ?
                       AND s.is_active
                       AND r.code = ?
                )
                """,
                Boolean.class,
                userId,
                SYSTEM_ROLE
        ));
    }

    private boolean isSystemRole(long roleId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM public.roles WHERE id = ? AND code = ?)",
                Boolean.class,
                roleId,
                SYSTEM_ROLE
        ));
    }

    private boolean isOrgScoped(long roleId) {
        String maxScope = maxScopeOf(roleId);
        return maxScope != null && "ORG".equals(maxScope);
    }

    private String maxScopeOf(long roleId) {
        return jdbcTemplate.query(
                "SELECT max_scope FROM public.roles WHERE id = ?",
                (rs, rowNum) -> rs.getString("max_scope"),
                roleId
        ).stream().findFirst().orElse(null);
    }

    private Long requireDivision(long divisionId) {
        return jdbcTemplate.query(
                "SELECT id FROM public.divisions WHERE id = ?",
                (rs, rowNum) -> rs.getLong("id"),
                divisionId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unknown division"
        ));
    }

    private Long requireWing(long wingId) {
        return jdbcTemplate.query(
                "SELECT id FROM public.wings WHERE id = ?",
                (rs, rowNum) -> rs.getLong("id"),
                wingId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unknown wing"
        ));
    }

    private long requirePermission(long permissionId) {
        return jdbcTemplate.query(
                "SELECT id FROM public.permissions WHERE id = ?",
                (rs, rowNum) -> rs.getLong("id"),
                permissionId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unknown permission"
        ));
    }

    /**
     * A wing-scoped caller may assign a wing only inside their own wing, and a
     * division-scoped caller only inside a division they can reach. Both are
     * resolved through the same app.can_access_* functions row level security
     * uses, so the admin path cannot be wider than the data path.
     */
    private boolean canAssignWing(long actorId, Long wingId) {
        if (wingId == null) {
            return false;
        }

        Boolean allowed = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM public.user_role_scopes s
                      JOIN public.roles r ON r.id = s.role_id
                     WHERE s.user_id = ?
                       AND s.is_active
                       AND (
                            (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
                         OR (r.max_scope = 'WING' AND s.wing_id = ?)
                         OR (r.max_scope = 'DIVISION'
                             AND s.division_id IN (
                                 SELECT id FROM public.divisions WHERE wing_id = ?
                             ))
                       )
                )
                """,
                Boolean.class,
                actorId,
                wingId,
                wingId
        );

        return Boolean.TRUE.equals(allowed);
    }

    private boolean canAccessDivision(long actorId, long divisionId) {
        Boolean allowed = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM public.user_role_scopes s
                      JOIN public.roles r ON r.id = s.role_id
                     WHERE s.user_id = ?
                       AND s.is_active
                       AND (
                            (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
                         OR (r.max_scope = 'WING' AND s.wing_id = (
                                SELECT wing_id FROM public.divisions WHERE id = ?
                            ))
                         OR (r.max_scope = 'DIVISION' AND s.division_id = ?)
                       )
                )
                """,
                Boolean.class,
                actorId,
                divisionId,
                divisionId
        );

        return Boolean.TRUE.equals(allowed);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<AdminDtos.UserScope> requireScopes(List<AdminDtos.UserScope> scopes) {
        List<AdminDtos.UserScope> result = new ArrayList<>();

        if (scopes == null || scopes.isEmpty()) {
            return result;
        }

        Set<String> seen = new LinkedHashSet<>();

        for (AdminDtos.UserScope scope : scopes) {
            if (scope == null || scope.roleId() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "each assignment needs a role"
                );
            }

            if (!seen.add(String.valueOf(scope.roleId()))) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "a role may only be assigned once"
                );
            }

            result.add(scope);
        }

        return result;
    }

    private static String requireText(String value, String field, Integer maxLength) {
        String trimmed = trimToNull(value);

        if (trimmed == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, field + " is required"
            );
        }

        if (maxLength != null && trimmed.length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, field + " must be at most " + maxLength
            );
        }

        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    /** "?, ?, ?" for an IN clause the caller wraps in parentheses. */
    private static String inPlaceholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private long currentUserId() {
        Long actor = jdbcTemplate.queryForObject(
                "SELECT app.current_user_id()", Long.class
        );

        if (actor == null) {
            throw new IllegalStateException("Authenticated database user context is missing");
        }

        return actor;
    }

    /** The user columns for one row of the user page. */
    private record UserRow(
            String username,
            String displayName,
            String email,
            String employeeCode,
            boolean active,
            boolean mustChangePassword,
            boolean mfaEnabled
    ) {
    }

    private void audit(String action, long entityId, Long divisionId, String newValues) {
        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    ?, 'USER', ?::varchar,
                    NULL::bigint, ?::bigint,
                    NULL::jsonb, ?::jsonb,
                    NULL::text, NULL::uuid
                )
                """,
                Long.class,
                action,
                String.valueOf(entityId),
                divisionId,
                newValues
        );
    }

    private static String jsonOf(String displayName, String email, String employeeCode) {
        return "{"
                + "\"display_name\": " + quote(displayName) + ", "
                + "\"email\": " + quote(email) + ", "
                + "\"employee_code\": " + quote(employeeCode)
                + "}";
    }

    private static String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }

        List<String> parts = new ArrayList<>();

        for (String value : values) {
            parts.add(quote(value));
        }

        return "[" + String.join(", ", parts) + "]";
    }

    private static String jsonOfPermissions(List<String> permissions) {
        return jsonArray(permissions);
    }

    private static String jsonOfAssignments(List<AdminDtos.Assignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return "[]";
        }

        List<String> parts = new ArrayList<>();

        for (AdminDtos.Assignment assignment : assignments) {
            parts.add("{\"role\": " + quote(assignment.roleCode())
                    + ", \"wing\": " + quote(assignment.wingCode())
                    + ", \"division\": " + quote(assignment.divisionCode()) + "}");
        }

        return "[" + String.join(", ", parts) + "]";
    }

    private static String jsonOfScopeIds(List<AdminDtos.UserScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "[]";
        }

        List<String> parts = new ArrayList<>();

        for (AdminDtos.UserScope scope : scopes) {
            parts.add("{\"role_id\": " + scope.roleId()
                    + ", \"wing_id\": " + scope.wingId()
                    + ", \"division_id\": " + scope.divisionId() + "}");
        }

        return "[" + String.join(", ", parts) + "]";
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }

        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}