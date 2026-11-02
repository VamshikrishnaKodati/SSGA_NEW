package SSGA_Backend.service;

import SSGA_Backend.controller.CreateEmployeeRequest;
import SSGA_Backend.controller.EmployeeResponse;
import SSGA_Backend.controller.EmployeeSalaryResponse;
import SSGA_Backend.controller.EmployeeSalaryValuesResponse;
import SSGA_Backend.controller.UpdateEmployeeRequest;
import SSGA_Backend.security.RlsContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EmployeeService {

    private static final String EMPLOYEE_SELECT = """
            SELECT
                e.id,
                e.employee_code,
                e.hrms_id,
                e.name,
                e.division_id,
                d.name AS division_name,
                d.wing_id,
                w.name AS wing_name,
                o.name AS organization_name,
                e.designation_id,
                des.name AS designation_name,
                e.grade_id,
                g.name AS grade_name,
                e.contact,
                e.place_of_work,
                e.date_of_birth,
                e.date_of_joining,
                e.employment_type,
                e.status,
                e.pay_scale,
                e.department,
                e.circle_id,
                ci.name AS circle_name,
                e.ward_id,
                wa.name AS ward_name,
                e.payroll_unit_id,
                pu.name AS payroll_unit_name
            FROM public.employees e
            LEFT JOIN public.divisions d ON d.id = e.division_id
            LEFT JOIN public.wings w ON w.id = d.wing_id
            LEFT JOIN public.organizations o ON o.id = w.organization_id
            LEFT JOIN public.designations des ON des.id = e.designation_id
            LEFT JOIN public.grades g ON g.id = e.grade_id
            LEFT JOIN public.circles ci ON ci.id = e.circle_id
            LEFT JOIN public.wards wa ON wa.id = e.ward_id
            LEFT JOIN public.payroll_units pu ON pu.id = e.payroll_unit_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public EmployeeService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    // =========================================================
    // GET ALL EMPLOYEES
    // =========================================================

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public List<EmployeeResponse> findAll() {

        rlsContext.setCurrentUser();

        return jdbcTemplate.query(
                EMPLOYEE_SELECT + " ORDER BY e.id",
                (rs, rowNum) -> map(rs)
        );
    }

    // =========================================================
    // GET EMPLOYEE BY ID (with identities + bank, masked by role)
    // =========================================================

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public Optional<EmployeeResponse> findById(long id) {

        rlsContext.setCurrentUser();

        Optional<EmployeeResponse> base = jdbcTemplate.query(
                EMPLOYEE_SELECT + " WHERE e.id = ?",
                (rs, rowNum) -> map(rs),
                id
        ).stream().findFirst();

        return base.map(row -> attachDetails(row, hasPrivilegedView()));
    }

    // =========================================================
    // CREATE EMPLOYEE
    // =========================================================

    @Transactional(
            transactionManager = "dataSourceTransactionManager"
    )
    public EmployeeResponse create(CreateEmployeeRequest request) {

        String employeeCode = request.employeeCode() == null
                ? null
                : request.employeeCode().trim();

        String hrmsId = request.hrmsId() == null
                ? ""
                : request.hrmsId().trim();

        if (employeeCode == null || employeeCode.isBlank()) {
            throw new IllegalArgumentException(
                    "Employee code is required"
            );
        }

        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException(
                    "Employee name is required"
            );
        }

        if (request.divisionId() <= 0) {
            throw new IllegalArgumentException(
                    "Division is required"
            );
        }

        if (hrmsId.isBlank()) {
            hrmsId = generateTemporaryHrmsId();
        }

        final String finalHrmsId = hrmsId;

        long employeeId = jdbcTemplate.execute((Connection connection) -> {

            rlsContext.setCurrentUser(connection);

            requireCanCreate(connection, request.divisionId());

            if (request.payrollUnitId() != null && request.payrollUnitId() > 0) {
                requireActiveUnit(connection, request.payrollUnitId());
            }

            long id = insertEmployee(
                    connection,
                    employeeCode,
                    finalHrmsId,
                    request
            );

            saveIdentities(connection, id, request);
            savePrimaryBank(connection, id, request);

            audit(connection, "EMPLOYEE_CREATE", id, employeeCode, request.name());

            return id;
        });

        return loadDetail(employeeId, true);
    }

    // =========================================================
    // UPDATE EMPLOYEE
    // =========================================================

    @Transactional(
            transactionManager = "dataSourceTransactionManager"
    )
    public EmployeeResponse update(long id, UpdateEmployeeRequest request) {

        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException(
                    "Employee name is required"
            );
        }

        jdbcTemplate.execute((Connection connection) -> {

            rlsContext.setCurrentUser(connection);

            requireCanAccess(connection, id);

            Long[] refs = readRefs(connection, id);

            Long circleId = resolveRef(request.circleId(), refs[0]);
            Long wardId = resolveRef(request.wardId(), refs[1]);
            Long payrollUnitId = resolveRef(request.payrollUnitId(), refs[2]);

            if (payrollUnitId != null) {
                requireActiveUnit(connection, payrollUnitId);
            }

            String updateSql = """
                    UPDATE public.employees
                    SET name = ?,
                        hrms_id = COALESCE(?, hrms_id),
                        designation_id = ?,
                        grade_id = ?,
                        contact = ?,
                        place_of_work = ?,
                        date_of_birth = ?,
                        date_of_joining = ?,
                        employment_type = ?,
                        status = COALESCE(?, status),
                        pay_scale = ?,
                        department = ?,
                        circle_id = ?,
                        ward_id = ?,
                        payroll_unit_id = ?,
                        updated_at = now()
                    WHERE id = ?
                    """;

            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {

                statement.setString(1, request.name().trim());
                setNullableString(statement, 2, request.hrmsId());
                setNullableLong(statement, 3, request.designationId());
                setNullableLong(statement, 4, request.gradeId());
                setNullableString(statement, 5, request.contact());
                setNullableString(statement, 6, request.placeOfWork());
                setNullableDate(statement, 7, request.dateOfBirth());
                setNullableDate(statement, 8, request.dateOfJoining());
                setNullableString(statement, 9, request.employmentType());
                setNullableString(statement, 10, normalizeStatus(request.status()));
                setNullableString(statement, 11, request.payScale());
                setNullableString(statement, 12, request.department());
                setNullableLong(statement, 13, circleId);
                setNullableLong(statement, 14, wardId);
                setNullableLong(statement, 15, payrollUnitId);
                statement.setLong(16, id);

                try {
                    int updated = statement.executeUpdate();

                    if (updated == 0) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Employee not found or not in your scope"
                        );
                    }
                } catch (SQLException e) {
                    throw translateDuplicate(
                            e,
                            "The new HRMS id is already used by another employee."
                    );
                }
            }

            saveIdentities(connection, id, request);
            savePrimaryBank(connection, id, request);

            audit(connection, "EMPLOYEE_UPDATE", id, null, request.name());

            return null;
        });

        return loadDetail(id, true);
    }

    // =========================================================
    // EMPLOYEE SALARY STRUCTURE (all active components; configured or not)
    // =========================================================

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public EmployeeSalaryResponse salaryStructure(long employeeId) {

        rlsContext.setCurrentUser();

        Optional<EmployeeResponse> employee = findById(employeeId);

        if (employee.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Employee not found or not in your scope"
            );
        }

        LocalDate asOf = LocalDate.now();

        List<EmployeeSalaryResponse.Component> components = jdbcTemplate.query(
                """
                SELECT c.id AS component_id,
                       c.component_code,
                       c.component_name,
                       c.category,
                       v.amount
                FROM public.salary_components c
                LEFT JOIN (
                    SELECT DISTINCT ON (salary_component_id)
                           salary_component_id, amount
                    FROM public.employee_salary_values
                    WHERE employee_id = ?
                      AND effective_from <= ?
                      AND (effective_to IS NULL OR effective_to >= ?)
                    ORDER BY salary_component_id, effective_from DESC
                ) v ON v.salary_component_id = c.id
                WHERE c.status = 'ACTIVE'
                ORDER BY (c.category = 'EARNING') DESC,
                         c.display_order NULLS LAST,
                         c.component_code
                """,
                (rs, rowNum) -> new EmployeeSalaryResponse.Component(
                        rs.getLong("component_id"),
                        rs.getString("component_code"),
                        rs.getString("component_name"),
                        rs.getString("category"),
                        rs.getBigDecimal("amount")
                ),
                employeeId,
                java.sql.Date.valueOf(asOf),
                java.sql.Date.valueOf(asOf)
        );

        BigDecimal totalEarnings = BigDecimal.ZERO;
        BigDecimal totalDeductions = BigDecimal.ZERO;
        BigDecimal basicPay = null;
        int configured = 0;

        for (EmployeeSalaryResponse.Component component : components) {

            BigDecimal amount = component.amount();

            if (amount != null) {
                configured++;

                if ("EARNING".equals(component.category())) {
                    totalEarnings = totalEarnings.add(amount);
                } else {
                    totalDeductions = totalDeductions.add(amount);
                }

                if ("BASIC_PAY".equalsIgnoreCase(component.componentCode())) {
                    basicPay = amount;
                }
            }
        }

        BigDecimal gross = totalEarnings;
        BigDecimal net = gross.subtract(totalDeductions);

        String note = configured == 0
                ? "No salary structure is configured for this employee."
                : null;

        EmployeeSalaryResponse.LatestRun latestRun = findLatestRun(employeeId);

        return new EmployeeSalaryResponse(
                employee.get().id(),
                employee.get().employeeCode(),
                employee.get().name(),
                asOf.toString(),
                basicPay,
                totalEarnings,
                gross,
                totalDeductions,
                net,
                configured,
                components.size(),
                note,
                components,
                latestRun
        );
    }

    private EmployeeSalaryResponse.LatestRun findLatestRun(long employeeId) {

        return jdbcTemplate.query(
                """
                SELECT p.period_year, p.period_month, t.status,
                       t.gross_salary, t.total_deductions, t.net_salary
                FROM public.salary_transactions t
                JOIN public.payroll_batches b ON b.id = t.payroll_batch_id
                JOIN public.payroll_periods p ON p.id = b.period_id
                WHERE t.employee_id = ?
                ORDER BY p.period_year DESC, p.period_month DESC, b.id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new EmployeeSalaryResponse.LatestRun(
                        periodLabel(
                                rs.getInt("period_year"),
                                rs.getInt("period_month")
                        ),
                        rs.getString("status"),
                        rs.getBigDecimal("gross_salary"),
                        rs.getBigDecimal("total_deductions"),
                        rs.getBigDecimal("net_salary")
                ),
                employeeId
        ).stream().findFirst().orElse(null);
    }

    // =========================================================
    // ALL EMPLOYEES' EFFECTIVE COMPONENT VALUES (bulk, for the master grid)
    // =========================================================

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public List<EmployeeSalaryValuesResponse> allSalaryValues() {

        rlsContext.setCurrentUser();

        java.sql.Date asOf = java.sql.Date.valueOf(LocalDate.now());

        Map<Long, Map<String, BigDecimal>> byEmployee = new LinkedHashMap<>();

        jdbcTemplate.query(
                """
                SELECT DISTINCT ON (v.employee_id, v.salary_component_id)
                       v.employee_id,
                       c.component_code,
                       v.amount
                FROM public.employee_salary_values v
                JOIN public.salary_components c ON c.id = v.salary_component_id
                WHERE c.status = 'ACTIVE'
                  AND v.effective_from <= ?
                  AND (v.effective_to IS NULL OR v.effective_to >= ?)
                ORDER BY v.employee_id, v.salary_component_id, v.effective_from DESC
                """,
                rs -> {
                    byEmployee
                            .computeIfAbsent(
                                    rs.getLong("employee_id"),
                                    key -> new LinkedHashMap<>()
                            )
                            .put(rs.getString("component_code"), rs.getBigDecimal("amount"));
                },
                asOf,
                asOf
        );

        return byEmployee.entrySet().stream()
                .map(entry -> new EmployeeSalaryValuesResponse(
                        entry.getKey(),
                        entry.getValue()
                ))
                .toList();
    }

    // =========================================================
    // MANUAL SALARY COMPONENT ENTRY
    // =========================================================

    @Transactional(
            transactionManager = "dataSourceTransactionManager"
    )
    public EmployeeSalaryResponse setSalaryComponent(
            long employeeId,
            long componentId,
            BigDecimal amount,
            LocalDate effectiveFrom
    ) {

        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException(
                    "Salary amount is required and cannot be negative"
            );
        }

        LocalDate effectiveDate = effectiveFrom == null
                ? LocalDate.now()
                : effectiveFrom;

        jdbcTemplate.execute((Connection connection) -> {

            rlsContext.setCurrentUser(connection);

            requireCanAccess(connection, employeeId);

            String componentCode;

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT component_code FROM public.salary_components
                    WHERE id = ? AND status = 'ACTIVE'
                    """
            )) {
                statement.setLong(1, componentId);

                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException(
                                "Salary component not found or inactive"
                        );
                    }
                    componentCode = rs.getString(1);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO public.employee_salary_values (
                        employee_id, salary_component_id, amount,
                        effective_from, source
                    )
                    VALUES (?, ?, ?, ?, 'MANUAL')
                    ON CONFLICT (employee_id, salary_component_id, effective_from)
                    DO UPDATE SET amount = EXCLUDED.amount,
                                  source = EXCLUDED.source,
                                  updated_at = now()
                    """
            )) {
                statement.setLong(1, employeeId);
                statement.setLong(2, componentId);
                statement.setBigDecimal(3, amount);
                statement.setDate(4, java.sql.Date.valueOf(effectiveDate));
                statement.executeUpdate();
            }

            audit(
                    connection,
                    "EMPLOYEE_SALARY_UPDATE",
                    employeeId,
                    componentCode,
                    amount.toPlainString()
            );

            return null;
        });

        return salaryStructure(employeeId);
    }

    // =========================================================
    // DETAIL LOADING (identities + bank, masked by role)
    // =========================================================
    private EmployeeResponse loadDetail(long id, boolean privileged) {

        EmployeeResponse base = jdbcTemplate.query(
                EMPLOYEE_SELECT + " WHERE e.id = ?",
                (rs, rowNum) -> map(rs),
                id
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Employee could not be read back"
        ));

        return attachDetails(base, privileged);
    }

    private EmployeeResponse attachDetails(EmployeeResponse base, boolean privileged) {

        return new EmployeeResponse(
                base.id(),
                base.employeeCode(),
                base.hrmsId(),
                base.name(),
                base.divisionId(),
                base.divisionName(),
                base.wingId(),
                base.wingName(),
                base.organizationName(),
                base.designationId(),
                base.designationName(),
                base.gradeId(),
                base.gradeName(),
                base.contact(),
                base.placeOfWork(),
                base.dateOfBirth(),
                base.dateOfJoining(),
                base.employmentType(),
                base.status(),
                base.payScale(),
                base.department(),
                base.circleId(),
                base.circleName(),
                base.wardId(),
                base.wardName(),
                base.payrollUnitId(),
                base.payrollUnitName(),
                loadIdentities(base.id(), privileged),
                loadBankAccounts(base.id(), privileged)
        );
    }

    private List<EmployeeResponse.Identity> loadIdentities(
            long employeeId,
            boolean privileged
    ) {

        return jdbcTemplate.query(
                """
                SELECT identity_type, identity_value
                FROM public.employee_identity_mappings
                WHERE employee_id = ? AND status = 'ACTIVE'
                ORDER BY identity_type
                """,
                (rs, rowNum) -> {
                    String value = rs.getString("identity_value");

                    return new EmployeeResponse.Identity(
                            rs.getString("identity_type"),
                            privileged ? value : mask(value),
                            !privileged
                    );
                },
                employeeId
        );
    }

    private List<EmployeeResponse.BankAccount> loadBankAccounts(
            long employeeId,
            boolean privileged
    ) {

        return jdbcTemplate.query(
                """
                SELECT id, bank_name, branch_name, account_number,
                       ifsc_code, is_primary, status
                FROM public.employee_bank_accounts
                WHERE employee_id = ?
                ORDER BY is_primary DESC, id
                """,
                (rs, rowNum) -> new EmployeeResponse.BankAccount(
                        rs.getLong("id"),
                        rs.getString("bank_name"),
                        rs.getString("branch_name"),
                        privileged
                                ? rs.getString("account_number")
                                : mask(rs.getString("account_number")),
                        rs.getString("ifsc_code"),
                        rs.getBoolean("is_primary"),
                        rs.getString("status")
                ),
                employeeId
        );
    }

    // =========================================================
    // WRITE HELPERS
    // =========================================================

    private long insertEmployee(
            Connection connection,
            String employeeCode,
            String hrmsId,
            CreateEmployeeRequest request
    ) throws SQLException {

        String insertSql = """
                INSERT INTO employees
                (
                    employee_code, hrms_id, division_id, name,
                    designation_id, grade_id, contact, place_of_work,
                    date_of_birth, date_of_joining, employment_type,
                    status, pay_scale, department, circle_id, ward_id,
                    payroll_unit_id
                )
                VALUES
                (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                RETURNING id
                """;

        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {

            statement.setString(1, employeeCode);
            statement.setString(2, hrmsId);
            statement.setLong(3, request.divisionId());
            statement.setString(4, request.name().trim());
            setNullableLong(statement, 5, request.designationId());
            setNullableLong(statement, 6, request.gradeId());
            setNullableString(statement, 7, request.contact());
            setNullableString(statement, 8, request.placeOfWork());
            setNullableDate(statement, 9, request.dateOfBirth());
            setNullableDate(statement, 10, request.dateOfJoining());
            setNullableString(statement, 11, request.employmentType());

            String status = request.status() == null || request.status().isBlank()
                    ? "ACTIVE"
                    : request.status().trim();

            statement.setString(12, status);
            setNullableString(statement, 13, request.payScale());
            setNullableString(statement, 14, request.department());
            setNullableLong(statement, 15, positiveOrNull(request.circleId()));
            setNullableLong(statement, 16, positiveOrNull(request.wardId()));
            setNullableLong(statement, 17, positiveOrNull(request.payrollUnitId()));

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Employee was created but no id was returned"
                    );
                }

                return rs.getLong(1);
            } catch (SQLException e) {
                throw translateDuplicate(
                        e,
                        "An employee with this employee code or HRMS id already exists."
                );
            }
        }
    }

    private void saveIdentities(
            Connection connection,
            long employeeId,
            CreateEmployeeRequest request
    ) throws SQLException {

        saveIdentity(connection, employeeId, "PAN", request.pan());
        saveIdentity(connection, employeeId, "AADHAAR", request.aadhaar());
        saveIdentity(connection, employeeId, "PF", request.pf());
        saveIdentity(connection, employeeId, "NPS", request.nps());
        saveIdentity(connection, employeeId, "GPF", request.gpf());
        saveIdentity(connection, employeeId, "EMAIL", request.email());
    }

    private void saveIdentities(
            Connection connection,
            long employeeId,
            UpdateEmployeeRequest request
    ) throws SQLException {

        saveIdentity(connection, employeeId, "PAN", request.pan());
        saveIdentity(connection, employeeId, "AADHAAR", request.aadhaar());
        saveIdentity(connection, employeeId, "PF", request.pf());
        saveIdentity(connection, employeeId, "NPS", request.nps());
        saveIdentity(connection, employeeId, "GPF", request.gpf());
        saveIdentity(connection, employeeId, "EMAIL", request.email());
    }

    /**
     * null = unchanged, blank = clear, value = set.
     */
    private void saveIdentity(
            Connection connection,
            long employeeId,
            String type,
            String value
    ) throws SQLException {

        if (value == null) {
            return;
        }

        String trimmed = value.trim();

        if (trimmed.isEmpty()) {

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    DELETE FROM public.employee_identity_mappings
                    WHERE employee_id = ? AND identity_type = ?
                    """
            )) {
                statement.setLong(1, employeeId);
                statement.setString(2, type);
                statement.executeUpdate();
            }

            return;
        }

        int updated;

        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE public.employee_identity_mappings
                SET identity_value = ?, updated_at = now()
                WHERE employee_id = ? AND identity_type = ? AND status = 'ACTIVE'
                """
        )) {
            statement.setString(1, trimmed);
            statement.setLong(2, employeeId);
            statement.setString(3, type);

            try {
                updated = statement.executeUpdate();
            } catch (SQLException e) {
                throw translateDuplicate(
                        e,
                        "This " + type + " value is already mapped to another employee."
                );
            }
        }

        if (updated > 0) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO public.employee_identity_mappings
                    (employee_id, identity_type, identity_value, is_primary, status)
                VALUES (?, ?, ?, true, 'ACTIVE')
                """
        )) {
            statement.setLong(1, employeeId);
            statement.setString(2, type);
            statement.setString(3, trimmed);

            try {
                statement.executeUpdate();
            } catch (SQLException e) {
                throw translateDuplicate(
                        e,
                        "This " + type + " value is already mapped to another employee."
                );
            }
        }
    }

    private void savePrimaryBank(
            Connection connection,
            long employeeId,
            CreateEmployeeRequest request
    ) throws SQLException {

        savePrimaryBank(
                connection,
                employeeId,
                request.bankName(),
                request.branchName(),
                request.bankAccountNumber(),
                request.ifscCode(),
                true
        );
    }

    private void savePrimaryBank(
            Connection connection,
            long employeeId,
            UpdateEmployeeRequest request
    ) throws SQLException {

        savePrimaryBank(
                connection,
                employeeId,
                request.bankName(),
                request.branchName(),
                request.bankAccountNumber(),
                request.ifscCode(),
                false
        );
    }

    private void savePrimaryBank(
            Connection connection,
            long employeeId,
            String bankName,
            String branchName,
            String accountNumber,
            String ifscCode,
            boolean creating
    ) throws SQLException {

        boolean anyProvided = bankName != null
                || branchName != null
                || accountNumber != null
                || ifscCode != null;

        if (!anyProvided) {
            return;
        }

        boolean allBlank = isBlank(bankName)
                && isBlank(branchName)
                && isBlank(accountNumber)
                && isBlank(ifscCode);

        if (allBlank) {

            if (creating) {
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    DELETE FROM public.employee_bank_accounts
                    WHERE employee_id = ? AND is_primary = true
                    """
            )) {
                statement.setLong(1, employeeId);
                statement.executeUpdate();
            }

            return;
        }

        if (isBlank(bankName) || isBlank(accountNumber)) {
            throw new IllegalArgumentException(
                    "Bank name and account number must be provided together."
            );
        }

        Long existingId = findPrimaryBankId(connection, employeeId);

        if (existingId != null) {

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE public.employee_bank_accounts
                    SET bank_name = ?, branch_name = ?, account_number = ?,
                        ifsc_code = ?, updated_at = now()
                    WHERE id = ?
                    """
            )) {
                statement.setString(1, bankName.trim());
                setNullableString(statement, 2, branchName);
                statement.setString(3, accountNumber.trim());
                setNullableString(statement, 4, ifscCode);
                statement.setLong(5, existingId);

                try {
                    statement.executeUpdate();
                } catch (SQLException e) {
                    throw translateDuplicate(
                            e,
                            "This bank account number is already recorded for the employee."
                    );
                }
            }

            return;
        }

        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO public.employee_bank_accounts
                    (employee_id, bank_name, branch_name, account_number,
                     ifsc_code, is_primary, status)
                VALUES (?, ?, ?, ?, ?, true, 'ACTIVE')
                """
        )) {
            statement.setLong(1, employeeId);
            statement.setString(2, bankName.trim());
            setNullableString(statement, 3, branchName);
            statement.setString(4, accountNumber.trim());
            setNullableString(statement, 5, ifscCode);

            try {
                statement.executeUpdate();
            } catch (SQLException e) {
                throw translateDuplicate(
                        e,
                        "This bank account number is already recorded for the employee."
                );
            }
        }
    }

    private Long findPrimaryBankId(Connection connection, long employeeId)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT id FROM public.employee_bank_accounts
                WHERE employee_id = ? AND is_primary = true
                ORDER BY id LIMIT 1
                """
        )) {
            statement.setLong(1, employeeId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void audit(
            Connection connection,
            String action,
            long employeeId,
            String code,
            String name
    ) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT app.write_audit_log(
                    ?::varchar, 'EMPLOYEE', ?::varchar,
                    NULL::bigint, app.current_user_id(),
                    NULL::jsonb,
                    jsonb_build_object('code', ?::text, 'name', ?::text),
                    NULL::text, NULL::uuid
                )
                """
        )) {
            statement.setString(1, action);
            statement.setString(2, String.valueOf(employeeId));
            statement.setString(3, code);
            statement.setString(4, name);
            statement.executeQuery();
        }
    }

    // =========================================================
    // AUTHORIZATION HELPERS
    // =========================================================

    private void requireCanCreate(Connection connection, long divisionId)
            throws SQLException {

        boolean allowed;

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT app.can_create_employee(?)"
        )) {
            statement.setLong(1, divisionId);

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Unable to verify employee creation scope"
                    );
                }

                allowed = rs.getBoolean(1);
            }
        }

        if (!allowed) {
            throw new AccessDeniedException(
                    "You are not allowed to create an employee in this division"
            );
        }
    }

    private void requireCanAccess(Connection connection, long id)
            throws SQLException {

        boolean allowed;

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT app.can_access_employee(?)"
        )) {
            statement.setLong(1, id);

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Unable to verify employee access scope"
                    );
                }

                allowed = rs.getBoolean(1);
            }
        }

        if (!allowed) {
            throw new AccessDeniedException(
                    "You are not allowed to update this employee"
            );
        }
    }

    // =========================================================
    // SMALL HELPERS
    // =========================================================

    private Long[] readRefs(Connection connection, long id)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT circle_id, ward_id, payroll_unit_id
                FROM public.employees WHERE id = ?
                """
        )) {
            statement.setLong(1, id);

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Employee not found or not in your scope"
                    );
                }

                return new Long[]{
                        rs.getObject("circle_id", Long.class),
                        rs.getObject("ward_id", Long.class),
                        rs.getObject("payroll_unit_id", Long.class)
                };
            }
        }
    }

    private void requireActiveUnit(Connection connection, long payrollUnitId)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM public.payroll_units WHERE id = ? AND status = 'ACTIVE'"
        )) {
            statement.setLong(1, payrollUnitId);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                            "The selected payroll unit does not exist or is inactive"
                    );
                }
            }
        }
    }

    /** null = unchanged, <= 0 = clear, > 0 = set. */
    private static Long resolveRef(Long requested, Long current) {

        if (requested == null) {
            return current;
        }

        return requested > 0 ? requested : null;
    }

    private static Long positiveOrNull(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private static String normalizeStatus(String status) {

        if (status == null || status.isBlank()) {
            return null;
        }

        String trimmed = status.trim();

        if (trimmed.equals("ACTIVE") || trimmed.equals("INACTIVE")) {
            return trimmed;
        }

        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String mask(String value) {

        if (value == null || value.isBlank()) {
            return value;
        }

        String trimmed = value.trim();

        if (trimmed.length() <= 4) {
            return "••••";
        }

        return "••••" + trimmed.substring(trimmed.length() - 4);
    }

    private static boolean hasPrivilegedView() {

        var authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null) {
            return false;
        }

        return authentication.getAuthorities().stream().anyMatch(authority ->
                "employee.update".equals(authority.getAuthority())
                        || "salary.manage".equals(authority.getAuthority())
        );
    }

    private static ResponseStatusException translateDuplicate(
            SQLException e,
            String message
    ) {

        if ("23505".equals(e.getSQLState())) {
            return new ResponseStatusException(HttpStatus.CONFLICT, message);
        }

        throw new IllegalStateException(
                "Unexpected database error: " + e.getMessage(),
                e
        );
    }

    private static void setNullableString(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {

        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.trim());
        }
    }

    private static void setNullableLong(
            PreparedStatement statement,
            int index,
            Long value
    ) throws SQLException {

        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableDate(
            PreparedStatement statement,
            int index,
            LocalDate value
    ) throws SQLException {

        if (value == null) {
            statement.setNull(index, Types.DATE);
        } else {
            statement.setObject(index, value);
        }
    }

    private static EmployeeResponse map(ResultSet rs) throws SQLException {

        java.sql.Date dob = rs.getDate("date_of_birth");
        java.sql.Date doj = rs.getDate("date_of_joining");

        return new EmployeeResponse(
                rs.getLong("id"),
                rs.getString("employee_code"),
                rs.getString("hrms_id"),
                rs.getString("name"),
                rs.getLong("division_id"),
                rs.getString("division_name"),
                rs.getObject("wing_id", Long.class),
                rs.getString("wing_name"),
                rs.getString("organization_name"),
                rs.getObject("designation_id", Long.class),
                rs.getString("designation_name"),
                rs.getObject("grade_id", Long.class),
                rs.getString("grade_name"),
                rs.getString("contact"),
                rs.getString("place_of_work"),
                dob == null ? null : dob.toLocalDate(),
                doj == null ? null : doj.toLocalDate(),
                rs.getString("employment_type"),
                rs.getString("status"),
                rs.getString("pay_scale"),
                rs.getString("department"),
                rs.getObject("circle_id", Long.class),
                rs.getString("circle_name"),
                rs.getObject("ward_id", Long.class),
                rs.getString("ward_name"),
                rs.getObject("payroll_unit_id", Long.class),
                rs.getString("payroll_unit_name"),
                List.of(),
                List.of()
        );
    }

    private String generateTemporaryHrmsId() {

        Long nextNumber = jdbcTemplate.queryForObject(
                "SELECT nextval('employee_temp_hrms_seq')",
                Long.class
        );

        if (nextNumber == null) {
            throw new IllegalStateException(
                    "Unable to generate temporary HRMS ID"
            );
        }

        return String.format("T-%04d", nextNumber);
    }

    private static String periodLabel(int year, int month) {
        return String.format("%04d-%02d", year, month);
    }
}
