package ai.jobmatcher.applications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every lookup that starts from a user id is owner-scoped. */
public interface ApplicationRepository extends JpaRepository<JobApplication, UUID> {

    Optional<JobApplication> findByIdAndUserId(UUID id, UUID userId);

    List<JobApplication> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    @Query("select a from JobApplication a where a.userId = :user and (a.jobId = :job or a.fingerprint = :fp) order by a.createdAt desc")
    List<JobApplication> findPossibleDuplicates(@Param("user") UUID userId, @Param("job") UUID jobId, @Param("fp") String fingerprint);

    @Query("select a from JobApplication a where a.userId = :user and a.fingerprint = :fp order by a.createdAt desc")
    List<JobApplication> findByFingerprint(@Param("user") UUID userId, @Param("fp") String fingerprint);

    long countByUserId(UUID userId);
}
