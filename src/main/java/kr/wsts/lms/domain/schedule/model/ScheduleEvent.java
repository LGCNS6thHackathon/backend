package kr.wsts.lms.domain.schedule.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code schedule_events}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record ScheduleEvent(
        Long id,
        Long cohortId,
        String title,
        Type type,
        Instant startAt,
        Instant endAt,
        String location,
        String description,
        Long createdBy,
        Instant createdAt
) {
    public enum Type {
        LECTURE, PROJECT, SPECIAL, HOLIDAY
    }
}
