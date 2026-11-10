package kr.wsts.lms.domain.lecture.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.lecture.model.LectureChapter;
import kr.wsts.lms.domain.lecture.model.LectureVideo;
import kr.wsts.lms.global.support.Db;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link LectureRepository}.
 * Student reads only return non-deleted PUBLIC videos of the requested cohort.
 */
@Repository
public class JdbcLectureRepository implements LectureRepository {
    private static final String VIDEO_COLUMNS = "v.id,v.cohort_id,v.created_by,v.title,v.youtube_url,v.youtube_video_id,v.lecture_date,v.period_no,v.duration_seconds,v.visibility,v.created_at,v.updated_at,v.deleted_at";
    private static final String STUDENT_VISIBLE = " v.deleted_at IS NULL AND v.visibility='PUBLIC' ";

    private final JdbcTemplate jdbc;
    private final Db db;

    public JdbcLectureRepository(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    @Override
    public List<LectureVideo> findStudentVideosForDate(long cohortId, LocalDate date) {
        return jdbc.query("SELECT " + VIDEO_COLUMNS + " FROM lecture_videos v WHERE v.cohort_id=? AND v.lecture_date=? AND" + STUDENT_VISIBLE
                + "ORDER BY v.period_no", JdbcLectureRepository::video, cohortId, date);
    }

    @Override
    public List<LectureVideo> findStudentVideosBetween(long cohortId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT " + VIDEO_COLUMNS + " FROM lecture_videos v WHERE v.cohort_id=? AND v.lecture_date BETWEEN ? AND ? AND" + STUDENT_VISIBLE
                + "ORDER BY v.lecture_date,v.period_no", JdbcLectureRepository::video, cohortId, from, to);
    }

    @Override
    public Optional<LectureVideo> findStudentVideo(long cohortId, long videoId) {
        return jdbc.query("SELECT " + VIDEO_COLUMNS + " FROM lecture_videos v WHERE v.cohort_id=? AND v.id=? AND" + STUDENT_VISIBLE,
                JdbcLectureRepository::video, cohortId, videoId).stream().findFirst();
    }

    @Override
    public Optional<LectureVideo> findVideoForStaff(long cohortId, long videoId) {
        return jdbc.query("SELECT " + VIDEO_COLUMNS + " FROM lecture_videos v WHERE v.cohort_id=? AND v.id=? AND v.deleted_at IS NULL",
                JdbcLectureRepository::video, cohortId, videoId).stream().findFirst();
    }

    @Override
    public List<LectureChapter> findChapters(long cohortId, long videoId) {
        return jdbc.query("SELECT c.* FROM lecture_chapters c JOIN lecture_videos v ON v.id=c.video_id WHERE v.cohort_id=? AND c.video_id=? AND v.deleted_at IS NULL ORDER BY c.position",
                JdbcLectureRepository::chapter, cohortId, videoId);
    }

    @Override
    public Optional<LectureChapter> findChapter(long cohortId, long chapterId) {
        return jdbc.query("SELECT c.* FROM lecture_chapters c JOIN lecture_videos v ON v.id=c.video_id WHERE v.cohort_id=? AND c.id=? AND v.deleted_at IS NULL",
                JdbcLectureRepository::chapter, cohortId, chapterId).stream().findFirst();
    }

    @Override
    public LectureVideo saveVideo(LectureVideo video) {
        var now = Db.now();
        String visibility = video.visibility() == null ? LectureVideo.Visibility.PRIVATE.name() : video.visibility().name();
        long id;
        if (video.id() == null) {
            id = db.insert("INSERT INTO lecture_videos(cohort_id,created_by,title,youtube_url,youtube_video_id,lecture_date,period_no,duration_seconds,visibility,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    video.cohortId(), video.createdBy(), video.title(), video.youtubeUrl(), video.youtubeVideoId(), video.lectureDate(), video.periodNo(),
                    video.durationSeconds(), visibility, now, now);
        } else {
            id = video.id();
            db.update("UPDATE lecture_videos SET title=?,youtube_url=?,youtube_video_id=?,lecture_date=?,period_no=?,duration_seconds=?,visibility=?,updated_at=?,deleted_at=? WHERE id=? AND cohort_id=?",
                    video.title(), video.youtubeUrl(), video.youtubeVideoId(), video.lectureDate(), video.periodNo(), video.durationSeconds(), visibility, now,
                    video.deletedAt() == null ? null : video.deletedAt().atOffset(ZoneOffset.UTC).toLocalDateTime(), id, video.cohortId());
        }
        return jdbc.query("SELECT " + VIDEO_COLUMNS + " FROM lecture_videos v WHERE v.id=?", JdbcLectureRepository::video, id).get(0);
    }

    @Override
    public LectureChapter saveChapter(LectureChapter chapter) {
        var now = Db.now();
        int start = chapter.startSeconds() == null ? 0 : chapter.startSeconds();
        long id;
        if (chapter.id() == null) {
            id = db.insert("INSERT INTO lecture_chapters(video_id,topic_id,title,start_seconds,position,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    chapter.videoId(), chapter.topicId(), chapter.title(), start, chapter.position(), now, now);
        } else {
            id = chapter.id();
            db.update("UPDATE lecture_chapters SET topic_id=?,title=?,start_seconds=?,position=?,updated_at=? WHERE id=? AND video_id=?",
                    chapter.topicId(), chapter.title(), start, chapter.position(), now, id, chapter.videoId());
        }
        return jdbc.query("SELECT c.* FROM lecture_chapters c WHERE c.id=?", JdbcLectureRepository::chapter, id).get(0);
    }

    private static LectureVideo video(ResultSet rs, int row) throws SQLException {
        return new LectureVideo(rs.getLong("id"), rs.getLong("cohort_id"), rs.getLong("created_by"), rs.getString("title"),
                rs.getString("youtube_url"), rs.getString("youtube_video_id"), rs.getObject("lecture_date", LocalDate.class),
                rs.getInt("period_no"), (Integer) rs.getObject("duration_seconds", Integer.class),
                LectureVideo.Visibility.valueOf(rs.getString("visibility")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("deleted_at")));
    }

    private static LectureChapter chapter(ResultSet rs, int row) throws SQLException {
        return new LectureChapter(rs.getLong("id"), rs.getLong("video_id"), (Long) rs.getObject("topic_id", Long.class), rs.getString("title"),
                rs.getInt("start_seconds"), rs.getInt("position"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    /** Timestamps are stored as UTC wall-clock values (see {@link Db#now()}). */
    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
