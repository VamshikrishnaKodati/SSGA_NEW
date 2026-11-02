package SSGA_Backend.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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
 * Read-only mapping preview and downloadable error report. Renders the first
 * rows of a mapped worksheet exactly as they would be read, with sensitive
 * values masked, and streams the persisted row issues as CSV. It never imports
 * data.
 */
@Service
public class ImportJobSheetPreviewService {

    private static final int PREVIEW_ROWS = 20;
    private static final Set<String> MASKED_FIELDS =
            Set.of("pan", "aadhaar", "bankAccount");

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final Path storageRoot;

    public ImportJobSheetPreviewService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper,
            @Value("${ssga.import.storage-dir:}") String storageDirectory
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;

        Path configured = storageDirectory == null || storageDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "ssga-data", "imports")
                : Paths.get(storageDirectory);

        this.storageRoot = configured.toAbsolutePath().normalize();
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobSheetPreviewResponse preview(long jobId, int sheetIndex)
            throws IOException {

        Map<String, Object> record = requireSheet(jobId, sheetIndex);

        long sheetId = ((Number) record.get("sheet_id")).longValue();
        String sheetName = (String) record.get("sheet_name");
        String importType = (String) record.get("confirmed_type");

        List<String> headers = Arrays.asList(
                jsonMapper.readValue((String) record.get("headers_json"), String[].class));

        SheetMapping mapping = SheetMapping.parse(
                jsonMapper, (String) record.get("mapping_json"));

        if (mapping.columns().isEmpty() && mapping.components().isEmpty()) {
            return new ImportJobSheetPreviewResponse(
                    jobId, sheetIndex, sheetName, List.of(), List.of(),
                    0, 0, 0, false, List.of(),
                    "Save a column mapping before previewing rows."
            );
        }

        List<ImportJobSheetPreviewResponse.PreviewColumn> columns =
                new ArrayList<>();

        for (Map.Entry<String, String> entry : mapping.columns().entrySet()) {
            columns.add(new ImportJobSheetPreviewResponse.PreviewColumn(
                    entry.getKey(),
                    labelFor(importType, entry.getKey()),
                    entry.getValue(),
                    MASKED_FIELDS.contains(entry.getKey())
            ));
        }

        for (Map.Entry<String, String> entry : mapping.components().entrySet()) {
            columns.add(new ImportJobSheetPreviewResponse.PreviewColumn(
                    entry.getKey(), entry.getKey(), entry.getValue(), false));
        }

        List<ImportJobSheetPreviewResponse.PreviewRow> rows = readRows(
                (String) record.get("file_key"),
                sheetIndex,
                headerRowNumber(record),
                headers,
                columns
        );

        List<ImportJobSheetPreviewResponse.Issue> issues = loadIssues(sheetId);

        int validRows = intValue(record.get("valid_rows"));
        int errorRows = intValue(record.get("error_rows"));
        int warningRows = intValue(record.get("warning_rows"));
        boolean validated = record.get("validated_at") != null;

        String message = validated
                ? "Preview reflects the last validation run."
                : "Preview only. Run validation to check references and duplicates.";

        return new ImportJobSheetPreviewResponse(
                jobId, sheetIndex, sheetName, columns, rows,
                validRows, errorRows, warningRows, validated, issues, message
        );
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public String errorReportCsv(long jobId, int sheetIndex) {
        Map<String, Object> record = requireSheet(jobId, sheetIndex);
        long sheetId = ((Number) record.get("sheet_id")).longValue();

        List<ImportJobSheetPreviewResponse.Issue> issues = loadIssues(sheetId);

        StringBuilder csv = new StringBuilder();
        csv.append("Row,Severity,Field,Code,Message\r\n");

        for (ImportJobSheetPreviewResponse.Issue issue : issues) {
            csv.append(issue.sourceRowNumber()).append(',')
                    .append(csvField(issue.severity())).append(',')
                    .append(csvField(issue.fieldName())).append(',')
                    .append(csvField(issue.errorCode())).append(',')
                    .append(csvField(issue.message())).append("\r\n");
        }

        return csv.toString();
    }

    private Map<String, Object> requireSheet(long jobId, int sheetIndex) {
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
                    s.header_row_number,
                    s.headers::text AS headers_json,
                    s.column_mapping::text AS mapping_json,
                    s.valid_rows,
                    s.error_rows,
                    s.warning_rows,
                    s.validated_at,
                    j.file_key
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

    private List<ImportJobSheetPreviewResponse.PreviewRow> readRows(
            String fileKey,
            int sheetIndex,
            Integer headerRowNumber,
            List<String> headers,
            List<ImportJobSheetPreviewResponse.PreviewColumn> columns
    ) throws IOException {

        List<ImportJobSheetPreviewResponse.PreviewRow> rows = new ArrayList<>();

        if (fileKey == null || headerRowNumber == null || headerRowNumber < 1) {
            return rows;
        }

        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (fileKey.contains("..") || !filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "The stored workbook could not be found."
            );
        }

        Map<String, Integer> headerIndexes = new LinkedHashMap<>();

        for (int index = 0; index < headers.size(); index++) {
            headerIndexes.putIfAbsent(headers.get(index), index);
        }

        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {

            if (sheetIndex >= workbook.getNumberOfSheets()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Worksheet index does not exist in the workbook."
                );
            }

            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int headerIndex = headerRowNumber - 1;

            for (int index = headerIndex + 1;
                 index <= sheet.getLastRowNum() && rows.size() < PREVIEW_ROWS;
                 index++) {

                Row row = sheet.getRow(index);

                if (row == null || ImportCells.isBlankRow(row)) {
                    continue;
                }

                Map<String, String> values = new LinkedHashMap<>();

                for (ImportJobSheetPreviewResponse.PreviewColumn column : columns) {
                    Integer columnIndex = headerIndexes.get(column.sourceHeader());
                    String raw = ImportCells.text(row, columnIndex, formatter);
                    values.put(
                            column.field(),
                            column.masked() ? mask(raw) : (raw == null ? "" : raw)
                    );
                }

                rows.add(new ImportJobSheetPreviewResponse.PreviewRow(
                        index + 1, null, values));
            }
        }

        return rows;
    }

    private List<ImportJobSheetPreviewResponse.Issue> loadIssues(long sheetId) {
        return jdbcTemplate.query(
                """
                SELECT source_row_number, severity, field_name, error_code, message
                FROM public.import_job_sheet_issues
                WHERE import_job_sheet_id = ?
                ORDER BY source_row_number, severity DESC
                LIMIT 500
                """,
                (rs, rowNum) -> new ImportJobSheetPreviewResponse.Issue(
                        rs.getInt("source_row_number"),
                        rs.getString("severity"),
                        rs.getString("field_name"),
                        rs.getString("error_code"),
                        rs.getString("message")
                ),
                sheetId
        );
    }

    private String labelFor(String type, String field) {
        List<ColumnMappingCatalog.FieldSpec> specs =
                ColumnMappingCatalog.BY_TYPE.get(type);

        if (specs != null) {
            for (ColumnMappingCatalog.FieldSpec spec : specs) {
                if (spec.key().equals(field)) {
                    return spec.label();
                }
            }
        }

        return field;
    }

    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();

        if (trimmed.length() <= 4) {
            return "\u2022\u2022\u2022\u2022";
        }

        return "\u2022\u2022\u2022\u2022"
                + trimmed.substring(trimmed.length() - 4);
    }

    private String csvField(String value) {
        if (value == null) {
            return "";
        }

        String sanitized = sanitizeCsvFormula(value);
        if (sanitized.contains(",") || sanitized.contains("\"")
                || sanitized.contains("\n") || sanitized.contains("\r")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }

        return sanitized;
    }

    private String sanitizeCsvFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return value;
        }
        char first = trimmed.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' || first == '\n') {
            return "'" + value;
        }
        // Also catch formulas after whitespace
        for (int i = 0; i < Math.min(trimmed.length(), 10); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == '=' || c == '+' || c == '-' || c == '@') {
                return "'" + value;
            }
            break;
        }
        return value;
    }

    private static Integer headerRowNumber(Map<String, Object> record) {
        Object value = record.get("header_row_number");
        return value == null ? null : ((Number) value).intValue();
    }

    private static int intValue(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
