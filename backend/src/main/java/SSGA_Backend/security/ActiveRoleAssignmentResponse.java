package SSGA_Backend.security;

public record ActiveRoleAssignmentResponse(
        long assignmentId,
        String roleCode,
        String roleName,
        String maxScope,
        Long wingId,
        String wingName,
        Long divisionId,
        String divisionName,
        String scopeLabel
) {
}
