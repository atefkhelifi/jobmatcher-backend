package ai.jobmatcher.contacts;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Records that a search ran for a user and an offer, so "nothing found" is not confused with "never looked". */
@Entity
@Table(name = "contact_search")
@IdClass(ContactSearch.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class ContactSearch {

    public record Key(UUID jobId, UUID userId) implements Serializable {
    }

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "searched_at", nullable = false)
    private Instant searchedAt;

    @Column(name = "pages_read", nullable = false)
    private int pagesRead;

    private String note;

    public ContactSearch(UUID jobId, UUID userId) {
        this.jobId = jobId;
        this.userId = userId;
    }
}
