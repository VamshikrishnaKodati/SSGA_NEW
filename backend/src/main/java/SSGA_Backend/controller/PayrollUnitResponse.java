package SSGA_Backend.controller;

public record PayrollUnitResponse(
        long id,
        long parentGroupId,
        String parentGroupName,
        String code,
        String name,
        String unitType,
        String status,
        String verificationStatus,
        String verificationNote,
        long employeeCount
) {
}
