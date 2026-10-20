package ai.jobmatcher.cv;

import java.util.UUID;

import ai.jobmatcher.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A CV version owned by one user. {@code extractedText} and {@code parsedJson} hold AES-GCM ciphertext. */
@Entity
@Table(name = "cv_document")
public class CvDocument extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String label;

    @Column(name = "source_type", nullable = false, updatable = false)
    private String sourceType;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "media_type")
    private String mediaType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    private String language;

    @Column(name = "extracted_text", nullable = false)
    private String extractedText;

    @Column(name = "parsed_json", nullable = false)
    private String parsedJson;

    @Column(nullable = false)
    private boolean reviewed;

    protected CvDocument() {
    }

    public CvDocument(UUID userId, String label, String sourceType, String originalFilename, String mediaType,
            Long sizeBytes, String language, String extractedText, String parsedJson) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.label = label;
        this.sourceType = sourceType;
        this.originalFilename = originalFilename;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.language = language;
        this.extractedText = extractedText;
        this.parsedJson = parsedJson;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getSourceType() { return sourceType; }
    public String getOriginalFilename() { return originalFilename; }
    public String getMediaType() { return mediaType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getExtractedText() { return extractedText; }
    public String getParsedJson() { return parsedJson; }
    public void setParsedJson(String parsedJson) { this.parsedJson = parsedJson; }
    public boolean isReviewed() { return reviewed; }
    public void setReviewed(boolean reviewed) { this.reviewed = reviewed; }
}
