package SSGA_Backend.controller;

import java.util.List;

public record PayrollUnitDetailResponse(
        long id,
        long parentGroupId,
        String parentGroupName,
        String code,
        String name,
        String unitType,
        String status,
        String verificationStatus,
        String verificationNote,
        long employeeCount,
        List<EmployeeRef> employees
) {

    public record EmployeeRef(
            long id,
            String employeeCode,
            String hrmsId,
            String name,
            String designationName,
            String status
    ) {
    }
}
