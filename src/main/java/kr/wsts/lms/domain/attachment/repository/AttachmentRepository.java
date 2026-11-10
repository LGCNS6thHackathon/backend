package kr.wsts.lms.domain.attachment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.attachment.model.StoredFile;

/**
 * Metadata persistence contract, not disk/object-storage implementation.
 * Services must authorize the referenced parent before reading or mutating.
 * Submission attachments are private to their owner and authorized administrators.
 */
public interface AttachmentRepository {
    Optional<StoredFile> findPostFile(long fileId, long cohortId);
    Optional<StoredFile> findAssignmentFile(long fileId, long cohortId);
    Optional<StoredFile> findSubmissionFileForStudent(long fileId, long studentId);
    Optional<StoredFile> findSubmissionFileForStaff(long fileId, long cohortId);
    List<StoredFile> findSubmissionFilesForStudent(long submissionId, long studentId);
    List<StoredFile> findPostFiles(long postId, long cohortId);
    StoredFile insert(StoredFile file);
    boolean softDelete(long fileId, long cohortId, Instant deletedAt);
}
