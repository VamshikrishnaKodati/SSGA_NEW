package SSGA_Backend.payroll;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record PayrollBatchDetailResponse(
        long batchId,
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
        List<TransactionDetail> transactions,
        long version,
        String wingName,
        OffsetDateTime createdAt,
        OffsetDateTime submittedAt,
        String makerName,
        String checkerName,
        OffsetDateTime finalizedAt
) {
    public record TransactionDetail(
            long transactionId,
            String employeeCode,
            String hrmsId,
            String name,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            String status,
            List<Line> lines
    ) {
    }

    public record Line(
            long componentId,
            String componentCode,
            String componentName,
            String category,
            BigDecimal amount
    ) {
    }
}
