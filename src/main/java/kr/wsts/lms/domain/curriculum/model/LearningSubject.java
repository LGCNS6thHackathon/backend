package kr.wsts.lms.domain.curriculum.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code learning_subjects}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record LearningSubject(
        Long id,
        Long cohortId,
        String title,
        Integer position,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
