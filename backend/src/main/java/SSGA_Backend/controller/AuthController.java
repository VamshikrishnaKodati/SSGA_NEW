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
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import SSGA_Backend.security.ActiveRoleAssignmentResponse;
import SSGA_Backend.security.ActiveRoleAssignmentService;
import SSGA_Backend.security.SsgaPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final ActiveRoleAssignmentService roleAssignmentService;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            ActiveRoleAssignmentService roleAssignmentService) {

        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.roleAssignmentService = roleAssignmentService;
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
}