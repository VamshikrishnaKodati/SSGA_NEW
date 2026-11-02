package SSGA_Backend.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class SsgaPrincipal extends User {

    private final long userId;
    private final Long activeRoleScopeId;

    public SsgaPrincipal(
            long userId,
            String username,
            String password,
            boolean enabled,
            boolean accountNonLocked,
            Collection<? extends GrantedAuthority> authorities) {
        this(userId, username, password, enabled, accountNonLocked, authorities, null);
    }

    public SsgaPrincipal(
            long userId,
            String username,
            String password,
            boolean enabled,
            boolean accountNonLocked,
            Collection<? extends GrantedAuthority> authorities,
            Long activeRoleScopeId) {
        super(username, password, enabled, true, true, accountNonLocked, authorities);
        this.userId = userId;
        this.activeRoleScopeId = activeRoleScopeId;
    }

    public long getUserId() {
        return userId;
    }

    public Long getActiveRoleScopeId() {
        return activeRoleScopeId;
    }
}