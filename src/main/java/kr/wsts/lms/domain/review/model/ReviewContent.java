package kr.wsts.lms.domain.review.model;

/**
 * The two persisted review subtypes share the primary key of ReviewSession.
 * Services enforce exactly one subtype whose kind matches the parent mode.
 */
public sealed interface ReviewContent permits BlankReview, ReviewQuiz {
    Long sessionId();
}
