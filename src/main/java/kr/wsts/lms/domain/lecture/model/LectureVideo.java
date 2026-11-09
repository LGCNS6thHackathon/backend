package kr.wsts.lms.domain.lecture.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * JDBC domain state for {@code lecture_videos}.
 * PUBLIC means visible within the authorized cohort, not an anonymous public route.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record LectureVideo(
        Long id,
        Long cohortId,
        Long createdBy,
        String title,
        String youtubeUrl,
        String youtubeVideoId,
        LocalDate lectureDate,
        Integer periodNo,
        Integer durationSeconds,
        Visibility visibility,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public enum Visibility {
        PUBLIC, PRIVATE
    }
}
