package SSGA_Backend.service;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import SSGA_Backend.controller.SalaryComponentResponse;

@Service
public class SalaryComponentService {

    private final JdbcTemplate jdbcTemplate;

    public SalaryComponentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public List<SalaryComponentResponse> findAll() {

        return jdbcTemplate.query(
                """
                SELECT id,
                       component_code,
                       component_name,
                       category,
                       calculation_type,
                       rate,
                       display_order
                FROM public.salary_components
                WHERE status = 'ACTIVE'
                ORDER BY display_order NULLS LAST, component_code
                """,
                (rs, rowNum) -> {
                    int displayOrder = rs.getInt("display_order");
                    Integer order = rs.wasNull() ? null : displayOrder;
                    return new SalaryComponentResponse(
                            rs.getLong("id"),
                            rs.getString("component_code"),
                            rs.getString("component_name"),
                            rs.getString("category"),
                            rs.getString("calculation_type"),
                            rs.getBigDecimal("rate"),
                            order
                    );
                }
        );
    }
}
