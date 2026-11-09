package kr.wsts.lms.domain.assignment.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code submission_reviews}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record SubmissionReview(
        Long id,
        Long submissionId,
        Long reviewerId,
        Integer score,
        String feedback,
        Instant createdAt,
        Instant updatedAt
) {
}
