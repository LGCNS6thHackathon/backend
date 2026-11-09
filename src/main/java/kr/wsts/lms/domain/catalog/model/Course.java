package kr.wsts.lms.domain.catalog.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code courses}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Course(
        Long id,
        String title,
        String description,
        Integer totalHours,
        Instant createdAt,
        Instant updatedAt
) {
}
