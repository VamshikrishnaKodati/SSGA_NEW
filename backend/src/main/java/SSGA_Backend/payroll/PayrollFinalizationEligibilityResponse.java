package SSGA_Backend.payroll;

public record PayrollFinalizationEligibilityResponse(
        boolean eligible,
        String status,
        long version,
        String reason
) {
}
