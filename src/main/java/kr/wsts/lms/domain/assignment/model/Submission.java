package kr.wsts.lms.domain.assignment.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code submissions}.
 * submittedAt is the last successful submission/file upload time.
 * The feature owner must connect upload metadata updates to this timestamp.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Submission(
        Long id,
        Long assignmentId,
        Long userId,
        String body,
        String repositoryUrl,
        Instant submittedAt,
        Instant updatedAt
) {
}
