package kr.wsts.lms.domain.assessment.model;

/**
 * JDBC domain state for {@code assessment_question_review_links}.
 * Services must verify the assessment and chapter belong to the same cohort.
 * Review links are released together with assessment answers/explanations.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record AssessmentQuestionReviewLink(
        Long id,
        Long questionId,
        Long chapterId,
        Integer position
) {
}
