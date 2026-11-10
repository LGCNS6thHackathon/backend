package kr.wsts.lms.domain.lecture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.time.*;
import java.util.*;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 강의 다시보기·문제별 복습 링크 API.
 * Fixture videos, topics and review links are inserted directly because their admin CRUD belongs to another feature owner.
 * The YouTube ids are placeholders, not real lectures.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lms-lecture-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.jwt-secret=lecture-test-only-fixed-key-at-least-32-bytes",
        "app.bootstrap.admin-email=", "app.bootstrap.admin-password="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LectureReplayIntegrationTests {
    private static final String PASSWORD = "LecturePassword!42";
    private final JsonMapper json = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired PasswordEncoder passwords;
    private User admin, student, outsider;
    private long courseId, cohortId, foreignCohortId;
    private LocalDate today, lectureDay;

    @BeforeEach
    void fixture() throws Exception {
        admin = account(true);
        student = account(false);
        outsider = account(false);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        lectureDay = today.minusDays(3);
        courseId = id(call(admin, post("/api/courses"), Map.of("title", "다시보기 검증 과정", "totalHours", 80), 201));
        cohortId = cohort();
        foreignCohortId = cohort();
        call(admin, post("/api/cohorts/" + cohortId + "/enrollments"), Map.of("userId", student.id, "role", "STUDENT"), 201);
        call(admin, post("/api/cohorts/" + foreignCohortId + "/enrollments"), Map.of("userId", outsider.id, "role", "STUDENT"), 201);
    }

    @Test
    void calendarShowsOnlyDatesWithPublicVideosOfOwnCohort() throws Exception {
        video(cohortId, lectureDay, 1, "PUBLIC", "AAAAAAAAAA1");
        video(cohortId, lectureDay, 2, "PUBLIC", "AAAAAAAAAA2");
        video(cohortId, lectureDay.plusDays(1), 1, "PRIVATE", "AAAAAAAAAA3");
        long deleted = video(cohortId, lectureDay.plusDays(2), 1, "PUBLIC", "AAAAAAAAAA4");
        db.update("UPDATE lecture_videos SET deleted_at=? WHERE id=?", Db.now(), deleted);
        video(foreignCohortId, lectureDay.plusDays(3), 1, "PUBLIC", "AAAAAAAAAA5");

        mvc.perform(get("/api/cohorts/" + cohortId + "/lecture-replays/dates").header("Authorization", bearer(student))
                        .param("from", lectureDay.minusDays(1).toString()).param("to", lectureDay.plusDays(5).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates.length()").value(1))
                .andExpect(jsonPath("$.dates[0].date").value(lectureDay.toString()))
                .andExpect(jsonPath("$.dates[0].videoCount").value(2));

        call(outsider, get("/api/cohorts/" + cohortId + "/lecture-replays/dates").param("from", lectureDay.toString()).param("to", lectureDay.toString()), null, 403);
        call(student, get("/api/cohorts/" + cohortId + "/lecture-replays/dates").param("from", lectureDay.toString()).param("to", lectureDay.minusDays(1).toString()), null, 400);
        call(student, get("/api/cohorts/" + cohortId + "/lecture-replays/dates").param("from", "2026/10/01").param("to", lectureDay.toString()), null, 400);
    }

    @Test
    void dailyReplayListsPeriodsInOrderWithChaptersAndEmptyMessage() throws Exception {
        long second = video(cohortId, lectureDay, 2, "PUBLIC", "BBBBBBBBBB2");
        long first = video(cohortId, lectureDay, 1, "PUBLIC", "BBBBBBBBBB1");
        chapter(first, null, "서비스 디스커버리", 0, 1);
        chapter(first, null, "API Gateway", 1930, 2);

        mvc.perform(get("/api/cohorts/" + cohortId + "/lecture-replays").header("Authorization", bearer(student)).param("date", lectureDay.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videos.length()").value(2))
                .andExpect(jsonPath("$.videos[0].id").value(first))
                .andExpect(jsonPath("$.videos[0].periodNo").value(1))
                .andExpect(jsonPath("$.videos[0].embedUrl").value("https://www.youtube.com/embed/BBBBBBBBBB1?autoplay=0&rel=0"))
                .andExpect(jsonPath("$.videos[0].chapters[1].title").value("API Gateway"))
                .andExpect(jsonPath("$.videos[0].chapters[1].startSeconds").value(1930))
                .andExpect(jsonPath("$.videos[1].id").value(second))
                .andExpect(jsonPath("$.emptyMessage").doesNotExist());

        mvc.perform(get("/api/cohorts/" + cohortId + "/lecture-replays").header("Authorization", bearer(student)).param("date", today.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videos.length()").value(0))
                .andExpect(jsonPath("$.emptyMessage").value("등록된 강의가 없습니다."));
    }

    @Test
    void chapterPlaybackStartsAtChapterAndBlocksOtherCohortsAndHiddenVideos() throws Exception {
        long video = video(cohortId, lectureDay, 1, "PUBLIC", "CCCCCCCCCC1");
        long chapter = chapter(video, null, "Saga 패턴", 754, 1);
        long fromStart = chapter(video, null, "도입", 0, 2);

        mvc.perform(get("/api/cohorts/" + cohortId + "/lecture-chapters/" + chapter + "/playback").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startSeconds").value(754))
                .andExpect(jsonPath("$.embedUrl").value("https://www.youtube.com/embed/CCCCCCCCCC1?autoplay=0&rel=0&start=754"))
                .andExpect(jsonPath("$.watchUrl").value("https://www.youtube.com/watch?v=CCCCCCCCCC1&t=754s"))
                .andExpect(jsonPath("$.chapterTitle").value("Saga 패턴"))
                .andExpect(jsonPath("$.autoplayNotice").isNotEmpty())
                .andExpect(jsonPath("$.unavailableMessage").isNotEmpty());
        mvc.perform(get("/api/cohorts/" + cohortId + "/lecture-chapters/" + fromStart + "/playback").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startSeconds").value(0))
                .andExpect(jsonPath("$.embedUrl").value("https://www.youtube.com/embed/CCCCCCCCCC1?autoplay=0&rel=0"));

        call(outsider, get("/api/cohorts/" + cohortId + "/lecture-chapters/" + chapter + "/playback"), null, 403);
        call(outsider, get("/api/cohorts/" + foreignCohortId + "/lecture-chapters/" + chapter + "/playback"), null, 404);
        mvc.perform(get("/api/cohorts/" + cohortId + "/lecture-videos/" + video + "/playback").header("Authorization", bearer(student)).param("start", "120"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.startSeconds").value(120));

        db.update("UPDATE lecture_videos SET visibility='PRIVATE' WHERE id=?", video);
        call(student, get("/api/cohorts/" + cohortId + "/lecture-chapters/" + chapter + "/playback"), null, 404);
    }

    @Test
    void reviewGuideShowsClassificationAlwaysButReviewLinksOnlyAfterRelease() throws Exception {
        long topic = topic(cohortId, "AM 기반 MSA 기술 I", "MSA 아키텍처", "데이터 일관성");
        long foreignTopic = topic(foreignCohortId, "다른 기수 과목", "다른 단원", "다른 개념");
        long assessment = assessment();
        long classified = question(assessment, 1);
        long unclassified = question(assessment, 2);
        long foreignClassified = question(assessment, 3);
        db.update("UPDATE assessment_questions SET topic_id=? WHERE id=?", topic, classified);
        db.update("UPDATE assessment_questions SET topic_id=? WHERE id=?", foreignTopic, foreignClassified);
        long video = video(cohortId, lectureDay, 1, "PUBLIC", "DDDDDDDDDD1");
        long chapter = chapter(video, topic, "Saga 패턴과 보상 트랜잭션", 754, 1);
        long foreignVideo = video(foreignCohortId, lectureDay, 1, "PUBLIC", "DDDDDDDDDD2");
        long foreignChapter = chapter(foreignVideo, null, "다른 기수 소단원", 10, 1);
        db.insert("INSERT INTO assessment_question_review_links(question_id,chapter_id,position) VALUES(?,?,?)", classified, chapter, 1);
        db.insert("INSERT INTO assessment_question_review_links(question_id,chapter_id,position) VALUES(?,?,?)", unclassified, foreignChapter, 1);

        call(student, get("/api/assessments/" + assessment + "/review-guide"), null, 404);
        db.update("UPDATE assessments SET status='PUBLISHED' WHERE id=?", assessment);
        call(student, get("/api/assessments/" + assessment + "/review-guide"), null, 403);
        call(outsider, get("/api/assessments/" + assessment + "/review-guide"), null, 403);

        long attempt = id(call(student, post("/api/assessments/" + assessment + "/attempts"), null, 201));
        mvc.perform(get("/api/assessments/" + assessment + "/review-guide").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewReleased").value(false))
                .andExpect(jsonPath("$.questions[0].classification.topic").value("데이터 일관성"))
                .andExpect(jsonPath("$.questions[0].classificationLabel").value("AM 기반 MSA 기술 I > MSA 아키텍처 > 데이터 일관성"))
                .andExpect(jsonPath("$.questions[0].reviewLinks").doesNotExist())
                .andExpect(jsonPath("$.questions[1].classificationLabel").value("단원 미분류"))
                .andExpect(jsonPath("$.questions[2].classificationLabel").value("단원 미분류"));

        call(student, post("/api/assessment-attempts/" + attempt + "/submit"), null, 200);
        mvc.perform(get("/api/assessments/" + assessment + "/review-guide").header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewReleased").value(false));

        db.update("UPDATE assessments SET status='CLOSED' WHERE id=?", assessment);
        mvc.perform(get("/api/assessments/" + assessment + "/review-guide").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewReleased").value(true))
                .andExpect(jsonPath("$.questions[0].reviewLinks[0].chapterId").value(chapter))
                .andExpect(jsonPath("$.questions[0].reviewLinks[0].lectureDate").value(lectureDay.toString()))
                .andExpect(jsonPath("$.questions[0].reviewLinks[0].periodNo").value(1))
                .andExpect(jsonPath("$.questions[0].reviewLinks[0].chapterTitle").value("Saga 패턴과 보상 트랜잭션"))
                .andExpect(jsonPath("$.questions[0].reviewLinks[0].embedUrl").value("https://www.youtube.com/embed/DDDDDDDDDD1?autoplay=0&rel=0&start=754"))
                .andExpect(jsonPath("$.questions[0].reviewMessage").doesNotExist())
                .andExpect(jsonPath("$.questions[1].reviewLinks.length()").value(0))
                .andExpect(jsonPath("$.questions[1].reviewMessage").value("연결된 복습 영상 없음"));

        db.update("UPDATE lecture_videos SET visibility='PRIVATE' WHERE id=?", video);
        mvc.perform(get("/api/assessments/" + assessment + "/review-guide").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].reviewLinks.length()").value(0))
                .andExpect(jsonPath("$.questions[0].reviewMessage").value("연결된 복습 영상 없음"));
    }

    @Test
    void recentReplaysAreArchiveCardsGroupedByDateNewestFirst() throws Exception {
        long d1p1 = video(cohortId, lectureDay.minusDays(2), 1, "PUBLIC", "HHHHHHHHHH1");
        video(cohortId, lectureDay.minusDays(2), 2, "PUBLIC", "HHHHHHHHHH2");
        chapter(d1p1, null, "첫 소단원", 30, 1);
        video(cohortId, lectureDay, 1, "PUBLIC", "HHHHHHHHHH3");
        video(cohortId, today.plusDays(2), 1, "PUBLIC", "HHHHHHHHHH4");
        mvc.perform(get("/api/cohorts/" + cohortId + "/lecture-replays/recent").header("Authorization", bearer(student)).param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].date").value(lectureDay.toString()))
                .andExpect(jsonPath("$.items[1].date").value(lectureDay.minusDays(2).toString()))
                .andExpect(jsonPath("$.items[1].title").value("테스트 강의"))
                .andExpect(jsonPath("$.items[1].periods.length()").value(2))
                .andExpect(jsonPath("$.items[1].keyTopics[0]").value("첫 소단원"))
                .andExpect(jsonPath("$.items[1].videoUrl").value("https://www.youtube.com/watch?v=HHHHHHHHHH1"))
                .andExpect(jsonPath("$.items[1].hasAiSummary").value(false));
        call(outsider, get("/api/cohorts/" + cohortId + "/lecture-replays/recent"), null, 403);
        call(student, get("/api/cohorts/" + cohortId + "/lecture-replays/recent").param("limit", "0"), null, 400);
    }

    @Test
    void recommendationsGroupWrongAnswersByChapterAfterRelease() throws Exception {
        long topic = topic(cohortId, "MSA", "MSA I", "MSA 아키텍처");
        long video = video(cohortId, lectureDay, 1, "PUBLIC", "IIIIIIIIII1");
        long saga = chapter(video, topic, "Saga 패턴", 754, 1);
        long assessment = assessment();
        long q1 = question(assessment, 1), q2 = question(assessment, 2), q3 = question(assessment, 3), q4 = question(assessment, 4);
        db.update("UPDATE assessment_questions SET topic_id=? WHERE id IN (?,?,?)", topic, q1, q2, q3);
        db.insert("INSERT INTO assessment_question_review_links(question_id,chapter_id,position) VALUES(?,?,1)", q1, saga);
        db.update("UPDATE assessments SET status='PUBLISHED' WHERE id=?", assessment);
        long attempt = id(call(student, post("/api/assessments/" + assessment + "/attempts"), null, 201));
        long correctQ3 = number(db.one("SELECT id FROM assessment_options WHERE question_id=? AND is_correct=TRUE", q3).get("id"));
        long wrongQ1 = number(db.one("SELECT id FROM assessment_options WHERE question_id=? AND is_correct=FALSE", q1).get("id"));
        call(student, put("/api/assessment-attempts/" + attempt + "/answers"), Map.of("answers", List.of(
                Map.of("questionId", q1, "optionId", wrongQ1), Map.of("questionId", q3, "optionId", correctQ3))), 200);
        call(student, post("/api/assessment-attempts/" + attempt + "/submit"), null, 200);

        mvc.perform(get("/api/assessments/" + assessment + "/review-recommendations").header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewReleased").value(false)).andExpect(jsonPath("$.items.length()").value(0));
        db.update("UPDATE assessments SET status='CLOSED' WHERE id=?", assessment);
        mvc.perform(get("/api/assessments/" + assessment + "/review-recommendations").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewReleased").value(true))
                .andExpect(jsonPath("$.wrongCount").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].chapterId").value(saga))
                .andExpect(jsonPath("$.items[0].matchType").value("QUESTION"))
                .andExpect(jsonPath("$.items[0].questionPositions[0]").value(1))
                .andExpect(jsonPath("$.items[0].questionPositions[1]").value(2))
                .andExpect(jsonPath("$.summary").isNotEmpty());
        call(outsider, get("/api/assessments/" + assessment + "/review-recommendations"), null, 403);
    }

    private static long number(Object value) { return ((Number) value).longValue(); }

    @Test
    void questionWithoutDirectLinkFallsBackToEarliestChapterOfSameConcept() throws Exception {
        long topic = topic(cohortId, "MSA", "Docker", "Docker 기초");
        long otherTopic = topic(cohortId, "MSA", "Kubernetes", "Kubernetes 기초");
        long later = video(cohortId, lectureDay, 2, "PUBLIC", "GGGGGGGGGG2");
        long earlier = video(cohortId, lectureDay.minusDays(1), 1, "PUBLIC", "GGGGGGGGGG1");
        long direct = chapter(later, topic, "docker run 실습", 600, 1);
        long first = chapter(earlier, topic, "Docker 개념 처음 설명", 120, 1);
        chapter(later, topic, "Docker 이미지", 30, 2);
        long assessment = assessment();
        long linked = question(assessment, 1), byConcept = question(assessment, 2), noChapter = question(assessment, 3);
        db.update("UPDATE assessment_questions SET topic_id=? WHERE id IN (?,?)", topic, linked, byConcept);
        db.update("UPDATE assessment_questions SET topic_id=? WHERE id=?", otherTopic, noChapter);
        db.insert("INSERT INTO assessment_question_review_links(question_id,chapter_id,position) VALUES(?,?,1)", linked, direct);

        mvc.perform(get("/api/assessments/" + assessment + "/review-guide").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].reviewLinks.length()").value(1))
                .andExpect(jsonPath("$.questions[0].reviewLinks[0].chapterId").value(direct))
                .andExpect(jsonPath("$.questions[0].reviewLinks[0].matchType").value("QUESTION"))
                .andExpect(jsonPath("$.questions[1].reviewLinks.length()").value(1))
                .andExpect(jsonPath("$.questions[1].reviewLinks[0].chapterId").value(first))
                .andExpect(jsonPath("$.questions[1].reviewLinks[0].matchType").value("TOPIC"))
                .andExpect(jsonPath("$.questions[1].reviewMessage").doesNotExist())
                .andExpect(jsonPath("$.questions[2].reviewLinks.length()").value(0))
                .andExpect(jsonPath("$.questions[2].reviewMessage").value("연결된 복습 영상 없음"));

        db.update("UPDATE lecture_videos SET visibility='PRIVATE' WHERE id=?", earlier);
        db.update("UPDATE assessments SET status='PUBLISHED' WHERE id=?", assessment);
        long attempt = id(call(student, post("/api/assessments/" + assessment + "/attempts"), null, 201));
        call(student, post("/api/assessment-attempts/" + attempt + "/submit"), null, 200);
        db.update("UPDATE assessments SET status='CLOSED' WHERE id=?", assessment);
        mvc.perform(get("/api/assessments/" + assessment + "/review-guide").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[1].reviewLinks[0].chapterTitle").value("Docker 이미지"))
                .andExpect(jsonPath("$.questions[1].reviewLinks[0].matchType").value("TOPIC"));
    }

    @Test
    void questionTopicIsSavedOnlyForOwnCohortAndReviewLinksDoNotBlockDeletion() throws Exception {
        long topic = topic(cohortId, "MSA", "아키텍처", "Saga");
        long foreignTopic = topic(foreignCohortId, "다른 과목", "다른 단원", "다른 개념");
        long assessment = assessment();
        Map<String, Object> base = Map.of("prompt", "올바른 선택지를 고르세요.", "position", 1, "points", 1, "explanation", "해설",
                "options", List.of(Map.of("label", "정답", "isCorrect", true), Map.of("label", "오답", "isCorrect", false)));

        Map<String, Object> withTopic = new HashMap<>(base);
        withTopic.put("topicId", topic);
        String created = call(admin, post("/api/assessments/" + assessment + "/questions"), withTopic, 201);
        long question = id(created);
        org.junit.jupiter.api.Assertions.assertEquals(topic, ((Number) JsonPath.read(created, "$.topicId")).longValue());

        Map<String, Object> foreign = new HashMap<>(base);
        foreign.put("position", 2);
        foreign.put("topicId", foreignTopic);
        call(admin, post("/api/assessments/" + assessment + "/questions"), foreign, 400);
        call(admin, patch("/api/assessment-questions/" + question), Map.of("topicId", foreignTopic), 400);
        call(student, patch("/api/assessment-questions/" + question), Map.of("topicId", topic), 403);

        mvc.perform(patch("/api/assessment-questions/" + question).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("prompt", "주제는 유지되는 수정"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.topicId").value(topic));
        Map<String, Object> clear = new HashMap<>();
        clear.put("topicId", null);
        mvc.perform(patch("/api/assessment-questions/" + question).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(clear)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.topicId").doesNotExist());

        long video = video(cohortId, lectureDay, 1, "PUBLIC", "EEEEEEEEEE1");
        long chapter = chapter(video, topic, "Saga", 30, 1);
        db.insert("INSERT INTO assessment_question_review_links(question_id,chapter_id,position) VALUES(?,?,?)", question, chapter, 1);
        call(admin, delete("/api/assessment-questions/" + question), null, 204);
        org.junit.jupiter.api.Assertions.assertEquals(0, db.count("SELECT COUNT(*) FROM assessment_question_review_links WHERE chapter_id=?", chapter));

        long other = question(assessment, 3);
        db.insert("INSERT INTO assessment_question_review_links(question_id,chapter_id,position) VALUES(?,?,?)", other, chapter, 1);
        call(admin, delete("/api/assessments/" + assessment), null, 204);
        org.junit.jupiter.api.Assertions.assertEquals(0, db.count("SELECT COUNT(*) FROM assessment_question_review_links WHERE chapter_id=?", chapter));
    }

    @Test
    void questionChapterIdLinksOneReviewChapterAndIsHiddenUntilRelease() throws Exception {
        long video = video(cohortId, lectureDay, 1, "PUBLIC", "FFFFFFFFFF1");
        long chapter = chapter(video, null, "Gateway 라우팅", 353, 1);
        long other = chapter(video, null, "Gateway 필터", 900, 2);
        long foreignChapter = chapter(video(foreignCohortId, lectureDay, 1, "PUBLIC", "FFFFFFFFFF2"), null, "다른 기수", 0, 1);
        long assessment = assessment();
        Map<String, Object> body = new HashMap<>(Map.of("prompt", "올바른 선택지를 고르세요.", "position", 1, "points", 1, "explanation", "해설",
                "options", List.of(Map.of("label", "정답", "isCorrect", true), Map.of("label", "오답", "isCorrect", false))));
        body.put("chapterId", chapter);
        String created = call(admin, post("/api/assessments/" + assessment + "/questions"), body, 201);
        long question = id(created);
        org.junit.jupiter.api.Assertions.assertEquals(chapter, ((Number) JsonPath.read(created, "$.chapterId")).longValue());

        body.put("position", 2);
        body.put("chapterId", foreignChapter);
        call(admin, post("/api/assessments/" + assessment + "/questions"), body, 400);
        call(admin, patch("/api/assessment-questions/" + question), Map.of("chapterId", foreignChapter), 400);

        mvc.perform(patch("/api/assessment-questions/" + question).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("chapterId", other))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chapterId").value(other));
        mvc.perform(patch("/api/assessment-questions/" + question).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("prompt", "링크는 유지되는 수정"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chapterId").value(other));
        org.junit.jupiter.api.Assertions.assertEquals(1, db.count("SELECT COUNT(*) FROM assessment_question_review_links WHERE question_id=?", question));

        Map<String, Object> clear = new HashMap<>();
        clear.put("chapterId", null);
        mvc.perform(patch("/api/assessment-questions/" + question).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(clear)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chapterId").isEmpty());
        call(admin, patch("/api/assessment-questions/" + question), Map.of("chapterId", chapter), 200);

        db.update("UPDATE assessments SET status='PUBLISHED' WHERE id=?", assessment);
        call(student, post("/api/assessments/" + assessment + "/attempts"), null, 201);
        mvc.perform(get("/api/assessments/" + assessment).header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].chapterId").doesNotExist());
        mvc.perform(get("/api/assessments/" + assessment).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].chapterId").value(chapter));
    }

    private long video(long cohort, LocalDate date, int period, String visibility, String youtubeId) {
        return db.insert("INSERT INTO lecture_videos(cohort_id,created_by,title,youtube_url,youtube_video_id,lecture_date,period_no,visibility,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                cohort, admin.id, "테스트 강의 " + period + "교시", "https://www.youtube.com/watch?v=" + youtubeId, youtubeId, date, period, visibility, Db.now(), Db.now());
    }

    private long chapter(long video, Long topic, String title, int start, int position) {
        return db.insert("INSERT INTO lecture_chapters(video_id,topic_id,title,start_seconds,position,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                video, topic, title, start, position, Db.now(), Db.now());
    }

    private long topic(long cohort, String subject, String unit, String topic) {
        int position = (int) db.count("SELECT COUNT(*) FROM learning_subjects WHERE cohort_id=?", cohort) + 1;
        long subjectId = db.insert("INSERT INTO learning_subjects(cohort_id,title,position,created_at,updated_at) VALUES(?,?,?,?,?)", cohort, subject, position, Db.now(), Db.now());
        long unitId = db.insert("INSERT INTO learning_units(subject_id,title,position,created_at,updated_at) VALUES(?,?,?,?,?)", subjectId, unit, 1, Db.now(), Db.now());
        return db.insert("INSERT INTO learning_topics(unit_id,title,description,position,created_at,updated_at) VALUES(?,?,?,?,?,?)", unitId, topic, "", 1, Db.now(), Db.now());
    }

    private long assessment() throws Exception {
        return id(call(admin, post("/api/cohorts/" + cohortId + "/assessments"), Map.of("title", "복습 연결 평가", "domain", "MSA",
                "startAt", iso(Db.now().minusMinutes(5)), "endAt", iso(Db.now().plusHours(2)), "durationMinutes", 30), 201));
    }

    private long question(long assessment, int position) throws Exception {
        return id(call(admin, post("/api/assessments/" + assessment + "/questions"), Map.of("prompt", "올바른 선택지를 고르세요.", "position", position, "points", 1,
                "explanation", "해설", "options", List.of(Map.of("label", "정답", "isCorrect", true), Map.of("label", "오답", "isCorrect", false))), 201));
    }

    private long cohort() throws Exception {
        return id(call(admin, post("/api/courses/" + courseId + "/cohorts"), Map.of("name", "다시보기 기수",
                "startDate", today.minusDays(30).toString(), "endDate", today.plusDays(100).toString()), 201));
    }

    private User account(boolean administrator) throws Exception {
        // Public sign-up was removed (accounts are issued by administrators), so fixtures insert users directly.
        String email = "lecture-" + UUID.randomUUID() + "@example.test";
        var now = Db.now();
        long id = db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?,?)",
                email, passwords.encode(PASSWORD), "다시보기 테스트 사용자", administrator ? "ADMIN" : "STUDENT", now, now);
        String session = call(null, post("/api/auth/login"), Map.of("email", email, "password", PASSWORD), 200);
        return new User(id, JsonPath.read(session, "$.accessToken"));
    }

    private String call(User user, MockHttpServletRequestBuilder builder, Object body, int expected) throws Exception {
        if (user != null) builder.header("Authorization", bearer(user));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private static long id(String response) { return ((Number) JsonPath.read(response, "$.id")).longValue(); }
    private static String iso(LocalDateTime date) { return date.atOffset(ZoneOffset.UTC).toString(); }
    private static String bearer(User user) { return "Bearer " + user.access; }
    private record User(long id, String access) { }
}
