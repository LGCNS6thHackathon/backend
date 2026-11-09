package kr.wsts.lms.domain.review.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code blank_reviews}.
 * Private BLANK subtype, sharing its primary key with review_sessions.
 * Feedback fields may be null while draft/pending or after a failed AI request.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record BlankReview(
        Long sessionId,
        String content,
        String strengths,
        String gaps,
        String recommendations,
        Status status,
        Instant submittedAt,
        Instant feedbackAt,
        String errorCode
) implements ReviewContent {
    public enum Status {
        DRAFT, PENDING, COMPLETED, FAILED
    }
}
