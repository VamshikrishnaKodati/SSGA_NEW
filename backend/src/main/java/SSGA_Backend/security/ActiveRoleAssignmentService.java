package SSGA_Backend.security;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ActiveRoleAssignmentService {

    private final JdbcTemplate jdbcTemplate;

    public ActiveRoleAssignmentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ActiveRoleAssignmentResponse> findAssignments(long userId) {
        return jdbcTemplate.query(
                """
                SELECT assignment_id, role_code, role_name, max_scope,
                       wing_id, wing_name, division_id, division_name,
                       scope_label
                FROM app.find_user_role_assignments(?)
                """,
                (rs, rowNum) -> new ActiveRoleAssignmentResponse(
                        rs.getLong("assignment_id"),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getString("max_scope"),
                        rs.getObject("wing_id", Long.class),
                        rs.getString("wing_name"),
                        rs.getObject("division_id", Long.class),
                        rs.getString("division_name"),
                        rs.getString("scope_label")
                ),
                userId
        );
    }

    public List<String> findAuthorities(long userId, long assignmentId) {
        return jdbcTemplate.query(
                """
                SELECT authority
                FROM app.find_assignment_authorities(?, ?)
                """,
                (rs, rowNum) -> rs.getString("authority"),
                userId,
                assignmentId
        );
    }
}
