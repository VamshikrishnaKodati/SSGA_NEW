package SSGA_Backend.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Pure unit checks for the shared cell coercion used by validation, preview and
 * import. No database and no Spring context.
 */
class ImportCellsTest {

    @Test
    void amountTextStripsCurrencyAndGrouping() {
        assertEquals(new BigDecimal("1234.50"),
                ImportCells.parseAmountText("₹ 1,234.50"));
        assertEquals(new BigDecimal("1234.50"),
                ImportCells.parseAmountText("$1,234.50"));
        assertEquals(new BigDecimal("-1234"),
                ImportCells.parseAmountText("(1,234)"));
        assertNull(ImportCells.parseAmountText("not a number"));
        assertNull(ImportCells.parseAmountText("  "));
    }

    @Test
    void parseDateAcceptsTheSupportedFormats() {
        assertEquals(LocalDate.of(2024, 3, 12),
                ImportCells.parseDate("2024-03-12"));
        assertEquals(LocalDate.of(2024, 3, 12),
                ImportCells.parseDate("2024/03/12"));
        assertEquals(LocalDate.of(2024, 3, 12),
                ImportCells.parseDate("12/03/2024"));
        assertEquals(LocalDate.of(2024, 3, 12),
                ImportCells.parseDate("12-Mar-2024"));
        assertEquals(LocalDate.of(2024, 3, 12),
                ImportCells.parseDate("12 Mar 2024"));
        assertEquals(LocalDate.of(2024, 3, 12),
                ImportCells.parseDate("Mar 12, 2024"));
        assertNull(ImportCells.parseDate("not a date"));
        assertNull(ImportCells.parseDate(null));
    }

    @Test
    void blankRowDetectionIgnoresEmptyCells() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S");

            Row blank = sheet.createRow(0);
            blank.createCell(0, CellType.BLANK);
            assertTrue(ImportCells.isBlankRow(blank));

            Row filled = sheet.createRow(1);
            filled.createCell(0).setCellValue("Emp Code");
            assertFalse(ImportCells.isBlankRow(filled));
        }
    }

    @Test
    void amountReadsNumericAndStringCells() throws Exception {
        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = new XSSFWorkbook()) {
            Row row = workbook.createSheet("S").createRow(0);

            row.createCell(0).setCellValue(1500.5);
            assertEquals(new BigDecimal("1500.50"),
                    ImportCells.amount(row, 0));

            row.createCell(1).setCellValue("1,200");
            assertEquals(new BigDecimal("1200.00"),
                    ImportCells.amount(row, 1));

            assertNull(ImportCells.amount(row, 5));
            assertNull(ImportCells.text(row, 5, formatter));
        }
    }
}
