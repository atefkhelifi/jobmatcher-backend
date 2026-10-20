package ai.jobmatcher.applications;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A person the user dealt with. Every text field is encrypted. */
@Entity
@Table(name = "application_contact")
@Getter
@Setter
public class ApplicationContact {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private String name;

    private String role;

    private String email;

    private String phone;

    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
