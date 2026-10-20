package ai.jobmatcher.applications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationContactRepository extends JpaRepository<ApplicationContact, UUID> {

    List<ApplicationContact> findByApplicationIdOrderByCreatedAtAsc(UUID applicationId);

    Optional<ApplicationContact> findByIdAndApplicationId(UUID id, UUID applicationId);
}
