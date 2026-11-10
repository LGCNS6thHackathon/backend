package kr.wsts.lms.domain.curriculum;

import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Db;
import org.springframework.stereotype.Component;

/** Cohort checks for topics and review chapters linked to assessment questions. FKs alone do not guarantee the cohort matches. */
@Component
public class CurriculumAccess {
    private final Db db;

    public CurriculumAccess(Db db) {
        this.db = db;
    }

    /** Throws 400 unless the topic (and its unit and subject) exists, is not deleted and belongs to the cohort. */
    public void requireTopicInCohort(long topicId, long cohortId) {
        long found = db.count("SELECT COUNT(*) FROM learning_topics t JOIN learning_units u ON u.id=t.unit_id JOIN learning_subjects s ON s.id=u.subject_id"
                + " WHERE t.id=? AND s.cohort_id=? AND t.deleted_at IS NULL AND u.deleted_at IS NULL AND s.deleted_at IS NULL", topicId, cohortId);
        if (found != 1) throw AppException.bad("해당 기수의 학습 주제(topicId)를 입력해주세요.");
    }

    /** Throws 400 unless the review chapter belongs to a non-deleted lecture video of the cohort. */
    public void requireChapterInCohort(long chapterId, long cohortId) {
        long found = db.count("SELECT COUNT(*) FROM lecture_chapters c JOIN lecture_videos v ON v.id=c.video_id"
                + " WHERE c.id=? AND v.cohort_id=? AND v.deleted_at IS NULL", chapterId, cohortId);
        if (found != 1) throw AppException.bad("해당 기수의 복습 강의 소단원(chapterId)을 입력해주세요.");
    }
}
