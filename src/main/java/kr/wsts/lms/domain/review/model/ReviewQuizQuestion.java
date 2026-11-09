package kr.wsts.lms.domain.review.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code review_quiz_questions}.
 * Exactly four choices and one nullable selectedChoice.
 * Internal model includes correctChoice/explanation; hide them until answer submission.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record ReviewQuizQuestion(
        Long id,
        Long quizSessionId,
        Integer sequenceNo,
        String prompt,
        String choiceA,
        String choiceB,
        String choiceC,
        String choiceD,
        Integer correctChoice,
        String explanation,
        Integer selectedChoice,
        Boolean isCorrect,
        Instant createdAt,
        Instant answeredAt
) {
}
