package SSGA_Backend.service;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import SSGA_Backend.controller.DivisionResponse;
import SSGA_Backend.security.RlsContext;

@Service
public class DivisionService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public DivisionService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public List<DivisionResponse> findMyScopeDivisions() {

        // Set PostgreSQL RLS context for the logged-in user.
        rlsContext.setCurrentUser();

        return jdbcTemplate.query(
                """
                SELECT
                    d.id,
                    d.wing_id,
                    w.name AS wing_name,
                    w.organization_id,
                    d.name,
                    d.code,
                    d.status
                FROM divisions d
                JOIN wings w
                    ON w.id = d.wing_id
                ORDER BY d.wing_id, d.id
                """,
                (rs, rowNum) -> new DivisionResponse(
                        rs.getLong("id"),
                        rs.getLong("wing_id"),
                        rs.getString("wing_name"),
                        rs.getLong("organization_id"),
                        rs.getString("name"),
                        rs.getString("code"),
                        rs.getString("status")
                )
        );
    }
}