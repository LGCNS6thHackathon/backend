package kr.wsts.lms.domain.lecture;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.wsts.lms.domain.learning.LearningSupport;
import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.Classification;
import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.QuestionGuide;
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
 */
@Service
public class AssessmentReviewGuideService {
    static final String UNCLASSIFIED = "단원 미분류";
    static final String NO_REVIEW_VIDEO = "연결된 복습 영상 없음";

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
            List<ReviewLink> links = released ? links(cohortId, questionId, staff) : null;
            String message = released && links.isEmpty() ? NO_REVIEW_VIDEO : null;
            questions.add(new QuestionGuide(questionId, ((Number) q.get("position")).intValue(), classification, label, links, message));
        }
        return new ReviewGuide(assessmentId, released, questions);
    }

    private List<ReviewLink> links(long cohortId, long questionId, boolean staff) {
        String visibility = staff ? "" : " AND v.visibility='PUBLIC'";
        List<ReviewLink> result = new ArrayList<>();
        for (Map<String, Object> row : db.list("SELECT c.id AS chapter_id,c.title AS chapter_title,c.start_seconds,v.id AS video_id,v.title AS video_title,v.lecture_date,v.period_no,v.youtube_video_id"
                + " FROM assessment_question_review_links l"
                + " JOIN lecture_chapters c ON c.id=l.chapter_id"
                + " JOIN lecture_videos v ON v.id=c.video_id AND v.cohort_id=? AND v.deleted_at IS NULL" + visibility
                + " WHERE l.question_id=? ORDER BY l.position", cohortId, questionId)) {
            int start = row.get("startSeconds") == null ? 0 : ((Number) row.get("startSeconds")).intValue();
            String videoId = (String) row.get("youtubeVideoId");
            result.add(new ReviewLink(Db.id(row, "chapterId"), (String) row.get("chapterTitle"), start, Db.id(row, "videoId"),
                    (String) row.get("videoTitle"), LocalDate.parse(String.valueOf(row.get("lectureDate"))),
                    ((Number) row.get("periodNo")).intValue(), YouTubeLinks.embedUrl(videoId, start), YouTubeLinks.watchUrl(videoId, start)));
        }
        return result;
    }

    /** Same rule as AssessmentController#answersReleased. */
    private static boolean answersReleased(Map<String, Object> assessment) {
        return "CLOSED".equals(assessment.get("status")) || !Db.now().isBefore(LearningSupport.utc(assessment.get("endAt")));
    }
}
