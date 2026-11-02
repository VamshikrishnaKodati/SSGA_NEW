
package SSGA_Backend.importer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;

@Service
public class ImportJobSheetMappingService {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "EMPLOYEE_MASTER",
            "EMPLOYEE_MASTER_AND_COMPONENTS",
            "SALARY_ENTITLEMENTS",
            "PAYROLL_HISTORY",
            "SALARY_REVISIONS",
            "REFERENCE_ONLY",
            "SKIP"
    );

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final ImportJobSheetValidationService validationService;

    public ImportJobSheetMappingService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper,
            ImportJobSheetValidationService validationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;
        this.validationService = validationService;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager"
    )
    public ImportJobSheetMappingResponse confirmMapping(
            long jobId,
            int sheetIndex,
            ConfirmSheetMappingRequest request
    ) throws IOException {

        if (jobId <= 0 || sheetIndex < 0 || request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Valid job ID, sheet index and mapping are required."
            );
        }

        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal()
                        instanceof SsgaPrincipal user)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated user not found."
            );
        }

        String type = request.confirmedType() == null
                ? ""
                : request.confirmedType().trim()
                        .toUpperCase(Locale.ROOT);

        if (!SUPPORTED_TYPES.contains(type)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported sheet type: " + type
            );
        }

        rlsContext.setCurrentUser();

        List<Map<String, Object>> records =
                jdbcTemplate.queryForList(
                        """
                        SELECT
                            s.sheet_name,
                            s.headers::text AS headers_json,
                            s.status
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
                    "Import job or worksheet not found."
            );
        }

        Map<String, Object> record = records.get(0);
        String sheetName = (String) record.get("sheet_name");
        String currentStatus = (String) record.get("status");
        String headersJson = (String) record.get("headers_json");

        if (!Set.of("DETECTED", "MAPPED", "FAILED", "SKIPPED",
                "VALIDATED", "HAS_ERRORS")
                .contains(currentStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The worksheet can no longer be remapped in status "
                            + currentStatus + "."
            );
        }

        String[] headerArray =
                jsonMapper.readValue(headersJson, String[].class);

        List<String> headers = Arrays.asList(headerArray);

        Map<String, Object> submittedColumns =
                request.columnMapping() == null
                        ? Map.of()
                        : request.columnMapping();

        Map<String, Object> submittedOptions =
                request.options() == null
                        ? Map.of()
                        : request.options();

        Map<String, Object> columns =
                validateColumns(type, headers, submittedColumns);

        Map<String, Object> options =
                validateOptions(type, submittedOptions);

        validateRequiredMappings(type, columns, options);

        Map<String, Object> storedMapping = new LinkedHashMap<>();
        storedMapping.put("columns", columns);
        storedMapping.put("options", options);

        String mappingJson =
                jsonMapper.writeValueAsString(storedMapping);

        String notes = request.notes() == null
                ? null
                : request.notes().trim();

        if (notes != null && notes.length() > 2000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Notes cannot exceed 2000 characters."
            );
        }

        String newStatus =
                type.equals("SKIP") || type.equals("REFERENCE_ONLY")
                        ? "SKIPPED"
                        : "MAPPED";

        int updated = jdbcTemplate.update(
                """
                UPDATE public.import_job_sheets s
                SET confirmed_type = ?,
                    status = ?,
                    column_mapping = ?::jsonb,
                    confirmed_by = ?,
                    confirmed_at = now(),
                    notes = COALESCE(NULLIF(?, ''), s.notes),
                    valid_rows = 0,
                    error_rows = 0,
                    warning_rows = 0,
                    validation_summary = '{}'::jsonb,
                    validated_at = NULL,
                    updated_at = now()
                WHERE s.import_job_id = ?
                  AND s.sheet_index = ?
                  AND s.status IN (
                      'DETECTED',
                      'MAPPED',
                      'FAILED',
                      'SKIPPED',
                      'VALIDATED',
                      'HAS_ERRORS'
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM public.import_jobs j
                      WHERE j.id = s.import_job_id
                        AND j.uploaded_by = ?
                  )
                """,
                type,
                newStatus,
                mappingJson,
                user.getUserId(),
                notes,
                jobId,
                sheetIndex,
                user.getUserId()
        );

        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The worksheet could not be updated. Refresh and retry."
            );
        }

        jdbcTemplate.update(
                """
                DELETE FROM public.import_job_sheet_issues i
                USING public.import_job_sheets s
                WHERE i.import_job_sheet_id = s.id
                  AND s.import_job_id = ?
                  AND s.sheet_index = ?
                """,
                jobId,
                sheetIndex
        );

        // Remapping changes which worksheets count towards the job, so the
        // job-level totals/status must be recomputed here; otherwise a job
        // left in HAS_ERRORS stays unsubmittable even after the bad sheet is
        // reclassified as review-only.
        validationService.refreshJobAggregate(jobId);

        return new ImportJobSheetMappingResponse(
                jobId,
                sheetIndex,
                sheetName,
                type,
                newStatus,
                columns,
                options,
                "Sheet classification and column mapping saved. "
                        + "No salary data has been imported."
        );
    }

    private Map<String, Object> validateColumns(
            String type,
            List<String> headers,
            Map<String, Object> submitted
    ) {

        Set<String> allowedKeys = switch (type) {
            case "EMPLOYEE_MASTER" -> Set.of(
                    "employeeCode", "hrmsId", "employeeName",
                    "designation", "wing", "division", "divisionId",
                    "dateOfJoining", "dateOfBirth", "employmentType",
                    "payScale", "basicPay", "contact", "pan",
                    "aadhaar", "employeeStatus", "bankAccount",
                    "ifscCode", "bankName", "branch"
            );
            case "EMPLOYEE_MASTER_AND_COMPONENTS" -> Set.of(
                    "employeeCode", "hrmsId", "employeeName",
                    "designation", "wing", "division", "divisionId",
                    "dateOfJoining", "dateOfBirth", "employmentType",
                    "payScale", "basicPay", "contact", "pan",
                    "aadhaar", "employeeStatus", "bankAccount",
                    "ifscCode", "bankName", "branch",
                    "effectiveFrom", "effectiveTo",
                    "componentCode", "componentName", "category",
                    "amount", "componentColumns"
            );
            case "SALARY_ENTITLEMENTS" -> Set.of(
                    "employeeCode", "hrmsId", "employeeName",
                    "division", "divisionId", "effectiveFrom",
                    "effectiveTo", "componentCode", "componentName",
                    "category", "amount", "componentColumns"
            );
            case "PAYROLL_HISTORY" -> Set.of(
                    "employeeCode", "hrmsId", "employeeName",
                    "division", "divisionId", "period",
                    "payrollMonth", "payrollYear", "componentCode",
                    "componentName", "category", "amount",
                    "gross", "totalDeductions", "net",
                    "transactionStatus", "componentColumns"
            );
            case "SALARY_REVISIONS" -> Set.of(
                    "employeeCode", "hrmsId", "employeeName",
                    "effectiveFrom", "componentCode", "componentName",
                    "oldAmount", "newAmount", "amount", "reason"
            );
            default -> Set.of();
        };

        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> usedHeaders = new HashSet<>();

        if (type.equals("SKIP") || type.equals("REFERENCE_ONLY")) {
            if (!submitted.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Skipped/reference sheets do not need column mappings."
                );
            }

            return result;
        }

        for (Map.Entry<String, Object> entry : submitted.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (!allowedKeys.contains(key)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported mapping field: " + key
                );
            }

            if (key.equals("componentColumns")) {
                if (!(value instanceof Map<?, ?> componentMap)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "componentColumns must be an object of "
                                    + "component code to source header."
                    );
                }

                if (componentMap.isEmpty()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "componentColumns cannot be empty."
                    );
                }

                Map<String, String> validatedComponents =
                        new LinkedHashMap<>();

                for (Map.Entry<?, ?> component
                        : componentMap.entrySet()) {

                    if (!(component.getKey() instanceof String code)
                            || !(component.getValue()
                                    instanceof String header)) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Each component mapping must contain "
                                        + "a string code and header."
                        );
                    }

                    String normalizedCode =
                            code.trim().toUpperCase(Locale.ROOT);

                    validateSourceHeader(headers, header, usedHeaders);

                    Boolean exists = jdbcTemplate.queryForObject(
                            """
                            SELECT EXISTS (
                                SELECT 1
                                FROM public.salary_components
                                WHERE component_code = ?
                                  AND status = 'ACTIVE'
                            )
                            """,
                            Boolean.class,
                            normalizedCode
                    );

                    if (!Boolean.TRUE.equals(exists)) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Unknown or inactive salary component: "
                                        + normalizedCode
                        );
                    }

                    validatedComponents.put(
                            normalizedCode,
                            header
                    );
                }

                result.put(key, validatedComponents);

            } else {
                if (!(value instanceof String header)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Mapping field " + key
                                    + " must reference a source header."
                    );
                }

                validateSourceHeader(headers, header, usedHeaders);
                result.put(key, header);
            }
        }

        return result;
    }

    private void validateSourceHeader(
            List<String> headers,
            String header,
            Set<String> usedHeaders
    ) {
        if (header == null || header.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Source header cannot be blank."
            );
        }

        long matches = headers.stream()
                .filter(header::equals)
                .count();

        if (matches != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Source header must match exactly one detected column: "
                            + header
            );
        }

        if (!usedHeaders.add(header)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Source header is mapped more than once: " + header
            );
        }
    }

    private Map<String, Object> validateOptions(
            String type,
            Map<String, Object> submitted
    ) {

        Set<String> allowedOptions = switch (type) {
            case "EMPLOYEE_MASTER" -> Set.of("dateFormat");
            case "EMPLOYEE_MASTER_AND_COMPONENTS" -> Set.of(
                    "layout", "dateFormat", "effectiveFromDefault"
            );
            case "SALARY_ENTITLEMENTS" -> Set.of(
                    "layout", "dateFormat", "effectiveFromDefault"
            );
            case "PAYROLL_HISTORY" -> Set.of(
                    "layout", "dateFormat",
                    "periodYearDefault", "periodMonthDefault"
            );
            case "SALARY_REVISIONS" -> Set.of(
                    "dateFormat", "effectiveFromDefault"
            );
            default -> Set.of();
        };

        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : submitted.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (!allowedOptions.contains(key)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported import option: " + key
                );
            }

            if (!(value instanceof String) && !(value instanceof Number)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Import option " + key
                                + " must be a string or number."
                );
            }

            result.put(key, value);
        }

        Object effectiveFrom = result.get("effectiveFromDefault");

        if (effectiveFrom != null) {
            try {
                LocalDate.parse(effectiveFrom.toString());
            } catch (DateTimeParseException ex) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "effectiveFromDefault must use YYYY-MM-DD."
                );
            }
        }

        validatePeriodOption(result, "periodYearDefault", 1900, 2200);
        validatePeriodOption(result, "periodMonthDefault", 1, 12);

        return result;
    }

    private void validatePeriodOption(
            Map<String, Object> options,
            String key,
            int minimum,
            int maximum
    ) {
        Object value = options.get(key);

        if (value == null) {
            return;
        }

        try {
            int parsed = Integer.parseInt(value.toString());

            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    key + " must be between " + minimum
                            + " and " + maximum + "."
            );
        }
    }

    private void validateRequiredMappings(
            String type,
            Map<String, Object> columns,
            Map<String, Object> options
    ) {

        if (type.equals("SKIP") || type.equals("REFERENCE_ONLY")) {
            return;
        }

        boolean hasEmployeeIdentity =
                columns.containsKey("employeeCode")
                        || columns.containsKey("hrmsId");

        if (type.equals("EMPLOYEE_MASTER")) {
            if (!hasEmployeeIdentity
                    || !columns.containsKey("employeeName")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Employee master mapping requires employeeCode "
                                + "or hrmsId and employeeName."
                );
            }

            return;
        }

        if (type.equals("EMPLOYEE_MASTER_AND_COMPONENTS")) {
            boolean longLayout =
                    columns.containsKey("componentCode")
                            && columns.containsKey("amount");

            boolean wideLayout =
                    columns.containsKey("componentColumns");

            boolean hasEffectiveDate =
                    columns.containsKey("effectiveFrom")
                            || options.containsKey("effectiveFromDefault");

            if (!hasEmployeeIdentity
                    || !columns.containsKey("employeeName")
                    || !(longLayout || wideLayout)
                    || !hasEffectiveDate) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Employee master + salary components require "
                                + "employeeCode or hrmsId, employeeName, "
                                + "either componentCode plus amount or "
                                + "componentColumns, and an effective "
                                + "date column or an explicit default date."
                );
            }

            return;
        }

        if (type.equals("SALARY_ENTITLEMENTS")) {
            boolean longLayout =
                    columns.containsKey("componentCode")
                            && columns.containsKey("amount");

            boolean wideLayout =
                    columns.containsKey("componentColumns");

            boolean hasEffectiveDate =
                    columns.containsKey("effectiveFrom")
                            || options.containsKey("effectiveFromDefault");

            if (!hasEmployeeIdentity
                    || !(longLayout || wideLayout)
                    || !hasEffectiveDate) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Salary entitlements require employeeCode or "
                                + "hrmsId, either componentCode plus amount "
                                + "or componentColumns, and an effective "
                                + "date column or an explicit default date."
                );
            }

            return;
        }

        if (type.equals("PAYROLL_HISTORY")) {
            boolean hasPeriod =
                    columns.containsKey("period")
                    || (
                        columns.containsKey("payrollYear")
                        && columns.containsKey("payrollMonth")
                    )
                    || (
                        options.containsKey("periodYearDefault")
                        && options.containsKey("periodMonthDefault")
                    );

            boolean longLayout =
                    columns.containsKey("componentCode")
                            && columns.containsKey("amount");

            boolean wideLayout =
                    columns.containsKey("componentColumns");

            if (!hasEmployeeIdentity
                    || !hasPeriod
                    || !(longLayout || wideLayout)) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Payroll history requires an employee identifier, "
                                + "a payroll period, and component/amount "
                                + "columns or componentColumns."
                );
            }

            return;
        }

        if (type.equals("SALARY_REVISIONS")) {
            boolean hasAmount =
                    columns.containsKey("newAmount")
                            || columns.containsKey("amount");

            boolean hasEffectiveDate =
                    columns.containsKey("effectiveFrom")
                            || options.containsKey("effectiveFromDefault");

            if (!hasEmployeeIdentity
                    || !columns.containsKey("componentCode")
                    || !hasAmount
                    || !hasEffectiveDate) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Salary revisions require an employee identifier, "
                                + "componentCode, newAmount or amount, "
                                + "and an effective date."
                );
            }
        }
    }
}
