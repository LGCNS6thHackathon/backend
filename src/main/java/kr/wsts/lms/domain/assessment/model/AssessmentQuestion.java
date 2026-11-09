package kr.wsts.lms.domain.assessment.model;

/**
 * JDBC domain state for {@code assessment_questions}.
 * Internal model includes explanation; use a release-aware response DTO.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record AssessmentQuestion(
        Long id,
        Long assessmentId,
        String prompt,
        Integer position,
        Integer points,
        String explanation,
        Long topicId
) {
}
