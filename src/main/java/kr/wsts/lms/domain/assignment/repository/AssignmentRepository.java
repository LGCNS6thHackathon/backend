package kr.wsts.lms.domain.assignment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.assignment.model.Assignment;
import kr.wsts.lms.domain.assignment.model.Submission;
import kr.wsts.lms.domain.assignment.model.SubmissionReview;

/**
 * Assignment persistence contract. Student submission/review reads include owner ID.
 * Staff list/review operations require service-level staff authorization.
 * submittedAt is the last successful submission/file upload time; the upload
 * implementation must update it in the same transaction as file metadata.
 */
public interface AssignmentRepository {
    List<Assignment> findAssignments(long cohortId, int offset, int limit);
    Optional<Assignment> findAssignment(long cohortId, long assignmentId);
    Assignment saveAssignment(Assignment assignment);
    boolean softDeleteAssignment(long cohortId, long assignmentId, Instant deletedAt);
    Optional<Submission> findSubmissionForStudent(long submissionId, long studentId);
    Optional<Submission> findAssignmentSubmissionForStudent(long assignmentId, long studentId);
    Optional<SubmissionReview> findReviewForStudent(long submissionId, long studentId);
    List<Submission> findSubmissionsForStaff(long cohortId, long assignmentId, int offset, int limit);
    Submission saveSubmission(Submission submission);
    SubmissionReview saveReview(SubmissionReview review);
}
