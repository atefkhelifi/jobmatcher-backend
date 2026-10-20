package ai.jobmatcher.jobs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchConfigurationRepository extends JpaRepository<SearchConfiguration, UUID> {

    List<SearchConfiguration> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<SearchConfiguration> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    List<SearchConfiguration> findByAutoRunTrue();
}
