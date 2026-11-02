package SSGA_Backend.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class PayrollReportServiceTest {

    @Test
    void writesHeaderWhenThereAreNoRows() {
        assertEquals("wing,division,employees,gross,deductions,net\r\n",
                PayrollReportService.toCsv(List.of()));
    }

    @Test
    void escapesCommasAndWritesPlainDecimals() {
        DivisionSummaryRow row = new DivisionSummaryRow(
                7L, "O&M, Zone-1", 5L, "Engineering",
                1, new BigDecimal("63834.50"),
                new BigDecimal("21514.14"), new BigDecimal("42320.36")
        );

        assertEquals(
                "wing,division,employees,gross,deductions,net\r\n"
                        + "Engineering,\"O&M, Zone-1\",1,63834.50,21514.14,42320.36\r\n",
                PayrollReportService.toCsv(List.of(row))
        );
    }
}
