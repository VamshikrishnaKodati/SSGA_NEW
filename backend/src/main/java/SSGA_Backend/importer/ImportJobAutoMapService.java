package SSGA_Backend.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
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
 * Intelligent column auto-mapping: reads the stored workbook, samples each
 * column, and returns evidence-based suggestions with HIGH / REVIEW / UNMATCHED
 * confidence. Nothing is persisted here — the operator still confirms the
 * mapping through the existing mapping endpoint.
 */
@Service
public class ImportJobAutoMapService {

    private static final int SAMPLE_ROWS = 10;
    private static final int MAX_SCAN_ROWS = 200;
    private static final Set<String> WIDE_TYPES =
            Set.of("SALARY_ENTITLEMENTS", "PAYROLL_HISTORY", "EMPLOYEE_MASTER_AND_COMPONENTS");

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final Path storageRoot;

    public ImportJobAutoMapService(
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
    public ImportJobSheetAutoMapResponse autoMap(long jobId, int sheetIndex)
            throws IOException {

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
                    s.sheet_name,
                    s.suggested_type,
                    s.confirmed_type,
                    s.header_row_number,
                    s.headers::text AS headers_json,
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

        Map<String, Object> record = records.get(0);
        String sheetName = (String) record.get("sheet_name");
        String headersJson = (String) record.get("headers_json");

        String[] headerArray = jsonMapper.readValue(headersJson, String[].class);
        List<String> headers = Arrays.asList(headerArray);

        String importType = resolveType(
                (String) record.get("confirmed_type"),
                (String) record.get("suggested_type")
        );

        String signature = structureSignature(headers);

        if (importType == null) {
            return new ImportJobSheetAutoMapResponse(
                    jobId, sheetIndex, sheetName, null,
                    toInteger(record.get("header_row_number")),
                    signature, List.of(), List.of(),
                    "Classify this sheet as a known type before mapping columns."
            );
        }

        Map<String, List<String>> samples = readSamples(
                (String) record.get("file_key"),
                sheetIndex,
                toInteger(record.get("header_row_number")),
                headers
        );

        List<ImportJobSheetAutoMapResponse.FieldSuggestion> fields =
                ColumnMappingCatalog.suggestFields(importType, headers, samples)
                        .stream()
                        .map(item -> new ImportJobSheetAutoMapResponse.FieldSuggestion(
                                item.field(),
                                item.label(),
                                item.sourceHeader(),
                                item.confidence(),
                                item.reason(),
                                item.identifier()
                        ))
                        .toList();

        List<ImportJobSheetAutoMapResponse.ComponentSuggestion> components =
                WIDE_TYPES.contains(importType)
                        ? suggestComponents(headers)
                        : List.of();

        return new ImportJobSheetAutoMapResponse(
                jobId, sheetIndex, sheetName, importType,
                toInteger(record.get("header_row_number")),
                signature, fields, components,
                "Suggested mapping generated. Review REVIEW items and confirm before saving."
        );
    }

    private List<ImportJobSheetAutoMapResponse.ComponentSuggestion> suggestComponents(
            List<String> headers
    ) {
        Map<String, String> codeByNormalized = new LinkedHashMap<>();

        jdbcTemplate.query(
                """
                SELECT component_code, component_name
                FROM public.salary_components
                WHERE status = 'ACTIVE'
                """,
                rs -> {
                    String code = rs.getString("component_code");
                    String name = rs.getString("component_name");

                    codeByNormalized.putIfAbsent(
                            ColumnMappingCatalog.normalize(code), code);

                    if (name != null && !name.isBlank()) {
                        codeByNormalized.putIfAbsent(
                                ColumnMappingCatalog.normalize(name), code);
                    }
                }
        );

        List<ImportJobSheetAutoMapResponse.ComponentSuggestion> result =
                new ArrayList<>();

        for (String header : headers) {
            if (header == null || header.isBlank()) {
                continue;
            }

            String code = codeByNormalized.get(
                    ColumnMappingCatalog.normalize(header));

            if (code == null) {
                continue;
            }

            result.add(new ImportJobSheetAutoMapResponse.ComponentSuggestion(
                    code,
                    header,
                    ColumnMappingCatalog.REVIEW,
                    "Header matches salary component " + code
                            + " — confirm before saving."
            ));
        }

        return result;
    }

    private Map<String, List<String>> readSamples(
            String fileKey,
            int sheetIndex,
            Integer headerRowNumber,
            List<String> headers
    ) throws IOException {

        Map<String, List<String>> samples = new LinkedHashMap<>();

        if (fileKey == null || headerRowNumber == null || headerRowNumber < 1) {
            return samples;
        }

        Path filePath = storageRoot.resolve(fileKey).normalize();

        if (!filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "The stored workbook could not be found."
            );
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
            int lastRow = Math.min(sheet.getLastRowNum(), headerIndex + MAX_SCAN_ROWS);

            for (int rowIndex = headerIndex + 1; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);

                if (row == null) {
                    continue;
                }

                for (int column = 0; column < headers.size(); column++) {
                    Cell cell = row.getCell(
                            column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

                    if (cell == null) {
                        continue;
                    }

                    String value = formatter.formatCellValue(cell).trim();

                    if (value.isEmpty()) {
                        continue;
                    }

                    List<String> columnSamples = samples.computeIfAbsent(
                            headers.get(column), ignored -> new ArrayList<>());

                    if (columnSamples.size() < SAMPLE_ROWS) {
                        columnSamples.add(value);
                    }
                }
            }
        }

        return samples;
    }

    private String resolveType(String confirmedType, String suggestedType) {
        if (confirmedType != null
                && ColumnMappingCatalog.supportsType(confirmedType)) {
            return confirmedType;
        }

        if (suggestedType != null
                && ColumnMappingCatalog.supportsType(suggestedType)) {
            return suggestedType;
        }

        return null;
    }

    /** Stable fingerprint of a sheet's column structure, used for profiles. */
    static String structureSignature(List<String> headers) {
        StringBuilder builder = new StringBuilder();

        for (String header : headers) {
            if (header == null) {
                continue;
            }

            builder.append(ColumnMappingCatalog.normalize(header)).append('|');
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    builder.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable in this Java runtime.", ex);
        }
    }

    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
