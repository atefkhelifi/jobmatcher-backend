package ai.jobmatcher.matching;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobMatchRepository extends JpaRepository<JobMatch, UUID> {

    Optional<JobMatch> findByCvIdAndJobIdAndUserId(UUID cvId, UUID jobId, UUID userId);

    List<JobMatch> findByCvIdAndUserId(UUID cvId, UUID userId);

    List<JobMatch> findByCvIdAndUserIdAndJobIdIn(UUID cvId, UUID userId, Collection<UUID> jobIds);
}
