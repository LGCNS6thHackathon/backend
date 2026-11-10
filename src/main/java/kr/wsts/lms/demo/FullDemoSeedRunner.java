package kr.wsts.lms.demo;

import java.io.IOException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import kr.wsts.lms.domain.auth.AuthService;
import kr.wsts.lms.global.support.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Rendered LMS snapshot plus explicitly labeled synthetic presentation records. */
@Component
@Profile("full-demo")
@Order(Ordered.LOWEST_PRECEDENCE)
public class FullDemoSeedRunner implements ApplicationRunner {
    static final String COHORT_NAME = "AM Inspire Camp 6기 (LMS 기반 시연 v2)";
    private static final Logger log = LoggerFactory.getLogger(FullDemoSeedRunner.class);
    private final Db db;
    private final PasswordEncoder passwords;
    private final AuthService auth;
    @Value("${app.bootstrap.admin-email:}") String adminEmail;
    @Value("${app.bootstrap.admin-password:}") String adminPassword;
    @Value("${app.full-demo.password:}") String password;
    @Value("${app.full-demo.snapshot:demo-data/lms_snapshot.json}") String snapshotFile;
    @Value("${app.full-demo.video-tsv:demo-data/lecture_videos.tsv}") String videoTsv;
    @Value("${app.storage.directory}") String storage;
    @Value("${app.full-demo.synthetic-scenarios:false}") boolean syntheticScenarios;

    public FullDemoSeedRunner(Db db, PasswordEncoder passwords, AuthService auth) {
        this.db = db; this.passwords = passwords; this.auth = auth;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) throws IOException {
        if (db.count("SELECT COUNT(*) FROM cohorts WHERE name=?", COHORT_NAME) > 0) {
            log.info("[full-demo] Existing demo cohort; skipped without changing presentation progress.");
            return;
        }
        if (password.length() < 8 || password.length() > 72)
            throw new IllegalStateException("Set app.full-demo.password (8~72 characters) in the local .env.");
        Snapshot snapshot = JsonMapper.builder().build().readValue(Files.readString(Path.of(snapshotFile)), Snapshot.class);
        // Validate all external inputs before creating any demo data.
        List<String> videoLines = Files.readAllLines(Path.of(videoTsv), StandardCharsets.UTF_8);
        auth.bootstrapAdministrator(adminEmail, adminPassword);
        long admin = db.find("SELECT id FROM users WHERE email=? AND role='ADMIN' AND status='ACTIVE'", adminEmail.trim().toLowerCase(Locale.ROOT))
                .map(r -> Db.id(r, "id")).orElseThrow(() -> new IllegalStateException("Configure an active bootstrap administrator."));
        long student = user("student@full-demo.test", "시연 교육생", "STUDENT");
        long peer = user("peer@full-demo.test", "동료 교육생", "STUDENT");
        long instructor = user("instructor@full-demo.test", "시연 강사", "ADMIN");
        long manager = user("manager@full-demo.test", "시연 매니저", "ADMIN");
        LocalDate start = LocalDate.parse(snapshot.startDate()), end = LocalDate.parse(snapshot.endDate());
        long course = insert("courses", "title,description,total_hours,created_at,updated_at",
                snapshot.courseTitle(), "LMS 화면 기반 메타데이터 + 가상 시연 기록 (실제 학생 기록 아님)", snapshot.totalHours(), Db.now(), Db.now());
        long cohort = insert("cohorts", "course_id,name,start_date,end_date,created_at,updated_at", course, COHORT_NAME, start, end, Db.now(), Db.now());
        long enrollment = enroll(cohort, student, "STUDENT");
        enroll(cohort, peer, "STUDENT"); enroll(cohort, instructor, "ADMIN"); enroll(cohort, manager, "ADMIN");
        schedule(cohort, admin, snapshot);
        attendance(enrollment, admin, snapshot);
        community(cohort, admin, snapshot);
        sourceAssignment(cohort, admin, snapshot.assignment());
        sourceAssessments(cohort, admin, snapshot);
        long topic = syntheticScenarios ? curriculum(cohort) : 0;
        long chapter = videos(cohort, admin, topic, videoLines);
        if (syntheticScenarios) {
            assignments(cohort, admin, instructor, student);
            assessments(cohort, admin, student, topic, chapter);
        }
        for (String type : List.of("ATTENDANCE", "ASSIGNMENT", "ASSESSMENT"))
            insert("notifications", "user_id,cohort_id,type,title,body,created_at", student, cohort, type, "[시연] " + type + " 안내", "캘린더와 학습 현황을 확인해주세요.", Db.now());
        log.info("[full-demo] Complete: cohort={} snapshot={} student=student@full-demo.test (password is never logged)", cohort, snapshot.capturedDate());
    }

    private long user(String email, String name, String role) {
        var existing = db.find("SELECT id,role,status FROM users WHERE email=?", email);
        if (existing.isPresent()) {
            var row = existing.get();
            if (!role.equals(row.get("role")) || !"ACTIVE".equals(row.get("status")))
                throw new IllegalStateException("Demo email has an incompatible account: " + email);
            return Db.id(row, "id"); // Never reset a pre-existing password.
        }
        return insert("users", "email,password_hash,name,role,status,created_at,updated_at", email, passwords.encode(password), name, role, "ACTIVE", Db.now(), Db.now());
    }

    private long enroll(long cohort, long user, String role) {
        return insert("enrollments", "cohort_id,user_id,role,status,created_at,updated_at", cohort, user, role, "ACTIVE", Db.now(), Db.now());
    }

    private void schedule(long cohort, long admin, Snapshot snapshot) {
        for (SourceEvent event : snapshot.events()) {
            // Date-only source: KST midnight to midnight AFTER the inclusive last date.
            LocalDateTime start = LocalDate.parse(event.startDate()).atStartOfDay().minusHours(9);
            LocalDateTime end = LocalDate.parse(event.endDate()).plusDays(1).atStartOfDay().minusHours(9);
            insert("schedule_events", "cohort_id,title,type,start_at,end_at,location,description,created_by,created_at",
                    cohort, event.title(), event.type(), start, end, "",
                    "LMS 화면 수집: 날짜 범위만 확인. 시간·장소는 추정하지 않음. 출처: " + snapshot.sourceUrl() + "schedule 또는 roadmap", admin, Db.now());
        }
    }

    private void attendance(long enrollment, long admin, Snapshot snapshot) {
        Set<LocalDate> holidays = new HashSet<>();
        for (SourceEvent event : snapshot.events()) if (event.type().equals("HOLIDAY")) holidays.add(LocalDate.parse(event.startDate()));
        LocalDate last = LocalDate.parse(snapshot.capturedDate());
        for (LocalDate day = LocalDate.parse(snapshot.startDate()); !day.isAfter(last); day = day.plusDays(1)) {
            if (day.getDayOfWeek().getValue() > 5 || holidays.contains(day)) continue;
            String status = day.equals(LocalDate.of(2026,11,4)) ? "LATE" : day.equals(LocalDate.of(2026,11,5)) ? "ABSENT" : "PRESENT";
            long id = record(enrollment, day, 0, status);
            if (status.equals("LATE")) {
                correction(id, enrollment, status, "REJECTED", admin, "[가상 시연] 추가 확인 필요로 반려한 이력");
                correction(id, enrollment, status, "PENDING", null, null);
            }
            if (status.equals("ABSENT")) {
                correction(id, enrollment, status, "INVALIDATED", null, "[가상 시연] 원본 수정으로 무효화된 이력");
                db.update("UPDATE attendance_records SET version=1 WHERE id=?", id);
                correction(id, enrollment, status, "PENDING", null, null);
            }
            if (day.equals(LocalDate.of(2026,11,2))) {
                correction(id, enrollment, "LATE", "APPROVED", admin, "[가상 시연] 입실 확인 후 승인한 이력");
                db.update("UPDATE attendance_records SET version=1 WHERE id=?", id);
            }
        }
    }

    private long record(long enrollment, LocalDate date, int session, String state) {
        boolean times = Set.of("PRESENT", "LATE", "EARLY").contains(state);
        return insert("attendance_records", "enrollment_id,attendance_date,unit_type,session_no,status,check_in,check_out,note,updated_at",
                enrollment, date, session == 0 ? "DATE" : "SESSION", session, state,
                times ? utc(date, state.equals("LATE") ? 10 : 9, 0) : null,
                times ? utc(date, state.equals("EARLY") ? 15 : 18, 0) : null, "전체 시연용 합성 출결", Db.now());
    }

    private void correction(long attendance, long enrollment, String original, String state, Long reviewer, String note) {
        insert("attendance_corrections", "attendance_id,enrollment_id,requested_status,reason,status,original_status,original_version,reviewed_by,review_note,created_at,reviewed_at",
                attendance, enrollment, "PRESENT", "입실 기록을 확인하여 출결 정정을 요청합니다.", state, original, Db.id(db.one("SELECT version FROM attendance_records WHERE id=?", attendance), "version"), reviewer, note,
                Db.now().minusHours(1), state.equals("PENDING") ? null : Db.now());
    }

    private void community(long cohort, long admin, Snapshot snapshot) {
        for (SourceNotice notice : snapshot.notices()) {
            String body = notice.bodySummary().isBlank()
                    ? "[LMS 수집: 제목·작성일만 확인 / 본문 미수집]"
                    : "[LMS 공지 본문을 확인하여 요약한 내용] " + notice.bodySummary();
            body += "\n원문: " + snapshot.sourceUrl() + "dashboard/" + notice.sourceId();
            var created = LocalDate.parse(notice.date()).atStartOfDay().minusHours(9);
            insert("posts", "cohort_id,author_id,category,title,body,pinned,created_at,updated_at",
                    cohort, admin, "COURSE", notice.title(), body, false, created, created);
        }
        // Original authors, view counts, contacts and uncollected attachments are not copied.
    }

    private void sourceAssignment(long cohort, long admin, SourceAssignment source) {
        insert("assignments", "cohort_id,author_id,title,description,due_at,max_score,created_at,updated_at",
                cohort, admin, source.title(), source.description() + "\n[LMS 화면 수집: 원본 첨부는 미다운로드·미등록]",
                LocalDateTime.parse(source.dueAt()).minusHours(9), 100,
                LocalDateTime.parse(source.createdAt()).minusHours(9), LocalDateTime.parse(source.createdAt()).minusHours(9));
    }

    private void sourceAssessments(long cohort, long admin, Snapshot snapshot) {
        for (SourceAssessment assessment : snapshot.assessments()) {
            insert("assessments", "cohort_id,title,description,domain,start_at,end_at,duration_minutes,status,created_by,created_at",
                    cohort, assessment.title(), "[LMS 수집: 목록 메타데이터] 원본 " + assessment.sourceQuestionCount() + "문항. 문제·정답·응시 결과는 미수집.",
                    assessment.domain(), LocalDateTime.parse(assessment.startAt()).minusHours(9), LocalDateTime.parse(assessment.endAt()).minusHours(9), 40, "CLOSED", admin, Db.now());
        }
    }

    private void assignments(long cohort, long admin, long instructor, long student) throws IOException {
        for (int i = 0; i < 3; i++) {
            boolean reviewed = i == 2;
            long assignment = insert("assignments", "cohort_id,author_id,title,description,due_at,max_score,instructor_id,instructor_name,created_at,updated_at",
                    cohort, admin, "[가상 시연 과제] " + List.of("REST API 설계 과제", "DB 모델링 과제", "Spring 구현 과제").get(i),
                    "실제 LMS에서 수집한 과제가 아닌 제출·채점 시연용 별도 과제입니다.", Db.now().plusDays(7), 100, instructor, "시연 강사", Db.now(), Db.now());
            if (i == 0) continue; // Unsubmitted, submitted, reviewed states.
            long submission = insert("submissions", "assignment_id,user_id,body,submitted_at,updated_at", assignment, student, "과제 설계 결과를 제출합니다.", Db.now(), Db.now());
            attachment(cohort, student, "submission_id", submission, "과제답안.txt", "시연용 합성 과제 답안입니다.\n");
            if (reviewed) insert("submission_reviews", "submission_id,reviewer_id,score,feedback,created_at,updated_at", submission, instructor, 92, "API 계약과 제약 조건을 잘 정리했습니다.", Db.now(), Db.now());
        }
    }

    private long curriculum(long cohort) {
        long subject = insert("learning_subjects", "cohort_id,title,position,created_at,updated_at", cohort, "백엔드 개발", 1, Db.now(), Db.now());
        long unit = insert("learning_units", "subject_id,title,position,created_at,updated_at", subject, "Spring과 데이터베이스", 1, Db.now(), Db.now());
        return insert("learning_topics", "unit_id,title,description,position,created_at,updated_at", unit, "트랜잭션과 참조 무결성", "합성 평가와 다시보기를 연결하는 학습 주제", 1, Db.now(), Db.now());
    }

    private long videos(long cohort, long admin, long topic, List<String> lines) {
        long chapter = 0;
        // The checked-in TSV has a fixed date/title/period/id header. No network fetch is needed.
        List<String> header = List.of(lines.get(0).replace("\uFEFF", "").split("\t"));
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] cols = line.split("\t", -1);
            // Resolve columns by header so TSV column order changes do not break the seed.
            String id = cols[header.indexOf("id")].trim();
            LocalDate date = LocalDate.parse(cols[header.indexOf("date")].trim());
            int period = Integer.parseInt(cols[header.indexOf("period")].replaceAll("\\D", ""));
            if (db.count("SELECT COUNT(*) FROM lecture_videos WHERE cohort_id=? AND lecture_date=? AND period_no=?", cohort, date, period) > 0) continue;
            long video = insert("lecture_videos", "cohort_id,created_by,title,youtube_url,youtube_video_id,lecture_date,period_no,visibility,created_at,updated_at",
                    cohort, admin, cols[header.indexOf("title")].trim() + " " + period + "교시", "https://www.youtube.com/watch?v=" + id, id, date, period, "PUBLIC", Db.now(), Db.now());
            if (syntheticScenarios && chapter == 0) chapter = insert("lecture_chapters", "video_id,topic_id,title,start_seconds,position,created_at,updated_at", video, topic,
                    "[시연] 복습 연결 예시 (실제 내용·시작 시각 미검증)", 0, 1, Db.now(), Db.now());
        }
        if (db.count("SELECT COUNT(*) FROM lecture_videos WHERE cohort_id=?", cohort) == 0) throw new IllegalStateException("The video TSV must contain at least one video.");
        return chapter;
    }

    private void assessments(long cohort, long admin, long student, long topic, long chapter) {
        for (boolean closed : new boolean[]{false, true}) {
            long assessment = insert("assessments", "cohort_id,title,description,domain,start_at,end_at,duration_minutes,status,created_by,created_at",
                    cohort, closed ? "[시연] 완료된 기초 평가" : "[시연] 응시 가능한 기초 평가", "[가상 시연] 직접 작성한 합성 문제입니다. 운영 LMS 문항이 아닙니다.", "BACKEND",
                    Db.now().minusDays(1), closed ? Db.now().minusHours(1) : Db.now().plusDays(7), 40, closed ? "CLOSED" : "PUBLISHED", admin, Db.now());
            long attempt = closed ? insert("assessment_attempts", "assessment_id,user_id,status,started_at,submitted_at,score", assessment, student,
                    "SUBMITTED", Db.now().minusHours(3), Db.now().minusHours(3).plusMinutes(20), 50) : 0;
            String[] prompts = {"참조 무결성을 보장하는 제약 조건은?", "트랜잭션을 정상 완료할 때 사용하는 명령은?"};
            String[][] choices = {{"FOREIGN KEY", "ORDER BY", "LIMIT", "GROUP BY"}, {"COMMIT", "ROLLBACK", "SELECT", "EXPLAIN"}};
            for (int q = 0; q < 2; q++) {
                long question = insert("assessment_questions", "assessment_id,prompt,position,points,explanation,topic_id", assessment, prompts[q], q + 1, 1,
                        q == 0 ? "외래키는 테이블 사이의 참조 무결성을 보장합니다." : "COMMIT은 트랜잭션의 변경 내용을 확정합니다.", topic);
                long selected = 0;
                for (int o = 0; o < 4; o++) {
                    long option = insert("assessment_options", "question_id,label,is_correct", question, choices[q][o], o == 0);
                    if (o == q) selected = option;
                }
                insert("assessment_question_review_links", "question_id,chapter_id,position", question, chapter, 1);
                if (closed) insert("assessment_answers", "attempt_id,question_id,option_id,is_correct,earned_points", attempt, question, selected, q == 0, q == 0 ? 1 : 0);
            }
        }
    }

    private void attachment(long cohort, long user, String parentColumn, long parent, String original, String content) throws IOException {
        String stored = UUID.randomUUID().toString();
        Path target = Path.of(storage).toAbsolutePath().resolve(stored);
        Files.createDirectories(target.getParent());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) try { Files.deleteIfExists(target); }
                catch (IOException failure) { log.error("Could not remove rolled-back demo attachment {}", target, failure); }
            }
        });
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        insert("files", "cohort_id,uploader_id," + parentColumn + ",original_name,stored_name,content_type,size_bytes,created_at",
                cohort, user, parent, original, stored, "text/plain", bytes.length, Db.now());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Snapshot(String sourceUrl, String capturedDate, String courseTitle, String startDate,
                    String endDate, int totalHours, List<SourceEvent> events, List<SourceNotice> notices,
                    SourceAssignment assignment, List<SourceAssessment> assessments) {}
    record SourceAssessment(String title, String domain, String startAt, String endAt, int sourceQuestionCount) {}
    record SourceEvent(String title, String startDate, String endDate, String type) {}
    record SourceNotice(String sourceId, String date, String title, String bodySummary) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SourceAssignment(String title, String description, String createdAt, String dueAt) {}

    private static LocalDateTime utc(LocalDate day, int hour, int minute) { return day.atTime(hour, minute).minusHours(9); }

    /** Table/column names are internal constants only; every data value is parameterized. */
    private long insert(String table, String columns, Object... values) {
        return db.insert("INSERT INTO " + table + "(" + columns + ") VALUES(" + String.join(",", Collections.nCopies(values.length, "?")) + ")", values);
    }
}
