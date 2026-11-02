package SSGA_Backend.payroll;

public record PayrollPeriodResponse(
        long id,
        long organizationId,
        String organizationName,
        int year,
        int month,
        String label,
        String startDate,
        String endDate,
        String status
) {
}
