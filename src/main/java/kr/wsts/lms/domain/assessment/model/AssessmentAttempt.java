package kr.wsts.lms.domain.assessment.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code assessment_attempts}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record AssessmentAttempt(
        Long id,
        Long assessmentId,
        Long userId,
        Status status,
        Instant startedAt,
        Instant submittedAt,
        Integer score
) {
    public enum Status {
        IN_PROGRESS, SUBMITTED, EXPIRED
    }
}
