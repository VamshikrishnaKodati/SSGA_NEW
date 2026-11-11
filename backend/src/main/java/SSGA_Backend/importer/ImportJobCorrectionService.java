package SSGA_Backend.importer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies payroll-operator corrections to the error rows of an uploaded
 * worksheet. Corrections are written to a separate corrected workbook, the
 * worksheet is revalidated, and a correction is rejected when the same field
 * still has an error. Every accepted correction is persisted for audit.
 */
@Service
public class ImportJobCorrectionService {

    private static final Set<String> EDITABLE_STATUSES =
            Set.of("MAPPED", "VALIDATED", "HAS_ERRORS");

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final ImportJobSheetValidationService validationService;
    private final Path storageRoot;

    public ImportJobCorrectionService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper,
            ImportJobSheetValidationService validationService,
            @Value("${ssga.import.storage-dir:}") String storageDirectory
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;
        this.validationService = validationService;

        Path configured = storageDirectory == null || storageDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "ssga-data", "imports")
                : Paths.get(storageDirectory);

        this.storageRoot = configured.toAbsolutePath().normalize();
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public ImportJobSheetValidationResponse apply(
            long jobId,
            int sheetIndex,
            ImportJobCorrectionRequest request
    ) throws IOException {

        if (request == null
                || request.rows() == null
                || request.rows().isEmpty()) {
            throw badRequest("At least one corrected row is required.");
        }

        SsgaPrincipal user = currentUser();
        rlsContext.setCurrentUser();

        Map<String, Object> record = requireSheet(jobId, sheetIndex, user);
        String jobStatus = (String) record.get("job_status");

        if (!EDITABLE_STATUSES.contains(jobStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Corrections are only allowed before the draft is submitted."
            );
        }

        String importType = (String) record.get("confirmed_type");

        if (importType == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Confirm the worksheet type before correcting rows."
            );
        }

        String fileKey = (String) record.get("file_key");
        long sheetId = ((Number) record.get("sheet_id")).longValue();
        Integer headerRowNumber = nullableInt(record.get("header_row_number"));

        if (fileKey == null || headerRowNumber == null || headerRowNumber < 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The worksheet is not ready for corrections."
            );
        }

        SheetMapping mapping = SheetMapping.parse(
                jsonMapper, (String) record.get("mapping_json"));

        List<String> headers = Arrays.asList(
                jsonMapper.readValue(
                        (String) record.get("headers_json"), String[].class));

        Map<String, Integer> headerIndexes = new LinkedHashMap<>();

        for (int index = 0; index < headers.size(); index++) {
            headerIndexes.putIfAbsent(headers.get(index), index);
        }

        Path source = storageRoot.resolve(fileKey).normalize();

        if (fileKey.contains("..")
                || !source.startsWith(storageRoot)
                || !Files.isRegularFile(source)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stored workbook was not found."
            );
        }

        Files.createDirectories(storageRoot);

        Path target = storageRoot.resolve(
                "job-" + jobId + "-corrected.xlsx").normalize();
        Path temp = storageRoot.resolve(
                "job-" + jobId + "-corrected.tmp.xlsx").normalize();

        List<AppliedCorrection> applied = new ArrayList<>();

        try {
            try (Workbook workbook = WorkbookFactory.create(source.toFile())) {
                if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
                    throw badRequest("Worksheet not found in the workbook.");
                }

                Sheet sheet = workbook.getSheetAt(sheetIndex);
                DataFormatter formatter = new DataFormatter();

                for (ImportJobCorrectionRequest.RowCorrection correction
                        : request.rows()) {

                    if (correction == null
                            || correction.sourceRowNumber() <= headerRowNumber) {
                        throw badRequest(
                                "Corrected row numbers must be data rows below "
                                        + "the header.");
                    }

                    if (correction.values() == null
                            || correction.values().isEmpty()) {
                        throw badRequest(
                                "Corrected row "
                                        + correction.sourceRowNumber()
                                        + " has no values.");
                    }

                    Row row = sheet.getRow(correction.sourceRowNumber() - 1);

                    if (row == null) {
                        throw badRequest(
                                "Row " + correction.sourceRowNumber()
                                        + " was not found in the workbook.");
                    }

                    for (Map.Entry<String, String> entry
                            : correction.values().entrySet()) {

                        String field = entry.getKey();
                        String header = resolveHeader(mapping, field);
                        Integer columnIndex = header == null
                                ? null : headerIndexes.get(header);

                        if (columnIndex == null) {
                            throw badRequest(
                                    "Field '" + field
                                            + "' is not mapped to a column.");
                        }

                        String before = ImportCells.text(
                                row, columnIndex, formatter);

                        Cell cell = row.getCell(
                                columnIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        cell.setCellValue(
                                entry.getValue() == null ? "" : entry.getValue());

                        applied.add(new AppliedCorrection(
                                correction.sourceRowNumber(),
                                field,
                                before,
                                entry.getValue(),
                                correction.errorCode()));
                    }
                }

                try (OutputStream output = Files.newOutputStream(temp)) {
                    workbook.write(output);
                }
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

            String sha = ImportJobService.calculateSha256(target);

            jdbcTemplate.update(
                    """
                    UPDATE public.import_jobs
                    SET corrected_file_key = ?,
                        corrected_file_sha256 = ?,
                        resolution_version = resolution_version + 1
                    WHERE id = ?
                    """,
                    target.getFileName().toString(),
                    sha,
                    jobId
            );

            ImportJobSheetValidationResponse validation;

            try {
                validation = validationService.validate(jobId, sheetIndex);
            } catch (RuntimeException | IOException exception) {
                Files.deleteIfExists(target);
                throw exception;
            }

            for (AppliedCorrection correction : applied) {
                String remaining = remainingError(
                        sheetId, correction.rowNumber(), correction.field());

                if (remaining != null) {
                    Files.deleteIfExists(target);
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "Correction rejected for row "
                                    + correction.rowNumber() + ": " + remaining
                    );
                }
            }

            for (AppliedCorrection correction : applied) {
                jdbcTemplate.update(
                        """
                        INSERT INTO public.import_job_corrections (
                            import_job_id, import_job_sheet_id,
                            source_row_number, field_name, error_code,
                            correction_action, original_value, corrected_value,
                            status, created_by, applied_by, applied_at
                        )
                        VALUES (
                            ?, ?, ?, ?, ?, 'MANUAL_EDIT',
                            to_jsonb(?::text), to_jsonb(?::text),
                            'APPLIED', ?, ?, now()
                        )
                        """,
                        jobId,
                        sheetId,
                        correction.rowNumber(),
                        correction.field(),
                        correction.errorCode() == null
                                ? "MANUAL_EDIT" : correction.errorCode(),
                        correction.before(),
                        correction.after(),
                        user.getUserId(),
                        user.getUserId()
                );
            }

            recordEvent(
                    jobId,
                    jobStatus,
                    validation.status(),
                    sheetIndex,
                    applied.size()
            );

            audit(jobStatus, validation.status(), jobId, applied.size());

            return validation;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Map<String, Object> requireSheet(
            long jobId,
            int sheetIndex,
            SsgaPrincipal user
    ) {
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                """
                SELECT
                    s.id AS sheet_id,
                    s.sheet_name,
                    s.confirmed_type,
                    s.header_row_number,
                    s.headers::text AS headers_json,
                    s.column_mapping::text AS mapping_json,
                    j.status AS job_status,
                    COALESCE(j.corrected_file_key, j.file_key) AS file_key
                FROM public.import_job_sheets s
                JOIN public.import_jobs j ON j.id = s.import_job_id
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

        return records.get(0);
    }

    private String resolveHeader(SheetMapping mapping, String field) {
        if (field == null) {
            return null;
        }

        String header = mapping.columns().get(field);

        if (header != null) {
            return header;
        }

        if (field.startsWith("component:")) {
            return mapping.components().get(
                    field.substring("component:".length()));
        }

        return mapping.components().get(field);
    }

    private String remainingError(long sheetId, int rowNumber, String field) {
        List<Map<String, Object>> issues = jdbcTemplate.queryForList(
                """
                SELECT field_name, message
                FROM public.import_job_sheet_issues
                WHERE import_job_sheet_id = ?
                  AND source_row_number = ?
                  AND severity = 'ERROR'
                """,
                sheetId,
                rowNumber
        );

        Set<String> related = relatedFields(field);

        for (Map<String, Object> issue : issues) {
            String issueField = (String) issue.get("field_name");

            if (issueField == null) {
                continue;
            }

            if (related.contains(issueField)
                    || (field != null
                        && field.startsWith("component:")
                        && issueField.startsWith("component:"))) {
                return (String) issue.get("message");
            }
        }

        return null;
    }

    private Set<String> relatedFields(String field) {
        if (field == null) {
            return Set.of();
        }

        if (field.startsWith("component:")) {
            return Set.of(field, "componentCode", "componentColumns", "category");
        }

        return switch (field) {
            case "hrmsId" -> Set.of(
                    "hrmsId", "employeeIdentifier", "employeeName");
            case "aadhaar", "pan", "contact", "email", "pf", "nps", "gpf" ->
                    Set.of(field, "employeeIdentifier");
            case "componentCode" -> Set.of(
                    "componentCode", "componentColumns", "category");
            case "effectiveFrom" -> Set.of(
                    "effectiveFrom", "componentCode/effectiveFrom");
            case "effectiveTo" -> Set.of(
                    "effectiveTo", "componentCode/effectiveTo");
            case "division", "divisionId" -> Set.of("division", "divisionId");
            default -> Set.of(field);
        };
    }

    private void recordEvent(
            long jobId,
            String previousStatus,
            String newStatus,
            int sheetIndex,
            int correctedFields
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO public.import_job_workflow_events (
                    import_job_id, division_id, action,
                    previous_status, new_status, actor_user_id, details
                )
                VALUES (?, NULL::bigint, 'CORRECT_ROWS', ?, ?, ?, jsonb_build_object(
                    'sheetIndex', ?::int,
                    'correctedFields', ?::int
                ))
                """,
                jobId,
                previousStatus,
                newStatus,
                currentUserId(),
                sheetIndex,
                correctedFields
        );
    }

    private void audit(
            String previousStatus,
            String newStatus,
            long jobId,
            int correctedFields
    ) {
        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    'IMPORT_CORRECTION', 'IMPORT_JOB', ?::varchar,
                    NULL::bigint, NULL::bigint,
                    jsonb_build_object('status', ?::text),
                    jsonb_build_object('status', ?::text),
                    ?, NULL::uuid
                )
                """,
                Long.class,
                String.valueOf(jobId),
                previousStatus,
                newStatus,
                "Corrected " + correctedFields + " field(s) on an import row."
        );
    }

    private long currentUserId() {
        Long actor = jdbcTemplate.queryForObject(
                "SELECT app.current_user_id()", Long.class);

        if (actor == null) {
            throw new IllegalStateException(
                    "Authenticated database user context is missing."
            );
        }

        return actor;
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

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record AppliedCorrection(
            int rowNumber,
            String field,
            String before,
            String after,
            String errorCode
    ) {
    }
}
