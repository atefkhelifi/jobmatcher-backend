package ai.jobmatcher.applications;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, UUID> {

    List<ApplicationEvent> findByApplicationIdOrderByOccurredAtAsc(UUID applicationId);

    List<ApplicationEvent> findByUserIdOrderByOccurredAtAsc(UUID userId);
}
