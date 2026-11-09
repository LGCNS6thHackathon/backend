package kr.wsts.lms.domain.announcement;

import java.time.Instant;
import kr.wsts.lms.domain.announcement.model.NoticeDelivery;
import kr.wsts.lms.domain.announcement.repository.NoticeDeliveryRepository;
import kr.wsts.lms.global.error.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Reserves each published notice once, then sends its complete text to Discord. */
@Service
public class DiscordNoticeService {
    private static final Logger log = LoggerFactory.getLogger(DiscordNoticeService.class);
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private final PublishedNoticeSource source;
    private final NoticeDeliveryRepository deliveries;
    private final DiscordWebhookClient webhook;

    public DiscordNoticeService(PublishedNoticeSource source,
            NoticeDeliveryRepository deliveries, DiscordWebhookClient webhook) {
        this.source = source;
        this.deliveries = deliveries;
        this.webhook = webhook;
    }

    public NoticeDelivery send(long postId, long requestedBy) {
        String key = key(postId);
        var existing = deliveries.findByIdempotencyKey(key);
        if (existing.isPresent()) return existing.get();
        if (!webhook.isConfigured()) {
            throw new AppException(503, "DISCORD_NOT_CONFIGURED", "디스코드 전송 설정이 없습니다.");
        }

        var notice = source.find(postId);
        String content = "[공지] " + notice.title() + "\n\n" + notice.body()
                + "\n\n" + notice.publicUrl();
        if (content.length() > MAX_MESSAGE_LENGTH) {
            throw AppException.bad("공지 전문과 링크를 한 메시지로 보낼 수 없습니다. 길이를 줄여주세요.");
        }

        // The schema requires summary_snapshot, but Discord receives the full body.
        NoticeDelivery pending = new NoticeDelivery(null, postId, requestedBy,
                NoticeDelivery.Channel.DISCORD, NoticeDelivery.Status.PENDING, key,
                notice.title(), "", notice.publicUrl(), null, 1, null, Instant.now(), null);
        try {
            // The insert commits before the HTTP call. Repeated requests never send again.
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
                key, pending.titleSnapshot(), pending.summarySnapshot(), pending.publicUrl(),
                result.messageId(), 1, result.errorCode(), pending.createdAt(),
                result.sent() ? Instant.now() : null);
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
