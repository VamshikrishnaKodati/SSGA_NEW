package SSGA_Backend.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;

/**
 * Review-screen totals for an import job: current validated totals, the
 * operator division's previous finalized payroll, and warnings that do not
 * block a draft import.
 */
@Service
public class ImportJobReviewService {

    private static final BigDecimal TEN_PERCENT =
            new BigDecimal("0.10");

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public ImportJobReviewService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobReviewSummaryResponse summary(long jobId) {
        SsgaPrincipal user = currentUser();
        rlsContext.setCurrentUser();

        Map<String, Object> job = jdbcTemplate.queryForMap(
                """
                SELECT
                    j.id, j.status, j.valid_rows, j.error_rows, j.warning_rows,
                    MIN(s.validation_summary->>'periodStart') AS period_start,
                    MAX(s.validation_summary->>'periodEnd') AS period_end,
                    COALESCE(SUM(
                        (s.validation_summary->>'earningsTotal')::numeric
                    ), 0) AS earnings,
                    COALESCE(SUM(
                        (s.validation_summary->>'deductionsTotal')::numeric
                    ), 0) AS deductions,
                    COALESCE(SUM(
                        (s.validation_summary->>'basicPayTotal')::numeric
                    ), 0) AS basic_pay,
                    COALESCE(SUM(
                        (s.validation_summary->>'tempIdRows')::int
                    ), 0) AS temp_id_rows,
                    MAX(s.confirmed_type) AS import_type
                FROM public.import_jobs j
                LEFT JOIN public.import_job_sheets s
                       ON s.import_job_id = j.id
                      AND s.confirmed_type IS NOT NULL
                WHERE j.id = ?
                  AND j.uploaded_by = ?
                GROUP BY j.id
                """,
                jobId,
                user.getUserId()
        );

        BigDecimal earnings = decimal(job.get("earnings"));
        BigDecimal deductions = decimal(job.get("deductions"));
        BigDecimal basicPay = decimal(job.get("basic_pay"));

        ImportJobReviewSummaryResponse.Totals current =
                new ImportJobReviewSummaryResponse.Totals(
                        earnings,
                        deductions,
                        earnings.subtract(deductions),
                        basicPay
                );

        String periodStart = (String) job.get("period_start");
        YearMonth previousMonth = previousMonth(periodStart);

        Long divisionId = divisionId(user.getUserId());

        ImportJobReviewSummaryResponse.Totals previous = null;
        String previousSource = null;

        if (previousMonth != null && divisionId != null) {
            previous = previousTotals(divisionId, previousMonth);
            previousSource = previous == null ? null : "PAYROLL";
        }

        List<ImportJobReviewSummaryResponse.Warning> warnings =
                new ArrayList<>();

        int errorRows = ((Number) job.get("error_rows")).intValue();
        int tempIdRows = ((Number) job.get("temp_id_rows")).intValue();
        String status = (String) job.get("status");

        if (errorRows > 0) {
            warnings.add(new ImportJobReviewSummaryResponse.Warning(
                    "VALIDATION_ERRORS",
                    errorRows + " row(s) still have errors. Fix them before "
                            + "importing the draft.",
                    "ERROR"
            ));
        }

        if (tempIdRows > 0) {
            warnings.add(new ImportJobReviewSummaryResponse.Warning(
                    "TEMP_HRMS_IDS",
                    tempIdRows + " employee(s) will receive a temporary HRMS ID "
                            + "that HR can complete later.",
                    "WARNING"
            ));
        }

        if (previous == null) {
            warnings.add(new ImportJobReviewSummaryResponse.Warning(
                    "NO_PREVIOUS_PAYROLL",
                    previousMonth == null
                            ? "No payroll period was found for this import."
                            : "No finalized payroll was found for "
                                    + previousMonth + ".",
                    "INFO"
            ));
        } else {
            addBasicPayWarning(warnings, current, previous);
        }

        boolean canImportDraft = "VALIDATED".equals(status) && errorRows == 0;
        boolean canSubmit = "DRAFT_IMPORTED".equals(status);

        return new ImportJobReviewSummaryResponse(
                jobId,
                status,
                (String) job.get("import_type"),
                periodStart,
                (String) job.get("period_end"),
                current,
                previous,
                previousMonth == null ? null : previousMonth.toString(),
                previousSource,
                warnings,
                canImportDraft,
                canSubmit
        );
    }

    private void addBasicPayWarning(
            List<ImportJobReviewSummaryResponse.Warning> warnings,
            ImportJobReviewSummaryResponse.Totals current,
            ImportJobReviewSummaryResponse.Totals previous
    ) {
        BigDecimal previousBasic = previous.basicPay();

        if (previousBasic == null || previousBasic.signum() <= 0) {
            return;
        }

        BigDecimal change = current.basicPay()
                .subtract(previousBasic)
                .divide(previousBasic, 4, RoundingMode.HALF_UP);

        if (change.abs().compareTo(TEN_PERCENT) > 0) {
            warnings.add(new ImportJobReviewSummaryResponse.Warning(
                    "BASIC_PAY_CHANGE",
                    "Basic Pay changed by "
                            + change.multiply(new BigDecimal("100"))
                                    .setScale(1, RoundingMode.HALF_UP)
                            + "% versus the previous month. Check that this "
                            + "is expected before importing.",
                    "WARNING"
            ));
        }
    }

    private ImportJobReviewSummaryResponse.Totals previousTotals(
            long divisionId,
            YearMonth month
    ) {
        Map<String, Object> totals = jdbcTemplate.queryForMap(
                """
                WITH batch AS (
                    SELECT pb.id
                    FROM public.payroll_batches pb
                    JOIN public.payroll_periods pp ON pp.id = pb.period_id
                    WHERE pb.division_id = ?
                      AND pp.period_year = ?
                      AND pp.period_month = ?
                      AND pb.status = 'FINALIZED'
                )
                SELECT
                    COALESCE((
                        SELECT SUM(st.gross_salary)
                        FROM public.salary_transactions st
                        WHERE st.payroll_batch_id IN (SELECT id FROM batch)
                    ), 0) AS earnings,
                    COALESCE((
                        SELECT SUM(st.total_deductions)
                        FROM public.salary_transactions st
                        WHERE st.payroll_batch_id IN (SELECT id FROM batch)
                    ), 0) AS deductions,
                    COALESCE((
                        SELECT SUM(st.net_salary)
                        FROM public.salary_transactions st
                        WHERE st.payroll_batch_id IN (SELECT id FROM batch)
                    ), 0) AS net,
                    COALESCE((
                        SELECT SUM(stl.amount)
                        FROM public.salary_transaction_lines stl
                        JOIN public.salary_transactions st
                          ON st.id = stl.salary_transaction_id
                        WHERE st.payroll_batch_id IN (SELECT id FROM batch)
                          AND stl.component_code_snapshot IN (
                              'BASIC', 'BASIC_PAY'
                          )
                    ), 0) AS basic_pay
                """,
                divisionId,
                month.getYear(),
                month.getMonthValue()
        );

        BigDecimal earnings = decimal(totals.get("earnings"));

        if (earnings.signum() == 0) {
            return null;
        }

        return new ImportJobReviewSummaryResponse.Totals(
                earnings,
                decimal(totals.get("deductions")),
                decimal(totals.get("net")),
                decimal(totals.get("basic_pay"))
        );
    }

    private Long divisionId(long userId) {
        List<Long> divisions = jdbcTemplate.queryForList(
                """
                SELECT s.division_id
                FROM public.user_role_scopes s
                WHERE s.user_id = ?
                  AND s.division_id IS NOT NULL
                ORDER BY s.id
                LIMIT 1
                """,
                Long.class,
                userId
        );

        return divisions.isEmpty() ? null : divisions.get(0);
    }

    private static YearMonth previousMonth(String periodStart) {
        if (periodStart == null) {
            return null;
        }

        try {
            return YearMonth.parse(periodStart).minusMonths(1);
        } catch (RuntimeException exception) {
            try {
                return YearMonth.from(LocalDate.parse(periodStart))
                        .minusMonths(1);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }

        return value instanceof BigDecimal decimal
                ? decimal : new BigDecimal(value.toString());
    }

    private SsgaPrincipal currentUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal() instanceof SsgaPrincipal user)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated user not found."
            );
        }

        return user;
    }
}
