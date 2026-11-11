package SSGA_Backend.controller;

import java.time.LocalDate;

/**
 * Employee creation payload.
 *
 * <p>Statutory identifiers (pan / aadhaar / pf / nps / gpf / email) are stored
 * in {@code employee_identity_mappings}; {@code bankAccountNumber} and friends
 * are stored in {@code employee_bank_accounts}. All of these are optional.
 */
public record CreateEmployeeRequest(
        String hrmsId,
        String name,
        long divisionId,
        Long designationId,
        Long gradeId,
        String contact,
        String placeOfWork,
        LocalDate dateOfBirth,
        LocalDate dateOfJoining,
        String employmentType,
        String status,
        String payScale,
        String department,
        Long circleId,
        Long wardId,
        Long payrollUnitId,
        String pan,
        String aadhaar,
        String pf,
        String nps,
        String gpf,
        String email,
        String bankName,
        String branchName,
        String bankAccountNumber,
        String ifscCode
) {
}
