package kr.wsts.lms.domain.catalog.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code enrollments}.
 * Timestamp components use UTC instants.
 * role mirrors users.role for compatibility; authorization uses the account role only.
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
        ADMIN, STUDENT
    }

    public enum Status {
        ACTIVE, WITHDRAWN
    }
}
