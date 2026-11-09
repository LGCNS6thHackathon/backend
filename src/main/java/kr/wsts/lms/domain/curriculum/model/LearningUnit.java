package kr.wsts.lms.domain.curriculum.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code learning_units}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record LearningUnit(
        Long id,
        Long subjectId,
        String title,
        Integer position,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
