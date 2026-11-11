package SSGA_Backend.controller;

import java.time.LocalDate;
import java.util.List;

/**
 * Full employee master projection.
 *
 * <p>Exposes the columns that exist on {@code employees} plus joined master
 * names (division / wing / designation / grade / circle / ward). Statutory and
 * personal identifiers (PAN, Aadhaar, PF, NPS, GPF, EMAIL) come from
 * {@code employee_identity_mappings}; bank details come from
 * {@code employee_bank_accounts}. Both collections are populated only for the
 * single-employee detail lookup and are masked unless the caller holds a
 * privileged grant (see {@code EmployeeService}).
 */
public record EmployeeResponse(
        long id,
        String hrmsId,
        String name,
        long divisionId,
        String divisionName,
        Long wingId,
        String wingName,
        String organizationName,
        Long designationId,
        String designationName,
        Long gradeId,
        String gradeName,
        String contact,
        String placeOfWork,
        LocalDate dateOfBirth,
        LocalDate dateOfJoining,
        String employmentType,
        String status,
        String payScale,
        String department,
        Long circleId,
        String circleName,
        Long wardId,
        String wardName,
        Long payrollUnitId,
        String payrollUnitName,
        List<Identity> identities,
        List<BankAccount> bankAccounts
) {
    public record Identity(
            String identityType,
            String identityValue,
            boolean masked
    ) {
    }

    public record BankAccount(
            long id,
            String bankName,
            String branchName,
            String accountNumber,
            String ifscCode,
            boolean primary,
            String status
    ) {
    }
}
