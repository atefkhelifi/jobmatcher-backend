package ai.jobmatcher.applications;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationReminderRepository extends JpaRepository<ApplicationReminder, UUID> {

    List<ApplicationReminder> findByApplicationIdOrderByDueAtAsc(UUID applicationId);

    List<ApplicationReminder> findByApplicationIdInAndDoneAtIsNullOrderByDueAtAsc(Collection<UUID> applicationIds);

    List<ApplicationReminder> findByUserIdAndDoneAtIsNullOrderByDueAtAsc(UUID userId);

    List<ApplicationReminder> findByDoneAtIsNullAndNotifiedAtIsNullAndDueAtBefore(java.time.Instant now);

    Optional<ApplicationReminder> findByIdAndApplicationId(UUID id, UUID applicationId);
}
