package SSGA_Backend.reports;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;

/**
 * Read-only payroll reports (division gross/deduction/net summary and employee
 * salary history) plus a CSV export of the summary.
 *
 * <p>All queries run under PostgreSQL RLS, so the rows a caller sees are
 * automatically limited to their division/employee scope.
 */
@Service
public class PayrollReportService {

    private static final String DIVISION_SUMMARY_SQL = """
            SELECT d.id AS division_id, d.name AS division_name,
                   w.id AS wing_id, w.name AS wing_name,
                   COALESCE(SUM(b.employee_count), 0) AS employees,
                   COALESCE(SUM(b.gross_total), 0) AS gross,
                   COALESCE(SUM(b.deduction_total), 0) AS deductions,
                   COALESCE(SUM(b.net_total), 0) AS net
            FROM public.payroll_batches b
            JOIN public.divisions d ON d.id = b.division_id
            LEFT JOIN public.wings w ON w.id = d.wing_id
            WHERE b.status = 'FINALIZED'
              AND (?::bigint IS NULL OR b.period_id = ?::bigint)
            GROUP BY d.id, d.name, w.id, w.name
            ORDER BY w.name NULLS FIRST, d.name
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public PayrollReportService(JdbcTemplate jdbcTemplate, RlsContext rlsContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public List<DivisionSummaryRow> divisionSummary(Long periodId) {
        rlsContext.setCurrentUser();

        return jdbcTemplate.query(
                DIVISION_SUMMARY_SQL,
                (rs, rowNum) -> new DivisionSummaryRow(
                        rs.getLong("division_id"),
                        rs.getString("division_name"),
                        rs.getObject("wing_id", Long.class),
                        rs.getString("wing_name"),
                        rs.getInt("employees"),
                        rs.getBigDecimal("gross"),
                        rs.getBigDecimal("deductions"),
                        rs.getBigDecimal("net")
                ),
                periodId,
                periodId
        );
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public EmployeeSalaryHistoryResponse employeeHistory(long employeeId) {
        rlsContext.setCurrentUser();

        List<EmployeeSalaryHistoryResponse> headers = jdbcTemplate.query(
                """
                SELECT employee_code, name
                FROM public.employees
                WHERE id = ?
                """,
                (rs, rowNum) -> new EmployeeSalaryHistoryResponse(
                        employeeId,
                        rs.getString("employee_code"),
                        rs.getString("name"),
                        List.of()
                ),
                employeeId
        );

        if (headers.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Employee not found or not accessible."
            );
        }

        List<EmployeeSalaryHistoryResponse.HistoryRow> rows = jdbcTemplate.query(
                """
                SELECT b.id AS record_id,
                       to_char(make_date(p.period_year::integer,
                                         p.period_month::integer, 1),
                               'YYYY-MM') AS period,
                       'PAYROLL_BATCH'::text AS record_source,
                       b.status AS batch_status,
                       NULL::varchar AS component_code,
                       NULL::varchar AS component_name,
                       NULL::numeric AS amount,
                       NULL::text AS source_status,
                       s.gross_salary AS gross,
                       s.total_deductions AS deductions,
                       s.net_salary AS net
                FROM public.salary_transactions s
                JOIN public.payroll_batches b ON b.id = s.payroll_batch_id
                JOIN public.payroll_periods p ON p.id = b.period_id
                WHERE s.employee_id = ?
                UNION ALL
                SELECT h.id AS record_id,
                       to_char(h.payroll_period, 'YYYY-MM') AS period,
                       'IMPORTED_HISTORY'::text AS record_source,
                       'HISTORICAL_IMPORT'::varchar AS batch_status,
                       h.component_code_snapshot AS component_code,
                       h.component_name_snapshot AS component_name,
                       h.amount,
                       h.source_status,
                       h.gross_salary AS gross,
                       h.total_deductions AS deductions,
                       h.net_salary AS net
                FROM public.payroll_history_records h
                WHERE h.employee_id = ?
                ORDER BY period DESC, record_source, component_code NULLS FIRST,
                         record_id
                """,
                (rs, rowNum) -> new EmployeeSalaryHistoryResponse.HistoryRow(
                        rs.getLong("record_id"),
                        rs.getString("period"),
                        rs.getString("record_source"),
                        rs.getString("batch_status"),
                        rs.getString("component_code"),
                        rs.getString("component_name"),
                        rs.getBigDecimal("amount"),
                        rs.getString("source_status"),
                        rs.getBigDecimal("gross"),
                        rs.getBigDecimal("deductions"),
                        rs.getBigDecimal("net")
                ),
                employeeId,
                employeeId
        );

        return new EmployeeSalaryHistoryResponse(
                employeeId,
                headers.get(0).employeeCode(),
                headers.get(0).name(),
                rows
        );
    }

    static String toCsv(List<DivisionSummaryRow> rows) {
        StringBuilder csv = new StringBuilder(
                "wing,division,employees,gross,deductions,net\r\n"
        );

        for (DivisionSummaryRow row : rows) {
            csv.append(csvField(row.wingName())).append(',')
                    .append(csvField(row.divisionName())).append(',')
                    .append(row.employeeCount()).append(',')
                    .append(plain(row.gross())).append(',')
                    .append(plain(row.deductions())).append(',')
                    .append(plain(row.net())).append("\r\n");
        }

        return csv.toString();
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }

        if (value.contains(",") || value.contains("\"")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }

        return value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }
}
