package SSGA_Backend.admin;

import java.util.List;

/**
 * Request and response shapes for the System Administrator workspace.
 *
 * <p>Kept in one file because these types are only ever exchanged between the
 * admin controllers and the admin UI.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** One role assignment plus the organizational scope it applies to. */
    public record UserScope(long roleId, Long wingId, Long divisionId) {
    }

    public record UserCreateRequest(
            String username,
            String displayName,
            String email,
            String employeeCode,
            String password,
            List<UserScope> scopes
    ) {
    }

    public record UserUpdateRequest(String displayName, String email, String employeeCode) {
    }

    public record UserScopesRequest(List<UserScope> scopes) {
    }

    public record PasswordRequest(String password) {
    }

    public record OverrideRequest(long permissionId, boolean granted, String reason) {
    }

    public record Assignment(
            long scopeId,
            String roleCode,
            String roleName,
            String maxScope,
            Long wingId,
            String wingCode,
            Long divisionId,
            String divisionCode
    ) {
    }

    public record UserResponse(
            long id,
            String username,
            String displayName,
            String email,
            String employeeCode,
            boolean active,
            boolean mustChangePassword,
            boolean mfaEnabled,
            boolean systemAdministrator,
            List<Assignment> assignments,
            List<String> effectivePermissions,
            List<PermissionOverride> overrides
    ) {
    }

    /** One administrator grant or denial recorded against a single user. */
    public record PermissionOverride(
            long permissionId,
            String code,
            boolean granted,
            String reason
    ) {
    }

    public record UserPageResponse(List<UserResponse> users, long total) {
    }

    public record RoleResponse(
            long id,
            String code,
            String name,
            String maxScope,
            boolean active,
            boolean systemRole,
            List<String> permissions
    ) {
    }

    public record PermissionResponse(
            long id,
            String code,
            String module,
            String description,
            List<String> roles
    ) {
    }

    public record RolePermissionsRequest(List<String> permissions) {
    }

    public record ModuleResponse(String module, List<PermissionResponse> permissions) {
    }

    public record ScopeOption(long id, String code, String name, Long wingId, String wingCode) {
    }

    /**
     * Everything an administrator may assign. The service-level scope guard, not
     * this list, decides whether a given assignment is allowed.
     */
    public record ScopeCatalogue(List<ScopeOption> wings, List<ScopeOption> divisions) {
    }
}