package SSGA_Backend.security;

import java.time.Instant;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class SsgaUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public SsgaUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        try {
            var user = jdbcTemplate.queryForObject(
                "SELECT * FROM app.find_auth_user(?)",
                (rs, rowNum) -> new UserData(
                    rs.getLong("user_id"),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getBoolean("is_active"),
                    rs.getTimestamp("locked_until")
                ),
                username
            );

            boolean locked =
                user.lockedUntil() != null &&
                user.lockedUntil().toInstant().isAfter(Instant.now());

            return new SsgaPrincipal(
                user.userId(),
                user.username(),
                user.passwordHash(),
                user.active(),
                !locked,
                List.of()
            );

        } catch (EmptyResultDataAccessException e) {
            throw new UsernameNotFoundException("User not found");
        }
    }

    private record UserData(
        long userId,
        String username,
        String passwordHash,
        boolean active,
        java.sql.Timestamp lockedUntil
    ) {}
}