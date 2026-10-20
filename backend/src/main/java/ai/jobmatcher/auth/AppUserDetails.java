package ai.jobmatcher.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Minimal principal kept in the HTTP session: identity only, never the CV or profile. */
public record AppUserDetails(UUID id, String email, String passwordHash) implements UserDetails {

    static AppUserDetails of(User u) {
        return new AppUserDetails(u.getId(), u.getEmail(), u.getPasswordHash());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
