package SSGA_Backend.importer;

public record ImportJobAssignableUserResponse(
        long userId,
        String username,
        String displayName
) {
}