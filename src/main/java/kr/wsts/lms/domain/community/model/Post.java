package kr.wsts.lms.domain.community.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code posts}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record Post(
        Long id,
        Long cohortId,
        Long authorId,
        Category category,
        String title,
        String body,
        Boolean pinned,
        Integer views,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        Visibility visibility,
        String publicSlug
) {
    public enum Category {
        COURSE, SYSTEM, ATTENDANCE, QUESTION, FAQ
    }

    public enum Visibility {
        COHORT, PUBLIC
    }
}
