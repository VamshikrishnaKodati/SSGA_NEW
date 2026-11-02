package SSGA_Backend.payroll;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;

/**
 * Payroll period management.
 *
 * <p>A period is a (year, month) window for one organization; it starts OPEN so
 * salary batches can be generated against it. Creating the next month's period
 * is what advances the monthly payroll cycle. Only OPEN periods can generate
 * batches (enforced in {@link PayrollBatchService}).
 */
@Service
public class PayrollPeriodService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public PayrollPeriodService(JdbcTemplate jdbcTemplate, RlsContext rlsContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public List<PayrollPeriodResponse> list() {
        rlsContext.setCurrentUser();

        return jdbcTemplate.query(
                """
                SELECT p.id, p.organization_id, o.name AS organization_name,
                       p.period_year, p.period_month, p.period_start,
                       p.period_end, p.status
                FROM public.payroll_periods p
                JOIN public.organizations o ON o.id = p.organization_id
                WHERE app.can_manage_payroll_period(p.organization_id)
                   OR EXISTS (
                        SELECT 1
                        FROM public.divisions d
                        JOIN public.wings w ON w.id = d.wing_id
                        WHERE w.organization_id = p.organization_id
                          AND app.can_access_division(d.id)
                   )
                ORDER BY p.period_year DESC, p.period_month DESC
                """,
                (rs, rowNum) -> new PayrollPeriodResponse(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getString("organization_name"),
                        rs.getInt("period_year"),
                        rs.getInt("period_month"),
                        label(rs.getInt("period_year"), rs.getInt("period_month")),
                        rs.getDate("period_start").toString(),
                        rs.getDate("period_end").toString(),
                        rs.getString("status")
                )
        );
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public PayrollScopeSummaryResponse scopeSummary(
            long periodId,
            long divisionId
    ) {
        rlsContext.setCurrentUser();

        List<java.time.LocalDate> periodStarts = jdbcTemplate.query(
                """
                SELECT p.period_start
                FROM public.payroll_periods p
                JOIN public.divisions d ON d.id = ?
                JOIN public.wings w ON w.id = d.wing_id
                WHERE p.id = ?
                  AND p.organization_id = w.organization_id
                  AND app.can_access_division(d.id)
                """,
                (rs, rowNum) -> rs.getDate("period_start").toLocalDate(),
                divisionId,
                periodId
        );

        if (periodStarts.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payroll period or division not found or not accessible."
            );
        }

        LocalDate periodStart = periodStarts.get(0);
        List<PayrollScopeSummaryResponse> counts = jdbcTemplate.query(
                """
                SELECT count(*) AS total_employees,
                       count(*) FILTER (
                           WHERE e.status = 'ACTIVE'
                             AND EXISTS (
                                 SELECT 1
                                 FROM public.employee_salary_values v
                                 WHERE v.employee_id = e.id
                                   AND v.effective_from <= ?
                                   AND (v.effective_to IS NULL
                                        OR v.effective_to >= ?)
                             )
                       ) AS active_eligible_employees
                FROM public.employees e
                WHERE e.division_id = ?
                """,
                (rs, rowNum) -> new PayrollScopeSummaryResponse(
                        rs.getLong("total_employees"),
                        rs.getLong("active_eligible_employees"),
                        rs.getLong("total_employees")
                                - rs.getLong("active_eligible_employees"),
                        null,
                        null,
                        null
                ),
                Date.valueOf(periodStart),
                Date.valueOf(periodStart),
                divisionId
        );

        if (counts.isEmpty()) {
            throw new IllegalStateException(
                    "Payroll employee scope summary returned no aggregate row."
            );
        }

        List<PayrollScopeSummaryResponse> lastBatches = jdbcTemplate.query(
                """
                SELECT id, status, run_type
                FROM public.payroll_batches
                WHERE period_id = ? AND division_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new PayrollScopeSummaryResponse(
                        counts.get(0).totalEmployees(),
                        counts.get(0).activeEligibleEmployees(),
                        counts.get(0).inactiveOrExcludedEmployees(),
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("run_type")
                ),
                periodId,
                divisionId
        );

        return lastBatches.isEmpty() ? counts.get(0) : lastBatches.get(0);
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollPeriodResponse create(PayrollPeriodCreateRequest request) {
        rlsContext.setCurrentUser();

        if (request == null || request.year() == null || request.month() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "year and month are required."
            );
        }

        int year = request.year();
        int month = request.month();

        YearMonth yearMonth;

        try {
            yearMonth = periodMonth(year, month);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, ex.getMessage()
            );
        }

        long organizationId = resolveOrganization(request.organizationId());
        requireOrganizationPeriodManager(organizationId);

        Long existing = existingPeriodId(organizationId, year, month);

        if (existing != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A period for " + label(year, month) + " already exists "
                            + "(period " + existing + ")."
            );
        }

        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();

        Long id;

        try {
            id = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO public.payroll_periods (
                        organization_id, period_year, period_month,
                        period_start, period_end, status
                    )
                    VALUES (?, ?, ?, ?, ?, 'OPEN')
                    RETURNING id
                    """,
                    Long.class,
                    organizationId,
                    year,
                    month,
                    Date.valueOf(start),
                    Date.valueOf(end)
            );
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A period for " + label(year, month) + " already exists."
            );
        }

        if (id == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to create the payroll period."
            );
        }

        audit(id, organizationId, year, month, start, end);

        return byId(id);
    }

    /**
     * Locks an OPEN period. A locked period cannot generate new batches, so
     * the payroll for that month is frozen while it is being checked.
     */
    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollPeriodResponse lock(long periodId) {
        rlsContext.setCurrentUser();
        requirePeriodManager(periodId);
        return changeStatus(periodId, "OPEN", "LOCKED", "PAYROLL_PERIOD_LOCK");
    }

    /**
     * Closes a period. A period can only be closed when every payroll batch
     * for it is finalized, so a closed month has no open payroll work left.
     */
    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollPeriodResponse close(long periodId) {
        rlsContext.setCurrentUser();
        requirePeriodManager(periodId);

        Integer open = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM public.payroll_batches
                WHERE period_id = ? AND status <> 'FINALIZED'
                """,
                Integer.class,
                periodId
        );

        if (open != null && open > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The period cannot be closed: " + open
                            + " payroll batch(es) are not finalized."
            );
        }

        return changeStatus(periodId, "LOCKED", "CLOSED",
                "PAYROLL_PERIOD_CLOSE");
    }

    /**
     * Reopens a period. A closed period first goes back to LOCKED, so the
     * history of the period shows every step.
     */
    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollPeriodResponse reopen(long periodId) {
        rlsContext.setCurrentUser();
        requirePeriodManager(periodId);

        String status = requireStatus(periodId);

        if ("CLOSED".equals(status)) {
            return changeStatus(periodId, "CLOSED", "LOCKED",
                    "PAYROLL_PERIOD_REOPEN");
        }

        if ("LOCKED".equals(status)) {
            return changeStatus(periodId, "LOCKED", "OPEN",
                    "PAYROLL_PERIOD_REOPEN");
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only a LOCKED or CLOSED period can be reopened. Current "
                        + "status: " + status + "."
        );
    }

    private PayrollPeriodResponse changeStatus(
            long periodId,
            String expectedStatus,
            String newStatus,
            String action
    ) {
        rlsContext.setCurrentUser();

        String current = requireStatus(periodId);

        if (!expectedStatus.equals(current)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The period is " + current + "; only a " + expectedStatus
                            + " period can be changed to " + newStatus + "."
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE public.payroll_periods
                SET status = ?
                WHERE id = ? AND status = ?
                """,
                newStatus,
                periodId,
                expectedStatus
        );

        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The period could not be changed because its status "
                            + "changed. Refresh and retry."
            );
        }

        auditStatus(periodId, action, current, newStatus);

        return byId(periodId);
    }

    private String requireStatus(long periodId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT status FROM public.payroll_periods WHERE id = ?",
                (rs, rowNum) -> rs.getString("status"),
                periodId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payroll period not found."
            );
        }

        return rows.get(0);
    }

    private void requireOrganizationPeriodManager(long organizationId) {
        Boolean allowed = jdbcTemplate.queryForObject(
                "SELECT app.can_manage_payroll_period(?)",
                Boolean.class,
                organizationId
        );

        if (!Boolean.TRUE.equals(allowed)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Organization-wide payroll period management is not allowed."
            );
        }
    }

    private void requirePeriodManager(long periodId) {
        List<Boolean> rows = jdbcTemplate.query(
                """
                SELECT app.can_manage_payroll_period(organization_id)
                FROM public.payroll_periods
                WHERE id = ?
                """,
                (rs, rowNum) -> Boolean.TRUE.equals(rs.getObject(1)),
                periodId
        );

        if (rows.isEmpty() || !rows.get(0)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payroll period not found."
            );
        }
    }

    static YearMonth periodMonth(int year, int month) {        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException(
                    "Period year must be between " + MIN_YEAR + " and "
                            + MAX_YEAR + "."
            );
        }

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException(
                    "Period month must be between 1 and 12."
            );
        }

        return YearMonth.of(year, month);
    }

    private PayrollPeriodResponse byId(long id) {
        List<PayrollPeriodResponse> rows = jdbcTemplate.query(
                """
                SELECT p.id, p.organization_id, o.name AS organization_name,
                       p.period_year, p.period_month, p.period_start,
                       p.period_end, p.status
                FROM public.payroll_periods p
                JOIN public.organizations o ON o.id = p.organization_id
                WHERE p.id = ?
                """,
                (rs, rowNum) -> new PayrollPeriodResponse(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getString("organization_name"),
                        rs.getInt("period_year"),
                        rs.getInt("period_month"),
                        label(rs.getInt("period_year"), rs.getInt("period_month")),
                        rs.getDate("period_start").toString(),
                        rs.getDate("period_end").toString(),
                        rs.getString("status")
                ),
                id
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "The created payroll period could not be reloaded."
            );
        }

        return rows.get(0);
    }

    private long resolveOrganization(Long requested) {
        if (requested != null) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM public.organizations "
                            + "WHERE id = ?)",
                    Boolean.class,
                    requested
            );

            if (!Boolean.TRUE.equals(exists)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Organization " + requested + " was not found."
                );
            }

            return requested;
        }

        // ponytail: single-org deployment; add explicit org selection when
        // multiple organizations are onboarded.
        List<Long> ids = jdbcTemplate.query(
                """
                SELECT id FROM public.organizations
                WHERE status = 'ACTIVE'
                ORDER BY id
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong(1)
        );

        if (ids.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No active organization is configured."
            );
        }

        return ids.get(0);
    }

    private Long existingPeriodId(long organizationId, int year, int month) {
        List<Long> ids = jdbcTemplate.query(
                """
                SELECT id FROM public.payroll_periods
                WHERE organization_id = ? AND period_year = ?
                  AND period_month = ?
                """,
                (rs, rowNum) -> rs.getLong(1),
                organizationId,
                year,
                month
        );

        return ids.isEmpty() ? null : ids.get(0);
    }

    private void audit(
            long periodId,
            long organizationId,
            int year,
            int month,
            LocalDate start,
            LocalDate end
    ) {
        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    'PAYROLL_PERIOD_CREATE', 'PAYROLL_PERIOD', ?::varchar,
                    NULL::bigint, NULL::bigint,
                    NULL::jsonb,
                    jsonb_build_object(
                        'organization_id', ?::bigint,
                        'year', ?::int,
                        'month', ?::int,
                        'start', ?::text,
                        'end', ?::text,
                        'status', 'OPEN'::text
                    ),
                    NULL::text, NULL::uuid
                )
                """,
                Long.class,
                String.valueOf(periodId),
                organizationId,
                year,
                month,
                start.toString(),
                end.toString()
        );
    }

    private void auditStatus(
            long periodId,
            String action,
            String previousStatus,
            String newStatus
    ) {
        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    ?, 'PAYROLL_PERIOD', ?::varchar,
                    NULL::bigint, NULL::bigint,
                    jsonb_build_object('status', ?::text),
                    jsonb_build_object('status', ?::text),
                    NULL::text, NULL::uuid
                )
                """,
                Long.class,
                action,
                String.valueOf(periodId),
                previousStatus,
                newStatus
        );
    }

    private static String label(int year, int month) {
        return String.format("%04d-%02d", year, month);
    }
}
