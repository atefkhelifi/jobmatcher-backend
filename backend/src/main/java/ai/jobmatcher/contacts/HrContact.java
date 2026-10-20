package ai.jobmatcher.contacts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A contact someone published, with where and when it was found. Never inferred. */
@Entity
@Table(name = "hr_contact")
@Getter
@Setter
public class HrContact {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private String value;

    private String name;

    private String title;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "source_kind", nullable = false)
    private String sourceKind;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt;

    @PrePersist
    void onCreate() {
        if (discoveredAt == null) {
            discoveredAt = Instant.now();
        }
    }
}
