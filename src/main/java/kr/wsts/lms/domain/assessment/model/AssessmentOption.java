package kr.wsts.lms.domain.assessment.model;

/**
 * JDBC domain state for {@code assessment_options}.
 * Internal model includes the answer key; never expose it before answer release.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record AssessmentOption(
        Long id,
        Long questionId,
        String label,
        Boolean isCorrect
) {
}
