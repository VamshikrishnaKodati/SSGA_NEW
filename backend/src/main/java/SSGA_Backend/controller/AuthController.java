package SSGA_Backend.controller;

import java.util.List;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import SSGA_Backend.security.ActiveRoleAssignmentResponse;
import SSGA_Backend.security.ActiveRoleAssignmentService;
import SSGA_Backend.security.SsgaPrincipal;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final ActiveRoleAssignmentService roleAssignmentService;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final boolean devMode;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            ActiveRoleAssignmentService roleAssignmentService,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            @Value("${ssga.auth.dev-mode:false}") boolean devMode
    ) {

        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.roleAssignmentService = roleAssignmentService;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.devMode = devMode;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        try {
            Authentication authentication =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    request.username(),
                                    request.password()
                            )
                    );

            // Create session and protect against session fixation
            httpRequest.getSession(true);
            httpRequest.changeSessionId();

            // Create authenticated security context
            SecurityContext context =
                    SecurityContextHolder.createEmptyContext();

            context.setAuthentication(authentication);

            SecurityContextHolder.setContext(context);

            // Save authentication in HTTP session
            securityContextRepository.saveContext(
                    context,
                    httpRequest,
                    httpResponse
            );

            SsgaPrincipal user =
                    (SsgaPrincipal) authentication.getPrincipal();

            return ResponseEntity.ok(response(user));

        } catch (BadCredentialsException e) {

            return ResponseEntity
                    .status(401)
                    .body("Invalid username or password");
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(
            @RequestBody ForgotPasswordRequest request
    ) {

        String username = request.username();
        if (username == null || username.isBlank()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Username is required.");
        }

        // Look up the user without revealing whether the account exists.
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM public.users WHERE username = ? AND is_active = true AND auth_provider = 'LOCAL'",
                Long.class,
                username
        );

        if (userId != null) {

            String token = java.util.UUID.randomUUID().toString();
            java.time.Instant expiresAt =
                    java.time.Instant.now().plusSeconds(3600);

            jdbcTemplate.update(
                    """
                    UPDATE public.users
                    SET password_reset_token = ?,
                        password_reset_expires_at = ?,
                        updated_at = now()
                    WHERE id = ?
                    """,
                    token,
                    java.sql.Timestamp.from(expiresAt),
                    userId
            );

            if (devMode) {
                return ResponseEntity.ok(
                        new ForgotPasswordResponse(
                                true,
                                "Reset token issued (dev mode).",
                                token
                        )
                );
            }
        }

        // Always return a generic success message to avoid user enumeration.
        return ResponseEntity.ok(
                new ForgotPasswordResponse(
                        true,
                        "If an active local account exists for this username, a password reset token has been issued.",
                        null
                )
        );
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(
            @RequestBody ResetPasswordRequest request
    ) {

        String token = request.token();
        String newPassword = request.newPassword();

        if (token == null || token.isBlank()
                || newPassword == null || newPassword.isBlank()) {

            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Token and new password are required.");
        }

        var row = jdbcTemplate.queryForObject(
                """
                SELECT id, password_reset_expires_at
                FROM public.users
                WHERE password_reset_token = ?
                  AND password_reset_expires_at > now()
                """,
                (rs, rowNum) -> new Object[]{
                        rs.getLong("id"),
                        rs.getTimestamp("password_reset_expires_at")
                },
                token
        );

        if (row == null) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Invalid or expired reset token.");
        }

        Long userId = (Long) row[0];

        String hashed = passwordEncoder.encode(newPassword);

        jdbcTemplate.update(
                """
                UPDATE public.users
                SET password_hash = ?,
                    password_reset_token = NULL,
                    password_reset_expires_at = NULL,
                    must_change_password = false,
                    failed_attempts = 0,
                    locked_until = NULL,
                    updated_at = now()
                WHERE id = ?
                """,
                hashed,
                userId
        );

        return ResponseEntity.ok(
                new ResetPasswordResponse(
                        true,
                        "Password has been reset successfully. You can now sign in with your new password."
                )
        );
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {

        if (authentication == null ||
                !(authentication.getPrincipal() instanceof SsgaPrincipal user)) {

            return ResponseEntity
                    .status(401)
                    .body("Not authenticated");
        }

        return ResponseEntity.ok(response(user));
    }

    @PostMapping("/active-assignment")
    public ResponseEntity<LoginResponse> activateAssignment(
            @RequestBody ActivateAssignmentRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SsgaPrincipal currentUser)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        List<String> authorities = roleAssignmentService.findAuthorities(
                currentUser.getUserId(),
                request.assignmentId()
        );
        if (authorities.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The selected role and scope assignment is not active for this account."
            );
        }

        boolean listed = roleAssignmentService.findAssignments(currentUser.getUserId())
                .stream()
                .anyMatch(assignment -> assignment.assignmentId() == request.assignmentId());
        if (!listed) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The selected role and scope assignment is not active for this account."
            );
        }

        SsgaPrincipal activeUser = new SsgaPrincipal(
                currentUser.getUserId(),
                currentUser.getUsername(),
                currentUser.getPassword(),
                currentUser.isEnabled(),
                currentUser.isAccountNonLocked(),
                authorities.stream().map(SimpleGrantedAuthority::new).toList(),
                request.assignmentId()
        );
        Authentication activeAuthentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        activeUser,
                        null,
                        activeUser.getAuthorities()
                );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(activeAuthentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return ResponseEntity.ok(response(activeUser));
    }

    private LoginResponse response(SsgaPrincipal user) {
        List<ActiveRoleAssignmentResponse> assignments =
                roleAssignmentService.findAssignments(user.getUserId());
        ActiveRoleAssignmentResponse activeAssignment = assignments.stream()
                .filter(assignment -> Objects.equals(
                        assignment.assignmentId(),
                        user.getActiveRoleScopeId()
                ))
                .findFirst()
                .orElse(null);

        List<String> authorities = activeAssignment == null
                ? List.of()
                : user.getAuthorities().stream()
                        .map(authority -> authority.getAuthority())
                        .toList();

        return new LoginResponse(
                user.getUserId(),
                user.getUsername(),
                authorities,
                activeAssignment == null ? null : activeAssignment.assignmentId(),
                activeAssignment,
                assignments
        );
    }

    public record LoginRequest(
            String username,
            String password
    ) {}

    public record ActivateAssignmentRequest(long assignmentId) {}

    public record LoginResponse(
            long userId,
            String username,
            List<String> authorities,
            Long activeAssignmentId,
            ActiveRoleAssignmentResponse activeAssignment,
            List<ActiveRoleAssignmentResponse> assignments
    ) {}

    public record ForgotPasswordRequest(
            String username
    ) {}

    public record ForgotPasswordResponse(
            boolean ok,
            String message,
            String resetToken
    ) {}

    public record ResetPasswordRequest(
            String token,
            String newPassword
    ) {}

    public record ResetPasswordResponse(
            boolean ok,
            String message
    ) {}
}