package SSGA_Backend.controller;

import java.math.BigDecimal;
import java.util.List;

/**
 * An employee's salary information.
 *
 * <p>{@code components} lists every active master component (all 42 approved
 * codes) split by category. {@code amount} is the employee's effective
 * configured value from {@code employee_salary_values}, or {@code null} when no
 * value is configured for that employee (which the payroll calculation treats as
 * zero). This distinguishes a configured zero from an unconfigured component.
 * Totals mirror the payroll calculation (gross = sum of EARNING, net = gross -
 * deductions). {@code latestRun} is the most recent processed payroll
 * transaction for the employee, when one exists.
 */
public record EmployeeSalaryResponse(
        long employeeId,
        String employeeCode,
        String name,
        String asOf,
        BigDecimal basicPay,
        BigDecimal totalEarnings,
        BigDecimal gross,
        BigDecimal totalDeductions,
        BigDecimal net,
        int configuredCount,
        int masterCount,
        String note,
        List<Component> components,
        LatestRun latestRun
) {
    public record Component(
            long componentId,
            String componentCode,
            String componentName,
            String category,
            BigDecimal amount
    ) {
    }

    public record LatestRun(
            String period,
            String status,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net
    ) {
    }
}
