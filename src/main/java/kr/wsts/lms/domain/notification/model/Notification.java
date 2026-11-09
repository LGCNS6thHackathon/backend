package kr.wsts.lms.domain.notification.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code notifications}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Notification(
        Long id,
        Long userId,
        Long cohortId,
        String type,
        String title,
        String body,
        String resourceType,
        Long resourceId,
        Instant readAt,
        Instant createdAt
) {
}
