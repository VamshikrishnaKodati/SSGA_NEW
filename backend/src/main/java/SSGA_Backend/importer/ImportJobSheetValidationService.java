
package SSGA_Backend.importer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ImportJobSheetValidationService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final Path storageRoot;

    public ImportJobSheetValidationService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper,
            @Value("${ssga.import.storage-dir:}") String storageDirectory
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;

        Path configured = storageDirectory == null
                || storageDirectory.isBlank()
                ? Paths.get(
                        System.getProperty("user.home"),
                        "ssga-data",
                        "imports"
                )
                : Paths.get(storageDirectory);

        this.storageRoot = configured.toAbsolutePath().normalize();
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public ImportJobSheetValidationResponse validate(
            long jobId,
            int sheetIndex
    ) throws IOException {

        if (jobId <= 0 || sheetIndex < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A valid job ID and worksheet index are required."
            );
        }

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal()
                        instanceof SsgaPrincipal user)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated user not found."
            );
        }

        rlsContext.setCurrentUser();

        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                """
                SELECT
                    s.id AS sheet_id,
                    s.sheet_name,
                    s.confirmed_type,
                    s.status AS sheet_status,
                    s.header_row_number,
                    s.headers::text AS headers_json,
                    s.column_mapping::text AS mapping_json,
                    COALESCE(j.corrected_file_key, j.file_key) AS file_key
                FROM public.import_job_sheets s
                JOIN public.import_jobs j
                  ON j.id = s.import_job_id
                WHERE s.import_job_id = ?
                  AND s.sheet_index = ?
                  AND j.uploaded_by = ?
                """,
                jobId,
                sheetIndex,
                user.getUserId()
        );

        if (records.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Import job or worksheet was not found."
            );
        }

        Map<String, Object> record = records.get(0);

        String confirmedType = (String) record.get("confirmed_type");

        if (!Set.of(
                "SALARY_ENTITLEMENTS",
                "EMPLOYEE_MASTER",
                "EMPLOYEE_MASTER_AND_COMPONENTS",
                "PAYROLL_HISTORY",
                "SALARY_REVISIONS"
        ).contains(confirmedType)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Confirm the worksheet as SALARY_ENTITLEMENTS, "
                            + "EMPLOYEE_MASTER, EMPLOYEE_MASTER_AND_COMPONENTS, "
                            + "PAYROLL_HISTORY or SALARY_REVISIONS before validation."
            );
        }

        String existingStatus = (String) record.get("sheet_status");

        if (!Set.of("MAPPED", "VALIDATED", "HAS_ERRORS")
                .contains(existingStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Worksheet must be mapped before validation. Current status: "
                            + existingStatus
            );
        }

        SheetMapping mapping = SheetMapping.parse(
                jsonMapper, (String) record.get("mapping_json"));

        Map<String, String> columns = mapping.columns();

        if ("EMPLOYEE_MASTER_AND_COMPONENTS".equals(confirmedType)) {
            return validateSupplementaryRows(
                    jobId, sheetIndex, record, confirmedType, mapping,
                    user.getUserId()
            );
        }

        if (!"SALARY_ENTITLEMENTS".equals(confirmedType)) {
            return validateSupplementaryRows(
                    jobId, sheetIndex, record, confirmedType, mapping,
                    user.getUserId()
            );
        }

        String effectiveFromDefault = mapping.effectiveFromDefault();
        LocalDate defaultEffectiveFrom = effectiveFromDefault == null
                ? null
                : ImportCells.parseDate(effectiveFromDefault);

        if (effectiveFromDefault != null && defaultEffectiveFrom == null) {
            throw badRequest(
                    "The default effective date must use YYYY-MM-DD."
            );
        }

        if (!columns.containsKey("employeeCode")
                && !columns.containsKey("hrmsId")
                && !hasAnyIdentityColumn(columns)) {
            throw badRequest(
                    "Map an employee identifier (employee code, HRMS ID, "
                            + "Aadhaar, PAN, phone, email, PF, NPS or GPF) "
                            + "before validation."
            );
        }

        for (String required : List.of("componentCode", "amount")) {
            if (!columns.containsKey(required)) {
                throw badRequest(
                        "Required column mapping is missing: " + required
                );
            }
        }

        if (!columns.containsKey("effectiveFrom")
                && defaultEffectiveFrom == null) {
            throw badRequest(
                    "Map an effectiveFrom column or set a default "
                            + "effective date before validation."
            );
        }

        if (!"LONG".equals(mapping.layout())) {
            throw badRequest(
                    "This validator currently supports LONG salary-entitlement "
                            + "layout. Wide-layout validation will be added separately."
            );
        }

        String fileKey = (String) record.get("file_key");

        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (!filePath.startsWith(storageRoot)
                || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stored workbook was not found."
            );
        }

        long sheetId = ((Number) record.get("sheet_id")).longValue();

        int headerRowNumber =
                ((Number) record.get("header_row_number")).intValue();

        if (headerRowNumber < 1) {
            throw badRequest("The worksheet header row is not set.");
        }

        String sheetName = (String) record.get("sheet_name");

        String[] headerArray = jsonMapper.readValue(
                (String) record.get("headers_json"),
                String[].class
        );

        List<String> headers = Arrays.asList(headerArray);

        Map<String, Integer> columnIndexes =
                createColumnIndexes(headers);

        // Verify each mapped source header is present exactly once.
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            if (!columnIndexes.containsKey(entry.getValue())) {
                throw badRequest(
                        "Mapped source header is missing: "
                                + entry.getValue()
                );
            }
        }

        DataFormatter formatter = new DataFormatter();
        List<RowData> rows;

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {

            if (sheetIndex >= workbook.getNumberOfSheets()) {
                throw badRequest("Worksheet index does not exist.");
            }

            var sheet = workbook.getSheetAt(sheetIndex);

            if (!sheetName.equals(sheet.getSheetName())) {
                throw badRequest(
                        "Stored worksheet name does not match the workbook."
                );
            }

            int headerIndex = headerRowNumber - 1;

            if (sheet.getRow(headerIndex) == null) {
                throw badRequest("The worksheet header row is missing.");
            }

            rows = readRows(
                    sheet,
                    headerIndex,
                    columnIndexes,
                    columns,
                    formatter,
                    defaultEffectiveFrom
            );

        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read the worksheet for validation.",
                    ex
            );
        }

        // Fetch employee and component reference data in batches.
        Map<String, EmployeeRecord> employeesByCode = new HashMap<>();
        Map<String, EmployeeRecord> employeesByHrms = new HashMap<>();
        Map<String, EmployeeRecord> employeesByIdentity = new HashMap<>();

        loadEmployees(
                rows,
                employeesByCode,
                employeesByHrms,
                employeesByIdentity
        );

        Map<String, ComponentRecord> components = loadComponents();

        for (RowData row : rows) {
            validateReferences(
                    row,
                    columns,
                    employeesByCode,
                    employeesByHrms,
                    employeesByIdentity,
                    components
            );
        }

        // Duplicate entitlement keys inside the same upload are errors.
        markFileDuplicates(rows);

        // Do not silently overwrite existing effective-dated entitlements.
        Map<EmployeeComponentKey, List<ExistingInterval>> existingValues =
                loadExistingIntervals(rows);

        checkExistingIntervals(rows, existingValues);

        return completeValidation(
                jobId, sheetIndex, sheetId, sheetName, user.getUserId(),
                rows, List.of(), confirmedType, components
        );
    }

    /**
     * Aggregate non-skipped worksheet results back onto the import job so that
     * submit can require a validated job with a positive valid-row count. The
     * job is only advanced while it is still in a pre-submission state.
     */
    void refreshJobAggregate(long jobId) {
        jdbcTemplate.update(
                """
                UPDATE public.import_jobs j
                SET total_rows = COALESCE(agg.total_rows, 0),
                    valid_rows = COALESCE(agg.valid_rows, 0),
                    error_rows = COALESCE(agg.error_rows, 0),
                    warning_rows = COALESCE(agg.warning_rows, 0),
                    status = CASE
                        WHEN agg.has_errors THEN 'HAS_ERRORS'
                        WHEN agg.sheet_count = 0 THEN 'UPLOADED'
                        WHEN agg.pending = 0 THEN 'VALIDATED'
                        ELSE j.status
                    END,
                    validated_at = CASE
                        WHEN agg.sheet_count > 0 AND agg.pending = 0
                             AND NOT agg.has_errors
                            THEN COALESCE(j.validated_at, now())
                        ELSE j.validated_at
                    END
                FROM (
                    SELECT
                        COUNT(*) AS sheet_count,
                        COUNT(*) FILTER (
                            WHERE status NOT IN ('VALIDATED')
                        ) AS pending,
                        COALESCE(BOOL_OR(status = 'HAS_ERRORS'), FALSE)
                            AS has_errors,
                        SUM(valid_rows) AS valid_rows,
                        SUM(error_rows) AS error_rows,
                        SUM(warning_rows) AS warning_rows,
                        SUM(data_rows_estimate) AS total_rows
                    FROM public.import_job_sheets
                    WHERE import_job_id = ?
                      AND confirmed_type IS NOT NULL
                      AND confirmed_type NOT IN ('SKIP', 'REFERENCE_ONLY')
                ) agg
                WHERE j.id = ?
                  AND j.status IN (
                      'UPLOADED', 'VALIDATING', 'VALIDATED', 'HAS_ERRORS'
                  )
                """,
                jobId,
                jobId
        );
    }

    private List<RowData> readRows(
            org.apache.poi.ss.usermodel.Sheet sheet,            int headerIndex,
            Map<String, Integer> columnIndexes,
            Map<String, String> columns,
            DataFormatter formatter,
            LocalDate defaultEffectiveFrom
    ) {

        List<RowData> rows = new ArrayList<>();

        for (int index = headerIndex + 1;
             index <= sheet.getLastRowNum();
             index++) {

            Row row = sheet.getRow(index);

            if (row == null || ImportCells.isBlankRow(row)) {
                continue;
            }

            RowData data = new RowData(index + 1);

            data.employeeCode = readText(
                    row, columnIndexes, columns, "employeeCode",
                    formatter, data
            );

            data.hrmsId = readText(
                    row, columnIndexes, columns, "hrmsId",
                    formatter, data
            );

            data.aadhaar = readText(
                    row, columnIndexes, columns, "aadhaar",
                    formatter, data
            );

            data.pan = readText(
                    row, columnIndexes, columns, "pan",
                    formatter, data
            );

            data.contact = readText(
                    row, columnIndexes, columns, "contact",
                    formatter, data
            );

            data.email = readText(
                    row, columnIndexes, columns, "email",
                    formatter, data
            );

            data.pf = readText(
                    row, columnIndexes, columns, "pf",
                    formatter, data
            );

            data.nps = readText(
                    row, columnIndexes, columns, "nps",
                    formatter, data
            );

            data.gpf = readText(
                    row, columnIndexes, columns, "gpf",
                    formatter, data
            );

            data.employeeName = readText(
                    row, columnIndexes, columns, "employeeName",
                    formatter, data
            );

            data.componentCode = readText(
                    row, columnIndexes, columns, "componentCode",
                    formatter, data
            );

            data.componentName = readText(
                    row, columnIndexes, columns, "componentName",
                    formatter, data
            );

            data.category = readText(
                    row, columnIndexes, columns, "category",
                    formatter, data
            );

            data.amount = readAmount(
                    row,
                    columnIndexes.get(columns.get("amount")),
                    data
            );

            data.effectiveFrom = readDate(
                    row,
                    columnIndexes.get(columns.get("effectiveFrom")),
                    defaultEffectiveFrom,
                    data
            );

            rows.add(data);
        }

        return rows;
    }

    private String readText(
            Row row,
            Map<String, Integer> columnIndexes,
            Map<String, String> columns,
            String mappingKey,
            DataFormatter formatter,
            RowData data
    ) {

        String header = columns.get(mappingKey);

        if (header == null) {
            return null;
        }

        Integer columnIndex = columnIndexes.get(header);

        if (columnIndex == null) {
            addIssue(
                    data, "ERROR", mappingKey,
                    "SOURCE_COLUMN_MISSING",
                    "The mapped source column is missing: " + header
            );
            return null;
        }

        if (ImportCells.isFormula(row, columnIndex)) {
            addIssue(
                    data, "ERROR", mappingKey,
                    "FORMULA_NOT_ALLOWED",
                    "Formula cells are not accepted for mapped import fields."
            );
            return null;
        }

        return ImportCells.text(row, columnIndex, formatter);
    }

    private BigDecimal readAmount(
            Row row,
            Integer columnIndex,
            RowData data
    ) {

        if (columnIndex == null) {
            return null;
        }

        Cell cell = row.getCell(
                columnIndex,
                Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );

        if (cell == null || cell.getCellType() == CellType.BLANK) {
            addIssue(
                    data, "ERROR", "amount",
                    "AMOUNT_REQUIRED",
                    "Salary component amount is required."
            );
            return null;
        }

        if (cell.getCellType() == CellType.FORMULA) {
            addIssue(
                    data, "ERROR", "amount",
                    "FORMULA_NOT_ALLOWED",
                    "Formula cells are not accepted for salary amounts."
            );
            return null;
        }

        BigDecimal value;

        if (cell.getCellType() == CellType.NUMERIC) {
            value = BigDecimal.valueOf(cell.getNumericCellValue());
        } else if (cell.getCellType() == CellType.STRING) {

            String text = cell.getStringCellValue();

            if (text.isBlank()) {
                addIssue(
                        data, "ERROR", "amount",
                        "AMOUNT_REQUIRED",
                        "Salary component amount is required."
                );
                return null;
            }

            value = ImportCells.parseAmountText(text);

            if (value == null) {
                addIssue(
                        data, "ERROR", "amount",
                        "INVALID_AMOUNT",
                        "Amount is not a valid decimal number."
                );
                return null;
            }
        } else {
            addIssue(
                    data, "ERROR", "amount",
                    "INVALID_AMOUNT",
                    "Amount must be a numeric value."
            );
            return null;
        }

        if (value.stripTrailingZeros().scale() > 2) {
            addIssue(
                    data, "ERROR", "amount",
                    "AMOUNT_PRECISION",
                    "Salary amount cannot have more than two decimal places."
            );
            return null;
        }

        if (value.signum() < 0) {
            addIssue(
                    data, "ERROR", "amount",
                    "NEGATIVE_AMOUNT",
                    "Negative salary amounts are not allowed."
            );
            return null;
        }

        return value;
    }

    private LocalDate readDate(
            Row row,
            Integer columnIndex,
            LocalDate defaultDate,
            RowData data
    ) {

        if (columnIndex == null) {
            return fallbackEffectiveDate(defaultDate, data);
        }

        Cell cell = row.getCell(
                columnIndex,
                Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );

        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return fallbackEffectiveDate(defaultDate, data);
        }

        if (cell.getCellType() == CellType.FORMULA) {
            addIssue(
                    data, "ERROR", "effectiveFrom",
                    "FORMULA_NOT_ALLOWED",
                    "Formula cells are not accepted for effective dates."
            );
            return defaultDate;
        }

        LocalDate parsed = ImportCells.date(row, columnIndex, null);

        if (parsed != null) {
            return parsed;
        }

        addIssue(
                data, "ERROR", "effectiveFrom",
                "INVALID_EFFECTIVE_DATE",
                "Effective From must be a valid date. Use YYYY-MM-DD "
                        + "or a supported day/month/year format."
        );

        return null;
    }

    private LocalDate fallbackEffectiveDate(
            LocalDate defaultDate,
            RowData data
    ) {
        if (defaultDate != null) {
            return defaultDate;
        }

        addIssue(
                data, "ERROR", "effectiveFrom",
                "EFFECTIVE_DATE_REQUIRED",
                "Effective From date is required."
        );

        return null;
    }

    private LocalDate readOptionalDate(
            Row row,
            Integer columnIndex,
            String field,
            RowData data
    ) {
        if (columnIndex == null) {
            return null;
        }

        Cell cell = row.getCell(
                columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }

        if (cell.getCellType() == CellType.FORMULA) {
            addIssue(
                    data, "ERROR", field, "FORMULA_NOT_ALLOWED",
                    "Formula cells are not accepted for effective dates."
            );
            return null;
        }

        LocalDate parsed = ImportCells.date(row, columnIndex, null);

        if (parsed == null) {
            addIssue(
                    data, "ERROR", field, "INVALID_DATE",
                    field + " must be a valid date. Use YYYY-MM-DD "
                            + "or a supported day/month/year format."
            );
        }

        return parsed;
    }

    private void validateEffectiveWindow(RowData data) {
        if (data.effectiveFrom != null
                && data.effectiveTo != null
                && data.effectiveTo.isBefore(data.effectiveFrom)) {
            addIssue(
                    data, "ERROR", "effectiveTo",
                    "EFFECTIVE_TO_BEFORE_FROM",
                    "Effective To cannot be before Effective From."
            );
        }
    }

    private void readWideComponents(
            Row row,
            Map<String, Integer> columnIndexes,
            Map<String, String> componentByCode,
            RowData data
    ) {
        for (Map.Entry<String, String> entry : componentByCode.entrySet()) {
            String code = normalizedOrNull(entry.getKey());
            String header = entry.getValue();

            if (code == null) {
                continue;
            }

            Integer columnIndex = header == null
                    ? null
                    : columnIndexes.get(header);

            if (columnIndex == null) {
                addIssue(
                        data, "ERROR", "component:" + code,
                        "SOURCE_COLUMN_MISSING",
                        "The mapped source column is missing: " + header
                );
                continue;
            }

            BigDecimal amount = readOptionalAmount(
                    row, columnIndex, "component:" + code, data);

            if (amount == null) {
                continue;
            }

            if (amount.signum() < 0) {
                addIssue(
                        data, "ERROR", "component:" + code,
                        "NEGATIVE_AMOUNT",
                        "Negative amounts are not allowed."
                );
                continue;
            }

            data.componentAmounts.put(code, amount);
        }
    }

    private void loadEmployees(
            List<RowData> rows,
            Map<String, EmployeeRecord> byCode,
            Map<String, EmployeeRecord> byHrms,
            Map<String, EmployeeRecord> byIdentity
    ) {

        Set<String> employeeCodes = new LinkedHashSet<>();
        Set<String> hrmsIds = new LinkedHashSet<>();
        Map<String, Set<String>> valuesByType = new LinkedHashMap<>();

        for (RowData row : rows) {
            if (notBlank(row.employeeCode)) {
                employeeCodes.add(normalize(row.employeeCode));
            }
            if (notBlank(row.hrmsId)) {
                hrmsIds.add(normalize(row.hrmsId));
            }
            for (IdentityRef ref : row.identityRefs()) {
                valuesByType
                        .computeIfAbsent(ref.type(), key -> new LinkedHashSet<>())
                        .add(normalizeIdentity(ref.type(), ref.value()));
            }
        }

        loadEmployeesByCode(employeeCodes, byCode);
        loadEmployeesByHrms(hrmsIds, byHrms);
        loadEmployeesByIdentity(valuesByType, byIdentity);
    }

    private void loadEmployeesByCode(
            Set<String> employeeCodes,
            Map<String, EmployeeRecord> byCode
    ) {

        for (List<String> chunk : chunks(employeeCodes, 250)) {

            String sql = """
                    SELECT id, employee_code, hrms_id, name, division_id
                    FROM public.employees
                    WHERE upper(employee_code) IN (%s)
                    """.formatted(placeholders(chunk.size()));

            for (EmployeeRecord employee : jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> mapEmployee(rs),
                    chunk.toArray()
            )) {
                byCode.put(normalize(employee.employeeCode()), employee);
            }
        }
    }

    private void loadEmployeesByHrms(
            Set<String> hrmsIds,
            Map<String, EmployeeRecord> byHrms
    ) {

        for (List<String> chunk : chunks(hrmsIds, 250)) {

            String sql = """
                    SELECT id, employee_code, hrms_id, name, division_id
                    FROM public.employees
                    WHERE upper(hrms_id) IN (%s)
                    """.formatted(placeholders(chunk.size()));

            for (EmployeeRecord employee : jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> mapEmployee(rs),
                    chunk.toArray()
            )) {
                byHrms.put(normalize(employee.hrmsId()), employee);
            }
        }
    }

    private void loadEmployeesByIdentity(
            Map<String, Set<String>> valuesByType,
            Map<String, EmployeeRecord> byIdentity
    ) {

        for (Map.Entry<String, Set<String>> entry : valuesByType.entrySet()) {

            String type = entry.getKey();
            boolean phone = "PHONE".equals(type);
            boolean digitsOnly = phone || "AADHAAR".equals(type);

            for (List<String> chunk : chunks(entry.getValue(), 250)) {

                String valueExpression = digitsOnly
                        ? "regexp_replace(%s, '[^0-9]', '', 'g')"
                        : "upper(%s)";

                String sql;

                if (phone) {
                    sql = """
                            SELECT e.id, e.employee_code, e.hrms_id, e.name,
                                   e.division_id,
                                   coalesce(e.contact_no, e.contact)
                                       AS matched_value
                            FROM public.employees e
                            WHERE %s IN (%s)
                            """.formatted(
                            valueExpression.formatted(
                                    "coalesce(e.contact_no, e.contact, '')"),
                            placeholders(chunk.size())
                    );
                } else {
                    sql = """
                            SELECT e.id, e.employee_code, e.hrms_id, e.name,
                                   e.division_id, m.identity_value AS matched_value
                            FROM public.employee_identity_mappings m
                            JOIN public.employees e ON e.id = m.employee_id
                            WHERE m.identity_type = ? AND m.status = 'ACTIVE'
                              AND %s IN (%s)
                            """.formatted(
                            valueExpression.formatted("m.identity_value"),
                            placeholders(chunk.size())
                    );
                }

                List<IdentityMatch> found;

                if (phone) {
                    found = jdbcTemplate.query(
                            sql,
                            (rs, rowNum) -> new IdentityMatch(
                                    rs.getString("matched_value"),
                                    mapEmployee(rs)
                            ),
                            chunk.toArray()
                    );
                } else {
                    Object[] arguments = new Object[chunk.size() + 1];
                    arguments[0] = type;
                    int index = 1;
                    for (String value : chunk) {
                        arguments[index++] = value;
                    }
                    found = jdbcTemplate.query(
                            sql,
                            (rs, rowNum) -> new IdentityMatch(
                                    rs.getString("matched_value"),
                                    mapEmployee(rs)
                            ),
                            arguments
                    );
                }

                for (IdentityMatch match : found) {
                    byIdentity.put(
                            type + ":" + normalizeIdentity(type, match.value()),
                            match.employee()
                    );
                }
            }
        }
    }

    private static EmployeeRecord mapEmployee(ResultSet rs)
            throws SQLException {
        return new EmployeeRecord(
                rs.getLong("id"),
                rs.getString("employee_code"),
                rs.getString("hrms_id"),
                rs.getString("name"),
                rs.getLong("division_id")
        );
    }

    private static String normalizeIdentity(String type, String value) {
        if ("PHONE".equals(type) || "AADHAAR".equals(type)) {
            return value == null ? "" : value.replaceAll("[^0-9]", "");
        }
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean hasAnyIdentityColumn(Map<String, String> columns) {
        return columns.containsKey("aadhaar")
                || columns.containsKey("pan")
                || columns.containsKey("contact")
                || columns.containsKey("email")
                || columns.containsKey("pf")
                || columns.containsKey("nps")
                || columns.containsKey("gpf");
    }

    private Map<String, ComponentRecord> loadComponents() {
        return loadComponents(true);
    }

    private Map<String, ComponentRecord> loadComponents(boolean activeOnly) {

        List<ComponentRecord> found = jdbcTemplate.query(
                """
                SELECT id, component_code, component_name, category
                FROM public.salary_components
                WHERE (? = false OR status = 'ACTIVE')
                """,
                (rs, rowNum) -> new ComponentRecord(
                        rs.getLong("id"),
                        rs.getString("component_code"),
                        rs.getString("component_name"),
                        rs.getString("category")
                ),
                activeOnly
        );

        Map<String, ComponentRecord> result = new HashMap<>();

        for (ComponentRecord component : found) {
            result.put(
                    normalize(component.code()),
                    component
            );
            result.putIfAbsent(
                    ColumnMappingCatalog.componentKey(component.code()),
                    component
            );
        }

        return result;
    }

    /**
     * Exact component-code lookup first, then a fuzzy alias fallback so source
     * codes such as BASIC_PAY resolve to the master code BASIC.
     */
    private ComponentRecord resolveComponent(
            Map<String, ComponentRecord> components,
            String rawCode
    ) {
        String normalized = normalizedOrNull(rawCode);

        if (normalized == null) {
            return null;
        }

        ComponentRecord exact = components.get(normalized);

        return exact != null
                ? exact
                : components.get(
                        ColumnMappingCatalog.componentKey(normalized));
    }

    private void validateReferences(
            RowData row,
            Map<String, String> columns,
            Map<String, EmployeeRecord> byCode,
            Map<String, EmployeeRecord> byHrms,
            Map<String, EmployeeRecord> byIdentity,
            Map<String, ComponentRecord> components
    ) {

        resolveEmployeeReference(row, byCode, byHrms, byIdentity, "ERROR", false);

        String componentCode = normalizedOrNull(row.componentCode);

        if (componentCode == null) {
            addIssue(
                    row, "ERROR", "componentCode",
                    "COMPONENT_CODE_REQUIRED",
                    "Salary component code is required."
            );
        } else {
            row.component = resolveComponent(components, componentCode);

            if (row.component == null) {
                addIssue(
                        row, "ERROR", "componentCode",
                        "UNKNOWN_OR_INACTIVE_COMPONENT",
                        "Salary component is unknown or inactive: "
                                + componentCode
                );
            } else {
                if (!normalize(row.component.code()).equals(
                        normalize(componentCode))) {
                    addIssue(
                            row, "WARNING", "componentCode",
                            "COMPONENT_CODE_INTERPRETED",
                            "Source code " + componentCode
                                    + " was interpreted as component "
                                    + row.component.code() + "."
                    );
                }

                if (columns.containsKey("componentName")
                        && notBlank(row.componentName)
                        && !normalize(row.componentName).equals(
                                normalize(row.component.name()))) {

                    addIssue(
                            row, "WARNING", "componentName",
                            "COMPONENT_NAME_MISMATCH",
                            "Component name differs from the component catalog. "
                                    + "The validated component code remains authoritative."
                    );
                }

                if (columns.containsKey("category")) {

                    if (!notBlank(row.category)) {
                        addIssue(
                                row, "ERROR", "category",
                                "CATEGORY_REQUIRED",
                                "Component category is required."
                        );
                    } else if (!normalize(row.category).equals(
                            normalize(row.component.category()))) {

                        addIssue(
                                row, "ERROR", "category",
                                "COMPONENT_CATEGORY_MISMATCH",
                                "Uploaded category does not match the active component catalog."
                        );
                    }
                }
            }
        }
    }

    private void markFileDuplicates(List<RowData> rows) {

        Map<ValueKey, RowData> firstByKey = new LinkedHashMap<>();

        for (RowData row : rows) {
            if (row.hasErrors()
                    || row.employee == null
                    || row.component == null
                    || row.effectiveFrom == null
                    || row.amount == null) {
                continue;
            }

            ValueKey key = new ValueKey(
                    row.employee.id(),
                    row.component.id(),
                    row.effectiveFrom
            );

            RowData first = firstByKey.putIfAbsent(key, row);

            if (first != null) {
                addIssue(
                        first, "ERROR", "componentCode/effectiveFrom",
                        "DUPLICATE_ENTITLEMENT_IN_FILE",
                        "This employee, component and effective date occurs more than once."
                );

                addIssue(
                        row, "ERROR", "componentCode/effectiveFrom",
                        "DUPLICATE_ENTITLEMENT_IN_FILE",
                        "This employee, component and effective date occurs more than once."
                );
            }
        }
    }

    private Map<EmployeeComponentKey, List<ExistingInterval>>
            loadExistingIntervals(List<RowData> rows) {

        Set<Long> employeeIds = new LinkedHashSet<>();
        Set<Long> componentIds = new LinkedHashSet<>();

        for (RowData row : rows) {
            if (!row.hasErrors()
                    && row.employee != null
                    && row.component != null
                    && row.effectiveFrom != null) {

                employeeIds.add(row.employee.id());
                componentIds.add(row.component.id());
            }
        }

        Map<EmployeeComponentKey, List<ExistingInterval>> result =
                new HashMap<>();

        for (List<Long> employeeChunk : chunksLong(employeeIds, 150)) {
            for (List<Long> componentChunk : chunksLong(componentIds, 100)) {

                String employeeSlots =
                        placeholders(employeeChunk.size());

                String componentSlots =
                        placeholders(componentChunk.size());

                String sql = """
                        SELECT
                            employee_id,
                            salary_component_id,
                            amount,
                            effective_from,
                            effective_to
                        FROM public.employee_salary_values
                        WHERE employee_id IN (%s)
                          AND salary_component_id IN (%s)
                        """.formatted(employeeSlots, componentSlots);

                List<Object> args = new ArrayList<>();
                args.addAll(employeeChunk);
                args.addAll(componentChunk);

                jdbcTemplate.query(
                        sql,
                        args.toArray(),
                        rs -> {
                            EmployeeComponentKey key =
                                    new EmployeeComponentKey(
                                            rs.getLong("employee_id"),
                                            rs.getLong("salary_component_id")
                                    );

                            LocalDate from = rs.getObject(
                                    "effective_from", LocalDate.class
                            );

                            LocalDate to = rs.getObject(
                                    "effective_to", LocalDate.class
                            );

                            BigDecimal amount = rs.getBigDecimal("amount");

                            result.computeIfAbsent(
                                    key, ignored -> new ArrayList<>()
                            ).add(new ExistingInterval(from, to, amount));
                        }
                );
            }
        }

        return result;
    }

    private void checkExistingIntervals(
            List<RowData> rows,
            Map<EmployeeComponentKey, List<ExistingInterval>> existingValues
    ) {
        for (RowData row : rows) {

            if (row.hasErrors()
                    || row.employee == null
                    || row.component == null
                    || row.effectiveFrom == null) {
                continue;
            }

            EmployeeComponentKey key = new EmployeeComponentKey(
                    row.employee.id(),
                    row.component.id()
            );

            List<ExistingInterval> intervals =
                    existingValues.getOrDefault(key, List.of());

            for (ExistingInterval interval : intervals) {

                boolean startsBeforeOrOn =
                        !row.effectiveFrom.isBefore(interval.from());

                boolean endsAfterOrOn =
                        interval.to() == null
                        || !row.effectiveFrom.isAfter(interval.to());

                if (startsBeforeOrOn && endsAfterOrOn) {

                    boolean exactDate =
                            row.effectiveFrom.equals(interval.from());

                    addIssue(
                            row, "ERROR", "effectiveFrom",
                            exactDate
                                    ? "ENTITLEMENT_ALREADY_EXISTS"
                                    : "EFFECTIVE_DATE_OVERLAP",
                            exactDate
                                    ? "An entitlement already exists for this "
                                            + "employee, component and effective date."
                                    : "An existing entitlement is effective on "
                                            + "this date. Use the salary revision "
                                            + "workflow or resolve the overlap first."
                    );

                    break;
                }
            }
        }
    }

    /**
     * Combined sheets may update an existing effective-dated value, but only
     * when the upload supplies an amount. Identical values are skipped and
     * changed values are surfaced as warnings so the approver can review them.
     */
    private void checkCombinedExistingValues(
            List<RowData> rows,
            Map<EmployeeComponentKey, List<ExistingInterval>> existingValues
    ) {
        for (RowData row : rows) {
            if (row.hasErrors()
                    || row.employee == null
                    || row.component == null
                    || row.effectiveFrom == null
                    || row.amount == null) {
                continue;
            }

            EmployeeComponentKey key = new EmployeeComponentKey(
                    row.employee.id(),
                    row.component.id()
            );

            for (ExistingInterval interval
                    : existingValues.getOrDefault(key, List.of())) {

                boolean startsBeforeOrOn =
                        !row.effectiveFrom.isBefore(interval.from());
                boolean endsAfterOrOn =
                        interval.to() == null
                        || !row.effectiveFrom.isAfter(interval.to());

                if (!startsBeforeOrOn || !endsAfterOrOn) {
                    continue;
                }

                if (row.effectiveFrom.equals(interval.from())) {
                    boolean sameValue = interval.amount() != null
                            && row.amount.compareTo(interval.amount()) == 0;

                    addIssue(
                            row, "WARNING", "amount",
                            sameValue
                                    ? "COMPONENT_VALUE_UNCHANGED"
                                    : "COMPONENT_VALUE_CHANGED",
                            sameValue
                                    ? "Existing amount " + interval.amount()
                                            + " is unchanged; this row will be skipped."
                                    : "Existing amount " + interval.amount()
                                            + " will be updated to " + row.amount
                                            + " for this effective date."
                    );
                } else {
                    addIssue(
                            row, "WARNING", "effectiveFrom",
                            "COMPONENT_EFFECTIVE_DATE_OVERLAP",
                            "Existing amount " + interval.amount()
                                    + " is already effective on this date; the "
                                    + "import will add a new effective-dated value."
                    );
                }

                break;
            }
        }
    }

    private void resolveEmployeeReference(
            RowData row,
            Map<String, EmployeeRecord> byCode,
            Map<String, EmployeeRecord> byHrms,
            Map<String, EmployeeRecord> byIdentity,
            String notFoundSeverity,
            boolean allowTempId
    ) {
        String code = normalizedOrNull(row.employeeCode);
        String hrms = normalizedOrNull(row.hrmsId);
        List<IdentityRef> identities = row.identityRefs();

        EmployeeRecord codeMatch = code == null ? null : byCode.get(code);
        EmployeeRecord hrmsMatch = hrms == null ? null : byHrms.get(hrms);

        Map<Long, EmployeeRecord> matches = new LinkedHashMap<>();

        if (codeMatch != null) {
            matches.put(codeMatch.id(), codeMatch);
        }
        if (hrmsMatch != null) {
            matches.put(hrmsMatch.id(), hrmsMatch);
        }

        for (IdentityRef ref : identities) {
            EmployeeRecord found = byIdentity.get(
                    ref.type() + ":"
                            + normalizeIdentity(ref.type(), ref.value()));

            if (found != null) {
                matches.put(found.id(), found);
            }
        }

        if (matches.size() > 1) {
            addIssue(
                    row, "ERROR", "employeeIdentifier",
                    "EMPLOYEE_IDENTITY_MISMATCH",
                    "The supplied identifiers refer to different employees."
            );
            return;
        }

        EmployeeRecord match = codeMatch != null
                ? codeMatch
                : hrmsMatch != null
                        ? hrmsMatch
                        : matches.values().stream().findFirst().orElse(null);

        boolean hasIdentifier = code != null
                || hrms != null
                || !identities.isEmpty();

        if (match == null) {
            if (!hasIdentifier) {
                if (!allowTempId) {
                    addIssue(
                            row, "ERROR", "employeeIdentifier",
                            "EMPLOYEE_IDENTIFIER_REQUIRED",
                            "Provide an employee code, HRMS ID, Aadhaar, PAN, "
                                    + "phone, email, PF, NPS or GPF number."
                    );
                }
            } else {
                addIssue(
                        row, notFoundSeverity, "employeeIdentifier",
                        "EMPLOYEE_NOT_FOUND_OR_OUT_OF_SCOPE",
                        "No employee matched the supplied code, HRMS ID or "
                                + "identity, or the employee is outside your "
                                + "authorized scope."
                );
            }
            return;
        }

        row.employee = match;

        if (row.employee != null
                && notBlank(row.employeeName)
                && !normalize(row.employeeName)
                        .equals(normalize(row.employee.name()))) {

            addIssue(
                    row, "WARNING", "employeeName",
                    "EMPLOYEE_NAME_MISMATCH",
                    "Uploaded employee name differs from the current employee master."
            );
        }
    }

    /**
     * Validates EMPLOYEE_MASTER, PAYROLL_HISTORY and SALARY_REVISIONS sheets.
     *
     * <p>These types share the employee/component resolution and duplicate
     * detection used by salary entitlements. EMPLOYEE_MASTER remains preview
     * only. PAYROLL_HISTORY is validated as component-detail data and stored
     * separately from operational payroll. SALARY_REVISIONS commits into
     * salary_revision_batches/salary_revisions as DRAFT only; it never touches
     * employee_salary_values and never recalculates payroll.
     */
    private ImportJobSheetValidationResponse validateSupplementaryRows(
            long jobId,
            int sheetIndex,
            Map<String, Object> record,
            String type,
            SheetMapping mapping,
            long userId
    ) throws IOException {

        Map<String, String> columns = mapping.columns();

        if (!columns.containsKey("employeeCode")
                && !columns.containsKey("hrmsId")
                && !hasAnyIdentityColumn(columns)) {
            throw badRequest(
                    "Map an employee identifier (employee code, HRMS ID, "
                            + "Aadhaar, PAN, phone, email, PF, NPS or GPF) "
                            + "before validation."
            );
        }

        if ("EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)
                && !columns.containsKey("division")
                && !columns.containsKey("divisionId")) {
            throw badRequest(
                    "Map division or divisionId for employee master + components.");
        }

        if ("PAYROLL_HISTORY".equals(type)) {
            if (!columns.containsKey("componentCode")
                    || !columns.containsKey("amount")) {
                throw badRequest(
                        "Map componentCode and amount for payroll history."
                );
            }

            if (!columns.containsKey("period")
                    && !(columns.containsKey("payrollMonth")
                            && columns.containsKey("payrollYear"))
                    && !(mapping.option("periodMonthDefault") != null
                            && mapping.option("periodYearDefault") != null)) {
                throw badRequest(
                        "Map period, map both payrollMonth and payrollYear, "
                                + "or set defaults for both."
                );
            }
        }

        if (!mapping.components().isEmpty()
                && !"EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)) {
            throw badRequest(
                    "Wide component layout is not supported for " + type
                            + " yet. Map componentCode and amount instead."
            );
        }

        LocalDate defaultEffectiveFrom = null;

        if (mapping.effectiveFromDefault() != null) {
            defaultEffectiveFrom = ImportCells.parseDate(
                    mapping.effectiveFromDefault());

            if (defaultEffectiveFrom == null) {
                throw badRequest(
                        "The default effective date must use YYYY-MM-DD.");
            }
        }

        if ("EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)) {
            boolean wideLayout = !mapping.components().isEmpty();
            if (!wideLayout && (!columns.containsKey("componentCode")
                    || !columns.containsKey("amount"))) {
                throw badRequest(
                        "Map componentCode and amount, or map wide component columns, "
                                + "for employee master + components.");
            }
            if (!columns.containsKey("effectiveFrom")
                    && defaultEffectiveFrom == null) {
                throw badRequest(
                        "Map an effectiveFrom column or set a default effective "
                                + "date before validating employee master + components.");
            }
        }

        if ("SALARY_REVISIONS".equals(type)
                && !columns.containsKey("effectiveFrom")
                && defaultEffectiveFrom == null) {
            throw badRequest(
                    "Map an effectiveFrom column or set a default effective "
                            + "date before validating salary revisions.");
        }

        long sheetId = ((Number) record.get("sheet_id")).longValue();
        String sheetName = (String) record.get("sheet_name");
        int headerRowNumber =
                ((Number) record.get("header_row_number")).intValue();

        if (headerRowNumber < 1) {
            throw badRequest("The worksheet header row is not set.");
        }

        List<String> headers = Arrays.asList(
                jsonMapper.readValue(
                        (String) record.get("headers_json"), String[].class));

        Map<String, Integer> columnIndexes = createColumnIndexes(headers);

        for (Map.Entry<String, String> entry : columns.entrySet()) {
            if (!columnIndexes.containsKey(entry.getValue())) {
                throw badRequest(
                        "Mapped source header is missing: " + entry.getValue());
            }
        }

        String fileKey = (String) record.get("file_key");
        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (!filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Stored workbook was not found.");
        }

        DataFormatter formatter = new DataFormatter();
        List<RowData> rows = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {

            if (sheetIndex >= workbook.getNumberOfSheets()) {
                throw badRequest("Worksheet index does not exist.");
            }

            var sheet = workbook.getSheetAt(sheetIndex);

            if (!sheetName.equals(sheet.getSheetName())) {
                throw badRequest(
                        "Stored worksheet name does not match the workbook.");
            }

            int headerIndex = headerRowNumber - 1;

            if (sheet.getRow(headerIndex) == null) {
                throw badRequest("The worksheet header row is missing.");
            }

            for (int index = headerIndex + 1;
                 index <= sheet.getLastRowNum();
                 index++) {

                Row sourceRow = sheet.getRow(index);

                if (sourceRow == null || ImportCells.isBlankRow(sourceRow)) {
                    continue;
                }

                rows.add(readSupplementaryRow(
                        sourceRow, index + 1, type, columnIndexes, columns,
                        mapping.components(), mapping.options(), formatter,
                        defaultEffectiveFrom));
            }

        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read the worksheet for validation.",
                    ex
            );
        }

        if ("PAYROLL_HISTORY".equals(type)) {
            boolean grossMapped = columns.containsKey("gross");
            boolean deductionsMapped = columns.containsKey("totalDeductions");
            boolean netMapped = columns.containsKey("net");

            if ((grossMapped || deductionsMapped || netMapped)
                    && !(grossMapped && deductionsMapped && netMapped)) {
                throw badRequest(
                        "Map gross, totalDeductions and net together, or "
                                + "leave all three unmapped."
                );
            }
        }

        Map<String, EmployeeRecord> employeesByCode = new HashMap<>();
        Map<String, EmployeeRecord> employeesByHrms = new HashMap<>();
        Map<String, EmployeeRecord> employeesByIdentity = new HashMap<>();

        loadEmployees(
                rows, employeesByCode, employeesByHrms, employeesByIdentity);

        Map<String, ComponentRecord> components =
                "EMPLOYEE_MASTER".equals(type)
                        ? Map.of()
                        : loadComponents(!"PAYROLL_HISTORY".equals(type));
        List<DivisionMatcher.Candidate> divisions = loadDivisions();

        boolean wideComponents =
                "EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)
                        && !mapping.components().isEmpty();

        for (RowData row : rows) {
            boolean masterRow = "EMPLOYEE_MASTER".equals(type)
                    || "EMPLOYEE_MASTER_AND_COMPONENTS".equals(type);

            boolean tempIdRow = "EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)
                    && !notBlank(row.employeeCode)
                    && !notBlank(row.hrmsId)
                    && row.identityRefs().isEmpty();

            if (tempIdRow) {
                row.tempIdAssigned = true;
                addIssue(
                        row, "WARNING", "hrmsId",
                        "TEMP_ID_ASSIGNED",
                        "No employee identifier supplied; a temporary "
                                + "HRMS ID (T-####) will be assigned when the "
                                + "employee is created."
                );
            }

            resolveEmployeeReference(
                    row, employeesByCode, employeesByHrms, employeesByIdentity,
                    masterRow ? "WARNING" : "ERROR",
                    tempIdRow
            );

            if (masterRow && row.employee != null) {
                addIssue(
                        row, "WARNING", "employeeIdentifier",
                        "EMPLOYEE_EXISTS_MASTER_NOT_UPDATED",
                        "Employee already exists; employee master fields "
                                + "will not be overwritten."
                );
            }

            if (masterRow && row.employee == null) {
                if (!notBlank(row.employeeName)) {
                    addIssue(
                            row, "ERROR", "employeeName",
                            "EMPLOYEE_NAME_REQUIRED",
                            "Employee name is required to create an employee."
                    );
                }

                if (!columns.containsKey("division")
                        && !columns.containsKey("divisionId")) {
                    addIssue(
                            row, "ERROR", "division",
                            "DIVISION_MAPPING_REQUIRED",
                            "Map division or divisionId to create an employee."
                    );
                } else if (!notBlank(row.divisionName)
                        && !notBlank(row.sourceDivisionId)) {
                    addIssue(
                            row, "ERROR", "division", "DIVISION_REQUIRED",
                            "Division is required to create an employee."
                    );
                }

                if (notBlank(row.hrmsId)
                        && !normalize(row.hrmsId)
                                .matches("(T-[0-9]{4}|[0-9]{4})")) {
                    addIssue(
                            row, "ERROR", "hrmsId", "INVALID_HRMS_ID",
                            "A new employee's HRMS ID must be four digits or "
                                    + "T- followed by four digits."
                    );
                }
            }

            if (!"EMPLOYEE_MASTER".equals(type)) {
                if (wideComponents) {
                    if (row.componentAmounts.isEmpty()) {
                        addIssue(
                                row, "WARNING", "componentColumns",
                                "NO_COMPONENT_AMOUNTS",
                                "No salary component amounts were found in the mapped component columns for this row; no salary values will be imported."
                        );
                    }
                    for (String code : row.componentAmounts.keySet()) {
                        if (resolveComponent(components, code) == null) {
                            addIssue(
                                    row, "ERROR", "component:" + code,
                                    "UNKNOWN_OR_INACTIVE_COMPONENT",
                                    "Salary component is unknown or inactive: "
                                            + code
                            );
                        }
                    }
                } else {
                    validateSupplementaryComponent(
                            row, components, "PAYROLL_HISTORY".equals(type));

                    if (row.amount != null && row.amount.signum() < 0) {
                        addIssue(
                                row, "ERROR", "amount", "NEGATIVE_AMOUNT",
                                "Negative amounts are not allowed."
                        );
                    }
                }
            }

            if (!"EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)
                    || row.employee == null) {
                validateSupplementaryDivision(row, columns, divisions);
            }
        }

        if ("EMPLOYEE_MASTER".equals(type)) {
            markEmployeeMasterDuplicates(rows, true);
        } else if ("EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)) {
            if (wideComponents) {
                markEmployeeMasterDuplicates(rows, true);
            } else {
                markEmployeeMasterDuplicates(rows, false);

                checkCombinedExistingValues(
                        rows, loadExistingIntervals(rows));
            }
        } else if ("PAYROLL_HISTORY".equals(type)) {
            markHistoryDuplicates(rows);
        } else {
            markRevisionDuplicates(rows);
        }

        return completeValidation(
                jobId, sheetIndex, sheetId, sheetName, userId,
                rows, sheetWarningsFor(type, headerRowNumber), type, components
        );
    }

    private RowData readSupplementaryRow(
            Row row,
            int rowNumber,
            String type,
            Map<String, Integer> columnIndexes,
            Map<String, String> columns,
            Map<String, String> componentByCode,
            Map<String, String> options,
            DataFormatter formatter,
            LocalDate defaultEffectiveFrom
    ) {

        RowData data = new RowData(rowNumber);

        data.employeeCode = readText(
                row, columnIndexes, columns, "employeeCode", formatter, data);
        data.hrmsId = readText(
                row, columnIndexes, columns, "hrmsId", formatter, data);
        data.aadhaar = readText(
                row, columnIndexes, columns, "aadhaar", formatter, data);
        data.pan = readText(
                row, columnIndexes, columns, "pan", formatter, data);
        data.contact = readText(
                row, columnIndexes, columns, "contact", formatter, data);
        data.email = readText(
                row, columnIndexes, columns, "email", formatter, data);
        data.pf = readText(
                row, columnIndexes, columns, "pf", formatter, data);
        data.nps = readText(
                row, columnIndexes, columns, "nps", formatter, data);
        data.gpf = readText(
                row, columnIndexes, columns, "gpf", formatter, data);
        data.employeeName = readText(
                row, columnIndexes, columns, "employeeName", formatter, data);

        if ("EMPLOYEE_MASTER".equals(type)
                || "EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)) {
            data.divisionName = readText(
                    row, columnIndexes, columns, "division", formatter, data);
            data.sourceDivisionId = readText(
                    row, columnIndexes, columns, "divisionId", formatter, data);
            data.designation = readText(
                    row, columnIndexes, columns, "designation", formatter, data);
            data.dateOfJoining = readText(
                    row, columnIndexes, columns, "dateOfJoining", formatter, data);
            data.dateOfBirth = readText(
                    row, columnIndexes, columns, "dateOfBirth", formatter, data);
            data.employmentType = readText(
                    row, columnIndexes, columns, "employmentType", formatter, data);
            data.payScale = readText(
                    row, columnIndexes, columns, "payScale", formatter, data);
            data.basicPay = readOptionalAmount(
                    row, columnIndexes.get(columns.get("basicPay")),
                    "basicPay", data);
            data.bankAccount = readText(
                    row, columnIndexes, columns, "bankAccount", formatter, data);
            data.ifscCode = readText(
                    row, columnIndexes, columns, "ifscCode", formatter, data);
            data.bankName = readText(
                    row, columnIndexes, columns, "bankName", formatter, data);
            data.branch = readText(
                    row, columnIndexes, columns, "branch", formatter, data);

            if ("EMPLOYEE_MASTER".equals(type)) {
                if (columns.containsKey("division")
                        && !notBlank(data.divisionName)) {
                    addIssue(
                            data, "ERROR", "division", "DIVISION_REQUIRED",
                            "Division is required for an employee master row."
                    );
                }

                if (columns.containsKey("employeeName")
                        && !notBlank(data.employeeName)) {
                    addIssue(
                            data, "ERROR", "employeeName",
                            "EMPLOYEE_NAME_REQUIRED",
                            "Employee name is required for an employee "
                                    + "master row."
                    );
                }

                return data;
            }

            if (!componentByCode.isEmpty()) {
                data.effectiveFrom = readDate(
                        row,
                        columnIndexes.get(columns.get("effectiveFrom")),
                        defaultEffectiveFrom,
                        data
                );
                data.effectiveTo = readOptionalDate(
                        row,
                        columnIndexes.get(columns.get("effectiveTo")),
                        "effectiveTo",
                        data
                );

                validateEffectiveWindow(data);
                readWideComponents(
                        row, columnIndexes, componentByCode, data);

                return data;
            }
        }

        data.componentCode = readText(
                row, columnIndexes, columns, "componentCode", formatter, data);
        data.componentName = readText(
                row, columnIndexes, columns, "componentName", formatter, data);
        data.category = readText(
                row, columnIndexes, columns, "category", formatter, data);

        if ("PAYROLL_HISTORY".equals(type)) {
            data.divisionName = readText(
                    row, columnIndexes, columns, "division", formatter, data);
            data.sourceDivisionId = readText(
                    row, columnIndexes, columns, "divisionId", formatter, data);
            data.periodLabel = readText(
                    row, columnIndexes, columns, "period", formatter, data);

            if (!notBlank(data.periodLabel)) {
                String month = readText(
                        row, columnIndexes, columns, "payrollMonth",
                        formatter, data);
                String year = readText(
                        row, columnIndexes, columns, "payrollYear",
                        formatter, data);

                month = notBlank(month) ? month : options.get("periodMonthDefault");
                year = notBlank(year) ? year : options.get("periodYearDefault");

                if (notBlank(month) && notBlank(year)) {
                    try {
                        data.periodLabel = String.format(
                                "%04d-%02d",
                                Integer.parseInt(year.trim().replace(".0", "")),
                                Integer.parseInt(month.trim().replace(".0", ""))
                        );
                    } catch (NumberFormatException ex) {
                        data.periodLabel = null;
                    }
                }
            }
        }

        if ("SALARY_REVISIONS".equals(type)
                || "EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)) {
            data.amount = readOptionalAmount(
                    row, columnIndexes.get(columns.get("amount")),
                    "amount", data);

            if ("EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)
                    && columns.containsKey("amount")
                    && data.amount == null) {
                addIssue(
                        data, "WARNING", "amount",
                        "COMPONENT_AMOUNT_SKIPPED",
                        "Amount is blank, so this salary component will not "
                                + "be imported."
                );
            }
        } else {
            data.amount = readAmount(
                    row, columnIndexes.get(columns.get("amount")), data);
        }
        data.oldAmount = readOptionalAmount(
                row, columnIndexes.get(columns.get("oldAmount")),
                "oldAmount", data);

        BigDecimal newAmount = readOptionalAmount(
                row, columnIndexes.get(columns.get("newAmount")),
                "newAmount", data);

        data.newAmount = newAmount != null ? newAmount : data.amount;

        if ("PAYROLL_HISTORY".equals(type)) {
            data.gross = readOptionalHistoryAmount(
                    row, columnIndexes.get(columns.get("gross")),
                    "gross", data);
            data.totalDeductions = readOptionalHistoryAmount(
                    row, columnIndexes.get(columns.get("totalDeductions")),
                    "totalDeductions", data);
            data.net = readOptionalHistoryAmount(
                    row, columnIndexes.get(columns.get("net")), "net", data);
            data.sourceStatus = readText(
                    row, columnIndexes, columns, "transactionStatus",
                    formatter, data);
            if (columns.containsKey("transactionStatus")
                    && !notBlank(data.sourceStatus)) {
                addIssue(
                        data, "ERROR", "transactionStatus",
                        "SOURCE_STATUS_REQUIRED",
                        "A source transaction status is required when the "
                                + "status column is mapped."
                );
            }
        } else {
            data.gross = readOptionalAmount(
                    row, columnIndexes.get(columns.get("gross")),
                    "gross", data);
            data.totalDeductions = readOptionalAmount(
                    row, columnIndexes.get(columns.get("totalDeductions")),
                    "totalDeductions", data);
            data.net = readOptionalAmount(
                    row, columnIndexes.get(columns.get("net")), "net", data);
        }

        if ("SALARY_REVISIONS".equals(type)) {
            if (!columns.containsKey("newAmount")
                    && !columns.containsKey("amount")) {
                addIssue(
                        data, "ERROR", "newAmount", "NEW_AMOUNT_REQUIRED",
                        "Map newAmount or amount for salary revisions."
                );
            } else if (data.newAmount == null) {
                addIssue(
                        data, "ERROR", "newAmount", "NEW_AMOUNT_REQUIRED",
                        "Revised salary amount is required."
                );
            } else if (data.newAmount.signum() < 0) {
                addIssue(
                        data, "ERROR", "newAmount", "NEGATIVE_AMOUNT",
                        "Revised salary amount cannot be negative."
                );
            }
        }

        // Only salary revisions are keyed by an effective date; payroll
        // history uses its payroll period instead.
        if ("SALARY_REVISIONS".equals(type)) {
            data.effectiveFrom = readDate(
                    row,
                    columnIndexes.get(columns.get("effectiveFrom")),
                    defaultEffectiveFrom,
                    data
            );
        }

        if ("EMPLOYEE_MASTER_AND_COMPONENTS".equals(type)) {
            data.effectiveFrom = readDate(
                    row,
                    columnIndexes.get(columns.get("effectiveFrom")),
                    defaultEffectiveFrom,
                    data
            );
            data.effectiveTo = readOptionalDate(
                    row,
                    columnIndexes.get(columns.get("effectiveTo")),
                    "effectiveTo",
                    data
            );
            validateEffectiveWindow(data);
        }

        if ("PAYROLL_HISTORY".equals(type)) {
            data.periodLabel = resolvePeriodLabel(data.periodLabel, data);
        }

        return data;
    }

    private BigDecimal readOptionalAmount(
            Row row,
            Integer columnIndex,
            String field,
            RowData data
    ) {
        if (columnIndex == null) {
            return null;
        }

        Cell cell = row.getCell(
                columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }

        if (cell.getCellType() == CellType.FORMULA) {
            addIssue(
                    data, "ERROR", field, "FORMULA_NOT_ALLOWED",
                    "Formula cells are not accepted for mapped import fields."
            );
            return null;
        }

        BigDecimal value = ImportCells.amount(row, columnIndex);

        if (value == null) {
            addIssue(
                    data, "ERROR", field, "INVALID_AMOUNT",
                    "Value is not a valid decimal number."
            );
            return null;
        }

        if (value.stripTrailingZeros().scale() > 2) {
            addIssue(
                    data, "ERROR", field, "AMOUNT_PRECISION",
                    "Amount cannot have more than two decimal places."
            );
            return null;
        }

        return value;
    }

    private BigDecimal readOptionalHistoryAmount(
            Row row,
            Integer columnIndex,
            String field,
            RowData data
    ) {
        if (columnIndex == null) {
            return null;
        }

        Cell cell = row.getCell(
                columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null || cell.getCellType() == CellType.BLANK) {
            addIssue(
                    data, "ERROR", field, "AMOUNT_REQUIRED",
                    "Mapped payroll history totals are required."
            );
            return null;
        }

        if (cell.getCellType() == CellType.FORMULA) {
            addIssue(
                    data, "ERROR", field, "FORMULA_NOT_ALLOWED",
                    "Formula cells are not accepted for mapped import fields."
            );
            return null;
        }

        BigDecimal value = switch (cell.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> ImportCells.parseAmountText(
                    cell.getStringCellValue());
            default -> null;
        };

        if (value == null) {
            addIssue(
                    data, "ERROR", field, "INVALID_AMOUNT",
                    "Value is not a valid decimal number."
            );
            return null;
        }

        if (value.stripTrailingZeros().scale() > 2) {
            addIssue(
                    data, "ERROR", field, "AMOUNT_PRECISION",
                    "Payroll history amounts cannot have more than two "
                            + "decimal places."
            );
            return null;
        }

        if (value.signum() < 0) {
            addIssue(
                    data, "ERROR", field, "NEGATIVE_AMOUNT",
                    "Payroll history totals cannot be negative."
            );
            return null;
        }

        return value.setScale(2);
    }

    private void validateSupplementaryComponent(
            RowData row,
            Map<String, ComponentRecord> components,
            boolean includeInactive
    ) {
        String componentCode = normalizedOrNull(row.componentCode);

        if (componentCode == null) {
            addIssue(
                    row, "ERROR", "componentCode", "COMPONENT_CODE_REQUIRED",
                    "Salary component code is required."
            );
            return;
        }

        row.component = resolveComponent(components, componentCode);

        if (row.component == null) {
            addIssue(
                    row, "ERROR", "componentCode",
                    includeInactive
                            ? "UNKNOWN_COMPONENT"
                            : "UNKNOWN_OR_INACTIVE_COMPONENT",
                    "Salary component is unknown: " + componentCode
            );
        } else if (!normalize(row.component.code()).equals(
                normalize(componentCode))) {
            addIssue(
                    row, "WARNING", "componentCode",
                    "COMPONENT_CODE_INTERPRETED",
                    "Source code " + componentCode
                            + " was interpreted as component "
                            + row.component.code() + "."
            );
        } else if (notBlank(row.category)
                && !normalize(row.category).equals(
                        normalize(row.component.category()))) {
            addIssue(
                    row, "ERROR", "category", "COMPONENT_CATEGORY_MISMATCH",
                    "Source category does not match the salary component."
            );
        }
    }

    private void validateSupplementaryDivision(
            RowData row,
            Map<String, String> columns,
            List<DivisionMatcher.Candidate> divisions
    ) {
        String divisionName = normalizedOrNull(row.divisionName);

        if (columns.containsKey("division") && divisionName != null) {
            validateDivisionReference(
                    row, "division", divisions, divisionName);
        }

        String divisionRef = normalizedOrNull(row.sourceDivisionId);
        if (columns.containsKey("divisionId") && divisionRef != null) {
            validateDivisionReference(
                    row, "divisionId", divisions, divisionRef);
        }
    }

    private void validateDivisionReference(
            RowData row,
            String field,
            List<DivisionMatcher.Candidate> divisions,
            String reference
    ) {
        DivisionMatcher.Resolution resolution =
                DivisionMatcher.resolve(divisions, reference);

        if (resolution.ambiguous()) {
            addIssue(
                    row, "ERROR", field, "DIVISION_AMBIGUOUS",
                    "Division reference matches more than one division; map "
                            + "Division ID instead."
            );
        } else if (!resolution.matched()) {
            addIssue(
                    row, "ERROR", field,
                    "DIVISION_NOT_FOUND_OR_OUT_OF_SCOPE",
                    "Division '" + reference.trim() + "' was not found. "
                            + "Check the spelling or use the division code."
            );
        }
    }

    private List<DivisionMatcher.Candidate> loadDivisions() {
        return jdbcTemplate.query(
                "SELECT id, name, code FROM public.divisions",
                (rs, rowNum) -> new DivisionMatcher.Candidate(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("code")
                )
        );
    }

    private void markEmployeeMasterDuplicates(
            List<RowData> rows,
            boolean employeeLevel
    ) {
        Map<String, RowData> firstByKey = new LinkedHashMap<>();

        for (RowData row : rows) {
            String code = normalizedOrNull(row.employeeCode);
            String hrms = normalizedOrNull(row.hrmsId);
            String key = code != null
                    ? "C:" + code
                    : hrms != null
                            ? "H:" + hrms
                            : row.identityKey();

            if (key == null) {
                continue;
            }

            if (!employeeLevel) {
                String component = normalizedOrNull(row.componentCode);

                if (component != null) {
                    key = key + "|" + component;
                }
                if (row.effectiveFrom != null) {
                    key = key + "|" + row.effectiveFrom;
                }
            }

            RowData first = firstByKey.putIfAbsent(key, row);

            if (first != null) {
                addIssue(
                        row, "ERROR", "employeeIdentifier",
                        "DUPLICATE_EMPLOYEE_ROW",
                        "Employee appears more than once in this upload "
                                + "(first seen on row " + first.rowNumber + ")."
                );
            }
        }
    }

    private void markHistoryDuplicates(List<RowData> rows) {
        Map<String, RowData> firstByKey = new LinkedHashMap<>();
        Map<String, RowData> firstTotalsByPeriod = new LinkedHashMap<>();
        Set<String> existing = loadExistingHistoryKeys(rows);

        for (RowData row : rows) {
            if (row.employee == null || row.component == null
                    || row.periodLabel == null) {
                continue;
            }

            String key = row.employee.id() + "|" + row.component.id()
                    + "|" + row.periodLabel;

            RowData first = firstByKey.putIfAbsent(key, row);

            if (first != null) {
                addIssue(
                        row, "ERROR", "period",
                        "DUPLICATE_HISTORY_ROW",
                        "The same employee, period and component appear more "
                                + "than once (first seen on row "
                                + first.rowNumber + ")."
                );
            } else if (existing.contains(key)) {
                addIssue(
                        row, "ERROR", "period",
                        "HISTORY_ALREADY_RECORDED",
                        "Payroll history for this employee, period and "
                                + "component is already recorded."
                );
            }

            if (row.gross != null && row.totalDeductions != null
                    && row.net != null
                    && row.gross.subtract(row.totalDeductions)
                            .compareTo(row.net) != 0) {
                addIssue(
                        row, "ERROR", "net", "TOTALS_MISMATCH",
                        "Gross minus total deductions does not equal net on "
                                + "this row."
                );
            }

            if (row.gross != null && row.totalDeductions != null
                    && row.net != null) {
                String totalsKey = row.employee.id() + "|" + row.periodLabel;
                RowData firstTotals = firstTotalsByPeriod.putIfAbsent(
                                totalsKey, row);

                if (firstTotals != null
                                && (firstTotals.gross.compareTo(row.gross) != 0
                                        || firstTotals.totalDeductions.compareTo(
                                                row.totalDeductions) != 0
                                        || firstTotals.net.compareTo(row.net) != 0)) {
                    addIssue(
                                    row, "ERROR", "gross/totalDeductions/net",
                                    "INCONSISTENT_HISTORY_TOTALS",
                                    "Summary totals differ between component rows for "
                                            + "this employee and period."
                    );
                }
            }
        }
    }

    private Set<String> loadExistingHistoryKeys(List<RowData> rows) {
        Set<String> result = new HashSet<>();
        Set<Long> employeeIds = new LinkedHashSet<>();
        Set<Long> componentIds = new LinkedHashSet<>();

        for (RowData row : rows) {
            if (row.employee != null) {
                employeeIds.add(row.employee.id());
            }
            if (row.component != null) {
                componentIds.add(row.component.id());
            }
        }

        if (employeeIds.isEmpty() || componentIds.isEmpty()) {
            return result;
        }

        for (List<Long> employeeChunk : chunksLong(employeeIds, 500)) {
            List<Object> args = new ArrayList<>(employeeChunk);
            args.addAll(componentIds);

            List<Map<String, Object>> found = jdbcTemplate.queryForList(
                    """
                    SELECT employee_id, salary_component_id,
                           to_char(payroll_period, 'YYYY-MM') AS period_label
                    FROM public.payroll_history_records
                    WHERE employee_id IN (%s)
                      AND salary_component_id IN (%s)
                    """.formatted(
                            placeholders(employeeChunk.size()),
                            placeholders(componentIds.size())),
                    args.toArray()
            );

            for (Map<String, Object> row : found) {
                result.add(
                        ((Number) row.get("employee_id")).longValue() + "|"
                                + ((Number) row.get("salary_component_id")).longValue() + "|"
                                + row.get("period_label")
                );
            }
        }

        return result;
    }

    private void markRevisionDuplicates(List<RowData> rows) {
        Map<String, RowData> firstByKey = new LinkedHashMap<>();
        Set<String> existing = loadExistingRevisionKeys(rows);

        for (RowData row : rows) {
            if (row.employee == null || row.component == null
                    || row.effectiveFrom == null) {
                continue;
            }

            String key = row.employee.id() + "|" + row.component.id()
                    + "|" + row.effectiveFrom;

            RowData first = firstByKey.putIfAbsent(key, row);

            if (first != null) {
                addIssue(
                        row, "ERROR", "effectiveFrom",
                        "DUPLICATE_REVISION",
                        "The same employee, component and effective date appear "
                                + "more than once (first seen on row "
                                + first.rowNumber + ")."
                );
                continue;
            }

            if (existing.contains(key)) {
                addIssue(
                        row, "ERROR", "effectiveFrom",
                        "REVISION_ALREADY_RECORDED",
                        "A salary revision for this employee, component and "
                                + "effective date is already recorded."
                );
            }
        }
    }

    private Set<String> loadExistingRevisionKeys(List<RowData> rows) {
        Set<String> result = new HashSet<>();
        Set<Long> employeeIds = new LinkedHashSet<>();
        Set<Long> componentIds = new LinkedHashSet<>();

        for (RowData row : rows) {
            if (row.employee != null) {
                employeeIds.add(row.employee.id());
            }
            if (row.component != null) {
                componentIds.add(row.component.id());
            }
        }

        if (employeeIds.isEmpty() || componentIds.isEmpty()) {
            return result;
        }

        for (List<Long> employeeChunk : chunksLong(employeeIds, 500)) {

            List<Object> args = new ArrayList<>(employeeChunk);
            args.addAll(componentIds);

            List<Map<String, Object>> found = jdbcTemplate.queryForList(
                    """
                    SELECT employee_id, salary_component_id, effective_from
                    FROM public.salary_revisions
                    WHERE employee_id IN (%s)
                      AND salary_component_id IN (%s)
                      AND status NOT IN ('CANCELLED', 'REJECTED')
                    """.formatted(
                            placeholders(employeeChunk.size()),
                            placeholders(componentIds.size())),
                    args.toArray()
            );

            for (Map<String, Object> row : found) {
                result.add(
                        ((Number) row.get("employee_id")).longValue() + "|"
                                + ((Number) row.get("salary_component_id")).longValue() + "|"
                                + row.get("effective_from")
                );
            }
        }

        return result;
    }

    private List<ImportJobSheetValidationResponse.RowIssue> sheetWarningsFor(
            String type, int headerRowNumber
    ) {
        String message = switch (type) {
            case "EMPLOYEE_MASTER" -> "Existing employees are matched and "
                    + "skipped. Missing employees will be created on approval.";
            case "EMPLOYEE_MASTER_AND_COMPONENTS" ->
                    "Existing employees are matched and skipped; missing "
                            + "employees will be created on approval. Only "
                            + "supplied salary amounts are imported.";
            default -> null;
        };

        if (message == null) {
            return List.of();
        }

        String code = "EMPLOYEE_MASTER".equals(type)
                ? "IMPORT_WILL_CREATE_EMPLOYEES"
                : "IMPORT_WILL_CREATE_EMPLOYEES_WITH_SALARY";

        return List.of(new ImportJobSheetValidationResponse.RowIssue(
                headerRowNumber, "WARNING", "sheet_type",
                code, message));
    }

    private ImportJobSheetValidationResponse completeValidation(
            long jobId,
            int sheetIndex,
            long sheetId,
            String sheetName,
            long userId,
            List<RowData> rows,
            List<ImportJobSheetValidationResponse.RowIssue> sheetIssues,
            String importType,
            Map<String, ComponentRecord> components
    ) {
        List<ImportJobSheetValidationResponse.RowIssue> allIssues =
                new ArrayList<>(sheetIssues);

        Set<Integer> errorRows = new HashSet<>();
        Set<Integer> warningRows = new HashSet<>();
        int tempIdRows = 0;

        for (RowData row : rows) {
            allIssues.addAll(row.issues);

            if (row.tempIdAssigned) {
                tempIdRows++;
            }

            if (row.hasErrors()) {
                errorRows.add(row.rowNumber);
            }

            if (row.hasWarnings()) {
                warningRows.add(row.rowNumber);
            }
        }

        jdbcTemplate.update(
                """
                DELETE FROM public.import_job_sheet_issues
                WHERE import_job_sheet_id = ?
                """,
                sheetId
        );

        saveIssues(sheetId, allIssues);

        int totalRows = rows.size();
        int validRows = totalRows - errorRows.size();
        int errorCount = errorRows.size();
        int warningCount = warningRows.size();

        Totals totals = new Totals();
        LocalDate periodStart = null;
        LocalDate periodEnd = null;

        for (RowData row : rows) {
            if (row.hasErrors()) {
                continue;
            }

            LocalDate rowPeriod = row.effectiveFrom;

            if (rowPeriod == null && row.periodLabel != null) {
                try {
                    rowPeriod = java.time.YearMonth
                            .parse(row.periodLabel)
                            .atDay(1);
                } catch (RuntimeException ignored) {
                    rowPeriod = null;
                }
            }

            if (rowPeriod != null) {
                periodStart = periodStart == null || rowPeriod.isBefore(periodStart)
                        ? rowPeriod : periodStart;
                periodEnd = periodEnd == null || rowPeriod.isAfter(periodEnd)
                        ? rowPeriod : periodEnd;
            }

            if ("EMPLOYEE_MASTER_AND_COMPONENTS".equals(importType)) {
                for (Map.Entry<String, BigDecimal> entry
                        : row.componentAmounts.entrySet()) {
                    ComponentRecord component =
                            resolveComponent(components, entry.getKey());
                    if (component != null) {
                        totals.add(entry.getValue(), component.category(),
                                component.code());
                    }
                }

                if (row.amount != null && row.component != null) {
                    totals.add(row.amount, row.component.category(),
                            row.component.code());
                }
            } else if (!"EMPLOYEE_MASTER".equals(importType)) {
                BigDecimal amount = "SALARY_REVISIONS".equals(importType)
                        ? row.newAmount : row.amount;
                String category = row.component != null
                        ? row.component.category() : row.category;
                String code = row.component != null
                        ? row.component.code() : row.componentCode;
                totals.add(amount, category, code);
            }
        }

        String newStatus = errorCount > 0 ? "HAS_ERRORS" : "VALIDATED";

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRows", totalRows);
        summary.put("validRows", validRows);
        summary.put("errorRows", errorCount);
        summary.put("warningRows", warningCount);
        summary.put("tempIdRows", tempIdRows);
        summary.put("validationVersion", 1);
        summary.put("earningsTotal", totals.earnings);
        summary.put("deductionsTotal", totals.deductions);
        summary.put("basicPayTotal", totals.basicPay);
        summary.put("periodStart",
                periodStart == null ? null : periodStart.toString());
        summary.put("periodEnd",
                periodEnd == null ? null : periodEnd.toString());

        String summaryJson;
        try {
            summaryJson = jsonMapper.writeValueAsString(summary);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to store validation summary.", ex);
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE public.import_job_sheets s
                SET status = ?,
                    valid_rows = ?,
                    error_rows = ?,
                    warning_rows = ?,
                    validation_summary = ?::jsonb,
                    validated_at = now(),
                    updated_at = now()
                WHERE s.id = ?
                  AND s.import_job_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM public.import_jobs j
                      WHERE j.id = s.import_job_id
                        AND j.uploaded_by = ?
                  )
                """,
                newStatus,
                validRows,
                errorCount,
                warningCount,
                summaryJson,
                sheetId,
                jobId,
                userId
        );

        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The worksheet could not be updated."
            );
        }

        refreshJobAggregate(jobId);

        String message = errorCount == 0
                ? "Validation completed without errors. Review warnings before proceeding."
                : "Validation completed. Correct the reported errors before import.";

        return new ImportJobSheetValidationResponse(
                jobId, sheetIndex, sheetName, newStatus, totalRows, validRows,
                errorCount, warningCount, tempIdRows,
                allIssues.stream().limit(100).toList(),
                message
        );
    }

    private String resolvePeriodLabel(String raw, RowData data) {
        if (!notBlank(raw)) {
            addIssue(
                    data, "ERROR", "period", "PERIOD_REQUIRED",
                    "Payroll period is required."
            );
            return null;
        }

        String value = raw.trim();

        if (value.matches("^[0-9]{4}-(0[1-9]|1[0-2])$")
                && !"0000".equals(value.substring(0, 4))) {
            return value;
        }

        if (value.matches("^(0[1-9]|1[0-2])/[0-9]{4}$")
                && !"0000".equals(value.substring(3))) {
            return value.substring(3) + "-" + value.substring(0, 2);
        }

        addIssue(
                data, "ERROR", "period", "INVALID_PERIOD",
                "Payroll period must use YYYY-MM or MM/YYYY."
        );
        return null;
    }

    private void saveIssues(
            long sheetId,
            List<ImportJobSheetValidationResponse.RowIssue> issues
    ) {

        if (issues.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                """
                INSERT INTO public.import_job_sheet_issues (
                    import_job_sheet_id,
                    source_row_number,
                    severity,
                    field_name,
                    error_code,
                    message
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {

                    @Override
                    public void setValues(
                            PreparedStatement statement,
                            int index
                    ) throws SQLException {

                        var issue = issues.get(index);

                        statement.setLong(1, sheetId);
                        statement.setInt(2, issue.sourceRowNumber());
                        statement.setString(3, issue.severity());
                        statement.setString(4, issue.fieldName());
                        statement.setString(5, issue.errorCode());
                        statement.setString(6, issue.message());
                    }

                    @Override
                    public int getBatchSize() {
                        return issues.size();
                    }
                }
        );
    }

    private Map<String, Integer> createColumnIndexes(
            List<String> headers
    ) {

        Map<String, Integer> indexes = new HashMap<>();

        for (int index = 0; index < headers.size(); index++) {

            String header = headers.get(index);

            if (header == null || header.isBlank()) {
                continue;
            }

            if (indexes.putIfAbsent(header, index) != null) {
                throw badRequest(
                        "Duplicate source header detected: " + header
                );
            }
        }

        return indexes;
    }

    private void addIssue(
            RowData row,
            String severity,
            String fieldName,
            String errorCode,
            String message
    ) {
        row.issues.add(
                new ImportJobSheetValidationResponse.RowIssue(
                        row.rowNumber,
                        severity,
                        fieldName,
                        errorCode,
                        message
                )
        );
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizedOrNull(String value) {
        return notBlank(value) ? normalize(value) : null;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private String placeholders(int count) {
        return String.join(
                ",",
                java.util.Collections.nCopies(count, "?")
        );
    }

    private <T> List<List<T>> chunks(
            Set<T> values,
            int chunkSize
    ) {
        List<List<T>> result = new ArrayList<>();
        List<T> all = new ArrayList<>(values);

        for (int start = 0; start < all.size(); start += chunkSize) {
            result.add(
                    all.subList(
                            start,
                            Math.min(start + chunkSize, all.size())
                    )
            );
        }

        return result;
    }

    private List<List<Long>> chunksLong(
            Set<Long> values,
            int chunkSize
    ) {
        return chunks(values, chunkSize);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }

    private static final class Totals {

        private BigDecimal earnings = BigDecimal.ZERO;
        private BigDecimal deductions = BigDecimal.ZERO;
        private BigDecimal basicPay = BigDecimal.ZERO;

        private void add(BigDecimal amount, String category, String code) {
            if (amount == null) {
                return;
            }

            if ("EARNING".equalsIgnoreCase(category)) {
                earnings = earnings.add(amount);
            } else if ("DEDUCTION".equalsIgnoreCase(category)) {
                deductions = deductions.add(amount);
            }

            if (code != null
                    && ("BASIC".equalsIgnoreCase(code)
                        || "BASIC_PAY".equalsIgnoreCase(code))) {
                basicPay = basicPay.add(amount);
            }
        }
    }

    private static class RowData {

        private final int rowNumber;
        private final List<ImportJobSheetValidationResponse.RowIssue> issues =
                new ArrayList<>();

        private String hrmsId;
        private String employeeCode;
        private String aadhaar;
        private String pan;
        private String contact;
        private String email;
        private String pf;
        private String nps;
        private String gpf;
        private boolean tempIdAssigned;
        private String employeeName;
        private String designation;
        private String dateOfJoining;
        private String dateOfBirth;
        private String employmentType;
        private String payScale;
        private BigDecimal basicPay;
        private String bankAccount;
        private String ifscCode;
        private String bankName;
        private String branch;
        private String componentCode;
        private String componentName;
        private String category;

        private EmployeeRecord employee;
        private ComponentRecord component;

        private BigDecimal amount;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private final Map<String, BigDecimal> componentAmounts =
                new LinkedHashMap<>();

        private String divisionName;
        private String sourceDivisionId;
        private String periodLabel;
        private String sourceStatus;
        private BigDecimal oldAmount;
        private BigDecimal newAmount;
        private BigDecimal gross;
        private BigDecimal totalDeductions;
        private BigDecimal net;

        private RowData(int rowNumber) {
            this.rowNumber = rowNumber;
        }

        private List<IdentityRef> identityRefs() {
            List<IdentityRef> refs = new ArrayList<>();

            addIdentity(refs, "AADHAAR", aadhaar);
            addIdentity(refs, "PAN", pan);
            addIdentity(refs, "PHONE", contact);
            addIdentity(refs, "EMAIL", email);
            addIdentity(refs, "PF", pf);
            addIdentity(refs, "NPS", nps);
            addIdentity(refs, "GPF", gpf);

            return refs;
        }

        private String identityKey() {
            List<IdentityRef> refs = identityRefs();

            if (refs.isEmpty()) {
                return null;
            }

            StringBuilder key = new StringBuilder("I:");

            for (IdentityRef ref : refs) {
                key.append(ref.type())
                        .append('=')
                        .append(normalizeIdentity(ref.type(), ref.value()))
                        .append('|');
            }

            return key.toString();
        }

        private static void addIdentity(
                List<IdentityRef> refs,
                String type,
                String value
        ) {
            if (value != null && !value.isBlank()) {
                refs.add(new IdentityRef(type, value));
            }
        }

        private boolean hasErrors() {
            return issues.stream()
                    .anyMatch(issue -> "ERROR".equals(issue.severity()));
        }

        private boolean hasWarnings() {
            return issues.stream()
                    .anyMatch(issue -> "WARNING".equals(issue.severity()));
        }
    }

    private record EmployeeRecord(
            long id,
            String employeeCode,
            String hrmsId,
            String name,
            long divisionId
    ) {
    }

    private record IdentityRef(
            String type,
            String value
    ) {
    }

    private record IdentityMatch(
            String value,
            EmployeeRecord employee
    ) {
    }

    private record ComponentRecord(
            long id,
            String code,
            String name,
            String category
    ) {
    }

    private record ValueKey(
            long employeeId,
            long componentId,
            LocalDate effectiveFrom
    ) {
    }

    private record EmployeeComponentKey(
            long employeeId,
            long componentId
    ) {
    }

    private record ExistingInterval(
            LocalDate from,
            LocalDate to,
            BigDecimal amount
    ) {
    }
}
