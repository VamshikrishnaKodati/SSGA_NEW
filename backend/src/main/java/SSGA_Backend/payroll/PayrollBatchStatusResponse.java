package SSGA_Backend.payroll;

public record PayrollBatchStatusResponse(
        long batchId,
        String status,
        Long makerId,
        Long checkerId,
        int verifiedCount,
        int totalCount,
        String message
) {
}
