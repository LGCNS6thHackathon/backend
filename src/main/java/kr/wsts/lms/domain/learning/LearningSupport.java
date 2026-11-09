package kr.wsts.lms.domain.learning;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import kr.wsts.lms.global.support.Input;

@Component
public class LearningSupport {
    private final Db db;
    private final Actor actor;

    public LearningSupport(Db db, Actor actor) { this.db = db; this.actor = actor; }

    public boolean staff(long cohortId) {
        if (actor.admin()) return true;
        return Set.of("INSTRUCTOR", "MANAGER").contains(String.valueOf(actor.cohort(cohortId).get("role")));
    }

    public Map<String, Object> assignment(long id) {
        Map<String, Object> row = db.one("SELECT * FROM assignments WHERE id=? AND deleted_at IS NULL", id);
        actor.cohort(Db.id(row, "cohortId"));
        return row;
    }

    public Map<String, Object> assessment(long id) {
        Map<String, Object> row = db.one("SELECT * FROM assessments WHERE id=?", id);
        actor.cohort(Db.id(row, "cohortId"));
        return row;
    }

    public Map<String, Object> enrollment(long cohortId, long userId) {
        return db.find("SELECT * FROM enrollments WHERE cohort_id=? AND user_id=? AND status='ACTIVE'", cohortId, userId)
                .orElseThrow(() -> AppException.bad("해당 기수에 등록된 교육생이 아닙니다."));
    }

    public static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }

    public static LocalDate day(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    public static LocalDateTime utc(Object value) {
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof Timestamp date) return date.toLocalDateTime();
        if (value instanceof OffsetDateTime date) return date.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        String string = String.valueOf(value).replace(' ', 'T');
        try { return OffsetDateTime.parse(string).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
        catch (DateTimeException ignored) { return LocalDateTime.parse(string); }
    }

    public static Map<String, Object> merged(Map<String, Object> existing, Map<String, Object> changes) {
        Map<String, Object> result = new HashMap<>(existing);
        result.putAll(changes);
        return result;
    }

    public static LocalDate date(Map<String, Object> changes, String key, Map<String, Object> existing) {
        return changes.containsKey(key) ? Input.date(changes, key) : day(existing.get(key));
    }

    public static LocalDateTime dateTime(Map<String, Object> changes, String key, Map<String, Object> existing) {
        return changes.containsKey(key) ? Input.dateTime(changes, key) : utc(existing.get(key));
    }

    public void withinCohort(long cohortId, LocalDate start, LocalDate end) {
        Map<String, Object> cohort = db.one("SELECT start_date,end_date FROM cohorts WHERE id=?", cohortId);
        if (end.isBefore(start) || start.isBefore(day(cohort.get("startDate"))) || end.isAfter(day(cohort.get("endDate"))))
            throw AppException.bad("기수 교육 기간 안의 올바른 시작일과 종료일을 입력해주세요.");
    }

    public static void validRange(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) throw AppException.bad("종료 시각은 시작 시각 이후여야 합니다.");
    }

    public static int page(int page) { if (page < 0) throw AppException.bad("page는 0 이상이어야 합니다."); return page; }
    public static int size(int size) { if (size < 1 || size > 100) throw AppException.bad("size는 1~100 사이여야 합니다."); return size; }

    public static String month(String month) {
        if (month == null || month.isBlank()) return null;
        try { return YearMonth.parse(month).toString(); }
        catch (DateTimeException e) { throw AppException.bad("month는 YYYY-MM 형식이어야 합니다."); }
    }

    public void notify(long userId, long cohortId, String type, String title, String body, String resourceType, long resourceId) {
        db.insert("INSERT INTO notifications(user_id,cohort_id,type,title,body,resource_type,resource_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
                userId, cohortId, type, title, body, resourceType, resourceId, Db.now());
    }
}
