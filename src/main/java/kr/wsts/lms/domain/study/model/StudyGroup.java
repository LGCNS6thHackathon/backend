package kr.wsts.lms.domain.study.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code study_groups}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record StudyGroup(
        Long id,
        Long cohortId,
        Long ownerId,
        String name,
        String description,
        Integer capacity,
        Status status,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        OPEN, CLOSED
    }
}
