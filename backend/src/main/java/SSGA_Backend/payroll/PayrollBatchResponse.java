package SSGA_Backend.payroll;

import java.math.BigDecimal;
import java.util.List;

public record PayrollBatchResponse(
        long batchId,
        long periodId,
        String periodLabel,
        long divisionId,
        String divisionName,
        String runType,
        int runNo,
        String status,
        Long makerId,
        Long checkerId,
        int employeeCount,
        BigDecimal grossTotal,
        BigDecimal deductionTotal,
        BigDecimal netTotal,
        List<EmployeeTotals> employees,
        String message,
        long version
) {
    public record EmployeeTotals(
            long employeeId,
            String hrmsId,
            String name,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            int lineCount
    ) {
    }
}
