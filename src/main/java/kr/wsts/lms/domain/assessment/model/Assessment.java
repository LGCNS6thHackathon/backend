package kr.wsts.lms.domain.assessment.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code assessments}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Assessment(
        Long id,
        Long cohortId,
        String title,
        String description,
        String domain,
        Instant startAt,
        Instant endAt,
        Integer durationMinutes,
        Status status,
        Long createdBy,
        Instant createdAt
) {
    public enum Status {
        DRAFT, PUBLISHED, CLOSED
    }
}
