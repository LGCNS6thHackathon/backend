package kr.wsts.lms.domain.catalog.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * JDBC domain state for {@code cohorts}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Cohort(
        Long id,
        Long courseId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String classroomUrl,
        String materialsUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
