package SSGA_Backend.importer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved, reusable column-mapping profiles. Keyed to the import type and a
 * structure fingerprint so a changed workbook template can be detected before a
 * profile is applied. RLS restricts visibility to the creator or a shared
 * profile.
 */
@Service
public class ImportMappingProfileService {

    private static final Set<String> TYPES = Set.of(
            "EMPLOYEE_MASTER",
            "SALARY_ENTITLEMENTS",
            "PAYROLL_HISTORY",
            "SALARY_REVISIONS"
    );

    private static final String SELECT_COLUMNS = """
            SELECT id, name, import_type, structure_signature, schema_version,
                   column_mapping::text AS column_mapping_json,
                   options::text AS options_json,
                   is_shared, is_active, created_at::text AS created_at
            FROM public.import_mapping_profiles
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;

    public ImportMappingProfileService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public List<ImportMappingProfileResponse> list(String importType) {
        requireUser();
        rlsContext.setCurrentUser();

        String type = normalizeType(importType, false);

        List<Map<String, Object>> rows;

        if (type == null) {
            rows = jdbcTemplate.queryForList(
                    SELECT_COLUMNS + " WHERE is_active ORDER BY updated_at DESC");
        } else {
            rows = jdbcTemplate.queryForList(
                    SELECT_COLUMNS
                            + " WHERE is_active AND import_type = ?"
                            + " ORDER BY updated_at DESC",
                    type
            );
        }

        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportMappingProfileResponse create(ImportMappingProfileRequest request) {
        SsgaPrincipal user = requireUser();
        rlsContext.setCurrentUser();

        if (request == null) {
            throw badRequest("A profile definition is required.");
        }

        String name = requireName(request.name());
        String type = normalizeType(request.importType(), true);
        String signature = requireSignature(request.structureSignature());

        int schemaVersion = request.schemaVersion() == null
                ? 1
                : request.schemaVersion();

        if (schemaVersion < 1) {
            throw badRequest("schemaVersion must be positive.");
        }

        String mappingJson = writeJson(request.columnMapping());
        String optionsJson = writeJson(request.options());

        List<Long> ids = jdbcTemplate.query(
                """
                INSERT INTO public.import_mapping_profiles (
                    name, import_type, structure_signature, schema_version,
                    column_mapping, options, created_by, is_shared
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                RETURNING id
                """,
                (rs, rowNum) -> rs.getLong("id"),
                name,
                type,
                signature,
                schemaVersion,
                mappingJson,
                optionsJson,
                user.getUserId(),
                Boolean.TRUE.equals(request.shared())
        );

        if (ids.isEmpty()) {
            throw new IllegalStateException("The profile could not be created.");
        }

        return findById(ids.get(0), "Mapping profile saved.");
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportMappingProfileResponse update(
            long id,
            ImportMappingProfileRequest request
    ) {
        requireUser();
        rlsContext.setCurrentUser();

        if (id <= 0 || request == null) {
            throw badRequest("A valid profile and update are required.");
        }

        ImportMappingProfileResponse existing = findById(id, null);

        String name = request.name() == null
                ? existing.name()
                : requireName(request.name());

        String mappingJson = request.columnMapping() == null
                ? null
                : writeJson(request.columnMapping());

        String optionsJson = request.options() == null
                ? null
                : writeJson(request.options());

        Boolean shared = request.shared();

        int updated = jdbcTemplate.update(
                """
                UPDATE public.import_mapping_profiles
                SET name = ?,
                    column_mapping = COALESCE(?::jsonb, column_mapping),
                    options = COALESCE(?::jsonb, options),
                    is_shared = COALESCE(?, is_shared),
                    schema_version = COALESCE(?, schema_version),
                    updated_at = now()
                WHERE id = ?
                  AND created_by = app.current_user_id()
                """,
                name,
                mappingJson,
                optionsJson,
                shared,
                request.schemaVersion(),
                id
        );

        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only the profile owner can update it, or it no longer exists."
            );
        }

        return findById(id, "Mapping profile updated.");
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public void delete(long id) {
        requireUser();
        rlsContext.setCurrentUser();

        if (id <= 0) {
            throw badRequest("A valid profile is required.");
        }

        int deleted = jdbcTemplate.update(
                "DELETE FROM public.import_mapping_profiles WHERE id = ?",
                id
        );

        if (deleted != 1) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Mapping profile not found or not owned by you."
            );
        }
    }

    private ImportMappingProfileResponse findById(long id, String message) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_COLUMNS + " WHERE id = ?", id);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Mapping profile not found or not accessible."
            );
        }

        ImportMappingProfileResponse response = toResponse(rows.get(0));

        if (message == null) {
            return response;
        }

        return new ImportMappingProfileResponse(
                response.id(), response.name(), response.importType(),
                response.structureSignature(), response.schemaVersion(),
                response.columnMapping(), response.options(),
                response.shared(), response.active(),
                response.createdAt(), message
        );
    }

    private ImportMappingProfileResponse toResponse(Map<String, Object> row) {
        return new ImportMappingProfileResponse(
                ((Number) row.get("id")).longValue(),
                (String) row.get("name"),
                (String) row.get("import_type"),
                (String) row.get("structure_signature"),
                ((Number) row.get("schema_version")).intValue(),
                readJson((String) row.get("column_mapping_json")),
                readJson((String) row.get("options_json")),
                Boolean.TRUE.equals(row.get("is_shared")),
                Boolean.TRUE.equals(row.get("is_active")),
                (String) row.get("created_at"),
                null
        );
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }

        return jsonMapper.readValue(json, Map.class);
    }

    private String writeJson(Map<String, Object> value) {
        return jsonMapper.writeValueAsString(value == null ? Map.of() : value);
    }

    private String normalizeType(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw badRequest("An import type is required.");
            }

            return null;
        }

        String type = value.trim().toUpperCase(Locale.ROOT);

        if (!TYPES.contains(type)) {
            throw badRequest("Unsupported import type: " + type);
        }

        return type;
    }

    private String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("A profile name is required.");
        }

        String name = value.trim();

        if (name.length() > 120) {
            throw badRequest("The profile name cannot exceed 120 characters.");
        }

        return name;
    }

    private String requireSignature(String value) {
        if (value == null || !value.matches("^[0-9a-fA-F]{64}$")) {
            throw badRequest(
                    "A valid 64-character structure signature is required.");
        }

        return value.toLowerCase(Locale.ROOT);
    }

    private SsgaPrincipal requireUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal()
                        instanceof SsgaPrincipal user)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated user not found."
            );
        }

        return user;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
