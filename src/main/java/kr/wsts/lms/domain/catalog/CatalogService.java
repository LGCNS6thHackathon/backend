package kr.wsts.lms.domain.catalog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import kr.wsts.lms.global.support.Input;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final Db db;
    private final Actor actor;
    public CatalogService(Db db, Actor actor) { this.db = db; this.actor = actor; }

    public Map<String, Object> courses(int page, int size) {
        if (actor.admin()) return db.page("SELECT * FROM courses ORDER BY id DESC", "SELECT COUNT(*) FROM courses", page, size);
        String from = " FROM courses c WHERE EXISTS (SELECT 1 FROM cohorts h JOIN enrollments e ON e.cohort_id=h.id WHERE h.course_id=c.id AND e.user_id=? AND e.status='ACTIVE')";
        return db.page("SELECT c.*" + from + " ORDER BY c.id DESC", "SELECT COUNT(*)" + from, page, size, actor.id());
    }

    public Map<String, Object> course(long courseId) {
        Map<String, Object> course = db.one("SELECT * FROM courses WHERE id=?", courseId);
        if (!actor.admin() && db.count("SELECT COUNT(*) FROM cohorts h JOIN enrollments e ON e.cohort_id=h.id WHERE h.course_id=? AND e.user_id=? AND e.status='ACTIVE'", courseId, actor.id()) == 0) throw AppException.forbidden();
        return course;
    }

    @Transactional
    public Map<String, Object> createCourse(Map<String, Object> input) {
        actor.requireAdmin();
        long id = db.insert("INSERT INTO courses(title,description,total_hours,created_at,updated_at) VALUES(?,?,?,?,?)",
                Input.str(input, "title", 2, 160), Input.optional(input, "description", "", 10000), Input.integer(input, "totalHours", 0, 0, 10000), Db.now(), Db.now());
        return db.one("SELECT * FROM courses WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> updateCourse(long courseId, Map<String, Object> input) {
        actor.requireAdmin();
        Map<String, Object> merged = new LinkedHashMap<>(db.one("SELECT * FROM courses WHERE id=?", courseId)); merged.putAll(input);
        db.update("UPDATE courses SET title=?,description=?,total_hours=?,updated_at=? WHERE id=?",
                Input.str(merged, "title", 2, 160), Input.optional(merged, "description", "", 10000), Input.integer(merged, "totalHours", 0, 0, 10000), Db.now(), courseId);
        return db.one("SELECT * FROM courses WHERE id=?", courseId);
    }

    public Map<String, Object> cohorts(Long courseId, int page, int size) {
        if (courseId != null) course(courseId);
        boolean admin = actor.admin();
        String from = " FROM cohorts h JOIN courses c ON c.id=h.course_id WHERE 1=1";
        var args = new java.util.ArrayList<Object>();
        if (courseId != null) { from += " AND h.course_id=?"; args.add(courseId); }
        if (!admin) { from += " AND EXISTS(SELECT 1 FROM enrollments e WHERE e.cohort_id=h.id AND e.user_id=? AND e.status='ACTIVE')"; args.add(actor.id()); }
        return db.page("SELECT h.*,c.title AS course_title,c.total_hours" + from + " ORDER BY h.start_date DESC,h.id DESC", "SELECT COUNT(*)" + from, page, size, args.toArray());
    }

    public Map<String, Object> cohort(long cohortId) {
        actor.cohort(cohortId);
        return db.one("SELECT h.*,c.title AS course_title,c.total_hours FROM cohorts h JOIN courses c ON c.id=h.course_id WHERE h.id=?", cohortId);
    }

    @Transactional
    public Map<String, Object> createCohort(long courseId, Map<String, Object> input) {
        actor.requireAdmin();
        db.one("SELECT id FROM courses WHERE id=?", courseId);
        LocalDate start = Input.date(input, "startDate"), end = Input.date(input, "endDate");
        if (end.isBefore(start)) throw AppException.bad("과정 종료일은 시작일 이후여야 합니다.");
        long id = db.insert("INSERT INTO cohorts(course_id,name,start_date,end_date,classroom_url,materials_url,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                courseId, Input.str(input, "name", 2, 120), start, end, Input.url(input, "classroomUrl", false), Input.url(input, "materialsUrl", false), Db.now(), Db.now());
        return cohort(id);
    }

    @Transactional
    public Map<String, Object> updateCohort(long cohortId, Map<String, Object> input) {
        requireManager(cohortId);
        Map<String, Object> merged = new LinkedHashMap<>(db.one("SELECT * FROM cohorts WHERE id=?", cohortId)); merged.putAll(input);
        LocalDate start = Input.date(merged, "startDate"), end = Input.date(merged, "endDate");
        if (end.isBefore(start)) throw AppException.bad("과정 종료일은 시작일 이후여야 합니다.");
        if (db.count("SELECT COUNT(*) FROM roadmap_phases WHERE cohort_id=? AND (start_date<? OR end_date>?)", cohortId, start, end) > 0) throw AppException.conflict("로드맵이 포함되는 기간으로 설정해주세요.");
        LocalDateTime lower = start.atStartOfDay().minusHours(9), upper = end.plusDays(1).atStartOfDay().minusHours(9);
        if (db.count("SELECT COUNT(*) FROM schedule_events WHERE cohort_id=? AND (start_at<? OR end_at>?)",cohortId,lower,upper)>0
                || db.count("SELECT COUNT(*) FROM assignments WHERE cohort_id=? AND (due_at<? OR due_at>=?)",cohortId,lower,upper)>0
                || db.count("SELECT COUNT(*) FROM assessments WHERE cohort_id=? AND (start_at<? OR end_at>?)",cohortId,lower,upper)>0
                || db.count("SELECT COUNT(*) FROM attendance_records a JOIN enrollments e ON e.id=a.enrollment_id WHERE e.cohort_id=? AND (a.attendance_date<? OR a.attendance_date>?)",cohortId,start,end)>0)
            throw AppException.conflict("기존 일정·과제·평가·출결이 포함되는 교육 기간으로 설정해주세요.");
        db.update("UPDATE cohorts SET name=?,start_date=?,end_date=?,classroom_url=?,materials_url=?,updated_at=? WHERE id=?",
                Input.str(merged, "name", 2, 120), start, end, Input.url(merged, "classroomUrl", false), Input.url(merged, "materialsUrl", false), Db.now(), cohortId);
        return cohort(cohortId);
    }

    public Map<String, Object> enrollments(long cohortId, int page, int size) {
        actor.staff(cohortId);
        return db.page("SELECT e.*,u.name,u.email,u.phone FROM enrollments e JOIN users u ON u.id=e.user_id WHERE e.cohort_id=? ORDER BY e.id",
                "SELECT COUNT(*) FROM enrollments WHERE cohort_id=?", page, size, cohortId);
    }

    @Transactional
    public Map<String, Object> enroll(long cohortId, Map<String, Object> input) {
        requireManager(cohortId);
        long userId = Input.id(input, "userId");
        Map<String, Object> user = db.one("SELECT id,status FROM users WHERE id=?", userId);
        if (!"ACTIVE".equals(user.get("status"))) throw AppException.bad("활성 계정만 등록할 수 있습니다.");
        String role = input.containsKey("role") ? Input.choice(input, "role", "STUDENT", "INSTRUCTOR", "MANAGER") : "STUDENT";
        if (!actor.admin() && !"STUDENT".equals(role)) throw AppException.forbidden();
        if (db.count("SELECT COUNT(*) FROM enrollments WHERE cohort_id=? AND user_id=?", cohortId, userId) > 0) throw AppException.conflict("이미 등록된 수강생입니다. 기존 등록 정보를 수정해주세요.");
        long id = db.insert("INSERT INTO enrollments(cohort_id,user_id,role,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)", cohortId, userId, role, Db.now(), Db.now());
        return enrollment(cohortId, id);
    }

    @Transactional
    public Map<String, Object> updateEnrollment(long cohortId, long enrollmentId, Map<String, Object> input) {
        requireManager(cohortId);
        Map<String, Object> current = db.one("SELECT * FROM enrollments WHERE id=? AND cohort_id=? FOR UPDATE", enrollmentId, cohortId);
        Map<String, Object> merged = new LinkedHashMap<>(current); merged.putAll(input);
        String role = Input.choice(merged, "role", "STUDENT", "INSTRUCTOR", "MANAGER");
        String status = Input.choice(merged, "status", "ACTIVE", "WITHDRAWN");
        if (!actor.admin() && (!"STUDENT".equals(current.get("role")) || !"STUDENT".equals(role))) throw AppException.forbidden();
        db.update("UPDATE enrollments SET role=?,status=?,updated_at=? WHERE id=? AND cohort_id=?", role, status, Db.now(), enrollmentId, cohortId);
        return enrollment(cohortId, enrollmentId);
    }

    private Map<String, Object> enrollment(long cohortId, long id) { return db.one("SELECT e.*,u.name,u.email,u.phone FROM enrollments e JOIN users u ON u.id=e.user_id WHERE e.id=? AND e.cohort_id=?", id, cohortId); }

    private void requireManager(long cohortId) {
        var membership = actor.staff(cohortId);
        if (!actor.admin() && !"MANAGER".equals(membership.get("role"))) throw AppException.forbidden();
    }

    public Map<String, Object> dashboard(long cohortId) {
        var membership = actor.cohort(cohortId);
        var cohort = cohort(cohortId);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate start = LocalDate.parse((String) cohort.get("startDate")), end = LocalDate.parse((String) cohort.get("endDate"));
        long totalDays = ChronoUnit.DAYS.between(start, end);
        int progress = totalDays == 0 ? (today.isBefore(start) ? 0 : 100) : (int) Math.max(0, Math.min(100, Math.round(100.0 * ChronoUnit.DAYS.between(start, today) / totalDays)));
        LocalDateTime dayStart = today.atStartOfDay(ZoneId.of("Asia/Seoul")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var attendance = db.one("SELECT COUNT(*) AS training_days, COALESCE(SUM(CASE WHEN status IN ('PRESENT','LATE','EARLY','EXCUSED') THEN 1 ELSE 0 END),0) AS attended_days, COALESCE(SUM(CASE WHEN status='ABSENT' THEN 1 ELSE 0 END),0) AS absent_days, COALESCE(SUM(CASE WHEN status='LATE' THEN 1 ELSE 0 END),0) AS late_days, COALESCE(SUM(CASE WHEN status='EARLY' THEN 1 ELSE 0 END),0) AS early_days FROM attendance_records WHERE enrollment_id=? AND attendance_date<=?", Db.id(membership, "id"), today);
        var attendanceSummary = new LinkedHashMap<>(attendance);
        long trainingDays = Db.id(attendance, "trainingDays");
        attendanceSummary.put("attendanceRate", trainingDays == 0 ? 0 : Math.round(1000.0 * Db.id(attendance, "attendedDays") / trainingDays) / 10.0);
        var output = new LinkedHashMap<String, Object>();
        output.put("cohort", cohort); output.put("enrollment", Db.id(membership, "id") == 0 ? null : membership); output.put("progressPercent", progress);
        output.put("attendance", attendanceSummary);
        output.put("todayEvents", db.list("SELECT * FROM schedule_events WHERE cohort_id=? AND start_at<? AND end_at>? ORDER BY start_at,id", cohortId, dayStart.plusDays(1), dayStart));
        output.put("upcomingAssignments", db.list("SELECT a.* FROM assignments a WHERE a.cohort_id=? AND a.due_at>=? AND NOT EXISTS(SELECT 1 FROM submissions s WHERE s.assignment_id=a.id AND s.user_id=?) ORDER BY a.due_at,a.id LIMIT 5", cohortId, Db.now(), actor.id()));
        output.put("notices", db.list("SELECT p.id,p.title,p.category,p.pinned,p.created_at,u.name AS author_name FROM posts p JOIN users u ON u.id=p.author_id WHERE p.cohort_id=? AND p.deleted_at IS NULL AND p.category IN ('COURSE','SYSTEM') ORDER BY p.pinned DESC,p.created_at DESC,p.id DESC LIMIT 5", cohortId));
        output.put("activeMemberCount", db.count("SELECT COUNT(*) FROM enrollments WHERE cohort_id=? AND status='ACTIVE'", cohortId));
        output.put("unreadNotificationCount", db.count("SELECT COUNT(*) FROM notifications WHERE user_id=? AND read_at IS NULL AND (cohort_id=? OR cohort_id IS NULL)", actor.id(), cohortId));
        return output;
    }
}
