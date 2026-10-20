package ai.jobmatcher.applications;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "application_reminder")
@Getter
@Setter
public class ApplicationReminder {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    /** Encrypted. */
    @Column(nullable = false)
    private String text;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
