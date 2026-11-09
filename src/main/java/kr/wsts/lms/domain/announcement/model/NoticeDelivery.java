package kr.wsts.lms.domain.announcement.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code notice_deliveries}.
 * Delivery history/snapshots only; webhook URLs and provider credentials are not stored.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record NoticeDelivery(
        Long id,
        Long postId,
        Long requestedBy,
        Channel channel,
        Status status,
        String idempotencyKey,
        String titleSnapshot,
        String summarySnapshot,
        String publicUrl,
        String externalMessageId,
        Integer attemptCount,
        String errorCode,
        Instant createdAt,
        Instant sentAt
) {
    public enum Channel {
        DISCORD
    }

    public enum Status {
        PENDING, SENT, FAILED
    }
}
