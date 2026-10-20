package ai.jobmatcher.applications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationAttachmentRepository extends JpaRepository<ApplicationAttachment, UUID> {

    List<ApplicationAttachment> findByApplicationIdOrderByCreatedAtAsc(UUID applicationId);

    Optional<ApplicationAttachment> findByIdAndApplicationId(UUID id, UUID applicationId);
}
