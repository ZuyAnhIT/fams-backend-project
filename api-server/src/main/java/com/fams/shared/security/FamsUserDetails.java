package com.fams.shared.security;

import com.fams.modules.auth.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class FamsUserDetails implements UserDetails {

    private final UUID userId;
    private final String email;
    private final String passwordHash;
    private final boolean platformAdmin;
    private final Set<GrantedAuthority> authorities;
    private final String deviceId;

    public FamsUserDetails(User user) {
        this(user, Collections.emptySet());
    }

    public FamsUserDetails(User user, Set<String> permissionNames) {
        this(user, permissionNames, null);
    }

    /**
     * Issue #6 (docs/issues/ISSUES.md): carries the current request's deviceId (from the
     * JWT's `deviceId` claim) so a "list my sessions" endpoint can mark which one is the
     * session the caller is on right now.
     */
    public FamsUserDetails(User user, Set<String> permissionNames, String deviceId) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.platformAdmin = user.isPlatformAdmin();
        this.deviceId = deviceId;

        Set<GrantedAuthority> auths = new HashSet<>();
        if (user.isPlatformAdmin()) {
            auths.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
        }
        permissionNames.forEach(p -> auths.add(new SimpleGrantedAuthority(p)));
        this.authorities = Collections.unmodifiableSet(auths);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
