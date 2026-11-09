package kr.wsts.lms.domain.attachment.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code files}.
 * Metadata only. storedName is an internal storage key, not a public download URL.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record StoredFile(
        Long id,
        Long cohortId,
        Long uploaderId,
        Long postId,
        Long assignmentId,
        Long submissionId,
        String originalName,
        String storedName,
        String contentType,
        Long sizeBytes,
        Instant createdAt,
        Instant deletedAt
) {
}
