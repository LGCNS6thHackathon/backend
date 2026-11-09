package kr.wsts.lms.domain.schedule.model;

import java.time.LocalDate;

/**
 * JDBC domain state for {@code roadmap_modules}.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record RoadmapModule(
        Long id,
        Long phaseId,
        String title,
        Integer hours,
        LocalDate startDate,
        LocalDate endDate,
        Integer position
) {
}
