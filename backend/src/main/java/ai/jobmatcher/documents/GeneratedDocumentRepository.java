package ai.jobmatcher.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, UUID> {

    Optional<GeneratedDocument> findByIdAndUserId(UUID id, UUID userId);

    List<GeneratedDocument> findByUserIdOrderByCreatedAtAsc(UUID userId);

    List<GeneratedDocument> findByUserIdAndJobIdAndDocTypeOrderByVersionDesc(UUID userId, UUID jobId, GeneratedDocument.Type type);

    @Query("select coalesce(max(d.version), 0) from GeneratedDocument d where d.userId = :user and d.jobId = :job and d.docType = :type")
    int maxVersion(@Param("user") UUID userId, @Param("job") UUID jobId, @Param("type") GeneratedDocument.Type type);
}
