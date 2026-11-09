package kr.wsts.lms.domain.assignment.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code assignments}.
 * authorId identifies the author; instructorId/instructorName identify the assigned instructor.
 * Nullable instructor fields preserve existing assignments until a feature owner supplies them.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Assignment(
        Long id,
        Long cohortId,
        Long authorId,
        String title,
        String description,
        Instant dueAt,
        Integer maxScore,
        Instant createdAt,
        Instant updatedAt,
        Long instructorId,
        String instructorName,
        Instant deletedAt
) {
}
