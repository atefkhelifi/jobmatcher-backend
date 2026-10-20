package ai.jobmatcher.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.applications.ApplicationService;
import ai.jobmatcher.auth.CurrentUser;
import ai.jobmatcher.documents.DocumentService;
import ai.jobmatcher.notifications.NotificationService;
import ai.jobmatcher.jobs.SearchService;
import ai.jobmatcher.common.AuditLog;
import ai.jobmatcher.auth.User;
import ai.jobmatcher.auth.UserRepository;
import ai.jobmatcher.cv.CvService;
import ai.jobmatcher.profile.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Data portability and erasure: the user can download everything held about them, or delete it all. */
@RestController
@RequestMapping("/api/account")
class AccountController {

    public record DeleteRequest(@NotBlank String password) {
    }

    private final UserRepository users;
    private final ProfileService profiles;
    private final CvService cvs;
    private final PasswordEncoder encoder;
    private final AuditLog audit;
    private final ApplicationService applications;
    private final DocumentService documents;
    private final SearchService searches;
    private final NotificationService notifications;

    AccountController(UserRepository users, ProfileService profiles, CvService cvs, PasswordEncoder encoder, AuditLog audit,
            ApplicationService applications, DocumentService documents, SearchService searches, NotificationService notifications) {
        this.notifications = notifications;
        this.applications = applications;
        this.documents = documents;
        this.searches = searches;
        this.users = users;
        this.profiles = profiles;
        this.cvs = cvs;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping("/export")
    @Transactional(readOnly = true)
    Map<String, Object> export() {
        UUID id = CurrentUser.id();
        audit.event("account.export", id, null);
        User u = users.findById(id).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", Map.of("email", u.getEmail(), "displayName", u.getDisplayName(),
                "preferredLanguage", u.getPreferredLanguage(), "createdAt", u.getCreatedAt().toString(),
                "consentAt", u.getConsentAt().toString()));
        out.put("preferences", profiles.get(id));
        List<Object> cvList = cvs.list(id).stream().map(s -> (Object) cvs.get(id, s.id())).toList();
        out.put("cvs", cvList);
        out.put("savedSearches", searches.list(id));
        out.put("applications", applications.exportAll(id));
        out.put("documents", documents.exportAll(id));
        out.put("notifications", notifications.list(id, 100));
        return out;
    }

    /** Deletes the account and, through database cascades, every CV, analysis and preference row. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@Valid @RequestBody DeleteRequest body, HttpServletRequest http, HttpServletResponse res) {
        User u = users.findById(CurrentUser.id()).orElseThrow();
        if (!encoder.matches(body.password(), u.getPasswordHash())) {
            throw new ai.jobmatcher.common.InvalidInputException("The password is incorrect.");
        }
        audit.event("account.delete", u.getId(), http.getRemoteAddr());
        users.delete(u);
        new SecurityContextLogoutHandler().logout(http, res, SecurityContextHolder.getContext().getAuthentication());
    }
}
