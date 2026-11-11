
package SSGA_Backend.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

import tools.jackson.databind.json.JsonMapper;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;

@Service
public class ImportJobAnalysisService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final Path storageRoot;

    public ImportJobAnalysisService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper,
            @Value("${ssga.import.storage-dir:}") String storageDirectory
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;

        Path configuredPath;

        if (storageDirectory == null || storageDirectory.isBlank()) {
            configuredPath = Paths.get(
                    System.getProperty("user.home"),
                    "ssga-data",
                    "imports"
            );
        } else {
            configuredPath = Paths.get(storageDirectory);
        }

        this.storageRoot = configuredPath.toAbsolutePath().normalize();
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager"
    )
    public ImportJobAnalysisResponse analyze(long jobId)
            throws IOException {

        if (jobId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Import job ID must be positive."
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

        // Establish the PostgreSQL RLS context in this transaction.
        rlsContext.setCurrentUser();

        List<Map<String, Object>> jobs = jdbcTemplate.queryForList(
                """
                SELECT id, file_key, file_name, uploaded_by, status
                FROM public.import_jobs
                WHERE id = ?
                  AND uploaded_by = ?
                """,
                jobId,
                user.getUserId()
        );

        if (jobs.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Import job not found or not accessible."
            );
        }

        String fileKey = (String) jobs.get(0).get("file_key");

        Path filePath = storageRoot.resolve(fileKey).normalize();

        // Prevent a database file key from escaping the import directory.
        if (!filePath.startsWith(storageRoot)
                || !Files.isRegularFile(filePath)) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "The stored workbook could not be found."
            );
        }

        // Analyze workbook contents without importing salary values.
        List<ImportJobAnalysisResponse.SheetAnalysis> reports =
                analyzeWorkbook(filePath);

        // Persist worksheet details in the same database transaction.
        saveSheetRows(jobId, reports);

        String analysisJson =
                jsonMapper.writeValueAsString(reports);

        jdbcTemplate.update(
                """
                UPDATE public.import_jobs
                SET detected_format = 'MULTI_SHEET_EXCEL',
                    source_mapping =
                        source_mapping
                        || jsonb_build_object(
                            'analysis',
                            ?::jsonb,
                            'analysisVersion',
                            1
                        )
                WHERE id = ?
                """,
                analysisJson,
                jobId
        );

        return new ImportJobAnalysisResponse(
                jobId,
                ((Number) jobs.get(0).get("uploaded_by")).longValue(),
                (String) jobs.get(0).get("status"),
                "MULTI_SHEET_EXCEL",
                reports,
                "Workbook analysis completed. Review the suggested "
                        + "classifications before mapping or importing data."
        );
    }

    /**
     * Save each worksheet's detected structure.
     * Existing confirmed classifications and in-progress sheets are preserved.
     */
    private void saveSheetRows(
            long jobId,
            List<ImportJobAnalysisResponse.SheetAnalysis> reports
    ) throws IOException {

        String sql = """
                INSERT INTO public.import_job_sheets AS existing (
                    import_job_id,
                    sheet_index,
                    sheet_name,
                    suggested_type,
                    status,
                    header_row_number,
                    column_count,
                    non_empty_rows,
                    data_rows_estimate,
                    headers
                )
                VALUES (
                    ?, ?, ?, ?, 'DETECTED', ?, ?, ?, ?, ?::jsonb
                )
                ON CONFLICT (import_job_id, sheet_index)
                DO UPDATE SET
                    sheet_name = EXCLUDED.sheet_name,
                    suggested_type = EXCLUDED.suggested_type,
                    status = 'DETECTED',
                    header_row_number = EXCLUDED.header_row_number,
                    column_count = EXCLUDED.column_count,
                    non_empty_rows = EXCLUDED.non_empty_rows,
                    data_rows_estimate = EXCLUDED.data_rows_estimate,
                    headers = EXCLUDED.headers,
                    valid_rows = 0,
                    error_rows = 0,
                    warning_rows = 0,
                    validated_at = NULL,
                    updated_at = now()
                WHERE existing.confirmed_type IS NULL
                  AND existing.status IN ('DETECTED', 'FAILED')
                """;

        for (ImportJobAnalysisResponse.SheetAnalysis sheet : reports) {

            String headersJson =
                    jsonMapper.writeValueAsString(sheet.headers());

            jdbcTemplate.update(
                    sql,
                    jobId,
                    sheet.sheetIndex(),
                    sheet.sheetName(),
                    sheet.suggestedType(),
                    sheet.headerRowNumber(),
                    sheet.columnCount(),
                    sheet.nonEmptyRows(),
                    sheet.dataRowsEstimate(),
                    headersJson
            );
        }
    }

    private List<ImportJobAnalysisResponse.SheetAnalysis> analyzeWorkbook(
            Path filePath
    ) throws IOException {

        List<ImportJobAnalysisResponse.SheetAnalysis> reports =
                new ArrayList<>();

        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook =
                     WorkbookFactory.create(filePath.toFile())) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "The workbook contains no worksheets."
                );
            }

            for (int sheetIndex = 0;
                 sheetIndex < workbook.getNumberOfSheets();
                 sheetIndex++) {

                Sheet sheet = workbook.getSheetAt(sheetIndex);

                HeaderCandidate header =
                        detectHeader(sheet, formatter);

                int nonEmptyRows =
                        countNonEmptyRows(sheet, formatter);

                int dataRows = header.rowIndex() < 0
                        ? 0
                        : countNonEmptyRowsAfter(
                                sheet,
                                header.rowIndex(),
                                formatter
                        );

                String suggestedType = suggestType(
                        sheet.getSheetName(),
                        header.headers()
                );

                String note;

                if ("UNKNOWN".equals(suggestedType)) {
                    note = "The sheet could not be confidently classified. "
                            + "Manual review and column mapping are required.";
                } else if (
                        "REFERENCE_ONLY".equals(suggestedType)
                        || "COMPONENT_CATALOG_REFERENCE".equals(suggestedType)
                ) {
                    note = "This appears to be reference or test data. "
                            + "Do not treat it as actual payroll without review.";
                } else {
                    note = "Suggested classification only. Verify headers, "
                            + "identifiers, dates and business meaning.";
                }

                reports.add(
                        new ImportJobAnalysisResponse.SheetAnalysis(
                                sheetIndex,
                                sheet.getSheetName(),
                                nonEmptyRows,
                                dataRows,
                                header.rowIndex() < 0
                                        ? null
                                        : header.rowIndex() + 1,
                                header.headers().size(),
                                suggestedType,
                                List.copyOf(header.headers()),
                                note
                        )
                );
            }

        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to analyze the Excel workbook.",
                    ex
            );
        }

        return reports;
    }

    private HeaderCandidate detectHeader(
            Sheet sheet,
            DataFormatter formatter
    ) {
        // Many official salary workbooks have title rows before the headers.
        // Check the first 20 rows and select a likely header row.
        int lastRow = Math.min(sheet.getLastRowNum(), 19);
        int bestRowIndex = -1;
        int bestScore = 0;
        List<String> bestHeaders = List.of();

        Set<String> keywords = Set.of(
                "EMPLOYEE",
                "EMPLOYEE_CODE",
                "EMPLOYEE_ID",
                "HRMS",
                "NAME",
                "DESIGNATION",
                "DIVISION",
                "WING",
                "COMPONENT",
                "AMOUNT",
                "SALARY",
                "GROSS",
                "DEDUCTION",
                "NET",
                "PERIOD",
                "MONTH",
                "YEAR",
                "DATE",
                "BASIC_PAY",
                "CODE",
                "EARNING",
                "PAY",
                "EFFECTIVE"
        );

        for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {

            Row row = sheet.getRow(rowIndex);

            if (row == null) {
                continue;
            }

            List<String> headers =
                    readRowValues(row, formatter);

            int nonEmpty = 0;
            int textCount = 0;
            int keywordCount = 0;

            for (String value : headers) {

                if (value.isBlank()) {
                    continue;
                }

                nonEmpty++;

                String normalized = normalize(value);

                if (normalized.isEmpty()) {
                    continue;
                }

                textCount++;

                for (String keyword : keywords) {
                    if (normalized.contains(keyword)) {
                        keywordCount++;
                        break;
                    }
                }
            }

            int score = nonEmpty + textCount + (keywordCount * 4);

            if (nonEmpty >= 2 && score > bestScore) {
                bestScore = score;
                bestRowIndex = rowIndex;
                bestHeaders = headers;
            }
        }

        return new HeaderCandidate(
                bestRowIndex,
                bestHeaders
        );
    }

    private List<String> readRowValues(
            Row row,
            DataFormatter formatter
    ) {
        List<String> values = new ArrayList<>();

        int lastCell = row.getLastCellNum();

        if (lastCell < 0) {
            return values;
        }

        int columnLimit = Math.min(lastCell, 200);

        for (int cellIndex = 0;
             cellIndex < columnLimit;
             cellIndex++) {

            Cell cell = row.getCell(
                    cellIndex,
                    Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
            );

            String value = cell == null
                    ? ""
                    : formatter.formatCellValue(cell).trim();

            values.add(value);
        }

        while (!values.isEmpty()
                && values.get(values.size() - 1).isBlank()) {

            values.remove(values.size() - 1);
        }

        return values;
    }

    private int countNonEmptyRows(
            Sheet sheet,
            DataFormatter formatter
    ) {
        int count = 0;

        for (int rowIndex = sheet.getFirstRowNum();
             rowIndex <= sheet.getLastRowNum();
             rowIndex++) {

            Row row = sheet.getRow(rowIndex);

            if (row != null
                    && !readRowValues(row, formatter)
                            .stream()
                            .allMatch(String::isBlank)) {
                count++;
            }
        }

        return count;
    }

    private int countNonEmptyRowsAfter(
            Sheet sheet,
            int headerRowIndex,
            DataFormatter formatter
    ) {
        int count = 0;

        for (int rowIndex = headerRowIndex + 1;
             rowIndex <= sheet.getLastRowNum();
             rowIndex++) {

            Row row = sheet.getRow(rowIndex);

            if (row != null
                    && !readRowValues(row, formatter)
                            .stream()
                            .allMatch(String::isBlank)) {
                count++;
            }
        }

        return count;
    }

    String suggestType(
            String sheetName,
            List<String> rawHeaders
    ) {
        String name = normalize(sheetName);

        Set<String> headers = new LinkedHashSet<>();

        for (String header : rawHeaders) {
            String normalized = normalize(header);

            if (!normalized.isBlank()) {
                headers.add(normalized);
            }
        }

        // Known documentation and test-result sheets must never be
        // automatically imported as salary transactions.
        if (name.contains("README")
                || name.contains("TEST_SCENARIOS")
                || name.contains("AUDIT_TEST_CASES")
                || name.contains("ROLE_TEST_MATRIX")
                || name.contains("DB_MAPPING")
                || name.contains("PAYROLL_EXPECTED")
                || name.contains("EXPECTED_RESULTS")
                || name.contains("NOTES")
                || name.contains("INSTRUCTION")
                || name.contains("GUIDANCE")
                || name.contains("GUIDELINE")) {

            return "REFERENCE_ONLY";
        }

        if (name.contains("SALARY_COMPONENTS")) {
            return "REFERENCE_ONLY";
        }

        boolean employeeIdentity = hasAny(
                headers,
                "EMPLOYEE_CODE",
                "EMPLOYEE_ID",
                "HRMS_ID"
        );

        boolean employeeName = hasAny(
                headers,
                "EMPLOYEE_NAME",
                "NAME",
                "EMPLOYEENAME",
                "EMPLOYEE"
        );

        boolean division = hasAny(
                headers,
                "DIVISION",
                "DIVISION_ID",
                "DIVISION_CODE"
        );

        boolean componentCode = hasAny(
                headers,
                "COMPONENT_CODE",
                "SALARY_COMPONENT_CODE",
                "SALARY_COMPONENT"
        );

        boolean amount = hasAny(
                headers,
                "AMOUNT",
                "VALUE",
                "SALARY_AMOUNT"
        );

        boolean period = hasAny(
                headers,
                "PERIOD",
                "PAY_PERIOD",
                "SALARY_MONTH",
                "PAYROLL_MONTH",
                "MONTH_YEAR"
        );

        // A sheet whose headers are purely instructional (no payroll identity
        // or amount columns) is reference material, not payroll data.
        boolean instructionHeaders = headers.stream()
                .anyMatch(h ->
                        h.contains("INSTRUCTION")
                        || h.contains("GUIDANCE")
                        || h.contains("HOW_TO")
                        || h.contains("POLICY")
                        || h.contains("PROCEDURE")
                        || h.contains("OVERVIEW")
                        || h.contains("LEGEND")
                        || h.contains("KEY_"));

        boolean gross = hasAny(
                headers,
                "GROSS",
                "GROSS_SALARY"
        );

        boolean net = hasAny(
                headers,
                "NET",
                "NET_SALARY"
        );

        boolean payrollIdentityOrAmount = employeeIdentity || employeeName
                || amount || componentCode || gross || net || period;

        if (instructionHeaders && !payrollIdentityOrAmount) {
            return "REFERENCE_ONLY";
        }

        boolean wideComponentSignal = headers.stream()
                .filter(h ->
                        h.endsWith("_PAY")
                        || h.startsWith("DA_")
                        || h.startsWith("HRA_")
                        || h.contains("_ALLOW")
                        || h.contains("_ADV")
                        || h.contains("_RECOVERY")
                        || h.equals("BASIC_PAY")
                        || h.equals("PROF_TAX")
                        || h.equals("INCOME_TAX")
                )
                .count() >= 2;

        if (name.contains("SALARY_VALUES_WIDE")
                || (employeeIdentity
                    && employeeName
                    && division
                    && wideComponentSignal)) {

            return "EMPLOYEE_MASTER_AND_COMPONENTS";
        }

        if (name.contains("SALARY_VALUES_IMPORT")
                || (employeeIdentity
                    && employeeName
                    && componentCode
                    && amount)) {

            return "SALARY_ENTITLEMENTS";
        }

        if (name.contains("EMPLOYEE_MASTER")
                || (employeeIdentity
                    && employeeName
                    && division
                    && !wideComponentSignal
                    && !componentCode
                    && !period)) {

            return "EMPLOYEE_MASTER";
        }

        if (period && (gross || net || amount)) {
            return "PAYROLL_HISTORY";
        }

        if (employeeIdentity
                && wideComponentSignal
                && !period) {

            return "SALARY_ENTITLEMENTS";
        }

        return "UNKNOWN";
    }

    private boolean hasAny(
            Set<String> headers,
            String... candidates
    ) {
        for (String candidate : candidates) {
            if (headers.contains(candidate)) {
                return true;
            }
        }

        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private record HeaderCandidate(
            int rowIndex,
            List<String> headers
    ) {
    }
}
