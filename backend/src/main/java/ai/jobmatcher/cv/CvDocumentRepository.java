package ai.jobmatcher.cv;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every lookup is scoped by owner so one user can never reach another user's CV. */
public interface CvDocumentRepository extends JpaRepository<CvDocument, UUID> {

    List<CvDocument> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<CvDocument> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    long deleteByIdAndUserId(UUID id, UUID userId);
}
