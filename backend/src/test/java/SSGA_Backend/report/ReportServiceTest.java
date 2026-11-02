package SSGA_Backend.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReportServiceTest {

    @Test
    void csvQuotesCommasAndAppendsTotalsRow() {
        ReportService service = new ReportService(null, null);

        PayrollSummaryResponse summary = new PayrollSummaryResponse(
                "2026-10",
                "ALL",
                List.of(new DivisionSummary(
                        7L, "Water Works, Zone 1", "Engineering", 3,
                        new BigDecimal("1000.00"),
                        new BigDecimal("250.00"),
                        new BigDecimal("750.00")
                )),
                new DivisionSummary(
                        0L, "TOTAL", null, 3,
                        new BigDecimal("1000.00"),
                        new BigDecimal("250.00"),
                        new BigDecimal("750.00")
                )
        );

        String csv = service.toCsv(summary);

        assertTrue(csv.startsWith(
                "Wing,Division,Employees,Gross,Deductions,Net\r\n"),
                "unexpected header: " + csv);
        assertTrue(csv.contains("\"Water Works, Zone 1\""),
                "comma-containing field must be quoted: " + csv);
        assertTrue(csv.contains(",TOTAL,3,1000.00,250.00,750.00\r\n"),
                "totals row missing: " + csv);
    }
}
