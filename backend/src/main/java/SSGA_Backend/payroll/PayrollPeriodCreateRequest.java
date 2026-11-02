package SSGA_Backend.payroll;

public record PayrollPeriodCreateRequest(
        Long organizationId,
        Integer year,
        Integer month
) {
}
