package kr.wsts.lms.domain.lecture;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.wsts.lms.domain.learning.LearningSupport;
import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.Classification;
import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.QuestionGuide;
import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.Recommendation;
import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.Recommendations;
import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.ReviewGuide;
import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.ReviewLink;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import org.springframework.stereotype.Service;

/**
 * 문제별 과목·단원·핵심 개념과 복습 영상 링크.
 * Release rule matches the existing assessment API: a student sees review links only after submitting
 * and after the assessment is CLOSED or past its end time. Staff always see them.
 * Topics, chapters and videos are filtered to the assessment's cohort, since FKs alone do not guarantee it.
 * Without a direct link, the earliest chapter of the question's core concept is suggested (docs/REVIEW_LINK_RULES.md).
 */
@Service
public class AssessmentReviewGuideService {
    static final String UNCLASSIFIED = "단원 미분류";
    static final String NO_REVIEW_VIDEO = "연결된 복습 영상 없음";
    static final String MATCH_QUESTION = "QUESTION";
    static final String MATCH_TOPIC = "TOPIC";

    private final Db db;
    private final Actor actor;
    private final LearningSupport support;

    public AssessmentReviewGuideService(Db db, Actor actor, LearningSupport support) {
        this.db = db;
        this.actor = actor;
        this.support = support;
    }

    public ReviewGuide guide(long assessmentId) {
        Map<String, Object> assessment = support.assessment(assessmentId);
        long cohortId = Db.id(assessment, "cohortId");
        boolean staff = support.staff(cohortId);
        if (!staff && "DRAFT".equals(assessment.get("status"))) throw AppException.notFound();
        Optional<Map<String, Object>> attempt = db.find("SELECT status FROM assessment_attempts WHERE assessment_id=? AND user_id=?", assessmentId, actor.id());
        if (!staff && attempt.isEmpty()) throw AppException.forbidden();
        boolean finished = attempt.map(a -> !"IN_PROGRESS".equals(a.get("status"))).orElse(false);
        boolean released = staff || (finished && answersReleased(assessment));

        List<QuestionGuide> questions = new ArrayList<>();
        for (Map<String, Object> q : db.list("SELECT q.id,q.position,s.id AS subject_id,s.title AS subject_title,u.id AS unit_id,u.title AS unit_title,t.id AS topic_id,t.title AS topic_title"
                + " FROM assessment_questions q"
                + " LEFT JOIN learning_topics t ON t.id=q.topic_id AND t.deleted_at IS NULL"
                + " LEFT JOIN learning_units u ON u.id=t.unit_id AND u.deleted_at IS NULL"
                + " LEFT JOIN learning_subjects s ON s.id=u.subject_id AND s.deleted_at IS NULL AND s.cohort_id=?"
                + " WHERE q.assessment_id=? ORDER BY q.position,q.id", cohortId, assessmentId)) {
            long questionId = Db.id(q, "id");
            Classification classification = q.get("subjectId") == null ? null : new Classification(
                    Db.id(q, "subjectId"), (String) q.get("subjectTitle"), Db.id(q, "unitId"), (String) q.get("unitTitle"),
                    Db.id(q, "topicId"), (String) q.get("topicTitle"));
            String label = classification == null ? UNCLASSIFIED
                    : classification.subject() + " > " + classification.unit() + " > " + classification.topic();
            List<ReviewLink> links = null;
            if (released) {
                links = links(cohortId, questionId, staff);
                if (links.isEmpty() && classification != null) links = topicDefault(cohortId, classification.topicId(), staff);
            }
            String message = released && links.isEmpty() ? NO_REVIEW_VIDEO : null;
            questions.add(new QuestionGuide(questionId, ((Number) q.get("position")).intValue(), classification, label, links, message));
        }
        return new ReviewGuide(assessmentId, released, questions);
    }

    /**
     * Wrong (or unanswered) questions of the caller's own submitted attempt, grouped by their review chapter.
     * Uses the same links as {@link #guide}: direct link first, otherwise the concept default. Hidden until release.
     */
    public Recommendations recommendations(long assessmentId) {
        Map<String, Object> assessment = support.assessment(assessmentId);
        long cohortId = Db.id(assessment, "cohortId");
        if (!support.staff(cohortId) && "DRAFT".equals(assessment.get("status"))) throw AppException.notFound();
        Map<String, Object> attempt = db.find("SELECT id,status FROM assessment_attempts WHERE assessment_id=? AND user_id=?", assessmentId, actor.id())
                .orElseThrow(AppException::forbidden);
        boolean released = !"IN_PROGRESS".equals(attempt.get("status")) && answersReleased(assessment);
        if (!released) return new Recommendations(assessmentId, false, 0, List.of(), null);

        Map<Long, Recommendation> byChapter = new java.util.LinkedHashMap<>();
        int wrong = 0;
        for (Map<String, Object> q : db.list("SELECT q.id,q.position,t.id AS topic_id,s.id AS subject_id FROM assessment_questions q"
                + " LEFT JOIN assessment_answers a ON a.question_id=q.id AND a.attempt_id=?"
                + " LEFT JOIN learning_topics t ON t.id=q.topic_id AND t.deleted_at IS NULL"
                + " LEFT JOIN learning_units u ON u.id=t.unit_id AND u.deleted_at IS NULL"
                + " LEFT JOIN learning_subjects s ON s.id=u.subject_id AND s.deleted_at IS NULL AND s.cohort_id=?"
                + " WHERE q.assessment_id=? AND (a.is_correct IS NULL OR a.is_correct=FALSE) ORDER BY q.position,q.id",
                Db.id(attempt, "id"), cohortId, assessmentId)) {
            wrong++;
            long questionId = Db.id(q, "id");
            List<ReviewLink> links = links(cohortId, questionId, false);
            // subject_id is non-null only when the topic belongs to this cohort
            if (links.isEmpty() && q.get("subjectId") != null) links = topicDefault(cohortId, Db.id(q, "topicId"), false);
            if (links.isEmpty()) continue;
            ReviewLink l = links.get(0);
            int position = ((Number) q.get("position")).intValue();
            Recommendation current = byChapter.get(l.chapterId());
            List<Integer> positions = new ArrayList<>(current == null ? List.of() : current.questionPositions());
            positions.add(position);
            String matchType = current != null && MATCH_QUESTION.equals(current.matchType()) ? MATCH_QUESTION : l.matchType();
            byChapter.put(l.chapterId(), new Recommendation(l.chapterId(), l.chapterTitle(), l.lectureDate(), l.periodNo(), l.startSeconds(),
                    l.embedUrl(), l.watchUrl(), matchType, positions));
        }
        List<Recommendation> items = byChapter.values().stream()
                .sorted(java.util.Comparator.comparingInt((Recommendation r) -> -r.questionPositions().size())
                        .thenComparing(Recommendation::lectureDate).thenComparingInt(Recommendation::periodNo))
                .toList();
        String summary = items.isEmpty() ? null : summary(items.get(0));
        return new Recommendations(assessmentId, true, wrong, items, summary);
    }

    private static String summary(Recommendation top) {
        return String.format("교육 캘린더의 %d/%d %d교시 '%s' 다시보기 영상(%d:%02d부터)을 시청하세요. 틀린 문제 %d개(%s번)와 관련된 구간입니다.",
                top.lectureDate().getMonthValue(), top.lectureDate().getDayOfMonth(), top.periodNo(), top.chapterTitle(),
                top.startSeconds() / 60, top.startSeconds() % 60, top.questionPositions().size(),
                top.questionPositions().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
    }

    private static final String LINK_COLUMNS = "SELECT c.id AS chapter_id,c.title AS chapter_title,c.start_seconds,v.id AS video_id,v.title AS video_title,v.lecture_date,v.period_no,v.youtube_video_id";

    /** Chapters the author linked to this question (matchType=QUESTION). */
    private List<ReviewLink> links(long cohortId, long questionId, boolean staff) {
        return toLinks(db.list(LINK_COLUMNS
                + " FROM assessment_question_review_links l"
                + " JOIN lecture_chapters c ON c.id=l.chapter_id"
                + " JOIN lecture_videos v ON v.id=c.video_id AND v.cohort_id=? AND v.deleted_at IS NULL" + visibility(staff)
                + " WHERE l.question_id=? ORDER BY l.position", cohortId, questionId), MATCH_QUESTION);
    }

    /**
     * Fallback when a question has no direct link: the earliest chapter of the same core concept
     * (rule: the place the concept was first explained). Returns at most one link (matchType=TOPIC).
     */
    private List<ReviewLink> topicDefault(long cohortId, long topicId, boolean staff) {
        return toLinks(db.list(LINK_COLUMNS
                + " FROM lecture_chapters c"
                + " JOIN lecture_videos v ON v.id=c.video_id AND v.cohort_id=? AND v.deleted_at IS NULL" + visibility(staff)
                + " WHERE c.topic_id=? ORDER BY v.lecture_date,v.period_no,c.start_seconds,c.id LIMIT 1", cohortId, topicId), MATCH_TOPIC);
    }

    private static String visibility(boolean staff) {
        return staff ? "" : " AND v.visibility='PUBLIC'";
    }

    private static List<ReviewLink> toLinks(List<Map<String, Object>> rows, String matchType) {
        List<ReviewLink> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int start = row.get("startSeconds") == null ? 0 : ((Number) row.get("startSeconds")).intValue();
            String videoId = (String) row.get("youtubeVideoId");
            result.add(new ReviewLink(Db.id(row, "chapterId"), (String) row.get("chapterTitle"), start, Db.id(row, "videoId"),
                    (String) row.get("videoTitle"), LocalDate.parse(String.valueOf(row.get("lectureDate"))),
                    ((Number) row.get("periodNo")).intValue(), YouTubeLinks.embedUrl(videoId, start), YouTubeLinks.watchUrl(videoId, start), matchType));
        }
        return result;
    }

    /** Same rule as AssessmentController#answersReleased. */
    private static boolean answersReleased(Map<String, Object> assessment) {
        return "CLOSED".equals(assessment.get("status")) || !Db.now().isBefore(LearningSupport.utc(assessment.get("endAt")));
    }
}
