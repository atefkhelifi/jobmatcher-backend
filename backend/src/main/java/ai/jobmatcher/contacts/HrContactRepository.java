package ai.jobmatcher.contacts;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HrContactRepository extends JpaRepository<HrContact, UUID> {

    List<HrContact> findByJobIdOrderByVerifiedDescDiscoveredAtAsc(UUID jobId);

    boolean existsByJobIdAndKindAndValue(UUID jobId, String kind, String value);
}
