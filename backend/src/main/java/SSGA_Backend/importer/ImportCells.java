package SSGA_Backend.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;

/**
 * Side-effect-free Apache POI helpers shared by the salary-import pipeline.
 * Validation, preview and import read the same workbook, so blank-row
 * detection, currency/number cleaning and the accepted date formats live here
 * once instead of drifting across three services.
 */
final class ImportCells {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("uuuu/M/d"),
            DateTimeFormatter.ofPattern("uuuu/MM/dd"),
            DateTimeFormatter.ofPattern("uuuu-M-d"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd"),
            DateTimeFormatter.ofPattern("uuuu.M.d"),
            DateTimeFormatter.ofPattern("uuuu.MM.dd"),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("dd.MM.uuuu"),
            DateTimeFormatter.ofPattern("M/d/uuuu"),
            DateTimeFormatter.ofPattern("MM/dd/uuuu"),
            DateTimeFormatter.ofPattern("M-d-uuuu"),
            DateTimeFormatter.ofPattern("MM-dd-uuuu"),
            DateTimeFormatter.ofPattern("M.d.uuuu"),
            DateTimeFormatter.ofPattern("MM.dd.uuuu"),
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH)
    );

    private ImportCells() {
    }

    static boolean isBlankRow(Row row) {
        short lastCell = row.getLastCellNum();

        if (lastCell < 0) {
            return true;
        }

        for (int index = 0; index < lastCell; index++) {
            Cell cell = row.getCell(
                    index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

            if (cell != null
                    && cell.getCellType() != CellType.BLANK
                    && !cell.toString().trim().isEmpty()) {
                return false;
            }
        }

        return true;
    }

    static boolean isFormula(Row row, Integer columnIndex) {
        if (columnIndex == null) {
            return false;
        }

        Cell cell = row.getCell(
                columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        return cell != null && cell.getCellType() == CellType.FORMULA;
    }

    /** Trimmed display value, or {@code null} for missing/formula/blank cells. */
    static String text(Row row, Integer columnIndex, DataFormatter formatter) {
        if (columnIndex == null) {
            return null;
        }

        Cell cell = row.getCell(
                columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null || cell.getCellType() == CellType.FORMULA) {
            return null;
        }

        String value = formatter.formatCellValue(cell).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Lenient nullable amount: strips currency symbols and grouping separators
     * and treats {@code (1,234)} as {@code -1234}. Returns {@code null} for
     * missing, formula or unparseable cells (callers that need a reason apply
     * their own rules).
     */
    static BigDecimal amount(Row row, Integer columnIndex) {
        if (columnIndex == null) {
            return null;
        }

        Cell cell = row.getCell(
                columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null
                || cell.getCellType() == CellType.BLANK
                || cell.getCellType() == CellType.FORMULA) {
            return null;
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue())
                    .setScale(2, RoundingMode.HALF_UP);
        }

        if (cell.getCellType() == CellType.STRING) {
            BigDecimal parsed = parseAmountText(cell.getStringCellValue());
            return parsed == null
                    ? null
                    : parsed.setScale(2, RoundingMode.HALF_UP);
        }

        return null;
    }

    /** Cleans a currency/grouping-formatted string; {@code null} when invalid. */
    static BigDecimal parseAmountText(String raw) {
        if (raw == null) {
            return null;
        }

        String text = raw.replace("\u00A0", "")
                .replace(",", "")
                .replace("\u20B9", "")
                .replace("$", "")
                .trim();

        if (text.isEmpty()) {
            return null;
        }

        if (text.startsWith("(") && text.endsWith(")")) {
            text = "-" + text.substring(1, text.length() - 1);
        }

        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Parses the cell as a date, else the {@code defaultText}, else null. */
    static LocalDate date(Row row, Integer columnIndex, String defaultText) {
        if (columnIndex != null) {
            Cell cell = row.getCell(
                    columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

            if (cell != null && cell.getCellType() != CellType.FORMULA) {
                if (cell.getCellType() == CellType.NUMERIC
                        && DateUtil.isCellDateFormatted(cell)) {
                    return DateUtil.getLocalDateTime(
                            cell.getNumericCellValue()).toLocalDate();
                }

                if (cell.getCellType() == CellType.STRING) {
                    LocalDate parsed = parseDate(
                            cell.getStringCellValue().trim());

                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }

        return parseDate(defaultText);
    }

    static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // Try the next explicit date format.
            }
        }

        return null;
    }
}
