package SSGA_Backend.payroll;

public record PayrollGenerateRequest(
        Long periodId,
        Long divisionId,
        String runType
) {
}
