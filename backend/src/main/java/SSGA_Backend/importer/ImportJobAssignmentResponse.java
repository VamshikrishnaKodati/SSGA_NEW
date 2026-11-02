
package SSGA_Backend.importer;

public record ImportJobAssignmentResponse(
        long id,
        long importJobId,
        long userId,
        String assignmentRole,
        Long divisionId,
        long assignedBy,
        boolean active,
        String assignedAt,
        String notes,
        String message
) {
}
