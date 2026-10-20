package ai.jobmatcher.cv;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "cv_analysis")
public class CvAnalysisEntity {

    @Id
    private UUID id;

    @Column(name = "cv_id", nullable = false, updatable = false)
    private UUID cvId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "overall_score", nullable = false)
    private int overallScore;

    @Column(name = "report_json", nullable = false)
    private String reportJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CvAnalysisEntity() {
    }

    public CvAnalysisEntity(UUID cvId, UUID userId, int overallScore, String reportJson) {
        this.id = UUID.randomUUID();
        this.cvId = cvId;
        this.userId = userId;
        this.overallScore = overallScore;
        this.reportJson = reportJson;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCvId() { return cvId; }
    public int getOverallScore() { return overallScore; }
    public String getReportJson() { return reportJson; }
    public Instant getCreatedAt() { return createdAt; }
}
