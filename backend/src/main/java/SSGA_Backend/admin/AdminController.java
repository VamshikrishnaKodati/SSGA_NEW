package SSGA_Backend.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * System Administrator endpoints for user, role and scope management.
 *
 * <p>Every method also carries the same checks in {@link AdminUserService} and
 * {@link AdminRoleService}: the annotations are the route guard, the service is
 * the security boundary, and the service additionally enforces the rules an
 * annotation cannot express (self-escalation, last administrator, scope reach).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminUserService userService;
    private final AdminRoleService roleService;

    public AdminController(AdminUserService userService, AdminRoleService roleService) {
        this.userService = userService;
        this.roleService = roleService;
    }

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    @GetMapping("/users")
    @PreAuthorize(
            "hasRole('SYS_ADMIN') or hasAuthority('user.manage') "
            + "or hasAuthority('user.view') or hasAuthority('role.assign')"
    )
    public ResponseEntity<AdminDtos.UserPageResponse> listUsers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(userService.list(q, role, active, limit, offset));
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize(
            "hasRole('SYS_ADMIN') or hasAuthority('user.manage') "
            + "or hasAuthority('user.view') or hasAuthority('role.assign')"
    )
    public ResponseEntity<AdminDtos.UserResponse> getUser(@PathVariable long userId) {
        return ResponseEntity.ok(userService.get(userId));
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('SYS_ADMIN') or hasAuthority('user.manage') or hasAuthority('user.create')")
    public ResponseEntity<AdminDtos.UserResponse> createUser(
            @RequestBody AdminDtos.UserCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userService.create(request));
    }

    @PatchMapping("/users/{userId}")
    @PreAuthorize(
            "hasRole('SYS_ADMIN') or hasAuthority('user.manage') or hasAuthority('user.update')"
    )
    public ResponseEntity<AdminDtos.UserResponse> updateUser(
            @PathVariable long userId,
            @RequestBody AdminDtos.UserUpdateRequest request
    ) {
        return ResponseEntity.ok(userService.update(userId, request));
    }

    @PutMapping("/users/{userId}/scopes")
    @PreAuthorize(
            "hasRole('SYS_ADMIN') or hasAuthority('scope.bind') "
            + "or hasAuthority('role.assign')"
    )
    public ResponseEntity<AdminDtos.UserResponse> replaceScopes(
            @PathVariable long userId,
            @RequestBody AdminDtos.UserScopesRequest request
    ) {
        return ResponseEntity.ok(userService.replaceScopes(userId, request.scopes()));
    }

    @PostMapping("/users/{userId}/deactivate")
    @PreAuthorize("hasRole('SYS_ADMIN') or hasAuthority('user.manage') or hasAuthority('user.deactivate')")
    public ResponseEntity<AdminDtos.UserResponse> deactivate(@PathVariable long userId) {
        return ResponseEntity.ok(userService.setActive(userId, false));
    }

    @PostMapping("/users/{userId}/activate")
    @PreAuthorize("hasRole('SYS_ADMIN') or hasAuthority('user.manage') or hasAuthority('user.deactivate')")
    public ResponseEntity<AdminDtos.UserResponse> activate(@PathVariable long userId) {
        return ResponseEntity.ok(userService.setActive(userId, true));
    }

    @PostMapping("/users/{userId}/password")
    @PreAuthorize("hasRole('SYS_ADMIN') or hasAuthority('user.manage')")
    public ResponseEntity<Void> resetPassword(
            @PathVariable long userId,
            @RequestBody AdminDtos.PasswordRequest request
    ) {
        userService.resetPassword(userId, request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/overrides")
    @PreAuthorize("hasRole('SYS_ADMIN') or hasAuthority('user.manage') or hasAuthority('role.assign')")
    public ResponseEntity<AdminDtos.UserResponse> setOverride(
            @PathVariable long userId,
            @RequestBody AdminDtos.OverrideRequest request
    ) {
        return ResponseEntity.ok(userService.setOverride(userId, request));
    }

    // ------------------------------------------------------------------
    // Roles and permissions
    // ------------------------------------------------------------------

    @GetMapping("/roles")
    @PreAuthorize(
            "hasRole('SYS_ADMIN') or hasAuthority('role.assign') "
            + "or hasAuthority('user.manage')"
    )
    public ResponseEntity<List<AdminDtos.RoleResponse>> listRoles() {
        return ResponseEntity.ok(roleService.list());
    }

    @GetMapping("/permissions")
    @PreAuthorize(
            "hasRole('SYS_ADMIN') or hasAuthority('role.assign') "
            + "or hasAuthority('user.manage')"
    )
    public ResponseEntity<List<AdminDtos.ModuleResponse>> permissionCatalogue() {
        return ResponseEntity.ok(roleService.permissionCatalogue());
    }

    @GetMapping("/scopes")
    @PreAuthorize(
            "hasRole('SYS_ADMIN') or hasAuthority('scope.bind') "
            + "or hasAuthority('role.assign')"
    )
    public ResponseEntity<AdminDtos.ScopeCatalogue> scopeCatalogue() {
        return ResponseEntity.ok(roleService.scopeCatalogue());
    }

    @PutMapping("/roles/{roleId}/permissions")
    @PreAuthorize("hasRole('SYS_ADMIN') or hasAuthority('role.assign')")
    public ResponseEntity<AdminDtos.RoleResponse> replacePermissions(
            @PathVariable long roleId,
            @RequestBody AdminDtos.RolePermissionsRequest request
    ) {
        return ResponseEntity.ok(
                roleService.replacePermissions(roleId, request.permissions())
        );
    }
}