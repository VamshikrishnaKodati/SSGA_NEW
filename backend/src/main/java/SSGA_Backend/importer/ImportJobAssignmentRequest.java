
package SSGA_Backend.importer;

public record ImportJobAssignmentRequest(
        Long userId,
        String assignmentRole,
        Long divisionId,
        String notes
) {
}
