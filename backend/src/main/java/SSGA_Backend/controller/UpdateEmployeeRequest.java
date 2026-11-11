package SSGA_Backend.controller;

import java.time.LocalDate;

/**
 * Editable employee master fields.
 *
 * <p>{@code divisionId} (a transfer, managed through employee_transfers) is
 * intentionally not editable here.
 * {@code hrmsId} is only applied when a non-blank value is supplied so a
 * temporary identifier is never silently cleared.
 *
 * <p>For the optional circle / ward / identity / bank fields the convention is:
 * {@code null} = leave unchanged, blank string = clear, value = set.
 */
public record UpdateEmployeeRequest(
        String name,
        String hrmsId,
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
) {}
