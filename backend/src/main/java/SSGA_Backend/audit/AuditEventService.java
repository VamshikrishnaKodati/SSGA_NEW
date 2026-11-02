package SSGA_Backend.audit;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import SSGA_Backend.security.RlsContext;

@Service
public class AuditEventService {

    private static final int MAX_LIMIT = 200;
    private static final int MAX_OFFSET = 100_000;

    private static final RowMapper<AuditEventResponse> ROW_MAPPER =
            (rs, rowNum) -> new AuditEventResponse(
                    rs.getLong("id"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getObject("user_id", Long.class),
                    rs.getString("username"),
                    rs.getString("action"),
                    rs.getString("entity_type"),
                    rs.getString("entity_id"),
                    rs.getObject("wing_id", Long.class),
                    rs.getObject("division_id", Long.class),
                    rs.getString("reason"),
                    rs.getObject("request_id", java.util.UUID.class)
            );

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public AuditEventService(JdbcTemplate jdbcTemplate, RlsContext rlsContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(readOnly = true)
    public AuditEventPageResponse list(
            LocalDate from,
            LocalDate to,
            String username,
            String module,
            String action,
            String recordId,
            int limit,
            int offset
    ) {
        validate(from, to, username, module, action, recordId, limit, offset);
        rlsContext.setCurrentUser();

        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, user_id, username, action, entity_type,
                       entity_id, wing_id, division_id, reason, request_id
                FROM public.audit_logs
                WHERE true
                """);
        List<Object> parameters = new ArrayList<>();

        if (from != null) {
            sql.append(" AND occurred_at >= ?");
            parameters.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND occurred_at < ?");
            parameters.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        addExactFilter(sql, parameters, "LOWER(username) = LOWER(?)", username);
        addExactFilter(sql, parameters, "entity_type = ?", normalize(module));
        addExactFilter(sql, parameters, "action = ?", normalize(action));
        addExactFilter(sql, parameters, "entity_id = ?", trimToNull(recordId));

        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?");
        parameters.add(limit + 1);
        parameters.add(offset);

        List<AuditEventResponse> rows =
                jdbcTemplate.query(sql.toString(), ROW_MAPPER, parameters.toArray());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }

        return new AuditEventPageResponse(List.copyOf(rows), limit, offset, hasMore);
    }

    private static void addExactFilter(
            StringBuilder sql,
            List<Object> parameters,
            String condition,
            String value
    ) {
        if (value != null) {
            sql.append(" AND ").append(condition);
            parameters.add(value);
        }
    }

    private static void validate(
            LocalDate from,
            LocalDate to,
            String username,
            String module,
            String action,
            String recordId,
            int limit,
            int offset
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'.");
        }
        validateFilter("user", username, 100);
        validateFilter("module", module, 60);
        validateFilter("action", action, 40);
        validateFilter("recordId", recordId, 64);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIMIT + "."
            );
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException(
                    "offset must be between 0 and " + MAX_OFFSET + "."
            );
        }
    }

    private static void validateFilter(String name, String value, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " filter must be at most " + maxLength + " characters."
            );
        }
    }

    private static String normalize(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
