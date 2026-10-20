package ai.jobmatcher.applications;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationInterviewRepository extends JpaRepository<ApplicationInterview, UUID> {

    List<ApplicationInterview> findByApplicationIdOrderByScheduledAtAsc(UUID applicationId);

    List<ApplicationInterview> findByApplicationIdInOrderByScheduledAtAsc(Collection<UUID> applicationIds);

    List<ApplicationInterview> findByNotifiedAtIsNullAndScheduledAtBetween(java.time.Instant from, java.time.Instant to);

    Optional<ApplicationInterview> findByIdAndApplicationId(UUID id, UUID applicationId);
}
