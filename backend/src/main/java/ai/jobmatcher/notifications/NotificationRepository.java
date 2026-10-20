package ai.jobmatcher.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :user and n.readAt is null")
    int markAllRead(@Param("user") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from Notification n where n.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
