package SSGA_Backend.report;

import java.math.BigDecimal;

public record DivisionSummary(
        long divisionId,
        String divisionName,
        String wingName,
        int employeeCount,
        BigDecimal gross,
        BigDecimal deductions,
        BigDecimal net
) {
}
