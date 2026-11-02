package SSGA_Backend.importer;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SalaryExcelParser {

    private static final int EMPLOYEE_METADATA_COLUMNS = 5;
    private static final String[] SUPPORTED_FILE_EXTENSIONS = {
            ".xlsx",
            ".xlsm",
            ".xls"
    };

    private static final String[] EXPECTED_COMPONENT_CODES = {

            // Earnings
            "BASIC_PAY",
            "T_SPL_INCREMENT",
            "FP_PP",
            "SPL_PAY_G_MITRA",
            "DA_33_67",
            "HRA_24",
            "ADDL_HRA",
            "CCA",
            "PETROL_AUTO_ALLOW",
            "WASHING_ALLOW",
            "BOARD_FORMATION_DAY",
            "IR_5_2023",
            "DA_ARREARS_26_39",
            "DA_ARREARS_30_03",
            "DA_ARREARS_33_67",

            // Deductions
            "GPF_EPF",
            "COURT_ATCH",
            "PENSION_SCHEME",
            "TSGLIF",
            "GIS",
            "PRC_2020_NPS",
            "ELECT_CHAR",
            "FESTIVAL_ADV",
            "EDCN_ADV",
            "VEHICLE_ADV",
            "MOPED_ADV",
            "EXCESS_SALARY_RECOVERY",
            "EPF_12_PERCENT",
            "SBI_LIFE_INSURANCE",
            "ONE_DAY_SALARY",
            "MARRIAGE_LOAN",
            "MGB_AN",
            "PROF_TAX",
            "INCOME_TAX",
            "EPF_PRC_ARREARS",
            "PRC_EXCESS_RECOVERY",
            "MGB_PC",
            "TELANGANA_HARITHA_NIDHI",
            "CCA_RECOVERY_DARUSSALAM",
            "SBH_ANAND_NAGAR",
            "FLAG_DAY_2025",
            "LIC"
    };

    public SalaryImportPreview parse(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Excel file is required");
        }

        String filename = file.getOriginalFilename();
        String normalizedFilename = filename == null ? "" : filename.toLowerCase();

        boolean supportedExtension = false;
        for (String extension : SUPPORTED_FILE_EXTENSIONS) {
            if (normalizedFilename.endsWith(extension)) {
                supportedExtension = true;
                break;
            }
        }

        if (!supportedExtension) {
            throw new IllegalArgumentException(
                    "Only Excel files (.xls, .xlsx, .xlsm) are supported"
            );
        }

        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException(
                        "Excel workbook contains no sheets"
                );
            }

            Sheet sheet = workbook.getSheet("Salary_Values_Wide");

            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }

            if (sheet.getPhysicalNumberOfRows() < 5) {
                throw new IllegalArgumentException(
                        "Excel sheet does not contain enough data"
                );
            }

            Row headerRow = sheet.getRow(3);

            if (headerRow == null) {
                throw new IllegalArgumentException(
                        "Header row was not found"
                );
            }

            validateHeaders(headerRow);

            int totalRows = 0;
            int validRows = 0;
            int errorRows = 0;

            for (int rowIndex = 4;
                 rowIndex <= sheet.getLastRowNum();
                 rowIndex++) {

                Row row = sheet.getRow(rowIndex);

                if (row == null || isBlankRow(row)) {
                    continue;
                }

                totalRows++;

                try {

                    validateEmployeeCode(row, rowIndex);
                    validateHrmsId(row, rowIndex);
                    validateEmployeeName(row, rowIndex);
                    validateDivisionId(row, rowIndex);

                    for (int componentIndex = 0;
                         componentIndex < EXPECTED_COMPONENT_CODES.length;
                         componentIndex++) {

                        int columnIndex =
                                EMPLOYEE_METADATA_COLUMNS + componentIndex;

                        Cell cell = row.getCell(
                                columnIndex,
                                Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                        );

                        validateAmount(
                                cell,
                                rowIndex,
                                EXPECTED_COMPONENT_CODES[componentIndex]
                        );
                    }

                    validRows++;

                } catch (IllegalArgumentException ex) {
                    errorRows++;
                }
            }

            return new SalaryImportPreview(
                    totalRows,
                    validRows,
                    errorRows,
                    "Excel parsed successfully"
            );
        }
    }

    private void validateHeaders(Row headerRow) {

        Set<String> headers = new HashSet<>();

        for (int i = 0;
             i < EMPLOYEE_METADATA_COLUMNS + EXPECTED_COMPONENT_CODES.length;
             i++) {

            Cell cell = headerRow.getCell(
                    i,
                    Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
            );

            if (cell == null) {
                throw new IllegalArgumentException(
                        "Missing header at column " + (i + 1)
                );
            }

            String value = normalizeHeader(cell.toString());

            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "Blank header at column " + (i + 1)
                );
            }

            if (headers.contains(value)) {
                throw new IllegalArgumentException(
                        "Duplicate header detected: " + value
                );
            }

            headers.add(value);
        }

        for (String componentCode : EXPECTED_COMPONENT_CODES) {

            if (!headers.contains(normalizeHeader(componentCode))) {
                throw new IllegalArgumentException(
                        "Missing salary component column: "
                                + componentCode
                );
            }
        }
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim().toUpperCase();
        normalized = normalized.replaceAll("[^A-Z0-9]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return normalized;
    }

    private void validateEmployeeCode(
            Row row,
            int rowIndex
    ) {

        Cell cell = row.getCell(
                0,
                Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );

        if (cell == null ||
                cell.toString().trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Employee Code missing at row "
                            + (rowIndex + 1)
            );
        }
    }

    private void validateHrmsId(
            Row row,
            int rowIndex
    ) {

        Cell cell = row.getCell(
                1,
                Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );

        if (cell == null ||
                cell.toString().trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "HRMS ID missing at row "
                            + (rowIndex + 1)
            );
        }
    }

    private void validateEmployeeName(
            Row row,
            int rowIndex
    ) {

        Cell cell = row.getCell(
                2,
                Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );

        if (cell == null ||
                cell.toString().trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Employee name missing at row "
                            + (rowIndex + 1)
            );
        }
    }

    private void validateDivisionId(
            Row row,
            int rowIndex
    ) {

        Cell cell = row.getCell(
                3,
                Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );

        if (cell == null) {
            throw new IllegalArgumentException(
                    "Division ID missing at row "
                            + (rowIndex + 1)
            );
        }

        double divisionId = parseNumberCellValue(cell, "Division ID", rowIndex);
        if (Double.isNaN(divisionId) || Double.isInfinite(divisionId)) {
            throw new IllegalArgumentException(
                    "Division ID must be numeric at row "
                            + (rowIndex + 1)
            );
        }
    }

    private void validateAmount(
            Cell cell,
            int rowIndex,
            String componentCode
    ) {

        // Blank salary component is treated as 0.00.
        if (cell == null) {
            return;
        }

        double amount = parseNumberCellValue(
                cell,
                componentCode,
                rowIndex
        );

        if (Double.isNaN(amount) ||
                Double.isInfinite(amount)) {

            throw new IllegalArgumentException(
                    "Invalid numeric amount for "
                            + componentCode
            );
        }

        if (amount < 0) {
            throw new IllegalArgumentException(
                    "Negative amount is not allowed for "
                            + componentCode
                            + " at row "
                            + (rowIndex + 1)
            );
        }
    }

    private double parseNumberCellValue(
            Cell cell,
            String fieldName,
            int rowIndex
    ) {

        CellType cellType = cell.getCellType();

        if (cellType == CellType.FORMULA) {
            cellType = cell.getCachedFormulaResultType();
        }

        if (cellType == CellType.BLANK) {
            return 0.0;
        }

        if (cellType == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }

        if (cellType == CellType.BOOLEAN) {
            return cell.getBooleanCellValue() ? 1.0 : 0.0;
        }

        if (cellType == CellType.ERROR) {
            throw new IllegalArgumentException(
                    "Invalid numeric value for "
                            + fieldName
                            + " at row "
                            + (rowIndex + 1)
            );
        }

        if (cellType == CellType.STRING) {
            String value = cell.getStringCellValue().trim();
            if (value.isEmpty()) {
                return 0.0;
            }

            return parseStringNumberValue(value, fieldName, rowIndex);
        }

        throw new IllegalArgumentException(
                "Invalid numeric value for "
                        + fieldName
                        + " at row "
                        + (rowIndex + 1)
        );
    }

    private double parseStringNumberValue(
            String value,
            String fieldName,
            int rowIndex
    ) {
        String normalizedValue = value
                .replace("\u00A0", "")
                .replace(",", "")
                .replace("₹", "")
                .replace("$", "")
                .replace("€", "")
                .replace("(", "-")
                .replace(")", "")
                .trim();

        if (normalizedValue.isEmpty()) {
            return 0.0;
        }

        boolean percentageValue = normalizedValue.endsWith("%");
        if (percentageValue) {
            normalizedValue = normalizedValue.substring(
                    0,
                    normalizedValue.length() - 1
            );
        }

        normalizedValue = normalizedValue.replaceAll("[^0-9.\\-+eE]", "");

        if (normalizedValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid numeric value for "
                            + fieldName
                            + " at row "
                            + (rowIndex + 1)
            );
        }

        try {
            double parsedValue = Double.parseDouble(normalizedValue);
            return percentageValue ? parsedValue / 100.0 : parsedValue;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Invalid numeric value for "
                            + fieldName
                            + " at row "
                            + (rowIndex + 1)
            );
        }
    }

    private boolean isBlankRow(Row row) {

        for (int i = 0; i < row.getLastCellNum(); i++) {

            Cell cell = row.getCell(
                    i,
                    Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
            );

            if (cell != null &&
                    cell.getCellType() != CellType.BLANK &&
                    !cell.toString().trim().isEmpty()) {

                return false;
            }
        }

        return true;
    }
}