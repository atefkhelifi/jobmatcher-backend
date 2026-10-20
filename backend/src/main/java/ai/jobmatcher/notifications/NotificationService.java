package ai.jobmatcher.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.common.NotFoundException;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class NotificationService {

    public record View(UUID id, String type, String code, Map<String, Object> params, String link, Instant createdAt, Instant readAt) {
    }

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() { };
    static final int KEEP_DAYS = 90;

    private final NotificationRepository repo;
    private final JsonMapper json;

    NotificationService(NotificationRepository repo, JsonMapper json) {
        this.repo = repo;
        this.json = json;
    }

    @Transactional
    public void notify(UUID userId, Notification.Type type, String code, Map<String, Object> params, String link) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setCode(code);
        n.setParamsJson(json.writeValueAsString(params));
        n.setLink(link);
        repo.save(n);
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID userId, int limit) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public long unread(UUID userId) {
        return repo.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        Notification n = repo.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("Notification not found."));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repo.save(n);
        }
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repo.markAllRead(userId, Instant.now());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repo.delete(repo.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("Notification not found.")));
    }

    /** Old notifications are not worth keeping; called by the retention job. */
    @Transactional
    public int purgeOld() {
        return repo.deleteOlderThan(Instant.now().minus(Duration.ofDays(KEEP_DAYS)));
    }

    private View view(Notification n) {
        return new View(n.getId(), n.getType().name(), n.getCode(), json.readValue(n.getParamsJson(), PARAMS), n.getLink(), n.getCreatedAt(), n.getReadAt());
    }
}
