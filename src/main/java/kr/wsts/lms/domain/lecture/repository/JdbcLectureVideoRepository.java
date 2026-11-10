package kr.wsts.lms.domain.lecture.repository;

import java.util.Map;
import kr.wsts.lms.global.support.Db;
import org.springframework.stereotype.Repository;

/** CRUD queries for video management; the chapter/calendar repository contract remains independent. */
@Repository
public class JdbcLectureVideoRepository {
    private final Db db;
    public JdbcLectureVideoRepository(Db db) { this.db = db; }

    public Map<String,Object> list(long cohortId, boolean publicOnly, int page, int size) {
        String where = " FROM lecture_videos WHERE cohort_id=? AND deleted_at IS NULL"
                + (publicOnly ? " AND visibility='PUBLIC'" : "");
        return db.page("SELECT *" + where + " ORDER BY lecture_date DESC,period_no ASC,id DESC",
                "SELECT COUNT(*)" + where, page, size, cohortId);
    }

    public Map<String,Object> find(long id, boolean lock) {
        return db.one("SELECT * FROM lecture_videos WHERE id=? AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""), id);
    }

    public long create(long cohortId, long authorId, String title, String url, String videoId,
            java.time.LocalDate date, int period, Integer duration, String visibility) {
        return db.insert("INSERT INTO lecture_videos(cohort_id,created_by,title,youtube_url,youtube_video_id,lecture_date,period_no,duration_seconds,visibility,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                cohortId,authorId,title,url,videoId,date,period,duration,visibility,Db.now(),Db.now());
    }

    public void update(long id, String title, String url, String videoId, java.time.LocalDate date,
            int period, Integer duration, String visibility) {
        db.update("UPDATE lecture_videos SET title=?,youtube_url=?,youtube_video_id=?,lecture_date=?,period_no=?,duration_seconds=?,visibility=?,updated_at=? WHERE id=? AND deleted_at IS NULL",
                title,url,videoId,date,period,duration,visibility,Db.now(),id);
    }

    public void delete(long id) {
        db.update("UPDATE lecture_videos SET deleted_at=?,updated_at=? WHERE id=? AND deleted_at IS NULL",Db.now(),Db.now(),id);
    }
}
