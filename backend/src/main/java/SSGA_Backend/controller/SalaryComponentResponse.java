package SSGA_Backend.controller;

import java.math.BigDecimal;

/**
 * A single entry from the salary component master
 * ({@code salary_components}), used to render the catalogue of earnings and
 * deductions.
 */
public record SalaryComponentResponse(
        long id,
        String componentCode,
        String componentName,
        String category,
        String calculationType,
        BigDecimal rate,
        Integer displayOrder
) {}
