package kr.wsts.lms.domain.auth.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code users}.
 * Internal account model: never serialize passwordHash or tokenVersion as an API response.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record User(
        Long id,
        String email,
        String passwordHash,
        String name,
        String phone,
        Role role,
        Status status,
        Integer tokenVersion,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Role {
        ADMIN, INSTRUCTOR, STUDENT
    }

    public enum Status {
        ACTIVE, DISABLED
    }
}
