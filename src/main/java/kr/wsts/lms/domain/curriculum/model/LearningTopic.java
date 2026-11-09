package kr.wsts.lms.domain.curriculum.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code learning_topics}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record LearningTopic(
        Long id,
        Long unitId,
        String title,
        String description,
        Integer position,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
