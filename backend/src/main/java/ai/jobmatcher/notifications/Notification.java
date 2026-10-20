package ai.jobmatcher.notifications;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A message for the user inside the app. It stores a code and parameters, never finished text, so the interface can show it in
 * the user's language. Parameters hold public offer data (titles, counts) and never anything the user wrote privately.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
public class Notification {

    public enum Type { NEW_MATCHES, REMINDER_DUE, INTERVIEW_SOON }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "params_json", nullable = false, updatable = false)
    private String paramsJson = "{}";

    @Column(updatable = false)
    private String link;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
