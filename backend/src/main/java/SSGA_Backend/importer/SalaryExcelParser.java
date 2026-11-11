package SSGA_Backend.importer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class SalaryExcelParser {

    private static final String[] SUPPORTED_FILE_EXTENSIONS = {
            ".xlsx", ".xlsm", ".xls", ".csv"
    };

    // Known HMWSSB salary component codes for fuzzy matching
    private static final Set<String> KNOWN_COMPONENT_CODES = Set.of(
        "BASIC_PAY", "T_SPL_INCREMENT", "FP_PP", "SPL_PAY_G_MITRA", "DA_33_67",
        "HRA_24", "ADDL_HRA", "CCA", "PETROL_AUTO_ALLOW", "WASHING_ALLOW",
        "BOARD_FORMATION_DAY", "IR_5_2023", "DA_ARREARS_26_39", "DA_ARREARS_30_03", "DA_ARREARS_33_67",
        "GPF_EPF", "COURT_ATCH", "PENSION_SCHEME", "TSGLIF", "GIS", "PRC_2020_NPS",
        "ELECT_CHAR", "FESTIVAL_ADV", "EDCN_ADV", "VEHICLE_ADV", "MOPED_ADV",
        "EXCESS_SALARY_RECOVERY", "EPF_12_PERCENT", "SBI_LIFE_INSURANCE", "ONE_DAY_SALARY",
        "MARRIAGE_LOAN", "MGB_AN", "PROF_TAX", "INCOME_TAX", "EPF_PRC_ARREARS",
        "PRC_EXCESS_RECOVERY", "MGB_PC", "TELANGANA_HARITHA_NIDHI",
        "CCA_RECOVERY_DARUSSALAM", "SBH_ANAND_NAGAR", "FLAG_DAY_2025", "LIC"
    );

    // Keywords that indicate employee identity columns
    private static final Set<String> IDENTITY_KEYWORDS = Set.of(
        "EMPLOYEE_CODE", "EMPLOYEE_ID", "HRMS_ID", "HRMS", "EMP_CODE", "EMP_ID",
        "AADHAAR", "PAN", "CONTACT", "PHONE", "MOBILE", "EMAIL", "PF", "NPS", "GPF"
    );

    // Keywords that indicate salary component columns (wide layout)
    private static final Set<String> COMPONENT_KEYWORDS = Set.of(
        "PAY", "ALLOW", "DEDUCT", "TAX", "PF", "HRA", "DA", "ADV", "RECOVERY",
        "BASIC", "GROSS", "NET", "EARNING", "DEDUCTION", "INCREMENT", "ARREARS",
        "CCA", "GIS", "LIC", "INSURANCE", "LOAN", "FESTIVAL", "EDUCATION", "VEHICLE",
        "MOPED", "CHAR", "CHARGES", "COURT", "ATTACHMENT", "PENSION", "NPS", "GPF",
        "TSGLIF", "FLAG", "HARITHA", "NIDHI", "BOARD", "FORMATION", "IR", "SPL",
        "MGB", "PRC", "EPF", "PROF", "INCOME", "EXCESS", "SBI", "LIFE", "MARRIAGE",
        "ONE_DAY", "WASHING", "PETROL", "AUTO", "ADDITIONAL", "ADDL"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SalaryImportPreview parse(MultipartFile file) throws IOException {
        return parse(file, null);
    }

    /**
     * Parse Excel file with auto-detection of layout and dynamic unpivoting.
     * Returns preview with detected layout, headers, and suggested mappings.
     */
    public SalaryImportPreview parse(MultipartFile file, ParseOptions options) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Excel file is required");
        }

        String filename = file.getOriginalFilename();
        String normalizedFilename = filename == null ? "" : filename.toLowerCase();

        boolean supportedExtension = Arrays.stream(SUPPORTED_FILE_EXTENSIONS)
                .anyMatch(normalizedFilename::endsWith);

        if (!supportedExtension) {
            throw new IllegalArgumentException("Only Excel files (.xls, .xlsx, .xlsm) and CSV are supported");
        }

        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel workbook contains no sheets");
            }

            // Analyze all sheets to find the best data sheet
            SheetAnalysis bestSheet = analyzeWorkbook(workbook);
            
            if (bestSheet == null) {
                throw new IllegalArgumentException("No valid data sheet found in workbook");
            }

            Sheet sheet = workbook.getSheetAt(bestSheet.sheetIndex());
            int headerRowIndex = bestSheet.headerRowIndex();
            LayoutType layoutType = bestSheet.layoutType();
            List<String> headers = bestSheet.headers();
            Map<String, Integer> columnIndexes = bestSheet.columnIndexes();

            // Validate minimum data rows
            int dataRowCount = countDataRows(sheet, headerRowIndex);
            if (dataRowCount < 1) {
                throw new IllegalArgumentException("Sheet does not contain enough data rows");
            }

            // Generate preview based on detected layout
            SalaryImportPreview preview = generatePreview(sheet, headerRowIndex, headers, layoutType, 
                    columnIndexes, dataRowCount, bestSheet.suggestedMappings());

            return preview;
        }
    }

    /**
     * Analyze workbook to find the best data sheet and detect layout.
     */
    private SheetAnalysis analyzeWorkbook(Workbook workbook) {
        SheetAnalysis bestAnalysis = null;
        int bestScore = 0;

        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) continue;

            SheetAnalysis analysis = analyzeSheet(sheet, sheetIndex);
            if (analysis != null && analysis.score() > bestScore) {
                bestScore = analysis.score();
                bestAnalysis = analysis;
            }
        }

        return bestAnalysis;
    }

    /**
     * Analyze a single sheet for header row, layout type, and column mappings.
     */
    private SheetAnalysis analyzeSheet(Sheet sheet, int sheetIndex) {
        DataFormatter formatter = new DataFormatter();
        int maxHeaderSearchRows = Math.min(20, sheet.getLastRowNum());

        // Find header row by scoring
        int bestHeaderRow = -1;
        List<String> bestHeaders = List.of();
        int bestHeaderScore = 0;

        for (int rowIndex = 0; rowIndex <= maxHeaderSearchRows; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            List<String> headers = readRowHeaders(row, formatter);
            if (headers.size() < 3) continue; // Need at least some columns

            int score = scoreHeaderRow(headers);
            if (score > bestHeaderScore) {
                bestHeaderScore = score;
                bestHeaderRow = rowIndex;
                bestHeaders = headers;
            }
        }

        if (bestHeaderRow < 0 || bestHeaders.isEmpty()) {
            return null;
        }

        // Detect layout type
        LayoutType layoutType = detectLayoutType(bestHeaders);
        
        // Build column indexes
        Map<String, Integer> columnIndexes = new HashMap<>();
        for (int i = 0; i < bestHeaders.size(); i++) {
            String h = bestHeaders.get(i).trim();
            if (!h.isEmpty()) columnIndexes.put(h, i);
        }

        // Generate suggested mappings
        Map<String, Object> suggestedMappings = suggestColumnMappings(bestHeaders, layoutType);

        int dataRows = countDataRows(sheet, bestHeaderRow);

        return new SheetAnalysis(
            sheetIndex, sheet.getSheetName(), bestHeaderRow, bestHeaders,
            layoutType, columnIndexes, suggestedMappings, dataRows, bestHeaderScore
        );
    }

    /**
     * Score a header row based on keyword matches.
     */
    private int scoreHeaderRow(List<String> headers) {
        int score = 0;
        int nonEmpty = 0;
        int keywordMatches = 0;

        for (String header : headers) {
            if (header.isBlank()) continue;
            nonEmpty++;

            String normalized = normalizeHeader(header);
            if (normalized.isEmpty()) continue;

            // Check for identity keywords
            for (String kw : IDENTITY_KEYWORDS) {
                if (normalized.contains(kw)) {
                    keywordMatches += 3;
                    break;
                }
            }

            // Check for component keywords
            for (String kw : COMPONENT_KEYWORDS) {
                if (normalized.contains(kw)) {
                    keywordMatches += 2;
                    break;
                }
            }

            // Check for known component codes
            if (KNOWN_COMPONENT_CODES.contains(normalized)) {
                keywordMatches += 5;
            }
        }

        score = nonEmpty + keywordMatches;
        return score;
    }

    /**
     * Detect if layout is WIDE (component columns spread horizontally) or LONG (componentCode + amount).
     */
    private LayoutType detectLayoutType(List<String> headers) {
        boolean hasComponentCode = headers.stream()
            .anyMatch(h -> normalizeHeader(h).contains("COMPONENT_CODE") || 
                          normalizeHeader(h).equals("COMPONENT"));
        
        boolean hasAmount = headers.stream()
            .anyMatch(h -> normalizeHeader(h).equals("AMOUNT") || 
                          normalizeHeader(h).contains("VALUE"));

        if (hasComponentCode && hasAmount) {
            return LayoutType.LONG;
        }

        // Count component-like columns
        long componentColumnCount = headers.stream()
            .map(this::normalizeHeader)
            .filter(h -> KNOWN_COMPONENT_CODES.contains(h) || 
                        COMPONENT_KEYWORDS.stream().anyMatch(kw -> h.contains(kw)))
            .count();

        if (componentColumnCount >= 3) {
            return LayoutType.WIDE;
        }

        // Default to WIDE for salary data (most HMWSSB sheets are wide format)
        return LayoutType.WIDE;
    }

    /**
     * Suggest column mappings based on detected headers and layout.
     */
    private Map<String, Object> suggestColumnMappings(List<String> headers, LayoutType layoutType) {
        Map<String, Object> mappings = new LinkedHashMap<>();
        Map<String, String> normalizedToOriginal = new HashMap<>();

        for (String h : headers) {
            normalizedToOriginal.put(normalizeHeader(h), h);
        }

        // Identity columns (priority order: HRMS_ID > Aadhaar > PAN > Contact > Email > PF > NPS > GPF)
        String[] identityPriority = {"hrmsId", "aadhaar", "pan", "contact", "email", "pf", "nps", "gpf"};
        String[] identityKeywords = {"HRMS_ID", "HRMS", "EMPLOYEE_CODE", "EMPLOYEE_ID", "EMP_CODE", "EMP_ID",
                                     "AADHAAR", "PAN", "CONTACT", "PHONE", "MOBILE", "EMAIL", "PF", "NPS", "GPF"};

        for (int i = 0; i < identityPriority.length; i++) {
            String field = identityPriority[i];
            String keyword = identityKeywords[i];
            String match = normalizedToOriginal.get(keyword);
            if (match != null) {
                mappings.put(field, match);
            }
        }

        // Employee name
        String nameMatch = findBestMatch(normalizedToOriginal, "EMPLOYEE_NAME", "NAME", "EMP_NAME", "FULL_NAME");
        if (nameMatch != null) mappings.put("employeeName", nameMatch);

        // Division
        String divMatch = findBestMatch(normalizedToOriginal, "DIVISION", "DIVISION_ID", "DIV_CODE", "DIVISION_CODE", "DIV");
        if (divMatch != null) {
            if (divMatch.contains("ID") || divMatch.contains("CODE")) mappings.put("divisionId", divMatch);
            else mappings.put("division", divMatch);
        }

        // Designation, wing, dates, etc.
        String[] otherFields = {"designation", "wing", "dateOfJoining", "dateOfBirth", 
                               "employmentType", "payScale", "basicPay", "bankAccount", 
                               "ifscCode", "bankName", "branch", "employeeStatus"};
        String[] otherKeywords = {"DESIGNATION", "WING", "DATE_OF_JOINING", "DOJ", "JOINING_DATE",
                                 "DATE_OF_BIRTH", "DOB", "BIRTH_DATE", "EMPLOYMENT_TYPE", "EMP_TYPE",
                                 "PAY_SCALE", "PAYSCALE", "BASIC_PAY", "BASIC", "BANK_ACCOUNT", "ACCOUNT_NO",
                                 "IFSC_CODE", "IFSC", "BANK_NAME", "BANK", "BRANCH", "EMPLOYEE_STATUS", "STATUS"};

        for (int i = 0; i < otherFields.length; i++) {
            String match = findBestMatch(normalizedToOriginal, otherKeywords[i]);
            if (match != null) mappings.put(otherFields[i], match);
        }

        // Layout-specific mappings
        if (layoutType == LayoutType.LONG) {
            String compCodeMatch = findBestMatch(normalizedToOriginal, "COMPONENT_CODE", "COMPONENT", "SALARY_COMPONENT");
            String amountMatch = findBestMatch(normalizedToOriginal, "AMOUNT", "VALUE", "SALARY_AMOUNT");
            String categoryMatch = findBestMatch(normalizedToOriginal, "CATEGORY", "TYPE", "EARNING_DEDUCTION");
            String effFromMatch = findBestMatch(normalizedToOriginal, "EFFECTIVE_FROM", "EFFECTIVE_DATE", "FROM_DATE");
            String effToMatch = findBestMatch(normalizedToOriginal, "EFFECTIVE_TO", "TO_DATE", "END_DATE");

            if (compCodeMatch != null) mappings.put("componentCode", compCodeMatch);
            if (amountMatch != null) mappings.put("amount", amountMatch);
            if (categoryMatch != null) mappings.put("category", categoryMatch);
            if (effFromMatch != null) mappings.put("effectiveFrom", effFromMatch);
            if (effToMatch != null) mappings.put("effectiveTo", effToMatch);
        } else {
            // WIDE layout - map known component columns
            Map<String, String> componentColumns = new LinkedHashMap<>();
            for (String h : headers) {
                String norm = normalizeHeader(h);
                if (KNOWN_COMPONENT_CODES.contains(norm)) {
                    componentColumns.put(norm, h);
                }
            }
            if (!componentColumns.isEmpty()) {
                mappings.put("componentColumns", componentColumns);
            }
        }

        mappings.put("layout", layoutType.name());
        return mappings;
    }

    private String findBestMatch(Map<String, String> normalizedToOriginal, String... keywords) {
        for (String kw : keywords) {
            String match = normalizedToOriginal.get(kw);
            if (match != null) return match;
        }
        // Fuzzy match
        for (String kw : keywords) {
            for (Map.Entry<String, String> entry : normalizedToOriginal.entrySet()) {
                if (entry.getKey().contains(kw) || kw.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Generate preview response with metrics and sample data.
     */
    private SalaryImportPreview generatePreview(Sheet sheet, int headerRowIndex, List<String> headers,
            LayoutType layoutType, Map<String, Integer> columnIndexes, int dataRowCount,
            Map<String, Object> suggestedMappings) {

        // Read sample rows (first 5)
        List<Map<String, Object>> sampleRows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        for (int i = headerRowIndex + 1; i <= Math.min(headerRowIndex + 5, sheet.getLastRowNum()); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isBlankRow(row)) continue;

            Map<String, Object> rowData = new LinkedHashMap<>();
            for (String header : headers) {
                Integer idx = columnIndexes.get(header);
                if (idx != null) {
                    Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    rowData.put(header, cell == null ? "" : formatter.formatCellValue(cell));
                }
            }
            if (!rowData.isEmpty()) sampleRows.add(rowData);
        }

        // Quick validation scan
        ValidationMetrics metrics = scanValidation(sheet, headerRowIndex, headers, columnIndexes, layoutType);

        return new SalaryImportPreview(
            dataRowCount,
            metrics.validRows(),
            metrics.errorRows(),
            metrics.warningRows(),
            layoutType.name(),
            headers,
            suggestedMappings,
            sampleRows,
            metrics.errors(),
            metrics.warnings()
        );
    }

    /**
     * Quick validation scan without full processing.
     */
    private ValidationMetrics scanValidation(Sheet sheet, int headerRowIndex, List<String> headers,
            Map<String, Integer> columnIndexes, LayoutType layoutType) {
        int validRows = 0, errorRows = 0, warningRows = 0;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        DataFormatter formatter = new DataFormatter();

        for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isBlankRow(row)) continue;

            boolean hasError = false;
            boolean hasWarning = false;

            // Check for at least one identity column
            boolean hasIdentity = false;
            for (String field : List.of("hrmsId", "aadhaar", "pan", "contact", "email", "pf", "nps", "gpf")) {
                String mapped = getMappedHeader(headers, field);
                if (mapped != null) {
                    Integer idx = columnIndexes.get(mapped);
                    if (idx != null) {
                        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().trim().isEmpty()) {
                            hasIdentity = true;
                            break;
                        }
                    }
                }
            }

            if (!hasIdentity) {
                hasError = true;
                errors.add("Row " + (i + 1) + ": No employee identifier (HRMS ID, Aadhaar, PAN, Contact, etc.)");
            }

            // Check amount columns based on layout
            if (layoutType == LayoutType.LONG) {
                String amountHeader = getMappedHeader(headers, "amount");
                if (amountHeader != null) {
                    Integer idx = columnIndexes.get(amountHeader);
                    if (idx != null) {
                        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell == null || cell.getCellType() == CellType.BLANK) {
                            hasWarning = true;
                            warnings.add("Row " + (i + 1) + ": Amount is blank");
                        } else if (!isValidAmount(cell)) {
                            hasError = true;
                            errors.add("Row " + (i + 1) + ": Invalid amount value");
                        }
                    }
                }
            } else {
                // WIDE - check at least one component column has value
                boolean hasComponentValue = false;
                for (String header : headers) {
                    String norm = normalizeHeader(header);
                    if (KNOWN_COMPONENT_CODES.contains(norm)) {
                        Integer idx = columnIndexes.get(header);
                        if (idx != null) {
                            Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                            if (cell != null && cell.getCellType() != CellType.BLANK && isValidAmount(cell)) {
                                try {
                                    double val = getNumericValue(cell);
                                    if (val > 0) { hasComponentValue = true; break; }
                                } catch (Exception e) {}
                            }
                        }
                    }
                }
                if (!hasComponentValue && !hasError) {
                    hasWarning = true;
                    warnings.add("Row " + (i + 1) + ": No salary component values found");
                }
            }

            if (hasError) errorRows++;
            else if (hasWarning) warningRows++;
            else validRows++;
        }

        return new ValidationMetrics(validRows, errorRows, warningRows, errors, warnings);
    }

    private String getMappedHeader(List<String> headers, String field) {
        // This would come from suggestedMappings in real use
        // For preview, we do a simple heuristic
        String[] fieldKeywords = switch (field) {
            case "hrmsId" -> new String[]{"HRMS_ID", "HRMS", "EMPLOYEE_CODE", "EMPLOYEE_ID"};
            case "amount" -> new String[]{"AMOUNT", "VALUE", "SALARY_AMOUNT"};
            default -> new String[]{};
        };
        for (String kw : fieldKeywords) {
            for (String h : headers) {
                if (normalizeHeader(h).equals(kw)) return h;
            }
        }
        return null;
    }

    private boolean isValidAmount(Cell cell) {
        if (cell == null) return false;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) type = cell.getCachedFormulaResultType();
        return type == CellType.NUMERIC || 
               (type == CellType.STRING && !cell.getStringCellValue().trim().isEmpty());
    }

    private double getNumericValue(Cell cell) {
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) type = cell.getCachedFormulaResultType();
        if (type == CellType.NUMERIC) return cell.getNumericCellValue();
        if (type == CellType.STRING) {
            String text = cell.getStringCellValue().trim().replace(",", "").replace("₹", "");
            return Double.parseDouble(text);
        }
        return 0;
    }

    private int countDataRows(Sheet sheet, int headerRowIndex) {
        int count = 0;
        for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !isBlankRow(row)) count++;
        }
        return count;
    }

    private List<String> readRowHeaders(Row row, DataFormatter formatter) {
        List<String> headers = new ArrayList<>();
        int lastCell = row.getLastCellNum();
        for (int i = 0; i < lastCell; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
            headers.add(value);
        }
        while (!headers.isEmpty() && headers.get(headers.size() - 1).isBlank()) {
            headers.remove(headers.size() - 1);
        }
        return headers;
    }

    private String normalizeHeader(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase();
        normalized = normalized.replaceAll("[^A-Z0-9]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return normalized;
    }

    private boolean isBlankRow(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ============================================================
    // Inner classes for structured analysis
    // ============================================================

    private enum LayoutType { WIDE, LONG }

    private record SheetAnalysis(
        int sheetIndex,
        String sheetName,
        int headerRowIndex,
        List<String> headers,
        LayoutType layoutType,
        Map<String, Integer> columnIndexes,
        Map<String, Object> suggestedMappings,
        int dataRows,
        int score
    ) {}

    private record ValidationMetrics(
        int validRows, int errorRows, int warningRows,
        List<String> errors, List<String> warnings
    ) {}

    public static class ParseOptions {
        private boolean skipHeaderDetection = false;
        private int forcedHeaderRow = -1;
        private LayoutType forcedLayout = null;

        public boolean isSkipHeaderDetection() { return skipHeaderDetection; }
        public void setSkipHeaderDetection(boolean skipHeaderDetection) { this.skipHeaderDetection = skipHeaderDetection; }
        public int getForcedHeaderRow() { return forcedHeaderRow; }
        public void setForcedHeaderRow(int forcedHeaderRow) { this.forcedHeaderRow = forcedHeaderRow; }
        public LayoutType getForcedLayout() { return forcedLayout; }
        public void setForcedLayout(LayoutType forcedLayout) { this.forcedLayout = forcedLayout; }
    }
}