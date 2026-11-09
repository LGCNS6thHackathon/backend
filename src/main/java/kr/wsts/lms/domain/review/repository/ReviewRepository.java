package kr.wsts.lms.domain.review.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.review.model.BlankReview;
import kr.wsts.lms.domain.review.model.ReviewQuiz;
import kr.wsts.lms.domain.review.model.ReviewQuizQuestion;
import kr.wsts.lms.domain.review.model.ReviewSession;

/**
 * Private learning-review persistence contract; all user-facing reads bind owner ID.
 * Create parent and exactly one matching BLANK/QUIZ child atomically.
 * Quiz sequence allocation and next-question creation must lock the quiz row.
 * Question records contain answer keys and are never returned directly as DTOs.
 */
public interface ReviewRepository {
    List<ReviewSession> findSessionsForStudent(long studentId, int offset, int limit);
    Optional<ReviewSession> findSessionForStudent(long sessionId, long studentId);
    Optional<BlankReview> findBlankReviewForStudent(long sessionId, long studentId);
    Optional<ReviewQuiz> findQuizForStudent(long sessionId, long studentId);
    Optional<ReviewQuiz> findQuizForStudentForUpdate(long sessionId, long studentId);
    List<ReviewQuizQuestion> findQuestionsForStudent(long sessionId, long studentId);
    Optional<ReviewQuizQuestion> findQuestionForStudent(long questionId, long studentId);
    ReviewSession insertSession(ReviewSession session);
    BlankReview saveBlankReview(BlankReview review);
    ReviewQuiz saveQuiz(ReviewQuiz quiz);
    ReviewQuizQuestion insertQuestion(ReviewQuizQuestion question);
    boolean answerQuestionIfUnanswered(long questionId, long studentId, int selectedChoice,
            boolean isCorrect, Instant answeredAt);
    boolean updateSessionForStudent(ReviewSession session, long studentId);
}
