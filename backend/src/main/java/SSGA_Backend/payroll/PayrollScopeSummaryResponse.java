package SSGA_Backend.payroll;

public record PayrollScopeSummaryResponse(
        long totalEmployees,
        long activeEligibleEmployees,
        long inactiveOrExcludedEmployees,
        Long lastBatchId,
        String lastBatchStatus,
        String lastBatchRunType
) {
}
