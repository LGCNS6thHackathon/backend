package kr.wsts.lms.domain.lecture.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code lecture_chapters}.
 * startSeconds points into the original video; no split video file is stored.
 * Services must verify topic and video belong to the same cohort.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record LectureChapter(
        Long id,
        Long videoId,
        Long topicId,
        String title,
        Integer startSeconds,
        Integer position,
        Instant createdAt,
        Instant updatedAt
) {
}
