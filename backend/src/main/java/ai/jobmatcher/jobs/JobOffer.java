package ai.jobmatcher.jobs;

import java.time.Instant;
import java.util.UUID;

import ai.jobmatcher.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "job_offer")
@Getter
@Setter
public class JobOffer extends AuditedEntity {

    public enum Status { ACTIVE, EXPIRED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "source_key", nullable = false, updatable = false)
    private String sourceKey;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    /** Set for URLs a user submitted; such offers are visible to that user only. */
    @Column(name = "owner_user_id", updatable = false)
    private UUID ownerUserId;

    @Column(nullable = false)
    private String title;

    private String company;

    @Column(name = "location_text")
    private String locationText;

    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "remote_mode", nullable = false)
    private RemoteMode remoteMode = RemoteMode.UNKNOWN;

    @Column(nullable = false)
    private String description;

    @Column(name = "employment_type")
    private String employmentType;

    @Column(name = "salary_min")
    private Integer salaryMin;

    @Column(name = "salary_max")
    private Integer salaryMax;

    @Column(name = "salary_currency")
    private String salaryCurrency;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "valid_through")
    private Instant validThrough;

    @Column(name = "job_url", nullable = false)
    private String jobUrl;

    @Column(name = "apply_url")
    private String applyUrl;

    private String language;

    @Column(nullable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "duplicate_of")
    private UUID duplicateOf;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    /** Read-only link used to rank offers by match score. Always joined with a specific CV id. */
    @OneToMany(mappedBy = "job")
    private java.util.Set<ai.jobmatcher.matching.JobMatch> matches = new java.util.HashSet<>();
}
