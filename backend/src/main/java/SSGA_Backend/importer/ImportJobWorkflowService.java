package SSGA_Backend.importer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Import-job workflow: submit for approval, approve/reject by a different
 * user (maker-checker) and finalize by a third user, per migration 08.
 * Finalization writes validated SALARY_ENTITLEMENTS rows into
 * employee_salary_values. State transitions are enforced here because the
 * database intentionally leaves them to the application layer.
 */
@Service
public class ImportJobWorkflowService {

    private static final Set<String> EDITABLE_STATUSES =
            Set.of("VALIDATED", "HAS_ERRORS", "DRAFT_IMPORTED");

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final ImportJobProgressService progressService;
    private final Path storageRoot;

    public ImportJobWorkflowService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper,
            ImportJobProgressService progressService,
            @Value("${ssga.import.storage-dir:}") String storageDirectory
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;
        this.progressService = progressService;

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

    private int importRecordCount(long jobId) {
        Integer total = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(data_rows_estimate), 0)::integer
                FROM public.import_job_sheets
                WHERE import_job_id = ?
                  AND confirmed_type IS NOT NULL
                """,
                Integer.class,
                jobId
        );
        return total == null ? 0 : total;
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobWorkflowResponse submit(long jobId) {
        rlsContext.setCurrentUser();

        JobInfo job = requireJob(jobId);

        if (!EDITABLE_STATUSES.contains(job.status())) {
            throw conflict(
                    "Only a validated or draft-imported job can be submitted. "
                            + "Current status: " + job.status()
            );
        }

        if (job.validRows() <= 0 || "HAS_ERRORS".equals(job.status())) {
            throw conflict(
                    "Resolve all validation errors and revalidate before "
                            + "submitting for approval."
            );
        }

        long actor = currentUserId();

        int updated = jdbcTemplate.update(
                """
                UPDATE public.import_jobs
                SET status = 'AWAITING_APPROVAL',
                    submitted_by = ?,
                    submitted_at = now(),
                    rejection_reason = NULL,
                    approved_by = NULL,
                    approved_at = NULL
                WHERE id = ?
                  AND status IN ('VALIDATED', 'DRAFT_IMPORTED')
                """,
                actor,
                jobId
        );

        if (updated != 1) {
            throw conflict("The job could not be submitted. Refresh and retry.");
        }

        recordEvent(jobId, null, "SUBMIT", job.status(),
                "AWAITING_APPROVAL", null);
        audit(jobId, "IMPORT_SUBMIT", job.status(),
                "AWAITING_APPROVAL", null);

        return loadResponse(jobId,
                "Submitted for approval. A different user must approve.");
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobWorkflowResponse approve(
            long jobId,
            ImportJobDecisionRequest request
    ) throws IOException {
        rlsContext.setCurrentUser();

        JobInfo job = requireJob(jobId);

        if (!"AWAITING_APPROVAL".equals(job.status())) {
            throw conflict(
                    "Only a submitted job can be approved. Current status: "
                            + job.status()
            );
        }

        long actor = currentUserId();
        enforceSeparate(actor, job, "approve");
        if (job.submittedBy() != null && actor == job.submittedBy()) {
            throw conflict("Maker-checker violation: the submitter cannot approve the same job.");
        }

        // Surface blocked/mis-typed worksheets while a rejection is still
        // possible; after approval the only remaining action would be finalize.
        guardBeforeCommit(job.id(), job.validRows());

        String remarks = trimmed(request == null ? null : request.remarks());

        int updated = jdbcTemplate.update(
                """
                UPDATE public.import_jobs
                SET status = 'APPROVED',
                    approved_by = ?,
                    approved_at = now(),
                    rejection_reason = NULL,
                    notes = COALESCE(NULLIF(?, ''), notes)
                WHERE id = ?
                  AND status = 'AWAITING_APPROVAL'
                """,
                actor,
                remarks,
                jobId
        );

        if (updated != 1) {
            throw conflict("The job could not be approved. Refresh and retry.");
        }

        recordEvent(jobId, null, "APPROVE", job.status(), "APPROVED", remarks);
        audit(jobId, "IMPORT_APPROVE", job.status(), "APPROVED", remarks);

        if (job.draftImported()) {
            int started = jdbcTemplate.update(
                    """
                    UPDATE public.import_jobs
                    SET status = 'IMPORTING'
                    WHERE id = ?
                      AND status = 'APPROVED'
                    """,
                    jobId
            );

            if (started != 1) {
                throw conflict(
                        "The approved draft could not be imported. Refresh and retry."
                );
            }

            recordEvent(jobId, null, "AUTO_IMPORT", "APPROVED",
                    "IMPORTING", "Approved draft imported automatically.");

            ImportResult result = runImport(job, "IMPORT_APPROVE");

            return withResult(
                    loadResponse(jobId,
                            "Approved and imported. Review the import summary."),
                    result
            );
        }

        return loadResponse(jobId,
                "Approved. A third user must finalize the import.");
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobWorkflowResponse reject(
            long jobId,
            ImportJobDecisionRequest request
    ) {
        rlsContext.setCurrentUser();

        JobInfo job = requireJob(jobId);

        if (!"AWAITING_APPROVAL".equals(job.status())) {
            throw conflict(
                    "Only a submitted job can be rejected. Current status: "
                            + job.status()
            );
        }

        String reason = trimmed(request == null ? null : request.remarks());

        if (reason == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A rejection reason is required."
            );
        }

        long actor = currentUserId();
        enforceSeparate(actor, job, "reject");

        String targetStatus = job.draftImported()
                ? "DRAFT_IMPORTED" : "VALIDATED";

        int updated = jdbcTemplate.update(
                """
                UPDATE public.import_jobs
                SET status = ?,
                    rejection_reason = ?,
                    submitted_by = NULL,
                    submitted_at = NULL
                WHERE id = ?
                  AND status = 'AWAITING_APPROVAL'
                """,
                targetStatus,
                reason,
                jobId
        );

        if (updated != 1) {
            throw conflict("The job could not be rejected. Refresh and retry.");
        }

        recordEvent(jobId, null, "REJECT", job.status(), targetStatus, reason);
        audit(jobId, "IMPORT_REJECT", job.status(), targetStatus, reason);

        return loadResponse(jobId,
                "Rejected. The uploader can correct the draft and resubmit.");
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public List<ImportJobWorkflowEventResponse> events(long jobId) {
        rlsContext.setCurrentUser();
        requireJob(jobId);

        return jdbcTemplate.query(
                """
                SELECT e.id, e.action, e.previous_status, e.new_status,
                       e.actor_user_id, u.display_name AS actor_name,
                       e.details::text AS details, e.created_at
                FROM public.import_job_workflow_events e
                LEFT JOIN public.users u ON u.id = e.actor_user_id
                WHERE e.import_job_id = ?
                ORDER BY e.created_at DESC, e.id DESC
                """,
                (rs, rowNum) -> new ImportJobWorkflowEventResponse(
                        rs.getLong("id"),
                        rs.getString("action"),
                        rs.getString("previous_status"),
                        rs.getString("new_status"),
                        nullableLong(rs, "actor_user_id"),
                        rs.getString("actor_name"),
                        rs.getString("details"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                jobId
        );
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobWorkflowResponse importDraft(long jobId) {
        rlsContext.setCurrentUser();

        JobInfo job = requireJob(jobId);

        if (!"VALIDATED".equals(job.status())) {
            throw conflict(
                    "Only a validated job can be imported as a draft. "
                            + "Current status: " + job.status()
            );
        }

        if (job.validRows() <= 0) {
            throw conflict("There are no valid rows to import.");
        }

        long actor = currentUserId();

        int updated = jdbcTemplate.update(
                """
                UPDATE public.import_jobs
                SET status = 'DRAFT_IMPORTED',
                    draft_imported_by = ?,
                    draft_imported_at = now(),
                    rejection_reason = NULL
                WHERE id = ?
                  AND status = 'VALIDATED'
                """,
                actor,
                jobId
        );

        if (updated != 1) {
            throw conflict(
                    "The draft could not be imported. Refresh and retry."
            );
        }

        recordEvent(jobId, null, "IMPORT_DRAFT", job.status(),
                "DRAFT_IMPORTED",
                "Draft created. It is not part of payroll until approved.");
        audit(jobId, "IMPORT_DRAFT", job.status(), "DRAFT_IMPORTED",
                "Draft imported for approval.");

        return loadResponse(jobId,
                "Draft imported. Submit for approval when ready.");
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public ImportJobWorkflowResponse finalizeImport(long jobId)
            throws IOException {
        rlsContext.setCurrentUser();

        JobInfo job = requireJob(jobId);

        if (!"APPROVED".equals(job.status())) {
            throw conflict(
                    "Only an approved job can be finalized. Current status: "
                            + job.status()
            );
        }

        long actor = currentUserId();
        enforceSeparate(actor, job, "finalize");
        if (job.approvedBy() != null && actor == job.approvedBy()) {
            throw conflict("Maker-checker violation: the approver cannot finalize the same job.");
        }
        if (job.submittedBy() != null && actor == job.submittedBy()) {
            throw conflict("Maker-checker violation: the submitter cannot finalize the same job.");
        }

        // Re-check freshness immediately before committing: the finalizer is a
        // different user, so we verify the stored validation is current rather
        // than re-running it under the uploader's scope.
        guardBeforeCommit(job.id(), job.validRows());

        int started = jdbcTemplate.update(
                """
                UPDATE public.import_jobs
                SET status = 'IMPORTING',
                    finalized_by = ?,
                    finalized_at = now()
                WHERE id = ?
                  AND status = 'APPROVED'
                """,
                actor,
                jobId
        );

        if (started != 1) {
            throw conflict("The job could not be finalized. Refresh and retry.");
        }

        recordEvent(jobId, null, "FINALIZE", job.status(), "IMPORTING", null);

        ImportResult result = runImport(job, "IMPORT_FINALIZE");

        return withResult(
                loadResponse(
                        jobId,
                        "Import finished with status " + loadStatus(jobId) + "."
                ),
                result
        );
    }

    private ImportResult runImport(JobInfo job, String auditAction)
            throws IOException {

        long jobId = job.id();

        ImportProgressTracker progress = new ImportProgressTracker(
                jobId, importRecordCount(jobId), progressService
        );
        progress.publish("INFO", "Import started.");
        ImportResult result = executeImport(job, progress);
        progress.flush("Import processing completed.");

        String newStatus = result.importedRows() == 0
                ? "FAILED"
                : result.errorRows() > 0
                        ? "PARTIALLY_IMPORTED"
                        : "IMPORTED";

        jdbcTemplate.update(
                """
                UPDATE public.import_jobs
                SET status = ?,
                    imported_at = now(),
                    failure_reason = ?,
                    imported_amount_total = ?,
                    imported_earnings_total = ?,
                    imported_deductions_total = ?
                WHERE id = ?
                """,
                newStatus,
                result.importedRows() == 0
                        ? "No rows could be imported."
                        : null,
                result.amountTotal(),
                result.earningsTotal(),
                result.deductionsTotal(),
                jobId
        );

        progressService.recordCompletion(
                jobId,
                newStatus,
                progress.totalRecords(),
                progress.processedRecords(),
                result.importedRows(),
                result.skippedRows(),
                result.errorRows(),
                "Import completed with status " + newStatus + "."
        );
        recordEvent(jobId, null, newStatus, "IMPORTING", newStatus,
                "imported=" + result.importedRows()
                        + "; skipped=" + result.skippedRows()
                        + "; errors=" + result.errorRows());
        audit(jobId, auditAction, "IMPORTING", newStatus,
                "imported=" + result.importedRows()
                        + "; skipped=" + result.skippedRows()
                        + "; errors=" + result.errorRows());

        return result;
    }

    private ImportJobWorkflowResponse withResult(
            ImportJobWorkflowResponse response,
            ImportResult result
    ) {
        return new ImportJobWorkflowResponse(
                response.jobId(),
                response.uploadedBy(),
                response.status(),
                response.submittedBy(),
                response.approvedBy(),
                response.finalizedBy(),
                response.rejectionReason(),
                result.importedRows(),
                result.skippedRows(),
                result.errorRows(),
                result.amountTotal(),
                result.earningsTotal(),
                result.deductionsTotal(),
                response.message()
        );
    }

    private String loadStatus(long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM public.import_jobs WHERE id = ?",
                String.class,
                jobId
        );
    }

    private void guardBeforeCommit(long jobId, int approvedValidRows) {
        Map<String, Object> gate = jdbcTemplate.queryForMap(
                """
                SELECT
                    COUNT(*) FILTER (WHERE confirmed_type = 'SALARY_ENTITLEMENTS')
                        AS entitlement_sheets,
                    COUNT(*) FILTER (WHERE confirmed_type = 'SALARY_REVISIONS')
                        AS revision_sheets,
                    COUNT(*) FILTER (
                        WHERE confirmed_type IN ('SALARY_ENTITLEMENTS',
                                                 'SALARY_REVISIONS',
                                                 'PAYROLL_HISTORY')
                          AND status <> 'VALIDATED'
                    ) AS not_validated,
                    COUNT(*) FILTER (WHERE status = 'HAS_ERRORS') AS has_errors,
                    COUNT(*) FILTER (
                        WHERE confirmed_type = 'EMPLOYEE_MASTER'
                    ) AS unsupported_commit_sheets,
                    COUNT(*) FILTER (
                        WHERE confirmed_type = 'PAYROLL_HISTORY'
                    ) AS history_sheets,
                    COUNT(*) FILTER (
                        WHERE confirmed_type NOT IN (
                            'PAYROLL_HISTORY', 'SKIP', 'REFERENCE_ONLY'
                        )
                    ) AS non_history_sheets,
                    COALESCE(
                        SUM(valid_rows) FILTER (
                            WHERE confirmed_type IS NOT NULL
                              AND confirmed_type NOT IN ('SKIP', 'REFERENCE_ONLY')
                        ),
                        0
                    ) AS valid_rows
                FROM public.import_job_sheets
                WHERE import_job_id = ?
                """,
                jobId
        );

        long entitlementSheets = ((Number) gate.get("entitlement_sheets"))
                .longValue();
        long revisionSheets = ((Number) gate.get("revision_sheets"))
                .longValue();
        long notValidated = ((Number) gate.get("not_validated")).longValue();
        long unsupported = ((Number) gate.get("unsupported_commit_sheets"))
                .longValue();
        long historySheets = ((Number) gate.get("history_sheets"))
                .longValue();
        long nonHistorySheets = ((Number) gate.get("non_history_sheets"))
                .longValue();
        long hasErrors = ((Number) gate.get("has_errors")).longValue();
        int validRows = ((Number) gate.get("valid_rows")).intValue();

        if (unsupported > 0) {
            throw conflict(
                    "This job contains EMPLOYEE_MASTER worksheets that are "
                    + "validated for review only. "
                    + "Reclassify them as SKIP or REFERENCE_ONLY to import the "
                    + "other worksheets.");
        }

        if (historySheets > 0 && nonHistorySheets > 0) {
            throw conflict(
                    "Payroll history imports must be finalized in a separate "
                            + "job so the history commit is all-or-nothing."
            );
        }

        if (hasErrors > 0) {
            throw conflict(
                    "One or more worksheets still have validation errors. "
                    + "Re-run validation before finalizing.");
        }

        if ((entitlementSheets > 0 || revisionSheets > 0 || historySheets > 0)
                && notValidated > 0) {
            throw conflict(
                    "Mapped worksheets must be re-validated after any mapping "
                    + "change before finalizing.");
        }

        if (validRows != approvedValidRows) {
            throw conflict(
                    "The validated data changed since approval. "
                    + "Re-submit and re-approve before finalizing.");
        }
    }

    private ImportResult executeImport(
            JobInfo job,
            ImportProgressTracker progress
    ) throws IOException {
        List<ImportSheet> sheets = jdbcTemplate.query(
                """
                SELECT id, sheet_index, sheet_name, confirmed_type,
                       header_row_number, data_rows_estimate,
                       headers::text AS headers_json,
                       column_mapping::text AS mapping_json
                FROM public.import_job_sheets
                WHERE import_job_id = ?
                  AND confirmed_type IS NOT NULL
                ORDER BY sheet_index
                """,
                (rs, rowNum) -> new ImportSheet(
                        rs.getLong("id"),
                        rs.getInt("sheet_index"),
                        rs.getString("sheet_name"),
                        rs.getString("confirmed_type"),
                        rs.getObject("header_row_number", Integer.class),
                        rs.getInt("data_rows_estimate"),
                        rs.getString("headers_json"),
                        rs.getString("mapping_json")
                ),
                job.id()
        );

        int imported = 0;
        int skipped = 0;
        int errors = 0;
        BigDecimal amountTotal = BigDecimal.ZERO;
        BigDecimal earningsTotal = BigDecimal.ZERO;
        BigDecimal deductionsTotal = BigDecimal.ZERO;

        for (ImportSheet sheet : sheets) {
            SheetResult sheetResult;

            switch (sheet.confirmedType()) {
                case "SALARY_ENTITLEMENTS" ->
                        sheetResult = importSalaryEntitlements(
                                job, sheet, progress);
                case "SALARY_REVISIONS" ->
                        sheetResult = importSalaryRevisions(
                                job, sheet, progress);
                case "PAYROLL_HISTORY" ->
                        sheetResult = importPayrollHistory(
                                job, sheet, progress);
                case "EMPLOYEE_MASTER" ->
                        sheetResult = importEmployees(
                                job, sheet, progress, false);
                case "EMPLOYEE_MASTER_AND_COMPONENTS" ->
                        sheetResult = importEmployees(
                                job, sheet, progress, true);
                default -> {
                    skipped += sheet.dataRowsEstimate();
                    progress.skip(
                            sheet.dataRowsEstimate(),
                            "Skipped " + sheet.dataRowsEstimate()
                                    + " records from worksheet '"
                                    + sheet.sheetName() + "'."
                    );
                    continue;
                }
            }
            imported += sheetResult.imported();
            skipped += sheetResult.skipped();
            errors += sheetResult.errors();
            amountTotal = amountTotal.add(sheetResult.earningsTotal())
                    .add(sheetResult.deductionsTotal());
            earningsTotal = earningsTotal.add(sheetResult.earningsTotal());
            deductionsTotal = deductionsTotal.add(sheetResult.deductionsTotal());

            jdbcTemplate.update(
                    """
                    UPDATE public.import_job_sheets
                    SET imported_at = now(), updated_at = now()
                    WHERE id = ? AND import_job_id = ?
                    """,
                    sheet.id(),
                    job.id()
            );
        }

        return new ImportResult(imported, skipped, errors, amountTotal,
                earningsTotal, deductionsTotal);
    }

    private SheetResult importSalaryEntitlements(
            JobInfo job,
            ImportSheet sheet,
            ImportProgressTracker progress
    ) throws IOException {

        String fileKey = jdbcTemplate.queryForObject(
                "SELECT COALESCE(corrected_file_key, file_key) "
                        + "FROM public.import_jobs WHERE id = ?",
                String.class,
                job.id()
        );

        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (!filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            throw new IllegalStateException("Stored workbook was not found.");
        }

        SheetMapping mapping = SheetMapping.parse(
                jsonMapper, sheet.mappingJson());
        Map<String, String> columns = mapping.columns();

        String employeeCodeHeader = columns.get("employeeCode");
        String hrmsIdHeader = columns.get("hrmsId");

        if (employeeCodeHeader == null && hrmsIdHeader == null) {
            throw conflict("Sheet " + sheet.sheetName()
                    + " has no employee identifier mapping.");
        }

        List<String> headers = Arrays.asList(
                jsonMapper.readValue(sheet.headersJson(), String[].class)
        );

        Map<String, Integer> headerIndexes = new HashMap<>();

        for (int index = 0; index < headers.size(); index++) {
            headerIndexes.putIfAbsent(headers.get(index), index);
        }

        Map<String, Long> employeesByCode = new HashMap<>();
        Map<String, Long> employeesByHrms = new HashMap<>();

        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id, employee_code, hrms_id FROM public.employees"
        )) {
            employeesByCode.put(
                    normalize((String) row.get("employee_code")),
                    ((Number) row.get("id")).longValue()
            );
            employeesByHrms.put(
                    normalize((String) row.get("hrms_id")),
                    ((Number) row.get("id")).longValue()
            );
        }

        Map<String, Long> componentsByCode = new HashMap<>();
        Map<Long, String> categoryByComponentId = new HashMap<>();

        for (Map<String, Object> row : jdbcTemplate.queryForList(
                """
                SELECT id, component_code, category
                FROM public.salary_components
                WHERE status = 'ACTIVE'
                """
        )) {
            long componentId = ((Number) row.get("id")).longValue();

            componentsByCode.put(
                    normalize((String) row.get("component_code")),
                    componentId
            );

            categoryByComponentId.put(componentId, (String) row.get("category"));
        }

        int imported = 0;
        int skipped = 0;
        int errors = 0;
        BigDecimal earningsTotal = BigDecimal.ZERO;
        BigDecimal deductionsTotal = BigDecimal.ZERO;

        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            var workbookSheet = workbook.getSheetAt(sheet.sheetIndex());

            int headerIndex = sheet.headerRowNumber() - 1;

            for (int index = headerIndex + 1;
                 index <= workbookSheet.getLastRowNum();
                 index++) {

                Row row = workbookSheet.getRow(index);

                if (row == null || ImportCells.isBlankRow(row)) {
                    continue;
                }

                int importedBefore = imported;
                int skippedBefore = skipped;
                int errorsBefore = errors;
                try {
                    Long employeeId = resolveEmployee(
                            row,
                            headerIndexes,
                            formatter,
                            employeeCodeHeader,
                            hrmsIdHeader,
                            employeesByCode,
                            employeesByHrms
                    );

                    String componentCode = readText(
                            row, headerIndexes, formatter,
                            columns.get("componentCode")
                    );

                    Long componentId = componentCode == null
                            ? null
                            : componentsByCode.get(normalize(componentCode));

                    BigDecimal amount = readAmount(
                            row, headerIndexes, columns.get("amount")
                    );

                    LocalDate effectiveFrom = readDate(
                            row, headerIndexes, columns.get("effectiveFrom"),
                            mapping.effectiveFromDefault()
                    );

                    LocalDate effectiveTo = readDate(
                            row, headerIndexes, columns.get("effectiveTo"), null
                    );

                    if (employeeId == null || componentId == null
                            || amount == null || effectiveFrom == null) {
                        errors++;
                        continue;
                    }

                    if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
                        errors++;
                        continue;
                    }

                    int inserted = jdbcTemplate.update(
                            """
                            INSERT INTO public.employee_salary_values (
                                employee_id,
                                salary_component_id,
                                amount,
                                effective_from,
                                effective_to,
                                source,
                                import_job_id,
                                import_job_sheet_id,
                                import_row_number
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (employee_id, salary_component_id,
                                         effective_from)
                            DO NOTHING
                            """,
                            employeeId,
                            componentId,
                            amount,
                            effectiveFrom,
                            effectiveTo,
                            "IMPORT_JOB:" + job.id(),
                            job.id(),
                            sheet.id(),
                            index + 1
                    );

                    if (inserted == 0) {
                        // The same employee, component and effective date is
                        // already imported. The row is reported as an error
                        // instead of aborting the whole import.
                        errors++;
                        continue;
                    }

                    imported++;

                    if ("EARNING".equals(
                            categoryByComponentId.get(componentId))) {
                        earningsTotal = earningsTotal.add(amount);
                    } else {
                        deductionsTotal = deductionsTotal.add(amount);
                    }

                } catch (DataIntegrityViolationException ex) {
                    errors++;
                } catch (RuntimeException ex) {
                    errors++;
                } finally {
                    progress.record(
                            imported - importedBefore,
                            skipped - skippedBefore,
                            errors - errorsBefore
                    );
                }
            }
        }

        return new SheetResult(imported, skipped, errors, earningsTotal,
                deductionsTotal);
    }

    private SheetResult importEmployees(
            JobInfo job,
            ImportSheet sheet,
            ImportProgressTracker progress,
            boolean withComponents
    ) throws IOException {

        String fileKey = jdbcTemplate.queryForObject(
                "SELECT COALESCE(corrected_file_key, file_key) "
                        + "FROM public.import_jobs WHERE id = ?",
                String.class,
                job.id()
        );

        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (!filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            throw new IllegalStateException("Stored workbook was not found.");
        }

        SheetMapping mapping = SheetMapping.parse(
                jsonMapper, sheet.mappingJson());
        Map<String, String> columns = mapping.columns();

        if (columns.get("employeeCode") == null
                && columns.get("hrmsId") == null
                && columns.get("aadhaar") == null
                && columns.get("pan") == null
                && columns.get("contact") == null
                && columns.get("email") == null
                && columns.get("pf") == null
                && columns.get("nps") == null
                && columns.get("gpf") == null) {
            throw conflict("Sheet " + sheet.sheetName()
                    + " has no employee identifier mapping.");
        }

        if (sheet.headerRowNumber() == null || sheet.headerRowNumber() < 1) {
            throw conflict("Sheet " + sheet.sheetName()
                    + " has no header row.");
        }

        List<String> headers = Arrays.asList(
                jsonMapper.readValue(sheet.headersJson(), String[].class)
        );

        Map<String, Integer> headerIndexes = new HashMap<>();

        for (int index = 0; index < headers.size(); index++) {
            headerIndexes.putIfAbsent(headers.get(index), index);
        }

        Map<String, Long> employeeIndex = loadEmployeeIndex();
        List<DivisionMatcher.Candidate> divisions = loadDivisionCandidates();
        Map<String, Long> components = new HashMap<>();
        Map<Long, String> categoryByComponent = new HashMap<>();
        loadImportComponents(components, categoryByComponent);
        Map<String, Long> designations = loadDesignations();

        int imported = 0;
        int skipped = 0;
        int errors = 0;
        BigDecimal earningsTotal = BigDecimal.ZERO;
        BigDecimal deductionsTotal = BigDecimal.ZERO;

        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {

            var workbookSheet = workbook.getSheetAt(sheet.sheetIndex());
            int headerIndex = sheet.headerRowNumber() - 1;

            for (int index = headerIndex + 1;
                 index <= workbookSheet.getLastRowNum();
                 index++) {

                Row row = workbookSheet.getRow(index);

                if (row == null || ImportCells.isBlankRow(row)) {
                    continue;
                }

                int importedBefore = imported;
                int skippedBefore = skipped;
                int errorsBefore = errors;

                try {
                    EmployeeValues values = readEmployeeValues(
                            row, headerIndexes, columns, formatter);

                    Long employeeId = resolveIndexedEmployee(
                            employeeIndex, values);

                    boolean created = false;

                    if (employeeId == null) {
                        if (!notBlank(values.name())) {
                            throw new IllegalArgumentException(
                                    "Employee name is required.");
                        }

                        DivisionMatcher.Resolution resolution =
                                DivisionMatcher.resolve(
                                        divisions,
                                        firstNotBlank(
                                                values.divisionName(),
                                                values.divisionCode()));

                        if (!resolution.matched()) {
                            throw new IllegalArgumentException(
                                    resolution.ambiguous()
                                            ? "Division is ambiguous."
                                            : "Division was not found.");
                        }

                        DivisionMatcher.Candidate division = divisions.stream()
                                .filter(item -> item.id() == resolution.id())
                                .findFirst()
                                .orElseThrow();

                        String employeeCode = firstNotBlank(
                                values.employeeCode(), values.hrmsId());

                        boolean temporary = employeeCode == null;

                        if (temporary) {
                            employeeCode = nextTemporaryId(employeeIndex);
                        }

                        Long designationId = designations.get(
                                normalize(values.designation()));

                        employeeId = insertEmployee(
                                values,
                                division,
                                designationId,
                                employeeCode,
                                values.hrmsId(),
                                temporary
                        );

                        indexEmployee(employeeIndex, employeeId, values);
                        created = true;
                    }

                    int rowWrites = 0;

                    if (withComponents) {
                        Map<String, BigDecimal> amounts =
                                readComponentAmounts(
                                        row, headerIndexes, columns, mapping,
                                        formatter);

                        if (!amounts.isEmpty() && !columns.containsKey("effectiveFrom")
                                && mapping.effectiveFromDefault() == null) {
                            throw new IllegalArgumentException(
                                    "Effective date is required.");
                        }

                        LocalDate effectiveFrom = readDate(
                                row,
                                headerIndexes,
                                columns.get("effectiveFrom"),
                                mapping.effectiveFromDefault()
                        );

                        LocalDate effectiveTo = readDate(
                                row,
                                headerIndexes,
                                columns.get("effectiveTo"),
                                null
                        );

                        if (!amounts.isEmpty() && effectiveFrom == null) {
                            throw new IllegalArgumentException(
                                    "Effective date is required.");
                        }

                        if (effectiveTo != null && effectiveFrom != null
                                && effectiveTo.isBefore(effectiveFrom)) {
                            throw new IllegalArgumentException(
                                    "Effective To cannot be before Effective From.");
                        }

                        for (Map.Entry<String, BigDecimal> entry
                                : amounts.entrySet()) {

                            Long componentId = components.get(
                                    normalize(entry.getKey()));

                            if (componentId == null) {
                                throw new IllegalArgumentException(
                                        "Unknown salary component: "
                                                + entry.getKey());
                            }

                            ComponentWrite write = writeComponent(
                                    employeeId,
                                    componentId,
                                    entry.getValue(),
                                    effectiveFrom,
                                    effectiveTo,
                                    job,
                                    sheet,
                                    index + 1
                            );

                            if (write != ComponentWrite.SKIPPED) {
                                rowWrites++;

                                String category =
                                        categoryByComponent.get(componentId);

                                if ("EARNING".equals(category)) {
                                    earningsTotal = earningsTotal
                                            .add(entry.getValue());
                                } else {
                                    deductionsTotal = deductionsTotal
                                            .add(entry.getValue());
                                }
                            }
                        }
                    }

                    if (created || rowWrites > 0) {
                        imported++;
                    } else {
                        skipped++;
                    }

                } catch (DataIntegrityViolationException ex) {
                    errors++;
                } catch (RuntimeException ex) {
                    errors++;
                } finally {
                    progress.record(
                            imported - importedBefore,
                            skipped - skippedBefore,
                            errors - errorsBefore
                    );
                }
            }
        }

        return new SheetResult(imported, skipped, errors, earningsTotal,
                deductionsTotal);
    }

    private EmployeeValues readEmployeeValues(
            Row row,
            Map<String, Integer> headerIndexes,
            Map<String, String> columns,
            DataFormatter formatter
    ) {
        return new EmployeeValues(
                readText(row, headerIndexes, formatter,
                        columns.get("employeeCode")),
                readText(row, headerIndexes, formatter,
                        columns.get("hrmsId")),
                readText(row, headerIndexes, formatter,
                        columns.get("employeeName")),
                readText(row, headerIndexes, formatter,
                        columns.get("designation")),
                readText(row, headerIndexes, formatter,
                        columns.get("contact")),
                readText(row, headerIndexes, formatter,
                        columns.get("aadhaar")),
                readText(row, headerIndexes, formatter,
                        columns.get("pan")),
                readText(row, headerIndexes, formatter,
                        columns.get("email")),
                readText(row, headerIndexes, formatter,
                        columns.get("pf")),
                readText(row, headerIndexes, formatter,
                        columns.get("nps")),
                readText(row, headerIndexes, formatter,
                        columns.get("gpf")),
                readText(row, headerIndexes, formatter,
                        columns.get("bankAccount")),
                readText(row, headerIndexes, formatter,
                        columns.get("ifscCode")),
                readText(row, headerIndexes, formatter,
                        columns.get("bankName")),
                readText(row, headerIndexes, formatter,
                        columns.get("branch")),
                readText(row, headerIndexes, formatter,
                        columns.get("dateOfBirth")),
                readText(row, headerIndexes, formatter,
                        columns.get("dateOfJoining")),
                readText(row, headerIndexes, formatter,
                        columns.get("employmentType")),
                readText(row, headerIndexes, formatter,
                        columns.get("payScale")),
                readAmount(row, headerIndexes, columns.get("basicPay")),
                readText(row, headerIndexes, formatter,
                        columns.get("division")),
                readText(row, headerIndexes, formatter,
                        columns.get("divisionId"))
        );
    }

    private Map<String, BigDecimal> readComponentAmounts(
            Row row,
            Map<String, Integer> headerIndexes,
            Map<String, String> columns,
            SheetMapping mapping,
            DataFormatter formatter
    ) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();

        if (!mapping.components().isEmpty()) {
            for (Map.Entry<String, String> component
                    : mapping.components().entrySet()) {
                BigDecimal amount = readAmount(
                        row, headerIndexes, component.getValue());

                if (amount != null) {
                    result.put(component.getKey(), amount);
                }
            }

            return result;
        }

        String componentCode = readText(
                row, headerIndexes, formatter,
                columns.get("componentCode"));

        BigDecimal amount = readAmount(
                row, headerIndexes, columns.get("amount"));

        if (componentCode != null && amount != null) {
            result.put(componentCode, amount);
        }

        return result;
    }

    private long insertEmployee(
            EmployeeValues values,
            DivisionMatcher.Candidate division,
            Long designationId,
            String employeeCode,
            String hrmsId,
            boolean temporary
    ) {
        Long hmwssbDivisionId = findHmwssbDivisionId(division);
        LocalDate dateOfBirth = parseOptionalDate(values.dateOfBirth());
        LocalDate dateOfJoining = parseOptionalDate(values.dateOfJoining());

        return jdbcTemplate.queryForObject(
                """
                INSERT INTO public.employees (
                    employee_code, hrms_id, division_id, hmwssb_division_id,
                    name, designation_id, contact_no, date_of_birth,
                    date_of_joining, employment_type, pay_scale, basic_pay,
                    aadhaar_no, pan_no, email, pf_account_no, nps_id,
                    gpf_account_no, bank_account_no, ifsc_code, bank_name,
                    branch_name, is_temporary_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                employeeCode,
                trimmed(hrmsId),
                division.id(),
                hmwssbDivisionId,
                values.name().trim(),
                designationId,
                digits(values.contact()),
                dateOfBirth,
                dateOfJoining,
                trimmed(values.employmentType()),
                trimmed(values.payScale()),
                values.basicPay() == null
                        ? BigDecimal.ZERO : values.basicPay(),
                digits(values.aadhaar()),
                upperTrim(values.pan()),
                lowerTrim(values.email()),
                upperTrim(values.pf()),
                upperTrim(values.nps()),
                upperTrim(values.gpf()),
                trimmed(values.bankAccount()),
                upperTrim(values.ifscCode()),
                trimmed(values.bankName()),
                trimmed(values.branch()),
                temporary
        );
    }

    private ComponentWrite writeComponent(
            long employeeId,
            long componentId,
            BigDecimal amount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            JobInfo job,
            ImportSheet sheet,
            int sourceRowNumber
    ) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                """
                SELECT id, amount
                FROM public.employee_salary_values
                WHERE employee_id = ?
                  AND salary_component_id = ?
                  AND effective_from = ?
                """,
                employeeId,
                componentId,
                effectiveFrom
        );

        String source = "IMPORT_JOB:" + job.id();

        if (existing.isEmpty()) {
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO public.employee_salary_values (
                        employee_id, salary_component_id, amount,
                        effective_from, effective_to, source,
                        import_job_id, import_job_sheet_id, import_row_number
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (employee_id, salary_component_id,
                                 effective_from)
                    DO NOTHING
                    """,
                    employeeId,
                    componentId,
                    amount,
                    effectiveFrom,
                    effectiveTo,
                    source,
                    job.id(),
                    sheet.id(),
                    sourceRowNumber
            );

            return inserted == 1
                    ? ComponentWrite.INSERTED : ComponentWrite.SKIPPED;
        }

        Object current = existing.get(0).get("amount");

        if (current instanceof BigDecimal currentAmount
                && currentAmount.compareTo(amount) == 0) {
            return ComponentWrite.SKIPPED;
        }

        if (effectiveTo == null) {
            jdbcTemplate.update(
                    """
                    UPDATE public.employee_salary_values
                    SET amount = ?, source = ?, import_job_id = ?,
                        import_job_sheet_id = ?, import_row_number = ?,
                        updated_at = now()
                    WHERE id = ?
                    """,
                    amount,
                    source,
                    job.id(),
                    sheet.id(),
                    sourceRowNumber,
                    ((Number) existing.get(0).get("id")).longValue()
            );
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE public.employee_salary_values
                    SET amount = ?, effective_to = ?, source = ?,
                        import_job_id = ?, import_job_sheet_id = ?,
                        import_row_number = ?, updated_at = now()
                    WHERE id = ?
                    """,
                    amount,
                    effectiveTo,
                    source,
                    job.id(),
                    sheet.id(),
                    sourceRowNumber,
                    ((Number) existing.get(0).get("id")).longValue()
            );
        }

        return ComponentWrite.UPDATED;
    }

    private Map<String, Long> loadEmployeeIndex() {
        Map<String, Long> index = new HashMap<>();

        for (Map<String, Object> row : jdbcTemplate.queryForList(
                """
                SELECT id, employee_code, hrms_id, aadhaar_no, pan_no,
                       contact, contact_no, email, pf_account_no, nps_id,
                       gpf_account_no
                FROM public.employees
                """
        )) {
            indexValues(
                    index,
                    ((Number) row.get("id")).longValue(),
                    (String) row.get("employee_code"),
                    (String) row.get("hrms_id"),
                    (String) row.get("aadhaar_no"),
                    (String) row.get("pan_no"),
                    (String) row.get("contact_no") != null
                            ? (String) row.get("contact_no")
                            : (String) row.get("contact"),
                    (String) row.get("email"),
                    (String) row.get("pf_account_no"),
                    (String) row.get("nps_id"),
                    (String) row.get("gpf_account_no")
            );
        }

        for (Map<String, Object> row : jdbcTemplate.queryForList(
                """
                SELECT employee_id, identity_type, identity_value
                FROM public.employee_identity_mappings
                WHERE status = 'ACTIVE'
                """
        )) {
            putIndex(
                    index,
                    identityKey(
                            (String) row.get("identity_type"),
                            (String) row.get("identity_value")),
                    ((Number) row.get("employee_id")).longValue()
            );
        }

        return index;
    }

    private void indexEmployee(
            Map<String, Long> index,
            long employeeId,
            EmployeeValues values
    ) {
        indexValues(
                index,
                employeeId,
                values.employeeCode(),
                values.hrmsId(),
                values.aadhaar(),
                values.pan(),
                values.contact(),
                values.email(),
                values.pf(),
                values.nps(),
                values.gpf()
        );
    }

    private static void indexValues(
            Map<String, Long> index,
            long employeeId,
            String employeeCode,
            String hrmsId,
            String aadhaar,
            String pan,
            String contact,
            String email,
            String pf,
            String nps,
            String gpf
    ) {
        putIndex(index, "CODE:" + normalize(employeeCode), employeeId);
        putIndex(index, "HRMS:" + normalize(hrmsId), employeeId);
        putIndex(index, "AADHAAR:" + digits(aadhaar), employeeId);
        putIndex(index, "PAN:" + upperTrim(pan), employeeId);
        putIndex(index, "CONTACT:" + digits(contact), employeeId);
        putIndex(index, "EMAIL:" + lowerTrim(email), employeeId);
        putIndex(index, "PF:" + upperTrim(pf), employeeId);
        putIndex(index, "NPS:" + upperTrim(nps), employeeId);
        putIndex(index, "GPF:" + upperTrim(gpf), employeeId);
    }

    private static void putIndex(
            Map<String, Long> index,
            String key,
            long employeeId
    ) {
        if (key != null && !key.endsWith(":") && !key.endsWith(":null")) {
            index.putIfAbsent(key, employeeId);
        }
    }

    private Long resolveIndexedEmployee(
            Map<String, Long> index,
            EmployeeValues values
    ) {
        Set<Long> found = new java.util.LinkedHashSet<>();

        addFound(found, index.get("CODE:" + normalize(values.employeeCode())));
        addFound(found, index.get("HRMS:" + normalize(values.hrmsId())));
        addFound(found, index.get("AADHAAR:" + digits(values.aadhaar())));
        addFound(found, index.get("PAN:" + upperTrim(values.pan())));
        addFound(found, index.get("CONTACT:" + digits(values.contact())));
        addFound(found, index.get("EMAIL:" + lowerTrim(values.email())));
        addFound(found, index.get("PF:" + upperTrim(values.pf())));
        addFound(found, index.get("NPS:" + upperTrim(values.nps())));
        addFound(found, index.get("GPF:" + upperTrim(values.gpf())));

        if (found.size() > 1) {
            throw new IllegalArgumentException(
                    "The supplied identifiers refer to different employees.");
        }

        return found.isEmpty() ? null : found.iterator().next();
    }

    private static void addFound(Set<Long> found, Long employeeId) {
        if (employeeId != null) {
            found.add(employeeId);
        }
    }

    private static String identityKey(String type, String value) {
        if (type == null) {
            return null;
        }

        String normalized = switch (type) {
            case "AADHAAR", "PHONE" -> digits(value);
            case "EMAIL" -> lowerTrim(value);
            default -> upperTrim(value);
        };

        return "MAP:" + type + ":" + normalized;
    }

    private List<DivisionMatcher.Candidate> loadDivisionCandidates() {
        return jdbcTemplate.query(
                "SELECT id, name, code FROM public.divisions",
                (rs, rowNum) -> new DivisionMatcher.Candidate(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("code")
                )
        );
    }

    private Long findHmwssbDivisionId(
            DivisionMatcher.Candidate division
    ) {
        List<Long> found = jdbcTemplate.queryForList(
                """
                SELECT id FROM public.hmwssb_divisions
                WHERE division_code = ? OR division_name = ?
                ORDER BY division_code = ? DESC
                LIMIT 1
                """,
                Long.class,
                division.code(),
                division.name(),
                division.code()
        );

        return found.isEmpty() ? null : found.get(0);
    }

    private Map<String, Long> loadDesignations() {
        Map<String, Long> designations = new HashMap<>();

        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id, name FROM public.designations")) {
            designations.putIfAbsent(
                    normalize((String) row.get("name")),
                    ((Number) row.get("id")).longValue()
            );
        }

        return designations;
    }

    private void loadImportComponents(
            Map<String, Long> components,
            Map<Long, String> categories
    ) {
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                """
                SELECT id, component_code, category
                FROM public.salary_components
                WHERE status = 'ACTIVE'
                """
        )) {
            long id = ((Number) row.get("id")).longValue();
            String code = (String) row.get("component_code");

            components.putIfAbsent(normalize(code), id);
            components.putIfAbsent(
                    ColumnMappingCatalog.componentKey(code), id);
            categories.put(id, (String) row.get("category"));
        }
    }

    private static String nextTemporaryId(Map<String, Long> index) {
        int next = 1;

        for (String key : index.keySet()) {
            if (!key.startsWith("HRMS:T-") && !key.startsWith("CODE:T-")) {
                continue;
            }

            String suffix = key.substring(key.lastIndexOf('-') + 1);

            try {
                next = Math.max(next, Integer.parseInt(suffix) + 1);
            } catch (NumberFormatException ignored) {
                // Non-numeric temporary codes do not participate in numbering.
            }
        }

        String candidate = "T-" + String.format(Locale.ROOT, "%04d", next);

        while (index.containsKey("HRMS:" + candidate)
                || index.containsKey("CODE:" + candidate)) {
            next++;
            candidate = "T-" + String.format(Locale.ROOT, "%04d", next);
        }

        return candidate;
    }

    private static LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        LocalDate parsed = ImportCells.parseDate(value);

        if (parsed == null) {
            throw new IllegalArgumentException(
                    "Invalid date value: " + value);
        }

        return parsed;
    }

    private static String firstNotBlank(String first, String second) {
        return notBlank(first) ? first : notBlank(second) ? second : null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String digits(String value) {
        if (!notBlank(value)) {
            return null;
        }

        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }

    private static String upperTrim(String value) {
        return notBlank(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String lowerTrim(String value) {
        return notBlank(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
    }

    private record EmployeeValues(
            String employeeCode,
            String hrmsId,
            String name,
            String designation,
            String contact,
            String aadhaar,
            String pan,
            String email,
            String pf,
            String nps,
            String gpf,
            String bankAccount,
            String ifscCode,
            String bankName,
            String branch,
            String dateOfBirth,
            String dateOfJoining,
            String employmentType,
            String payScale,
            BigDecimal basicPay,
            String divisionName,
            String divisionCode
    ) {
    }

    private enum ComponentWrite {
        INSERTED,
        UPDATED,
        SKIPPED
    }

    private SheetResult importPayrollHistory(
            JobInfo job,
            ImportSheet sheet,
            ImportProgressTracker progress
    ) throws IOException {
        Map<String, Object> file = jdbcTemplate.queryForMap(
                """
                SELECT file_key, file_name, file_sha256
                FROM public.import_jobs
                WHERE id = ?
                """,
                job.id()
        );
        String fileKey = (String) file.get("file_key");
        String fileName = (String) file.get("file_name");
        String fileSha256 = ((String) file.get("file_sha256")).trim();
        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (!filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            throw conflict("The stored payroll history workbook is missing.");
        }

        if (!ImportJobService.calculateSha256(filePath)
                .equalsIgnoreCase(fileSha256)) {
            throw conflict(
                    "The stored payroll history workbook changed after "
                            + "validation. Upload and validate it again."
            );
        }

        SheetMapping mapping = SheetMapping.parse(
                jsonMapper, sheet.mappingJson());
        Map<String, String> columns = mapping.columns();
        String employeeCodeHeader = columns.get("employeeCode");
        String hrmsIdHeader = columns.get("hrmsId");

        if ((employeeCodeHeader == null && hrmsIdHeader == null)
                || columns.get("componentCode") == null
                || columns.get("amount") == null) {
            throw conflict(
                    "Payroll history requires an employee identifier, "
                            + "componentCode, and amount mapping."
            );
        }

        if (!mapping.components().isEmpty()) {
            throw conflict(
                    "Payroll history supports component-detail rows only; "
                            + "wide component layouts are not supported."
            );
        }

        if (!columns.containsKey("period")
                && !(columns.containsKey("payrollMonth")
                        && columns.containsKey("payrollYear"))
                && !(mapping.option("periodMonthDefault") != null
                        && mapping.option("periodYearDefault") != null)) {
            throw conflict(
                    "Map period, map both payrollMonth and payrollYear, or "
                            + "set defaults for both."
            );
        }

        boolean grossMapped = columns.containsKey("gross");
        boolean deductionsMapped = columns.containsKey("totalDeductions");
        boolean netMapped = columns.containsKey("net");
        if ((grossMapped || deductionsMapped || netMapped)
                && !(grossMapped && deductionsMapped && netMapped)) {
            throw conflict(
                    "Map gross, totalDeductions and net together, or leave "
                            + "all three unmapped."
            );
        }

        List<String> headers = Arrays.asList(
                jsonMapper.readValue(sheet.headersJson(), String[].class));
        Map<String, Integer> headerIndexes = new HashMap<>();

        for (int index = 0; index < headers.size(); index++) {
            if (headerIndexes.putIfAbsent(headers.get(index), index) != null) {
                throw conflict(
                        "Worksheet " + sheet.sheetName()
                                + " contains duplicate source headers."
                );
            }
        }

        for (Map.Entry<String, String> entry : columns.entrySet()) {
            if (!headerIndexes.containsKey(entry.getValue())) {
                throw conflict(
                        "Mapped source column is missing: " + entry.getValue()
                );
            }
        }

        Map<String, HistoryEmployee> employeesByCode = new HashMap<>();
        Map<String, HistoryEmployee> employeesByHrms = new HashMap<>();

        for (HistoryEmployee employee : jdbcTemplate.query(
                """
                SELECT id, employee_code, hrms_id, name
                FROM public.employees
                """,
                (rs, rowNum) -> new HistoryEmployee(
                        rs.getLong("id"),
                        rs.getString("employee_code"),
                        rs.getString("hrms_id"),
                        rs.getString("name")
                )
        )) {
            employeesByCode.put(normalize(employee.employeeCode()), employee);
            employeesByHrms.put(normalize(employee.hrmsId()), employee);
        }

        Map<String, HistoryComponent> componentsByCode = new HashMap<>();

        for (HistoryComponent component : jdbcTemplate.query(
                """
                SELECT id, component_code, component_name, category
                FROM public.salary_components
                """,
                (rs, rowNum) -> new HistoryComponent(
                        rs.getLong("id"),
                        rs.getString("component_code"),
                        rs.getString("component_name"),
                        rs.getString("category")
                )
        )) {
            componentsByCode.put(normalize(component.code()), component);
        }

        Map<Long, String> authorizedDivisions = new HashMap<>();
        for (Map<String, Object> division : jdbcTemplate.queryForList(
                "SELECT id, name FROM public.divisions")) {
            authorizedDivisions.put(
                    ((Number) division.get("id")).longValue(),
                    (String) division.get("name")
            );
        }

        long actor = currentUserId();
        int imported = 0;
        BigDecimal earningsTotal = BigDecimal.ZERO;
        BigDecimal deductionsTotal = BigDecimal.ZERO;
        Map<String, HistoryTotals> totalsByPeriod = new HashMap<>();
        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            if (sheet.sheetIndex() >= workbook.getNumberOfSheets()) {
                throw conflict("The payroll history worksheet no longer exists.");
            }

            var workbookSheet = workbook.getSheetAt(sheet.sheetIndex());

            if (!sheet.sheetName().equals(workbookSheet.getSheetName())) {
                throw conflict(
                        "The stored worksheet changed after validation."
                );
            }

            if (sheet.headerRowNumber() == null
                    || sheet.headerRowNumber() < 1
                    || workbookSheet.getRow(sheet.headerRowNumber() - 1) == null) {
                throw conflict("The payroll history header row is missing.");
            }

            for (int index = sheet.headerRowNumber();
                 index <= workbookSheet.getLastRowNum();
                 index++) {
                Row row = workbookSheet.getRow(index);

                if (row == null || ImportCells.isBlankRow(row)) {
                    continue;
                }

                int sourceRowNumber = index + 1;
                for (String header : columns.values()) {
                    if (ImportCells.isFormula(
                            row, headerIndexes.get(header))) {
                        throw historyRowConflict(
                                sourceRowNumber,
                                "Formula cells are not accepted for "
                                        + "mapped payroll history fields."
                        );
                    }
                }

                String employeeCode = readText(
                        row, headerIndexes, formatter, employeeCodeHeader);
                String hrmsId = readText(
                        row, headerIndexes, formatter, hrmsIdHeader);
                HistoryEmployee employee = resolveHistoryEmployee(
                        employeeCode, hrmsId, employeesByCode, employeesByHrms);

                if (employee == null) {
                    throw historyRowConflict(
                            sourceRowNumber,
                            "Employee does not resolve to an accessible "
                                    + "employee record."
                    );
                }

                String componentCode = readText(
                        row, headerIndexes, formatter,
                        columns.get("componentCode"));
                HistoryComponent component = componentCode == null
                        ? null
                        : componentsByCode.get(normalize(componentCode));

                if (component == null) {
                    throw historyRowConflict(
                            sourceRowNumber,
                            "Salary component does not resolve to a component."
                    );
                }

                YearMonth period = readHistoryPeriod(
                        row, headerIndexes, formatter, columns, mapping,
                        sourceRowNumber);
                validateHistorySourceDivision(
                        row, headerIndexes, formatter, columns,
                        authorizedDivisions, sourceRowNumber);
                BigDecimal amount = readHistoryAmount(
                        row, headerIndexes.get(columns.get("amount")),
                        sourceRowNumber, "amount");
                BigDecimal gross = readOptionalHistoryAmount(
                        row, headerIndexes, columns.get("gross"),
                        sourceRowNumber, "gross");
                BigDecimal deductions = readOptionalHistoryAmount(
                        row, headerIndexes, columns.get("totalDeductions"),
                        sourceRowNumber, "totalDeductions");
                BigDecimal net = readOptionalHistoryAmount(
                        row, headerIndexes, columns.get("net"),
                        sourceRowNumber, "net");

                if (gross != null && deductions != null && net != null
                        && gross.subtract(deductions).compareTo(net) != 0) {
                    throw historyRowConflict(
                            sourceRowNumber,
                            "Gross minus total deductions does not equal net."
                    );
                }
                if (gross != null && deductions != null && net != null) {
                    String totalsKey = employee.id() + "|" + period;
                    HistoryTotals currentTotals =
                            new HistoryTotals(gross, deductions, net);
                    HistoryTotals previousTotals = totalsByPeriod.putIfAbsent(
                            totalsKey, currentTotals);
                    if (previousTotals != null
                            && !previousTotals.equals(currentTotals)) {
                        throw historyRowConflict(
                                sourceRowNumber,
                                "Summary totals differ between component "
                                        + "rows for this employee and period."
                        );
                    }
                }

                String sourceStatus = readText(
                        row, headerIndexes, formatter,
                        columns.get("transactionStatus"));
                if (columns.containsKey("transactionStatus")
                        && sourceStatus == null) {
                    throw historyRowConflict(
                            sourceRowNumber,
                            "Source transaction status is required when the "
                                    + "status column is mapped."
                    );
                }
                String sourceCategory = readText(
                        row, headerIndexes, formatter, columns.get("category"));
                if (sourceCategory != null
                        && !normalize(sourceCategory).equals(
                                normalize(component.category()))) {
                    throw historyRowConflict(
                            sourceRowNumber,
                            "Source category does not match the salary "
                                    + "component."
                    );
                }
                String sourceName = readText(
                        row, headerIndexes, formatter,
                        columns.get("employeeName"));
                String sourceComponentName = readText(
                        row, headerIndexes, formatter,
                        columns.get("componentName"));
                String sourceValues = jsonMapper.writeValueAsString(
                        readSourceValues(row, headers, formatter));

                int inserted;
                try {
                    inserted = jdbcTemplate.update(
                            """
                            INSERT INTO public.payroll_history_records (
                                employee_id, salary_component_id,
                                payroll_period, employee_code_snapshot,
                                hrms_id_snapshot, employee_name_snapshot,
                                component_code_snapshot,
                                component_name_snapshot, category_snapshot,
                                amount, gross_salary, total_deductions,
                                net_salary, source_status, source_file_name,
                                source_file_sha256, source_sheet_name,
                                source_row_number, source_values,
                                import_job_id, import_job_sheet_id, imported_by
                            )
                            VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?, ?, ?, ?, ?::jsonb, ?, ?, ?
                            )
                            ON CONFLICT DO NOTHING
                            """,
                            employee.id(),
                            component.id(),
                            java.sql.Date.valueOf(period.atDay(1)),
                            employeeCode == null
                                    ? employee.employeeCode() : employeeCode,
                            hrmsId == null ? employee.hrmsId() : hrmsId,
                            sourceName == null ? employee.name() : sourceName,
                            componentCode,
                            sourceComponentName == null
                                    ? component.name() : sourceComponentName,
                            component.category(),
                            amount,
                            gross,
                            deductions,
                            net,
                            sourceStatus,
                            fileName,
                            fileSha256,
                            sheet.sheetName(),
                            sourceRowNumber,
                            sourceValues,
                            job.id(),
                            sheet.id(),
                            actor
                    );
                } catch (DataIntegrityViolationException ex) {
                    throw historyRowConflict(
                            sourceRowNumber,
                            "A linked employee or salary component changed "
                                    + "while importing."
                    );
                }

                if (inserted != 1) {
                    throw historyRowConflict(
                            sourceRowNumber,
                            "This employee, period and component are already "
                                    + "recorded, or this source row was already "
                                    + "imported."
                    );
                }

                imported++;
                progress.record(1, 0, 0);
                if ("EARNING".equals(component.category())) {
                    earningsTotal = earningsTotal.add(amount);
                } else {
                    deductionsTotal = deductionsTotal.add(amount);
                }
            }
        }

        return new SheetResult(imported, 0, 0, earningsTotal,
                deductionsTotal);
    }

    /**
     * Commits a SALARY_REVISIONS sheet as a DRAFT revision batch.
     *
     * <p>Revisions are never applied to employee_salary_values here and no
     * payroll result is recalculated: the batch must go through the normal
     * revision approval/apply process. old_amount is stored only when the
     * source file provides it (null means "not asserted"), and rows that
     * duplicate an existing non-cancelled revision are skipped as errors.
     */
    private SheetResult importSalaryRevisions(
            JobInfo job,
            ImportSheet sheet,
            ImportProgressTracker progress
    ) throws IOException {

        String fileKey = jdbcTemplate.queryForObject(
                "SELECT COALESCE(corrected_file_key, file_key) "
                        + "FROM public.import_jobs WHERE id = ?",
                String.class,
                job.id()
        );

        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (!filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            throw new IllegalStateException("Stored workbook was not found.");
        }

        SheetMapping mapping = SheetMapping.parse(
                jsonMapper, sheet.mappingJson());
        Map<String, String> columns = mapping.columns();

        String employeeCodeHeader = columns.get("employeeCode");
        String hrmsIdHeader = columns.get("hrmsId");

        if (employeeCodeHeader == null && hrmsIdHeader == null) {
            throw conflict("Sheet " + sheet.sheetName()
                    + " has no employee identifier mapping.");
        }

        String newAmountHeader = columns.containsKey("newAmount")
                ? columns.get("newAmount")
                : columns.get("amount");

        if (columns.get("componentCode") == null || newAmountHeader == null) {
            throw conflict("Sheet " + sheet.sheetName()
                    + " needs componentCode and newAmount/amount mappings.");
        }

        List<String> headers = Arrays.asList(
                jsonMapper.readValue(sheet.headersJson(), String[].class)
        );

        Map<String, Integer> headerIndexes = new HashMap<>();

        for (int index = 0; index < headers.size(); index++) {
            headerIndexes.putIfAbsent(headers.get(index), index);
        }

        Map<String, Long> employeesByCode = new HashMap<>();
        Map<String, Long> employeesByHrms = new HashMap<>();

        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id, employee_code, hrms_id FROM public.employees"
        )) {
            employeesByCode.put(
                    normalize((String) row.get("employee_code")),
                    ((Number) row.get("id")).longValue()
            );
            employeesByHrms.put(
                    normalize((String) row.get("hrms_id")),
                    ((Number) row.get("id")).longValue()
            );
        }

        Map<String, Long> componentsByCode = new HashMap<>();
        Map<Long, String> categoryByComponentId = new HashMap<>();

        for (Map<String, Object> row : jdbcTemplate.queryForList(
                """
                SELECT id, component_code, category
                FROM public.salary_components
                WHERE status = 'ACTIVE'
                """
        )) {
            long componentId = ((Number) row.get("id")).longValue();

            componentsByCode.put(
                    normalize((String) row.get("component_code")),
                    componentId
            );

            categoryByComponentId.put(componentId, (String) row.get("category"));
        }

        long actor = currentUserId();
        String batchNo = "IMP-" + job.id() + "-" + sheet.id();

        LocalDate firstEffective = firstEffectiveDate(
                job.id(), sheet, headerIndexes, columns, mapping);

        jdbcTemplate.update(
                """
                INSERT INTO public.salary_revision_batches (
                    batch_no, effective_date, reason, status, maker_id
                )
                VALUES (?, ?, ?, 'DRAFT', ?)
                ON CONFLICT (batch_no) DO NOTHING
                """,
                batchNo,
                java.sql.Date.valueOf(
                        firstEffective != null
                                ? firstEffective
                                : LocalDate.now()),
                "Imported salary revisions (job " + job.id() + ")",
                actor
        );

        Long batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM public.salary_revision_batches "
                        + "WHERE batch_no = ?",
                Long.class,
                batchNo
        );

        if (batchId == null) {
            throw new IllegalStateException(
                    "Unable to create the salary revision batch.");
        }

        int imported = 0;
        int skipped = 0;
        int errors = 0;
        BigDecimal earningsTotal = BigDecimal.ZERO;
        BigDecimal deductionsTotal = BigDecimal.ZERO;

        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            var workbookSheet = workbook.getSheetAt(sheet.sheetIndex());

            int headerIndex = sheet.headerRowNumber() - 1;

            for (int index = headerIndex + 1;
                 index <= workbookSheet.getLastRowNum();
                 index++) {

                Row row = workbookSheet.getRow(index);

                if (row == null || ImportCells.isBlankRow(row)) {
                    continue;
                }

                int importedBefore = imported;
                int skippedBefore = skipped;
                int errorsBefore = errors;
                try {
                    Long employeeId = resolveEmployee(
                            row, headerIndexes, formatter,
                            employeeCodeHeader, hrmsIdHeader,
                            employeesByCode, employeesByHrms
                    );

                    String componentCode = readText(
                            row, headerIndexes, formatter,
                            columns.get("componentCode")
                    );

                    Long componentId = componentCode == null
                            ? null
                            : componentsByCode.get(normalize(componentCode));

                    BigDecimal newAmount = readAmount(
                            row, headerIndexes, newAmountHeader);
                    BigDecimal oldAmount = readAmount(
                            row, headerIndexes, columns.get("oldAmount"));

                    LocalDate effectiveFrom = readDate(
                            row, headerIndexes, columns.get("effectiveFrom"),
                            mapping.effectiveFromDefault()
                    );

                    if (employeeId == null || componentId == null
                            || newAmount == null || effectiveFrom == null
                            || newAmount.signum() < 0) {
                        errors++;
                        continue;
                    }

                    String reason = readText(
                            row, headerIndexes, formatter,
                            columns.get("reason")
                    );

                    int inserted = jdbcTemplate.update(
                            """
                            INSERT INTO public.salary_revisions (
                                revision_batch_id, employee_id,
                                salary_component_id, old_amount, new_amount,
                                effective_from, reason, status, created_by,
                                import_job_id, import_job_sheet_id,
                                import_row_number
                            )
                            SELECT ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?
                            WHERE NOT EXISTS (
                                SELECT 1 FROM public.salary_revisions r
                                WHERE r.employee_id = ?
                                  AND r.salary_component_id = ?
                                  AND r.effective_from = ?
                                  AND r.status NOT IN ('CANCELLED', 'REJECTED')
                            )
                            """,
                            batchId, employeeId, componentId, oldAmount,
                            newAmount, effectiveFrom, reason, actor,
                            job.id(), sheet.id(), index + 1,
                            employeeId, componentId, effectiveFrom
                    );

                    if (inserted == 0) {
                        errors++;
                        continue;
                    }

                    imported++;

                    if ("EARNING".equals(
                            categoryByComponentId.get(componentId))) {
                        earningsTotal = earningsTotal.add(newAmount);
                    } else {
                        deductionsTotal = deductionsTotal.add(newAmount);
                    }

                } catch (DataIntegrityViolationException ex) {
                    errors++;
                } catch (RuntimeException ex) {
                    errors++;
                } finally {
                    progress.record(
                            imported - importedBefore,
                            skipped - skippedBefore,
                            errors - errorsBefore
                    );
                }
            }
        }

        return new SheetResult(imported, skipped, errors, earningsTotal,
                deductionsTotal);
    }

    private LocalDate firstEffectiveDate(
            long jobId,
            ImportSheet sheet,
            Map<String, Integer> headerIndexes,
            Map<String, String> columns,
            SheetMapping mapping
    ) throws IOException {

        String fileKey = jdbcTemplate.queryForObject(
                "SELECT COALESCE(corrected_file_key, file_key) "
                        + "FROM public.import_jobs WHERE id = ?",
                String.class,
                jobId
        );

        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (!filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            return null;
        }

        String header = columns.get("effectiveFrom");

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            var workbookSheet = workbook.getSheetAt(sheet.sheetIndex());
            int headerIndex = sheet.headerRowNumber() - 1;

            for (int index = headerIndex + 1;
                 index <= workbookSheet.getLastRowNum();
                 index++) {

                Row row = workbookSheet.getRow(index);

                if (row == null || ImportCells.isBlankRow(row)) {
                    continue;
                }

                LocalDate date = readDate(
                        row, headerIndexes, header,
                        mapping.effectiveFromDefault());

                if (date != null) {
                    return date;
                }
            }
        }

        return null;
    }

    private HistoryEmployee resolveHistoryEmployee(
            String employeeCode,
            String hrmsId,
            Map<String, HistoryEmployee> byCode,
            Map<String, HistoryEmployee> byHrms
    ) {
        HistoryEmployee codeMatch = employeeCode == null
                ? null
                : byCode.get(normalize(employeeCode));
        HistoryEmployee hrmsMatch = hrmsId == null
                ? null
                : byHrms.get(normalize(hrmsId));

        if (employeeCode != null && codeMatch == null
                || hrmsId != null && hrmsMatch == null) {
            return null;
        }

        if (codeMatch != null && hrmsMatch != null
                && codeMatch.id() != hrmsMatch.id()) {
            return null;
        }

        return codeMatch != null ? codeMatch : hrmsMatch;
    }

    private YearMonth readHistoryPeriod(
            Row row,
            Map<String, Integer> headerIndexes,
            DataFormatter formatter,
            Map<String, String> columns,
            SheetMapping mapping,
            int sourceRowNumber
    ) {
        String period = readText(
                row, headerIndexes, formatter, columns.get("period"));

        if (period != null) {
            try {
                if (period.matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
                    return checkedYearMonth(
                            Integer.parseInt(period.substring(0, 4)),
                            Integer.parseInt(period.substring(5)),
                            sourceRowNumber
                    );
                }

                if (period.matches("^(0[1-9]|1[0-2])/\\d{4}$")) {
                    return checkedYearMonth(
                            Integer.parseInt(period.substring(3)),
                            Integer.parseInt(period.substring(0, 2)),
                            sourceRowNumber
                    );
                }
            } catch (NumberFormatException ex) {
                throw historyRowConflict(
                        sourceRowNumber, "Payroll period is invalid.");
            }

            throw historyRowConflict(
                    sourceRowNumber,
                    "Payroll period must use YYYY-MM or MM/YYYY."
            );
        }

        String month = readText(
                row, headerIndexes, formatter, columns.get("payrollMonth"));
        String year = readText(
                row, headerIndexes, formatter, columns.get("payrollYear"));
        month = month == null ? mapping.option("periodMonthDefault") : month;
        year = year == null ? mapping.option("periodYearDefault") : year;

        if (month == null || year == null) {
            throw historyRowConflict(
                    sourceRowNumber, "Payroll period is required.");
        }

        try {
            return checkedYearMonth(
                    Integer.parseInt(year.trim().replace(".0", "")),
                    Integer.parseInt(month.trim().replace(".0", "")),
                    sourceRowNumber
            );
        } catch (NumberFormatException ex) {
            throw historyRowConflict(
                    sourceRowNumber, "Payroll year or month is invalid.");
        }
    }

    private YearMonth checkedYearMonth(
            int year,
            int month,
            int sourceRowNumber
    ) {
        if (year < 1 || year > 9999) {
            throw historyRowConflict(
                    sourceRowNumber, "Payroll year must be from 1 to 9999.");
        }

        try {
            return YearMonth.of(year, month);
        } catch (DateTimeException ex) {
            throw historyRowConflict(
                    sourceRowNumber, "Payroll year or month is invalid.");
        }
    }

    private void validateHistorySourceDivision(
            Row row,
            Map<String, Integer> headerIndexes,
            DataFormatter formatter,
            Map<String, String> columns,
            Map<Long, String> authorizedDivisions,
            int sourceRowNumber
    ) {
        String divisionName = readText(
                row, headerIndexes, formatter, columns.get("division"));
        if (divisionName != null
                && authorizedDivisions.values().stream().noneMatch(
                        name -> normalize(name).equals(normalize(divisionName)))) {
            throw historyRowConflict(
                    sourceRowNumber,
                    "Source division is outside your authorized scope."
            );
        }

        String divisionId = readText(
                row, headerIndexes, formatter, columns.get("divisionId"));
        if (divisionId != null) {
            try {
                long id = Long.parseLong(divisionId.replace(".0", ""));
                if (!authorizedDivisions.containsKey(id)) {
                    throw historyRowConflict(
                            sourceRowNumber,
                            "Source division is outside your authorized scope."
                    );
                }
            } catch (NumberFormatException ex) {
                throw historyRowConflict(
                        sourceRowNumber,
                        "Source division ID is invalid."
                );
            }
        }
    }

    private BigDecimal readHistoryAmount(
            Row row,
            Integer columnIndex,
            int sourceRowNumber,
            String field
    ) {
        if (columnIndex == null) {
            throw historyRowConflict(
                    sourceRowNumber, "Required mapped field is missing: " + field);
        }

        Cell cell = row.getCell(
                columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null || cell.getCellType() == CellType.BLANK) {
            throw historyRowConflict(
                    sourceRowNumber, "Required amount is missing: " + field);
        }

        if (cell.getCellType() == CellType.FORMULA) {
            throw historyRowConflict(
                    sourceRowNumber,
                    "Formula cells are not accepted for payroll history."
            );
        }

        BigDecimal value = switch (cell.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> ImportCells.parseAmountText(
                    cell.getStringCellValue());
            default -> null;
        };

        if (value == null) {
            throw historyRowConflict(
                    sourceRowNumber, "Amount is invalid: " + field);
        }

        if (value.stripTrailingZeros().scale() > 2) {
            throw historyRowConflict(
                    sourceRowNumber,
                    "Payroll history amounts cannot have more than two "
                            + "decimal places."
            );
        }

        if (value.signum() < 0) {
            throw historyRowConflict(
                    sourceRowNumber,
                    "Payroll history amounts cannot be negative."
            );
        }

        return value.setScale(2);
    }

    private BigDecimal readOptionalHistoryAmount(
            Row row,
            Map<String, Integer> headerIndexes,
            String header,
            int sourceRowNumber,
            String field
    ) {
        if (header == null) {
            return null;
        }

        return readHistoryAmount(
                row, headerIndexes.get(header), sourceRowNumber, field);
    }

    private Map<String, String> readSourceValues(
            Row row,
            List<String> headers,
            DataFormatter formatter
    ) {
        Map<String, String> values = new LinkedHashMap<>();

        for (int index = 0; index < headers.size(); index++) {
            Cell cell = row.getCell(
                    index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.put(headers.get(index),
                    cell == null ? "" : formatter.formatCellValue(cell));
        }

        return values;
    }

    private ResponseStatusException historyRowConflict(
            int sourceRowNumber,
            String message
    ) {
        return conflict(
                "Payroll history row " + sourceRowNumber + ": " + message
                        + " No rows from this history job were imported."
        );
    }

    private Long resolveEmployee(
            Row row,
            Map<String, Integer> headerIndexes,
            DataFormatter formatter,
            String employeeCodeHeader,
            String hrmsIdHeader,
            Map<String, Long> byCode,
            Map<String, Long> byHrms
    ) {
        String code = readText(row, headerIndexes, formatter, employeeCodeHeader);
        String hrms = readText(row, headerIndexes, formatter, hrmsIdHeader);

        Long byCodeId = code == null ? null : byCode.get(normalize(code));
        Long byHrmsId = hrms == null ? null : byHrms.get(normalize(hrms));

        if (byCodeId != null && byHrmsId != null && !byCodeId.equals(byHrmsId)) {
            return null;
        }

        return byCodeId != null ? byCodeId : byHrmsId;
    }

    private String readText(
            Row row,
            Map<String, Integer> headerIndexes,
            DataFormatter formatter,
            String header
    ) {
        if (header == null) {
            return null;
        }

        return ImportCells.text(row, headerIndexes.get(header), formatter);
    }

    private BigDecimal readAmount(
            Row row,
            Map<String, Integer> headerIndexes,
            String header
    ) {
        return ImportCells.amount(
                row, header == null ? null : headerIndexes.get(header));
    }

    private LocalDate readDate(
            Row row,
            Map<String, Integer> headerIndexes,
            String header,
            String defaultDate
    ) {
        return ImportCells.date(
                row, header == null ? null : headerIndexes.get(header),
                defaultDate);
    }

    private JobInfo requireJob(long jobId) {
        List<JobInfo> jobs = jdbcTemplate.query(
                """
                SELECT id, status, uploaded_by, submitted_by, approved_by,
                       finalized_by, valid_rows, rejection_reason,
                       draft_imported_at IS NOT NULL AS draft_imported
                FROM public.import_jobs
                WHERE id = ?
                """,
                (rs, rowNum) -> new JobInfo(
                        rs.getLong("id"),
                        rs.getString("status"),
                        nullableLong(rs, "uploaded_by"),
                        nullableLong(rs, "submitted_by"),
                        nullableLong(rs, "approved_by"),
                        nullableLong(rs, "finalized_by"),
                        rs.getInt("valid_rows"),
                        rs.getString("rejection_reason"),
                        rs.getBoolean("draft_imported")
                ),
                jobId
        );

        if (jobs.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Import job not found or not accessible."
            );
        }

        return jobs.get(0);
    }

    private void enforceSeparate(long actor, JobInfo job, String action) {
        boolean clash = actor == safe(job.uploadedBy())
                || actor == safe(job.submittedBy())
                || actor == safe(job.approvedBy());

        if (clash) {
            throw conflict(
                    "Maker-checker violation: the " + action
                            + " must be a different user from the uploader, "
                            + "submitter and approver."
            );
        }
    }

    private void recordEvent(
            long jobId,
            Long divisionId,
            String action,
            String previousStatus,
            String newStatus,
            String details
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO public.import_job_workflow_events (
                    import_job_id, division_id, action,
                    previous_status, new_status, actor_user_id, details
                )
                VALUES (?, ?::bigint, ?, ?, ?, ?, jsonb_build_object(
                    'details', ?::text
                ))
                """,
                jobId,
                divisionId,
                action,
                previousStatus,
                newStatus,
                currentUserId(),
                details
        );
    }

    private void audit(
            long jobId,
            String action,
            String previousStatus,
            String newStatus,
            String reason
    ) {
        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    ?, 'IMPORT_JOB', ?::varchar, NULL::bigint, NULL::bigint,
                    jsonb_build_object('status', ?::text),
                    jsonb_build_object('status', ?::text),
                    ?, NULL::uuid
                )
                """,
                Long.class,
                action,
                String.valueOf(jobId),
                previousStatus,
                newStatus,
                reason
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

    private ImportJobWorkflowResponse loadResponse(long jobId, String message) {
        return jdbcTemplate.queryForObject(
                """
                SELECT id, status, uploaded_by, submitted_by, approved_by,
                       finalized_by, rejection_reason, imported_amount_total,
                       imported_earnings_total, imported_deductions_total
                FROM public.import_jobs
                WHERE id = ?
                """,
                (rs, rowNum) -> new ImportJobWorkflowResponse(
                        rs.getLong("id"),
                        nullableLong(rs, "uploaded_by"),
                        rs.getString("status"),
                        nullableLong(rs, "submitted_by"),
                        nullableLong(rs, "approved_by"),
                        nullableLong(rs, "finalized_by"),
                        rs.getString("rejection_reason"),
                        null,
                        null,
                        null,
                        rs.getBigDecimal("imported_amount_total"),
                        rs.getBigDecimal("imported_earnings_total"),
                        rs.getBigDecimal("imported_deductions_total"),
                        message
                ),
                jobId
        );
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static long safe(Long value) {
        return value == null ? -1 : value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static final class ImportProgressTracker {

        private static final int EVENT_INTERVAL = 100;

        private final long jobId;
        private final int totalRecords;
        private final ImportJobProgressService progressService;
        private int processedRecords;
        private int importedRows;
        private int skippedRows;
        private int failedRows;
        private int lastPublishedRecords;

        private ImportProgressTracker(
                long jobId,
                int totalRecords,
                ImportJobProgressService progressService
        ) {
            this.jobId = jobId;
            this.totalRecords = totalRecords;
            this.progressService = progressService;
        }

        private void record(int imported, int skipped, int failed) {
            processedRecords++;
            importedRows += imported;
            skippedRows += skipped;
            failedRows += failed;

            if (processedRecords == 1
                    || processedRecords - lastPublishedRecords >= EVENT_INTERVAL
                    || processedRecords >= totalRecords) {
                publish("INFO", progressMessage());
            }
        }

        private void skip(int rows, String message) {
            if (rows <= 0) {
                return;
            }
            processedRecords += rows;
            skippedRows += rows;
            publish("INFO", message);
        }

        private void publish(String level, String message) {
            progressService.recordProgress(
                    jobId,
                    totalRecords,
                    processedRecords,
                    importedRows,
                    skippedRows,
                    failedRows,
                    level,
                    message
            );
            lastPublishedRecords = processedRecords;
        }

        private void flush(String message) {
            publish(failedRows > 0 ? "WARN" : "INFO", message);
        }

        private String progressMessage() {
            return "Processed " + processedRecords + " of " + totalRecords
                    + " records; " + importedRows + " ready, "
                    + failedRows + " failed, " + skippedRows + " skipped.";
        }

        private int processedRecords() {
            return processedRecords;
        }

        private int totalRecords() {
            return totalRecords;
        }
    }

    private record JobInfo(
            long id,
            String status,
            Long uploadedBy,
            Long submittedBy,
            Long approvedBy,
            Long finalizedBy,
            int validRows,
            String rejectionReason,
            boolean draftImported
    ) {
    }

    private record ImportSheet(
            long id,
            int sheetIndex,
            String sheetName,
            String confirmedType,
            Integer headerRowNumber,
            int dataRowsEstimate,
            String headersJson,
            String mappingJson
    ) {
    }

    private record HistoryEmployee(
            long id,
            String employeeCode,
            String hrmsId,
            String name
    ) {
    }

    private record HistoryComponent(
            long id,
            String code,
            String name,
            String category
    ) {
    }

    private record HistoryTotals(
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net
    ) {
    }

    private record SheetResult(
            int imported,
            int skipped,
            int errors,
            BigDecimal earningsTotal,
            BigDecimal deductionsTotal
    ) {
    }

    private record ImportResult(
            int importedRows,
            int skippedRows,
            int errorRows,
            BigDecimal amountTotal,
            BigDecimal earningsTotal,
            BigDecimal deductionsTotal
    ) {
    }
}
