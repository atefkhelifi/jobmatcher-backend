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

/** A letter or email version the user used, or a link (portal, assessment, offer letter). Files are not stored. */
@Entity
@Table(name = "application_attachment")
@Getter
@Setter
public class ApplicationAttachment {

    public enum Kind { DOCUMENT, LINK }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(nullable = false)
    private String label;

    private String url;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
