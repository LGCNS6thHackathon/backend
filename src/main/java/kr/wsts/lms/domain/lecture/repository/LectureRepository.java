package kr.wsts.lms.domain.lecture.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.lecture.model.LectureChapter;
import kr.wsts.lms.domain.lecture.model.LectureVideo;

/**
 * Student reads restrict by cohort, PUBLIC visibility, and non-deleted video.
 * Chapter positions reference seconds in the original YouTube video.
 */
public interface LectureRepository {
    List<LectureVideo> findStudentVideosForDate(long cohortId, LocalDate date);
    List<LectureVideo> findStudentVideosBetween(long cohortId, LocalDate from, LocalDate to);
    Optional<LectureVideo> findStudentVideo(long cohortId, long videoId);
    Optional<LectureVideo> findVideoForStaff(long cohortId, long videoId);
    List<LectureChapter> findChapters(long cohortId, long videoId);
    Optional<LectureChapter> findChapter(long cohortId, long chapterId);
    LectureVideo saveVideo(LectureVideo video);
    LectureChapter saveChapter(LectureChapter chapter);
}
