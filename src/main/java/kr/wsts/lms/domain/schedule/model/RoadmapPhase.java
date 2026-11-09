package kr.wsts.lms.domain.schedule.model;

import java.time.LocalDate;

/**
 * JDBC domain state for {@code roadmap_phases}.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record RoadmapPhase(
        Long id,
        Long cohortId,
        String title,
        String description,
        Integer position,
        Integer hours,
        LocalDate startDate,
        LocalDate endDate
) {
}
