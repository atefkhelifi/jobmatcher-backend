package ai.jobmatcher.jobs;

import java.time.Instant;
import java.util.UUID;

import ai.jobmatcher.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A saved search. {@code sourceKeys} is a JSON array; empty means "every enabled source". */
@Entity
@Table(name = "search_configuration")
@Getter
@Setter
public class SearchConfiguration extends AuditedEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    private String keywords;

    private String location;

    @Column(name = "remote_only", nullable = false)
    private boolean remoteOnly;

    @Column(name = "source_keys", nullable = false)
    private String sourceKeys = "[]";

    @Column(name = "auto_run", nullable = false)
    private boolean autoRun;

    @Column(name = "interval_hours", nullable = false)
    private int intervalHours = 24;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** Lowest match score that makes a new offer worth a notification, when the user has a CV. */
    @Column(name = "min_score", nullable = false)
    private int minScore = 60;

    @Column(name = "last_new_count")
    private Integer lastNewCount;
}
