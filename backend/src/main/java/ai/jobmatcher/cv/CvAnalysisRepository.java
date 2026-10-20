package ai.jobmatcher.cv;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CvAnalysisRepository extends JpaRepository<CvAnalysisEntity, UUID> {

    Optional<CvAnalysisEntity> findFirstByCvIdAndUserIdOrderByCreatedAtDesc(UUID cvId, UUID userId);
}
