package kr.wsts.lms.domain.assessment.repository;

import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.assessment.model.Assessment;
import kr.wsts.lms.domain.assessment.model.AssessmentAnswer;
import kr.wsts.lms.domain.assessment.model.AssessmentAttempt;
import kr.wsts.lms.domain.assessment.model.AssessmentOption;
import kr.wsts.lms.domain.assessment.model.AssessmentQuestion;
import kr.wsts.lms.domain.assessment.model.AssessmentQuestionReviewLink;

/**
 * Cohort/owner-scoped persistence contract. Models include answer keys:
 * services must construct DTOs that hide keys and review links until release.
 */
public interface AssessmentRepository {
    List<Assessment> findAssessments(long cohortId, int offset, int limit);
    Optional<Assessment> findAssessment(long cohortId, long assessmentId);
    List<AssessmentQuestion> findQuestions(long cohortId, long assessmentId);
    List<AssessmentOption> findOptions(long cohortId, long questionId);
    List<AssessmentQuestionReviewLink> findReviewLinks(long cohortId, long questionId);
    Optional<AssessmentAttempt> findAttemptForStudent(long attemptId, long studentId);
    Optional<AssessmentAttempt> findAssessmentAttemptForStudent(long assessmentId, long studentId);
    List<AssessmentAnswer> findAnswersForStudent(long attemptId, long studentId);
    Assessment saveAssessment(Assessment assessment);
    AssessmentQuestion saveQuestion(AssessmentQuestion question);
    AssessmentOption saveOption(AssessmentOption option);
    AssessmentQuestionReviewLink saveReviewLink(AssessmentQuestionReviewLink link);
    AssessmentAttempt saveAttempt(AssessmentAttempt attempt);
    AssessmentAnswer saveAnswer(AssessmentAnswer answer);
}
