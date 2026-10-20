package ai.jobmatcher.applications;

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

@Entity
@Table(name = "application_interview")
@Getter
@Setter
public class ApplicationInterview {

    public enum Kind { PHONE, VIDEO, ONSITE, TECHNICAL, HR, OTHER }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 60;

    /** Encrypted: an address or a video link. */
    private String location;

    /** Encrypted. */
    private String notes;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
