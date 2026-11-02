package SSGA_Backend.controller;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Manual salary component value for one employee.
 *
 * <p>{@code amount} is required and must be zero or greater. {@code effectiveFrom}
 * defaults to today when omitted. Setting a value for the same effective date
 * updates the existing row rather than creating a duplicate.
 */
public record UpdateEmployeeSalaryRequest(
        BigDecimal amount,
        LocalDate effectiveFrom
) {
}
