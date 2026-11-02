package SSGA_Backend.report;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;

/**
 * Read-only payroll reporting.
 *
 * <p>Aggregates salary transactions per division for a period (optionally
 * limited to a batch status). Row-level security on salary_transactions and
 * payroll_batches restricts the result to the caller's authorized scope, so the
 * same query safely serves division, wing and organization users.
 */
@Service
public class ReportService {

    private static final String ALL_STATUSES = "ALL";

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public ReportService(JdbcTemplate jdbcTemplate, RlsContext rlsContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public PayrollSummaryResponse summary(Long periodId, String status) {
        rlsContext.setCurrentUser();

        String statusFilter = normalizeStatus(status);

        String sql = """
                SELECT b.division_id, d.name AS division_name,
                       w.name AS wing_name,
                       count(DISTINCT t.employee_id) AS employees,
                       COALESCE(sum(t.gross_salary), 0) AS gross,
                       COALESCE(sum(t.total_deductions), 0) AS deductions,
                       COALESCE(sum(t.net_salary), 0) AS net
                FROM public.salary_transactions t
                JOIN public.payroll_batches b ON b.id = t.payroll_batch_id
                JOIN public.divisions d ON d.id = b.division_id
                LEFT JOIN public.wings w ON w.id = b.wing_id
                WHERE 1 = 1
                """;

        List<Object> args = new java.util.ArrayList<>();

        if (periodId != null) {
            sql += " AND b.period_id = ?";
            args.add(periodId);
        }

        if (!ALL_STATUSES.equals(statusFilter)) {
            sql += " AND b.status = ?";
            args.add(statusFilter);
        }

        sql += " GROUP BY b.division_id, d.name, w.name"
                + " ORDER BY w.name NULLS FIRST, d.name";

        List<DivisionSummary> divisions = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DivisionSummary(
                        rs.getLong("division_id"),
                        rs.getString("division_name"),
                        rs.getString("wing_name"),
                        rs.getInt("employees"),
                        rs.getBigDecimal("gross"),
                        rs.getBigDecimal("deductions"),
                        rs.getBigDecimal("net")
                ),
                args.toArray()
        );

        return new PayrollSummaryResponse(
                periodLabel(periodId),
                statusFilter,
                divisions,
                totals(divisions)
        );
    }

    public String toCsv(PayrollSummaryResponse summary) {
        StringBuilder csv = new StringBuilder();

        csv.append("Wing,Division,Employees,Gross,Deductions,Net\r\n");

        for (DivisionSummary row : summary.divisions()) {
            appendRow(csv, row);
        }

        appendRow(csv, summary.totals());

        return csv.toString();
    }

    private void appendRow(StringBuilder csv, DivisionSummary row) {
        csv.append(escape(row.wingName())).append(',')
                .append(escape(row.divisionName())).append(',')
                .append(row.employeeCount()).append(',')
                .append(row.gross().toPlainString()).append(',')
                .append(row.deductions().toPlainString()).append(',')
                .append(row.net().toPlainString()).append("\r\n");
    }

    private DivisionSummary totals(List<DivisionSummary> rows) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        int employees = 0;

        for (DivisionSummary row : rows) {
            gross = gross.add(row.gross());
            deductions = deductions.add(row.deductions());
            net = net.add(row.net());
            employees += row.employeeCount();
        }

        return new DivisionSummary(
                0L, "TOTAL", null, employees, gross, deductions, net
        );
    }

    private String periodLabel(Long periodId) {
        if (periodId == null) {
            return "ALL";
        }

        List<String> labels = jdbcTemplate.query(
                """
                SELECT period_year, period_month
                FROM public.payroll_periods
                WHERE id = ?
                """,
                (rs, rowNum) -> String.format(
                        "%04d-%02d",
                        rs.getInt("period_year"),
                        rs.getInt("period_month")
                ),
                periodId
        );

        if (labels.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payroll period " + periodId + " was not found."
            );
        }

        return labels.get(0);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return ALL_STATUSES;
        }

        return status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }

        if (value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }
}
