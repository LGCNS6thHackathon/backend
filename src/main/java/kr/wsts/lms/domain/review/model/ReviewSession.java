package kr.wsts.lms.domain.review.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code review_sessions}.
 * Common parent of one BLANK or QUIZ subtype, sharing the session ID.
 * Services enforce subtype exclusivity and matching mode in a transaction.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record ReviewSession(
        Long id,
        Long userId,
        Long topicId,
        Mode mode,
        Status status,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
    public enum Mode {
        BLANK, QUIZ
    }

    public enum Status {
        ACTIVE, COMPLETED, ABANDONED
    }
}
