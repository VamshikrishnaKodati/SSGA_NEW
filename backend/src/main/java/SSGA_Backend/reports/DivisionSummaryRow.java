package SSGA_Backend.reports;

import java.math.BigDecimal;

public record DivisionSummaryRow(
        long divisionId,
        String divisionName,
        Long wingId,
        String wingName,
        int employeeCount,
        BigDecimal gross,
        BigDecimal deductions,
        BigDecimal net
) {
}
