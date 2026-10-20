package ai.jobmatcher.auth;

import java.time.Duration;
import java.util.Locale;

import ai.jobmatcher.common.AuditLog;
import ai.jobmatcher.common.ConflictException;
import ai.jobmatcher.common.RateLimiter;
import ai.jobmatcher.profile.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final ProfileService profiles;
    private final RateLimiter limiter;
    private final AuditLog audit;
    private final int loginPerMinute;
    private final int registerPerHour;
    private final SecurityContextRepository contextRepo = new HttpSessionSecurityContextRepository();

    AuthController(UserRepository users, PasswordEncoder encoder, AuthenticationManager authManager,
            ProfileService profiles, RateLimiter limiter, AuditLog audit,
            @Value("${app.rate-limit.login-per-minute}") int loginPerMinute,
            @Value("${app.rate-limit.register-per-hour}") int registerPerHour) {
        this.users = users;
        this.encoder = encoder;
        this.authManager = authManager;
        this.profiles = profiles;
        this.limiter = limiter;
        this.audit = audit;
        this.loginPerMinute = loginPerMinute;
        this.registerPerHour = registerPerHour;
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128, message = "must be 12 to 128 characters") String password,
            @NotBlank @Size(max = 120) String displayName,
            @Pattern(regexp = "en|fr", message = "must be 'en' or 'fr'") String preferredLanguage,
            @AssertTrue(message = "consent to data processing is required") boolean consent) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record UserView(String id, String email, String displayName, String preferredLanguage) {
        static UserView of(User u) {
            return new UserView(u.getId().toString(), u.getEmail(), u.getDisplayName(), u.getPreferredLanguage());
        }
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    UserView register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http, HttpServletResponse res) {
        limiter.check("register:" + http.getRemoteAddr(), registerPerHour, Duration.ofHours(1));
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists.");
        }
        String lang = req.preferredLanguage() == null ? "en" : req.preferredLanguage();
        User user = users.save(new User(email, encoder.encode(req.password()), req.displayName().trim(), lang));
        profiles.createEmpty(user.getId());
        signIn(email, req.password(), http, res);
        audit.event("register", user.getId(), http.getRemoteAddr());
        return UserView.of(user);
    }

    @PostMapping("/login")
    UserView login(@Valid @RequestBody LoginRequest req, HttpServletRequest http, HttpServletResponse res) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        limiter.check("login:" + http.getRemoteAddr(), loginPerMinute, Duration.ofMinutes(1));
        limiter.check("login-user:" + email, loginPerMinute, Duration.ofMinutes(1));
        try {
            signIn(email, req.password(), http, res);
        } catch (org.springframework.security.core.AuthenticationException e) {
            audit.event("login.failed", null, http.getRemoteAddr());
            throw e;
        }
        User user = users.findByEmail(email).orElseThrow();
        audit.event("login", user.getId(), http.getRemoteAddr());
        return UserView.of(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest http, HttpServletResponse res) {
        audit.event("logout", CurrentUser.id(), http.getRemoteAddr());
        new SecurityContextLogoutHandler().logout(http, res, SecurityContextHolder.getContext().getAuthentication());
    }

    @GetMapping("/me")
    UserView me() {
        return users.findById(CurrentUser.id()).map(UserView::of).orElseThrow();
    }

    private void signIn(String email, String password, HttpServletRequest http, HttpServletResponse res) {
        Authentication auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        // Rotate the session id on login to prevent fixation.
        if (http.getSession(false) != null) {
            http.changeSessionId();
        }
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepo.saveContext(ctx, http, res);
    }
}
