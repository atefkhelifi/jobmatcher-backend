package ai.jobmatcher.applications;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One line of an application timeline. Events are only ever added, never changed. */
@Entity
@Table(name = "application_event")
@Getter
@Setter
public class ApplicationEvent {

    public enum Type { CREATED, STATUS, NOTE, CONTACT, INTERVIEW, REMINDER, ATTACHMENT }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", updatable = false)
    private ApplicationStatus toStatus;

    /** Encrypted. */
    @Column(updatable = false)
    private String note;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
