package SSGA_Backend.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RlsContext {

    private final JdbcTemplate jdbcTemplate;

    public RlsContext(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Set the PostgreSQL RLS user context on the supplied JDBC connection.
     *
     * The setting is transaction-local, so it does not leak to another
     * request when the connection is returned to the pool.
     */
    public void setCurrentUser(Connection connection) {

        SsgaPrincipal user = getCurrentPrincipal();

        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT set_config('app.user_id', ?, true),
                       set_config('app.active_role_scope_id', ?, true)
                """
        )) {
            statement.setString(1, String.valueOf(user.getUserId()));
            statement.setString(2, requireActiveScopeId(user));
            statement.executeQuery();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Unable to set PostgreSQL RLS user context",
                    e
            );
        }
    }

    /**
     * Existing helper for read operations.
     * It executes on the transaction-bound JdbcTemplate connection.
     */
    public void setCurrentUser() {

        SsgaPrincipal user = getCurrentPrincipal();

        jdbcTemplate.queryForObject(
                "SELECT set_config('app.user_id', ?, true)",
                String.class,
                String.valueOf(user.getUserId())
        );
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.active_role_scope_id', ?, true)",
                String.class,
                requireActiveScopeId(user)
        );
    }

    private static String requireActiveScopeId(SsgaPrincipal user) {
        if (user.getActiveRoleScopeId() == null) {
            throw new IllegalStateException(
                    "An active role and scope assignment is required"
            );
        }

        return String.valueOf(user.getActiveRoleScopeId());
    }

    private SsgaPrincipal getCurrentPrincipal() {

        var authentication =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null ||
                !(authentication.getPrincipal() instanceof SsgaPrincipal user)) {

            throw new IllegalStateException(
                    "Authenticated user not found"
            );
        }

        return user;
    }
}