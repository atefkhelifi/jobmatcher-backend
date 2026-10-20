package ai.jobmatcher.matching;

import java.util.UUID;

import ai.jobmatcher.common.AuditedEntity;
import ai.jobmatcher.jobs.JobOffer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A stored result for one CV version and one job. {@code reportJson} is encrypted because it quotes the CV. */
@Entity
@Table(name = "job_match")
@Getter
@Setter
public class JobMatch extends AuditedEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "cv_id", nullable = false, updatable = false)
    private UUID cvId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    /** Read-only link used to sort and filter offers by score. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", insertable = false, updatable = false)
    private JobOffer job;

    private Integer score;

    @Column(nullable = false)
    private int coverage;

    @Column(nullable = false)
    private String stamp;

    @Column(name = "report_json", nullable = false)
    private String reportJson;
}
