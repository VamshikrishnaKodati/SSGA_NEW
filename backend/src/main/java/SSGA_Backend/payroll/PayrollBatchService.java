package SSGA_Backend.payroll;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;

/**
 * Payroll batch generation (Phase B).
 *
 * <p>Generates one DRAFT batch per (period, division, run type). Each employee
 * found in the division with effective salary values gets a salary transaction
 * with one line per active salary component; gross is the sum of EARNING lines,
 * total deductions is the sum of DEDUCTION lines and net = gross - deductions,
 * matching the workbook's Payroll_Expected sheet.
 *
 * <p>Rows are written under PostgreSQL RLS, so division/employee scope is
 * enforced by the database policies as well as the explicit checks here.
 */
@Service
public class PayrollBatchService {

    private static final String DEFAULT_RUN_TYPE = "MONTHLY";

    /**
     * Payroll run types the generation engine actually supports. Only the
     * monthly run exists today; supplementary/arrears runs would need their
     * own approved business rules before they can be accepted here.
     */
    static final java.util.Set<String> SUPPORTED_RUN_TYPES =
            java.util.Set.of(DEFAULT_RUN_TYPE);

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public PayrollBatchService(JdbcTemplate jdbcTemplate, RlsContext rlsContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollBatchResponse generate(PayrollGenerateRequest request) {
        rlsContext.setCurrentUser();

        if (request == null || request.periodId() == null
                || request.divisionId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "periodId and divisionId are required."
            );
        }

        long periodId = request.periodId();
        long divisionId = request.divisionId();
        String runType = normalizeRunType(request.runType());

        Period period = requireOpenPeriod(periodId);
        Division division = requireDivision(divisionId);

        if (period.organizationId() != division.organizationId()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The selected division does not belong to the payroll "
                            + "period's organization."
            );
        }

        if (!canAccessDivision(divisionId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You do not have access to division " + divisionId + "."
            );
        }

        Long existing = existingBatchId(periodId, divisionId, runType);

        if (existing != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A " + runType + " batch already exists for this period and "
                            + "division (batch " + existing + "). Duplicate "
                            + "batches are not allowed."
            );
        }

        List<Component> components = loadActiveComponents();

        if (components.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No active salary components are configured."
            );
        }

        List<EmployeeRef> employees = loadEmployeesWithValues(
                divisionId, period.startDate()
        );

        if (employees.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No employees with salary values were found in division "
                            + divisionId + "."
            );
        }

        Map<Long, BigDecimal> values = loadEffectiveValues(
                employees, period.startDate()
        );

        long actorId = currentUserId();

        long batchId = insertBatch(
                periodId, division, runType, actorId
        );

        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal deductionTotal = BigDecimal.ZERO;
        int employeeCount = 0;
        List<PayrollBatchResponse.EmployeeTotals> totals = new ArrayList<>();

        for (EmployeeRef employee : employees) {
            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal deductions = BigDecimal.ZERO;
            List<Object[]> lineRows = new ArrayList<>();
            int lineCount = 0;

            long transactionId = insertTransaction(batchId, employee);

            for (Component component : components) {
                BigDecimal amount = values.getOrDefault(
                        key(employee.id(), component.id()),
                        BigDecimal.ZERO
                );

                if ("EARNING".equals(component.category())) {
                    gross = gross.add(amount);
                } else {
                    deductions = deductions.add(amount);
                }

                lineRows.add(new Object[]{
                        transactionId,
                        component.id(),
                        component.code(),
                        component.name(),
                        component.category(),
                        amount
                });

                lineCount++;
            }

            BigDecimal net = gross.subtract(deductions);

            jdbcTemplate.update(
                    """
                    UPDATE public.salary_transactions
                    SET gross_salary = ?, total_deductions = ?, net_salary = ?
                    WHERE id = ?
                    """,
                    gross, deductions, net, transactionId
            );

            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO public.salary_transaction_lines (
                        salary_transaction_id, salary_component_id,
                        component_code_snapshot, component_name_snapshot,
                        category_snapshot, amount
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    lineRows
            );

            grossTotal = grossTotal.add(gross);
            deductionTotal = deductionTotal.add(deductions);
            employeeCount++;
            totals.add(new PayrollBatchResponse.EmployeeTotals(
                    employee.id(),
                    employee.hrmsId(),
                    employee.name(),
                    gross,
                    deductions,
                    net,
                    lineCount
            ));
        }

        BigDecimal netTotal = grossTotal.subtract(deductionTotal);

        jdbcTemplate.update(
                """
                UPDATE public.payroll_batches
                SET employee_count = ?, gross_total = ?, deduction_total = ?,
                    net_total = ?
                WHERE id = ?
                """,
                employeeCount, grossTotal, deductionTotal, netTotal, batchId
        );

        audit(batchId, division.wingId(), divisionId, grossTotal,
                deductionTotal, netTotal, employeeCount);

        return new PayrollBatchResponse(
                batchId,
                periodId,
                period.label(),
                divisionId,
                division.name(),
                runType,
                1,
                "DRAFT",
                actorId,
                null,
                employeeCount,
                grossTotal,
                deductionTotal,
                netTotal,
                totals,
                "DRAFT batch generated with " + employeeCount + " employees.",
                1L
        );
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public List<PayrollBatchResponse> listBatches(Long periodId) {
        rlsContext.setCurrentUser();

        String sql = """
                SELECT b.id, b.period_id, p.period_year, p.period_month,
                       b.division_id, d.name AS division_name,
                       b.run_type, b.run_no, b.status, b.maker_id, b.checker_id,
                       b.employee_count,
                       b.gross_total, b.deduction_total, b.net_total,
                       b.version
                FROM public.payroll_batches b
                JOIN public.payroll_periods p ON p.id = b.period_id
                LEFT JOIN public.divisions d ON d.id = b.division_id
                """;

        List<PayrollBatchResponse> rows = new ArrayList<>();

        var callback = (org.springframework.jdbc.core.RowMapper<PayrollBatchResponse>)
                (rs, rowNum) -> new PayrollBatchResponse(
                        rs.getLong("id"),
                        rs.getLong("period_id"),
                        periodLabel(rs.getInt("period_year"),
                                rs.getInt("period_month")),
                        rs.getLong("division_id"),
                        rs.getString("division_name"),
                        rs.getString("run_type"),
                        rs.getInt("run_no"),
                        rs.getString("status"),
                        rs.getObject("maker_id", Long.class),
                        rs.getObject("checker_id", Long.class),
                        rs.getInt("employee_count"),
                        rs.getBigDecimal("gross_total"),
                        rs.getBigDecimal("deduction_total"),
                        rs.getBigDecimal("net_total"),
                        List.of(),
                        null,
                        rs.getLong("version")
                );

        if (periodId == null) {
            rows.addAll(jdbcTemplate.query(sql + " ORDER BY b.id", callback));
        } else {
            rows.addAll(jdbcTemplate.query(
                    sql + " WHERE b.period_id = ? ORDER BY b.id",
                    callback, periodId
            ));
        }

        return rows;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public PayrollBatchDetailResponse getBatch(long batchId) {
        return getBatchDetail(batchId, true);
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public PayrollBatchDetailResponse getBatchSummary(long batchId) {
        return getBatchDetail(batchId, false);
    }

    private PayrollBatchDetailResponse getBatchDetail(
            long batchId,
            boolean includeTransactions
    ) {
        rlsContext.setCurrentUser();

        List<PayrollBatchDetailResponse> headers = jdbcTemplate.query(
                """
                SELECT b.id, p.period_year, p.period_month, b.division_id,
                       COALESCE(b.division_name_snapshot, d.name)
                           AS division_name,
                       b.run_type, b.run_no, b.status,
                       b.maker_id, b.checker_id,
                       b.employee_count, b.gross_total, b.deduction_total,
                       b.net_total, b.version,
                       w.name AS wing_name,
                       b.created_at, b.submitted_at, b.finalized_at,
                       mu.display_name AS maker_name,
                       cu.display_name AS checker_name
                FROM public.payroll_batches b
                JOIN public.payroll_periods p ON p.id = b.period_id
                LEFT JOIN public.divisions d ON d.id = b.division_id
                LEFT JOIN public.wings w ON w.id = b.wing_id
                LEFT JOIN public.users mu ON mu.id = b.maker_id
                LEFT JOIN public.users cu ON cu.id = b.checker_id
                WHERE b.id = ?
                """,
                (rs, rowNum) -> new PayrollBatchDetailResponse(
                        rs.getLong("id"),
                        periodLabel(rs.getInt("period_year"),
                                rs.getInt("period_month")),
                        rs.getLong("division_id"),
                        rs.getString("division_name"),
                        rs.getString("run_type"),
                        rs.getInt("run_no"),
                        rs.getString("status"),
                        rs.getObject("maker_id", Long.class),
                        rs.getObject("checker_id", Long.class),
                        rs.getInt("employee_count"),
                        rs.getBigDecimal("gross_total"),
                        rs.getBigDecimal("deduction_total"),
                        rs.getBigDecimal("net_total"),
                        new ArrayList<>(),
                        rs.getLong("version"),
                        rs.getString("wing_name"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getString("maker_name"),
                        rs.getString("checker_name"),
                        rs.getObject("finalized_at", OffsetDateTime.class)
                ),
                batchId
        );

        if (headers.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payroll batch not found or not accessible."
            );
        }

        if (!includeTransactions) {
            return headers.get(0);
        }

        List<PayrollBatchDetailResponse> result = new ArrayList<>();

        for (PayrollBatchDetailResponse header : headers) {
            List<PayrollBatchDetailResponse.TransactionDetail> transactions =
                    jdbcTemplate.query(
                            """
                            SELECT id, hrms_id_snapshot, name_snapshot,
                                   gross_salary, total_deductions, net_salary,
                                   status
                            FROM public.salary_transactions
                            WHERE payroll_batch_id = ?
                            ORDER BY hrms_id_snapshot
                            """,
                            (rs, rowNum) -> new PayrollBatchDetailResponse
                                    .TransactionDetail(
                                    rs.getLong("id"),
                                    rs.getString("hrms_id_snapshot"),
                                    rs.getString("name_snapshot"),
                                    rs.getBigDecimal("gross_salary"),
                                    rs.getBigDecimal("total_deductions"),
                                    rs.getBigDecimal("net_salary"),
                                    rs.getString("status"),
                                    new ArrayList<>()
                            ),
                            batchId
                    );

            List<PayrollBatchDetailResponse.TransactionDetail> withLines =
                    new ArrayList<>();

            for (var transaction : transactions) {
                withLines.add(new PayrollBatchDetailResponse.TransactionDetail(
                        transaction.transactionId(),
                        transaction.hrmsId(),
                        transaction.name(),
                        transaction.gross(),
                        transaction.deductions(),
                        transaction.net(),
                        transaction.status(),
                        transactionLines(transaction.transactionId())
                ));
            }

            result.add(new PayrollBatchDetailResponse(
                    header.batchId(),
                    header.periodLabel(),
                    header.divisionId(),
                    header.divisionName(),
                    header.runType(),
                    header.runNo(),
                    header.status(),
                    header.makerId(),
                    header.checkerId(),
                    header.employeeCount(),
                    header.grossTotal(),
                    header.deductionTotal(),
                    header.netTotal(),
                    withLines,
                    header.version(),
                    header.wingName(),
                    header.createdAt(),
                    header.submittedAt(),
                    header.makerName(),
                    header.checkerName(),
                    header.finalizedAt()
            ));
        }

        return result.get(0);
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public PayrollTransactionPageResponse getTransactionPage(
            long batchId,
            int page,
            int size,
            String search,
            String hrmsId
    ) {
        rlsContext.setCurrentUser();

        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page must be at least 1 and size must be between 1 and 100."
            );
        }

        String normalizedSearch = normalizeFilter(search, "search");
        String normalizedHrmsId = normalizeFilter(hrmsId, "hrmsId");
        StringBuilder where = new StringBuilder(
                " WHERE st.payroll_batch_id = ?"
        );
        List<Object> arguments = new ArrayList<>();
        arguments.add(batchId);

        if (normalizedSearch != null) {
            where.append("""
                     AND (
                         LOWER(st.hrms_id_snapshot) LIKE LOWER(?)
                         OR LOWER(st.name_snapshot) LIKE LOWER(?)
                     )
                    """);
            String pattern = "%" + normalizedSearch + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (normalizedHrmsId != null) {
            where.append(" AND st.hrms_id_snapshot LIKE ?");
            arguments.add("%" + normalizedHrmsId + "%");
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.salary_transactions st" + where,
                Long.class,
                arguments.toArray()
        );

        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) (page - 1) * size);
        List<PayrollBatchDetailResponse.TransactionDetail> items =
                jdbcTemplate.query(
                        """
                        SELECT st.id, st.hrms_id_snapshot, st.name_snapshot,
                               st.gross_salary, st.total_deductions,
                               st.net_salary, st.status
                        FROM public.salary_transactions st
                        """ + where + """
                        ORDER BY st.hrms_id_snapshot, st.id
                        LIMIT ? OFFSET ?
                        """,
                        (rs, rowNum) -> new PayrollBatchDetailResponse
                                .TransactionDetail(
                                rs.getLong("id"),
                                rs.getString("hrms_id_snapshot"),
                                rs.getString("name_snapshot"),
                                rs.getBigDecimal("gross_salary"),
                                rs.getBigDecimal("total_deductions"),
                                rs.getBigDecimal("net_salary"),
                                rs.getString("status"),
                                List.of()
                        ),
                        pageArguments.toArray()
                );

        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new PayrollTransactionPageResponse(
                page, size, totalElements, totalPages, items
        );
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public PayrollBatchDetailResponse.TransactionDetail getTransactionDetail(
            long batchId,
            long transactionId
    ) {
        rlsContext.setCurrentUser();

        List<PayrollBatchDetailResponse.TransactionDetail> transactions =
                jdbcTemplate.query(
                        """
                        SELECT st.id, st.hrms_id_snapshot, st.name_snapshot,
                               st.gross_salary, st.total_deductions,
                               st.net_salary, st.status
                        FROM public.salary_transactions st
                        WHERE st.payroll_batch_id = ? AND st.id = ?
                        """,
                        (rs, rowNum) -> new PayrollBatchDetailResponse
                                .TransactionDetail(
                                rs.getLong("id"),
                                rs.getString("hrms_id_snapshot"),
                                rs.getString("name_snapshot"),
                                rs.getBigDecimal("gross_salary"),
                                rs.getBigDecimal("total_deductions"),
                                rs.getBigDecimal("net_salary"),
                                rs.getString("status"),
                                new ArrayList<>()
                        ),
                        batchId,
                        transactionId
                );

        if (transactions.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Salary transaction not found or not accessible."
            );
        }

        PayrollBatchDetailResponse.TransactionDetail transaction =
                transactions.get(0);
        return new PayrollBatchDetailResponse.TransactionDetail(
                transaction.transactionId(),
                transaction.hrmsId(),
                transaction.name(),
                transaction.gross(),
                transaction.deductions(),
                transaction.net(),
                transaction.status(),
                transactionLines(transaction.transactionId())
        );
    }

    private List<PayrollBatchDetailResponse.Line> transactionLines(
            long transactionId
    ) {
        return jdbcTemplate.query(
                """
                SELECT salary_component_id, component_code_snapshot,
                       component_name_snapshot, category_snapshot, amount
                FROM public.salary_transaction_lines
                WHERE salary_transaction_id = ?
                ORDER BY category_snapshot DESC, component_code_snapshot
                """,
                (rs, rowNum) -> new PayrollBatchDetailResponse.Line(
                        rs.getLong("salary_component_id"),
                        rs.getString("component_code_snapshot"),
                        rs.getString("component_name_snapshot"),
                        rs.getString("category_snapshot"),
                        rs.getBigDecimal("amount")
                ),
                transactionId
        );
    }

    private static String normalizeFilter(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " must not exceed 100 characters."
            );
        }

        return normalized;
    }

    private long insertBatch(
            long periodId,
            Division division,
            String runType,
            long actorId
    ) {
        Long batchId;

        try {
            batchId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO public.payroll_batches (
                        period_id, wing_id, division_id, run_type, run_no,
                        status, maker_id, division_name_snapshot
                    )
                    VALUES (?, ?, ?, ?, 1, 'DRAFT', ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    periodId,
                    division.wingId(),
                    division.id(),
                    runType,
                    actorId,
                    division.name()
            );
        } catch (DataIntegrityViolationException ex) {
            // Another run created the same period/division/run_type batch
            // between the duplicate check and this insert.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A " + runType + " batch already exists for this period "
                            + "and division. Duplicate batches are not allowed."
            );
        }

        if (batchId == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to create the payroll batch."
            );
        }

        return batchId;
    }

    private long insertTransaction(long batchId, EmployeeRef employee) {
        Long transactionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO public.salary_transactions (
                    payroll_batch_id, employee_id,
                    hrms_id_snapshot, name_snapshot,
                    wing_id_snapshot, division_id_snapshot,
                    designation_id_snapshot, grade_id_snapshot,
                    gross_salary, total_deductions, net_salary, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 'CALCULATED')
                RETURNING id
                """,
                Long.class,
                batchId,
                employee.id(),
                employee.hrmsId(),
                employee.name(),
                employee.wingId(),
                employee.divisionId(),
                employee.designationId(),
                employee.gradeId()
        );

        if (transactionId == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to create a salary transaction."
            );
        }

        return transactionId;
    }

    private Period requireOpenPeriod(long periodId) {
        List<Period> periods = jdbcTemplate.query(
                """
                SELECT id, organization_id, period_year, period_month,
                       period_start, status
                FROM public.payroll_periods
                WHERE id = ?
                """,
                (rs, rowNum) -> new Period(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getInt("period_year"),
                        rs.getInt("period_month"),
                        rs.getDate("period_start").toLocalDate(),
                        rs.getString("status")
                ),
                periodId
        );

        if (periods.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payroll period not found."
            );
        }

        Period period = periods.get(0);

        if (!"OPEN".equals(period.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payroll period is " + period.status()
                            + "; only OPEN periods can be processed."
            );
        }

        return period;
    }

    private Division requireDivision(long divisionId) {
        List<Division> divisions = jdbcTemplate.query(
                """
                SELECT d.id, d.wing_id, d.name, w.organization_id
                FROM public.divisions d
                JOIN public.wings w ON w.id = d.wing_id
                WHERE d.id = ?
                """,
                (rs, rowNum) -> new Division(
                        rs.getLong("id"),
                        rs.getObject("wing_id", Long.class),
                        rs.getString("name"),
                        rs.getLong("organization_id")
                ),
                divisionId
        );

        if (divisions.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Division not found."
            );
        }

        return divisions.get(0);
    }

    private boolean canAccessDivision(long divisionId) {
        Boolean allowed = jdbcTemplate.queryForObject(
                "SELECT app.can_access_division(?)",
                Boolean.class,
                divisionId
        );

        return Boolean.TRUE.equals(allowed);
    }

    private Long existingBatchId(
            long periodId,
            long divisionId,
            String runType
    ) {
        List<Long> ids = jdbcTemplate.query(
                """
                SELECT id FROM public.payroll_batches
                WHERE period_id = ? AND division_id = ? AND run_type = ?
                """,
                (rs, rowNum) -> rs.getLong("id"),
                periodId,
                divisionId,
                runType
        );

        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<Component> loadActiveComponents() {
        return jdbcTemplate.query(
                """
                SELECT id, component_code, component_name, category
                FROM public.salary_components
                WHERE status = 'ACTIVE'
                ORDER BY category, component_code
                """,
                (rs, rowNum) -> new Component(
                        rs.getLong("id"),
                        rs.getString("component_code"),
                        rs.getString("component_name"),
                        rs.getString("category")
                )
        );
    }

    private List<EmployeeRef> loadEmployeesWithValues(
            long divisionId,
            java.time.LocalDate onDate
    ) {
        return jdbcTemplate.query(
                """
                SELECT e.id, e.hrms_id, e.name,
                       e.division_id, d.wing_id, e.designation_id, e.grade_id
                FROM public.employees e
                JOIN public.divisions d ON d.id = e.division_id
                WHERE e.division_id = ?
                  AND e.status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1 FROM public.employee_salary_values v
                      WHERE v.employee_id = e.id
                        AND v.effective_from <= ?
                        AND (v.effective_to IS NULL OR v.effective_to >= ?)
                  )
                ORDER BY e.hrms_id
                """,
                (rs, rowNum) -> new EmployeeRef(
                        rs.getLong("id"),
                        rs.getString("hrms_id"),
                        rs.getString("name"),
                        rs.getLong("division_id"),
                        rs.getObject("wing_id", Long.class),
                        rs.getObject("designation_id", Long.class),
                        rs.getObject("grade_id", Long.class)
                ),
                divisionId,
                java.sql.Date.valueOf(onDate),
                java.sql.Date.valueOf(onDate)
        );
    }

    private Map<Long, BigDecimal> loadEffectiveValues(
            List<EmployeeRef> employees,
            java.time.LocalDate onDate
    ) {
        String placeholders = String.join(",",
                java.util.Collections.nCopies(employees.size(), "?"));

        List<Object> args = new ArrayList<>();

        for (EmployeeRef employee : employees) {
            args.add(employee.id());
        }

        args.add(java.sql.Date.valueOf(onDate));
        args.add(java.sql.Date.valueOf(onDate));

        List<Object[]> rows = jdbcTemplate.query(
                """
                SELECT employee_id, salary_component_id, amount
                FROM public.employee_salary_values
                WHERE employee_id IN (""" + placeholders + """
                )
                  AND effective_from <= ?
                  AND (effective_to IS NULL OR effective_to >= ?)
                ORDER BY employee_id, salary_component_id, effective_from DESC
                """,
                (rs, rowNum) -> new Object[]{
                        rs.getLong("employee_id"),
                        rs.getLong("salary_component_id"),
                        rs.getBigDecimal("amount")
                },
                args.toArray()
        );

        Map<Long, BigDecimal> values = new LinkedHashMap<>();

        for (Object[] row : rows) {
            long employeeId = ((Number) row[0]).longValue();
            long componentId = ((Number) row[1]).longValue();
            BigDecimal amount = (BigDecimal) row[2];

            values.putIfAbsent(key(employeeId, componentId), amount);
        }

        return values;
    }

    private void audit(
            long batchId,
            Long wingId,
            long divisionId,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            int employeeCount
    ) {
        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    'PAYROLL_BATCH_GENERATE', 'PAYROLL_BATCH', ?::varchar,
                    ?::bigint, ?::bigint,
                    NULL::jsonb,
                    jsonb_build_object(
                        'employees', ?::int,
                        'gross', ?::text,
                        'deductions', ?::text,
                        'net', ?::text
                    ),
                    NULL::text, NULL::uuid
                )
                """,
                Long.class,
                String.valueOf(batchId),
                wingId,
                divisionId,
                employeeCount,
                gross.toPlainString(),
                deductions.toPlainString(),
                net.toPlainString()
        );
    }

    private long currentUserId() {
        Long actor = jdbcTemplate.queryForObject(
                "SELECT app.current_user_id()", Long.class
        );

        if (actor == null) {
            throw new IllegalStateException(
                    "Authenticated database user context is missing."
            );
        }

        return actor;
    }

    static String normalizeRunType(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_RUN_TYPE;
        }

        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);

        if (!SUPPORTED_RUN_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported run type '" + normalized + "'. Supported run "
                            + "types: " + String.join(", ", SUPPORTED_RUN_TYPES) + "."
            );
        }

        return normalized;
    }

    private static String periodLabel(int year, int month) {
        return String.format("%04d-%02d", year, month);
    }

    private static long key(long employeeId, long componentId) {
        return employeeId * 1_000_000L + componentId;
    }

    private record Period(
            long id,
            long organizationId,
            int year,
            int month,
            java.time.LocalDate startDate,
            String status
    ) {
        String label() {
            return periodLabel(year, month);
        }
    }

    private record Division(
            long id,
            Long wingId,
            String name,
            long organizationId
    ) {
    }

    private record Component(long id, String code, String name, String category) {
    }

    private record EmployeeRef(
            long id,
            String hrmsId,
            String name,
            long divisionId,
            Long wingId,
            Long designationId,
            Long gradeId
    ) {
    }
}
