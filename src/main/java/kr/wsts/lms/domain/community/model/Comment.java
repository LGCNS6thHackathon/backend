package kr.wsts.lms.domain.community.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code comments}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Comment(
        Long id,
        Long postId,
        Long authorId,
        String body,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
