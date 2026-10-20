package ai.jobmatcher.documents;

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

/** One version of a letter or email for one offer. Subject, body and facts are stored encrypted. */
@Entity
@Table(name = "generated_document")
@Getter
@Setter
public class GeneratedDocument {

    public enum Type { LETTER, EMAIL }

    public enum Source { GENERATED, EDITED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "cv_id")
    private UUID cvId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, updatable = false)
    private Type docType;

    @Column(nullable = false)
    private String language;

    @Column(nullable = false, updatable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(nullable = false)
    private String generator;

    @Column(name = "params_json", nullable = false)
    private String paramsJson = "{}";

    private String subject;

    @Column(nullable = false)
    private String body;

    @Column(name = "facts_json", nullable = false)
    private String factsJson;

    @Column(name = "paragraph_facts_json", nullable = false)
    private String paragraphFactsJson = "[]";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
