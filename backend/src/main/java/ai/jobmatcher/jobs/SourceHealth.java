package ai.jobmatcher.jobs;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Monitoring row per source: when it last worked, how often it has failed in a row, and the last error text. */
@Entity
@Table(name = "job_source")
@Getter
@Setter
@NoArgsConstructor
public class SourceHealth {

    @Id
    @Column(name = "source_key")
    private String sourceKey;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_item_count")
    private Integer lastItemCount;

    @Column(name = "total_runs", nullable = false)
    private int totalRuns;

    public SourceHealth(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public void recordSuccess(int items) {
        Instant now = Instant.now();
        lastAttemptAt = now;
        lastSuccessAt = now;
        lastError = null;
        consecutiveFailures = 0;
        lastItemCount = items;
        totalRuns++;
    }

    public void recordFailure(String message) {
        lastAttemptAt = Instant.now();
        lastError = message == null ? "Unknown error" : message.length() > 300 ? message.substring(0, 300) : message;
        consecutiveFailures++;
        totalRuns++;
    }

    /** "healthy", "degraded" (one recent failure), "failing" (three or more in a row) or "unused". */
    public String state() {
        if (lastAttemptAt == null) {
            return "unused";
        }
        return consecutiveFailures == 0 ? "healthy" : consecutiveFailures < 3 ? "degraded" : "failing";
    }
}
