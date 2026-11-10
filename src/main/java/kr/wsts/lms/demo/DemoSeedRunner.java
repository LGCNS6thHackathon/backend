package kr.wsts.lms.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import kr.wsts.lms.domain.auth.AuthService;
import kr.wsts.lms.domain.lecture.YouTubeLinks;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 시연용 데이터 입력 (profile {@code demo-seed} 에서만 동작).
 * 데이터 파일 기본 위치는 저장소의 {@code demo-data/} 이다(docs/DEMO_SEED.md 참고):
 * <ul>
 *   <li>{@code app.demo-seed.video-tsv}: 강의 영상 목록 TSV (date, title, period, id)</li>
 *   <li>{@code app.demo-seed.data-dir}: lms_exam_*.json(평가 문항), review_links.json(소단원·문제 연결)</li>
 * </ul>
 * 같은 이름의 시연 기수가 이미 있으면 아무것도 하지 않는다.
 * 시연 학생의 응시 기록은 가상 데이터다(연결된 문항만 오답 처리하여 복습 링크를 보여주기 위함).
 */
@Component
@Profile("demo-seed")
@Order(Ordered.LOWEST_PRECEDENCE)
public class DemoSeedRunner implements ApplicationRunner {
    static final String COHORT_NAME = "AM Inspire Camp 6기 (시연)";
    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** examId → [start, end] in KST, from the operating LMS exam list. */
    private static final Map<Integer, LocalDateTime[]> EXAM_TIMES = Map.of(
            158, new LocalDateTime[]{LocalDateTime.parse("2026-10-06T17:00"), LocalDateTime.parse("2026-10-06T17:40")},
            159, new LocalDateTime[]{LocalDateTime.parse("2026-10-30T17:10"), LocalDateTime.parse("2026-10-30T17:50")});

    private final Db db;
    private final PasswordEncoder passwords;
    private final AuthService auth;
    @Value("${app.bootstrap.admin-email:}") String adminEmail;
    @Value("${app.bootstrap.admin-password:}") String adminPassword;
    @Value("${app.demo-seed.data-dir:demo-data}") String dataDir;
    @Value("${app.demo-seed.video-tsv:demo-data/lecture_videos.tsv}") String videoTsv;
    @Value("${app.demo-seed.student-email:student@demo.test}") String studentEmail;
    @Value("${app.demo-seed.student-password}") String studentPassword;
    @Value("${app.demo-seed.student-name:시연 교육생}") String studentName;

    public DemoSeedRunner(Db db, PasswordEncoder passwords, AuthService auth) {
        this.db = db;
        this.passwords = passwords;
        this.auth = auth;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (db.count("SELECT COUNT(*) FROM cohorts WHERE name=?", COHORT_NAME) > 0) {
            log.info("[demo-seed] '{}' already exists; skipping.", COHORT_NAME);
            return;
        }
        if (studentPassword == null || studentPassword.length() < 8) throw new IllegalStateException("app.demo-seed.student-password must be at least 8 characters.");
        // Runner order relative to the bootstrap runner is not guaranteed; this call is idempotent.
        auth.bootstrapAdministrator(adminEmail, adminPassword);
        long adminId = db.find("SELECT id FROM users WHERE role='ADMIN' ORDER BY id LIMIT 1").map(r -> Db.id(r, "id"))
                .orElseThrow(() -> new IllegalStateException("[demo-seed] An ADMIN account is required. Set BOOTSTRAP_ADMIN_EMAIL/PASSWORD first."));
        var now = Db.now();

        long courseId = db.insert("INSERT INTO courses(title,description,total_hours,created_at,updated_at) VALUES(?,?,?,?,?)",
                "LG CNS AM Inspire Camp 6기", "해커톤 시연용 과정", 960, now, now);
        long cohortId = db.insert("INSERT INTO cohorts(course_id,name,start_date,end_date,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                courseId, COHORT_NAME, LocalDate.parse("2026-07-24"), LocalDate.parse("2027-01-27"), now, now);
        long studentId = db.find("SELECT id FROM users WHERE email=?", studentEmail).map(r -> Db.id(r, "id")).orElseGet(() ->
                db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) VALUES(?,?,?,'STUDENT','ACTIVE',0,?,?)",
                        studentEmail, passwords.encode(studentPassword), studentName, now, now));
        db.insert("INSERT INTO enrollments(cohort_id,user_id,role,status,created_at,updated_at) VALUES(?,?,'STUDENT','ACTIVE',?,?)", cohortId, studentId, now, now);

        Map<String, Long> videos = seedVideos(cohortId, adminId);
        List<Map<String, Object>> exams = readExams();
        Map<String, Long> topics = seedCurriculum(cohortId, exams);
        Map<String, Long> questions = new HashMap<>();
        Map<String, Long> questionTopics = new HashMap<>();
        for (Map<String, Object> exam : exams) seedExam(cohortId, adminId, exam, topics, questions, questionTopics);
        Set<String> linked = seedReviewLinks(videos, questions, questionTopics);
        for (Map<String, Object> exam : exams) seedDemoAttempt(studentId, exam, questions, linked);
        log.info("[demo-seed] cohort={} videos={} questions={} linkedQuestions={} student={}", cohortId, videos.size(), questions.size(), linked.size(), studentEmail);
    }

    /** key "yyyy-MM-dd#period" → lecture_videos.id */
    private Map<String, Long> seedVideos(long cohortId, long adminId) throws IOException {
        Map<String, Long> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(Path.of(videoTsv), StandardCharsets.UTF_8);
        List<String> header = List.of(lines.get(0).replace("﻿", "").split("\t"));
        int date = header.indexOf("date"), title = header.indexOf("title"), period = header.indexOf("period"), id = header.indexOf("id");
        var now = Db.now();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] cols = line.split("\t", -1);
            String youtubeId = cols[id].trim();
            if (!YouTubeLinks.validVideoId(youtubeId)) { log.warn("[demo-seed] skip invalid video id: {}", line); continue; }
            int periodNo = Integer.parseInt(cols[period].replaceAll("\\D", ""));
            LocalDate day = LocalDate.parse(cols[date].trim());
            String key = day + "#" + periodNo;
            if (result.containsKey(key)) continue;
            long videoId = db.insert("INSERT INTO lecture_videos(cohort_id,created_by,title,youtube_url,youtube_video_id,lecture_date,period_no,visibility,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PUBLIC',?,?)",
                    cohortId, adminId, cols[title].trim() + " " + periodNo + "교시", YouTubeLinks.watchUrl(youtubeId, 0), youtubeId, day, periodNo, now, now);
            result.put(key, videoId);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readExams() throws IOException {
        List<Map<String, Object>> exams = new ArrayList<>();
        try (var files = Files.list(Path.of(dataDir))) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("lms_exam_\\d+.*\\.json")).sorted().toList())
                exams.add(JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class));
        }
        if (exams.isEmpty()) throw new IllegalStateException("[demo-seed] No lms_exam_*.json in " + dataDir);
        return exams;
    }

    /** subject(과정 분류) → unit(unitTitle) → topic(concept). key "unitTitle|concept" → topic id */
    @SuppressWarnings("unchecked")
    private Map<String, Long> seedCurriculum(long cohortId, List<Map<String, Object>> exams) {
        var now = Db.now();
        Map<String, Long> subjects = new LinkedHashMap<>(), units = new LinkedHashMap<>(), topics = new LinkedHashMap<>();
        Map<Long, Integer> unitPositions = new HashMap<>(), topicPositions = new HashMap<>();
        for (Map<String, Object> exam : exams) {
            String subject = (String) exam.get("subject");
            long subjectId = subjects.computeIfAbsent(subject, s -> db.insert("INSERT INTO learning_subjects(cohort_id,title,position,created_at,updated_at) VALUES(?,?,?,?,?)",
                    cohortId, s, subjects.size() + 1, now, now));
            for (Map<String, Object> p : (List<Map<String, Object>>) exam.get("problems")) {
                String unit = (String) p.get("unitTitle"), concept = (String) p.get("concept");
                long unitId = units.computeIfAbsent(subject + "|" + unit, k -> {
                    int pos = unitPositions.merge(subjectId, 1, Integer::sum);
                    return db.insert("INSERT INTO learning_units(subject_id,title,position,created_at,updated_at) VALUES(?,?,?,?,?)", subjectId, unit, pos, now, now);
                });
                topics.computeIfAbsent(unit + "|" + concept, k -> {
                    int pos = topicPositions.merge(unitId, 1, Integer::sum);
                    return db.insert("INSERT INTO learning_topics(unit_id,title,description,position,created_at,updated_at) VALUES(?,?,?,?,?,?)", unitId, concept, "", pos, now, now);
                });
            }
        }
        return topics;
    }

    @SuppressWarnings("unchecked")
    private void seedExam(long cohortId, long adminId, Map<String, Object> exam, Map<String, Long> topics,
                          Map<String, Long> questions, Map<String, Long> questionTopics) {
        int examId = ((Number) exam.get("examId")).intValue();
        LocalDateTime[] kst = EXAM_TIMES.getOrDefault(examId, new LocalDateTime[]{LocalDateTime.parse("2026-10-30T17:00"), LocalDateTime.parse("2026-10-30T17:40")});
        long assessmentId = db.insert("INSERT INTO assessments(cohort_id,title,description,domain,start_at,end_at,duration_minutes,status,created_by,created_at) VALUES(?,?,?,?,?,?,?,'CLOSED',?,?)",
                cohortId, exam.get("title"), "운영 LMS 역량평가 문항(시연용 사본)", "MSA", kst[0].minusHours(9), kst[1].minusHours(9), 40, adminId, Db.now());
        for (Map<String, Object> p : (List<Map<String, Object>>) exam.get("problems")) {
            int no = ((Number) p.get("no")).intValue();
            Long topicId = topics.get(p.get("unitTitle") + "|" + p.get("concept"));
            long questionId = db.insert("INSERT INTO assessment_questions(assessment_id,prompt,position,points,explanation,topic_id) VALUES(?,?,?,?,?,?)",
                    assessmentId, p.get("question"), no, 1, p.get("explanation"), topicId);
            List<String> options = (List<String>) p.get("options");
            int correct = ((Number) p.get("correct")).intValue();
            for (int i = 0; i < options.size(); i++)
                db.insert("INSERT INTO assessment_options(question_id,label,is_correct) VALUES(?,?,?)", questionId, options.get(i), i + 1 == correct);
            questions.put(examId + ":" + no, questionId);
            questionTopics.put(examId + ":" + no, topicId);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> seedReviewLinks(Map<String, Long> videos, Map<String, Long> questions, Map<String, Long> questionTopics) throws IOException {
        Path file = Path.of(dataDir, "review_links.json");
        Set<String> linked = new HashSet<>();
        if (!Files.exists(file)) { log.warn("[demo-seed] review_links.json not found; no review links."); return linked; }
        Map<String, Object> root = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class);
        Map<Long, Integer> chapterPositions = new HashMap<>(), linkPositions = new HashMap<>();
        var now = Db.now();
        for (Map<String, Object> c : (List<Map<String, Object>>) root.get("chapters")) {
            Long videoId = videos.get(c.get("date") + "#" + ((Number) c.get("period")).intValue());
            if (videoId == null) { log.warn("[demo-seed] no video for chapter {}", c); continue; }
            List<String> keys = (List<String>) c.get("questions");
            Long topicId = keys.stream().map(questionTopics::get).filter(Objects::nonNull).findFirst().orElse(null);
            int pos = chapterPositions.merge(videoId, 1, Integer::sum);
            long chapterId = db.insert("INSERT INTO lecture_chapters(video_id,topic_id,title,start_seconds,position,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    videoId, topicId, c.get("title"), ((Number) c.get("startSeconds")).intValue(), pos, now, now);
            for (String key : keys) {
                Long questionId = questions.get(key);
                if (questionId == null) { log.warn("[demo-seed] unknown question {}", key); continue; }
                db.insert("INSERT INTO assessment_question_review_links(question_id,chapter_id,position) VALUES(?,?,?)",
                        questionId, chapterId, linkPositions.merge(questionId, 1, Integer::sum));
                linked.add(key);
            }
        }
        return linked;
    }

    /** 가상 응시 기록: 복습 링크가 있는 문항 중 번호가 3의 배수인 것만 오답으로 저장한다. */
    @SuppressWarnings("unchecked")
    private void seedDemoAttempt(long studentId, Map<String, Object> exam, Map<String, Long> questions, Set<String> linked) {
        int examId = ((Number) exam.get("examId")).intValue();
        List<Map<String, Object>> problems = (List<Map<String, Object>>) exam.get("problems");
        long assessmentId = Db.id(db.one("SELECT assessment_id FROM assessment_questions WHERE id=?", questions.get(examId + ":1")), "assessmentId");
        Map<String, Object> a = db.one("SELECT start_at,end_at FROM assessments WHERE id=?", assessmentId);
        LocalDateTime start = LocalDateTime.parse(String.valueOf(a.get("startAt")).replace("Z", "")).plusMinutes(1);
        long attemptId = db.insert("INSERT INTO assessment_attempts(assessment_id,user_id,status,started_at,submitted_at,score) VALUES(?,?,'SUBMITTED',?,?,0)",
                assessmentId, studentId, start, start.plusMinutes(30));
        int earned = 0;
        for (Map<String, Object> p : problems) {
            String key = examId + ":" + p.get("no");
            long questionId = questions.get(key);
            // Demo only: about one in three linked questions is answered wrong so the report shows realistic recommendations.
            boolean correct = !(linked.contains(key) && ((Number) p.get("no")).intValue() % 3 == 0);
            Map<String, Object> option = db.one("SELECT id FROM assessment_options WHERE question_id=? AND is_correct=? ORDER BY id LIMIT 1", questionId, correct);
            db.insert("INSERT INTO assessment_answers(attempt_id,question_id,option_id,is_correct,earned_points) VALUES(?,?,?,?,?)",
                    attemptId, questionId, Db.id(option, "id"), correct, correct ? 1 : 0);
            if (correct) earned++;
        }
        db.update("UPDATE assessment_attempts SET score=? WHERE id=?", Math.round(earned * 100.0f / problems.size()), attemptId);
    }
}
