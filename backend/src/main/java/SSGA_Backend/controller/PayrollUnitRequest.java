package SSGA_Backend.controller;

/**
 * Create / edit payload for a payroll unit.
 *
 * For create, parentGroupId, code, name and unitType are required.
 * For edit (PATCH), a null field means "leave unchanged".
 */
public record PayrollUnitRequest(
        Long parentGroupId,
        String code,
        String name,
        String unitType,
        String status,
        String verificationStatus,
        String verificationNote
) {
}
