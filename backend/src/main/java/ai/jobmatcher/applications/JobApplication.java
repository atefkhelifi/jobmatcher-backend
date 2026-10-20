package ai.jobmatcher.applications;

import java.time.Instant;
import java.util.UUID;

import ai.jobmatcher.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One role the user is pursuing. Title, company and link are copied from the offer so the record outlives it. */
@Entity
@Table(name = "job_application")
@Getter
@Setter
public class JobApplication extends AuditedEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "cv_id")
    private UUID cvId;

    @Column(nullable = false)
    private String title;

    private String company;

    private String location;

    /** ISO country of the offer when the source stated it; used by the analytics. */
    private String country;

    @Column(name = "job_url")
    private String jobUrl;

    @Column(nullable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    /** Encrypted. */
    private String notes;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;
}
