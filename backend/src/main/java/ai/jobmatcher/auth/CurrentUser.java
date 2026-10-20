package ai.jobmatcher.auth;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the authenticated user's id. Every owner-scoped query takes this id. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AppUserDetails d) {
            return d.id();
        }
        throw new IllegalStateException("No authenticated user");
    }
}
