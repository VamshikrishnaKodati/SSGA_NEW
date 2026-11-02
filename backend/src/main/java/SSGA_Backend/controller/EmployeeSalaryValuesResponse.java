package SSGA_Backend.controller;

import java.math.BigDecimal;
import java.util.Map;

/**
 * An employee's configured salary component amounts, keyed by component code.
 *
 * <p>Used by the Employee master table to render all 42 approved components as
 * columns without issuing one {@code /salary} request per employee. Only
 * components with a value effective on the requested date are present, so a
 * missing key means "not configured" (distinct from a configured zero).
 */
public record EmployeeSalaryValuesResponse(
        long employeeId,
        Map<String, BigDecimal> values
) {
}
