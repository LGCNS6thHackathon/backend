package kr.wsts.lms.domain.review.model;

/**
 * JDBC domain state for {@code review_quizzes}.
 * Private QUIZ subtype, sharing its primary key with review_sessions.
 * Sequence allocation and question insertion must use the same transaction/row lock.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record ReviewQuiz(
        Long sessionId,
        Integer nextSequenceNo
) implements ReviewContent {
}
