package kr.wsts.lms.domain.assessment.model;

/**
 * JDBC domain state for {@code assessment_answers}.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record AssessmentAnswer(
        Long id,
        Long attemptId,
        Long questionId,
        Long optionId,
        Boolean isCorrect,
        Integer earnedPoints
) {
}
