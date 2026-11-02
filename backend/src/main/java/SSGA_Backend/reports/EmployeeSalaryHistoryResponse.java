package SSGA_Backend.reports;

import java.math.BigDecimal;
import java.util.List;

public record EmployeeSalaryHistoryResponse(
        long employeeId,
        String employeeCode,
        String name,
        List<HistoryRow> rows
) {
    public record HistoryRow(
                long recordId,
                String period,
                String recordSource,
                String batchStatus,
                String componentCode,
                String componentName,
                BigDecimal amount,
                String sourceStatus,
                BigDecimal gross,
                BigDecimal deductions,
                BigDecimal net
    ) {
    }
}
