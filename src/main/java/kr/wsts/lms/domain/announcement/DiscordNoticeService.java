package kr.wsts.lms.domain.announcement;

import java.time.Instant;
import java.util.Map;
import kr.wsts.lms.domain.announcement.model.NoticeDelivery;
import kr.wsts.lms.domain.announcement.repository.NoticeDeliveryRepository;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Sends an already saved course or system notice once when an administrator requests it. */
@Service
public class DiscordNoticeService {
    private static final Logger log = LoggerFactory.getLogger(DiscordNoticeService.class);
    private final Db db;
    private final NoticeDeliveryRepository deliveries;
    private final DiscordWebhookClient webhook;

    public DiscordNoticeService(Db db, NoticeDeliveryRepository deliveries, DiscordWebhookClient webhook) {
        this.db = db;
        this.deliveries = deliveries;
        this.webhook = webhook;
    }

    // The unique reservation is committed before the network call, so retries cannot duplicate a message.
    public NoticeDelivery send(long postId, long requestedBy) {
        String key = key(postId);
        var existing = deliveries.findByIdempotencyKey(key);
        if (existing.isPresent()) return existing.get();
        if (!webhook.isConfigured()) {
            throw new AppException(503, "DISCORD_NOT_CONFIGURED", "디스코드 전송 설정이 없습니다.");
        }

        Map<String, Object> post = db.find("SELECT title,body FROM posts WHERE id=? "
                + "AND category IN ('COURSE','SYSTEM') AND deleted_at IS NULL", postId)
                .orElseThrow(() -> AppException.conflict("저장된 과정·시스템 공지만 전송할 수 있습니다."));
        String title = (String) post.get("title");
        String content = "[공지] " + title + "\n\n" + post.get("body");
        if (content.length() > 2000) {
            throw AppException.bad("공지 전문을 한 메시지로 보낼 수 없습니다. 길이를 줄여주세요.");
        }

        NoticeDelivery pending = new NoticeDelivery(null, postId, requestedBy,
                NoticeDelivery.Channel.DISCORD, NoticeDelivery.Status.PENDING, key,
                title, "", "", null, 1, null, Instant.now(), null);
        try {
            pending = deliveries.insert(pending);
        } catch (DuplicateKeyException e) {
            return deliveries.findByIdempotencyKey(key).orElseThrow();
        }

        DiscordWebhookClient.DeliveryResult result;
        try {
            result = webhook.send(content);
        } catch (RuntimeException e) {
            log.warn("Discord delivery {} has an uncertain outcome", pending.id());
            result = new DiscordWebhookClient.DeliveryResult(false, null, "UNCERTAIN");
        }
        NoticeDelivery completed = new NoticeDelivery(pending.id(), postId, requestedBy,
                pending.channel(), result.sent() ? NoticeDelivery.Status.SENT : NoticeDelivery.Status.FAILED,
                key, title, "", "", result.messageId(), 1, result.errorCode(),
                pending.createdAt(), result.sent() ? Instant.now() : null);
        if (!deliveries.updateIfPending(completed)) {
            log.error("Discord delivery {} result could not be saved", pending.id());
            return pending;
        }
        return completed;
    }

    public NoticeDelivery find(long postId) {
        return deliveries.findByIdempotencyKey(key(postId)).orElseThrow(AppException::notFound);
    }

    private static String key(long postId) {
        if (postId < 1) throw AppException.bad("공지 ID를 확인해주세요.");
        return "discord-post-" + postId;
    }
}
