package kr.wsts.lms.domain.catalog.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code enrollments}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Enrollment(
        Long id,
        Long cohortId,
        Long userId,
        Role role,
        Status status,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Role {
        STUDENT, INSTRUCTOR, MANAGER
    }

    public enum Status {
        ACTIVE, WITHDRAWN
    }
}
