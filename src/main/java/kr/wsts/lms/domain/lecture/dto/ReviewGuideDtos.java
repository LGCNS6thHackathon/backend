package kr.wsts.lms.domain.lecture.dto;

import java.time.LocalDate;
import java.util.List;

/** 역량평가 문제별 학습 범위·복습 영상 응답 (spec rows 19, 20). */
public final class ReviewGuideDtos {
    private ReviewGuideDtos() {
    }

    /**
     * {@code reviewReleased=false}: answers/explanations are not public yet, so every question's review data is omitted.
     */
    public record ReviewGuide(long assessmentId, boolean reviewReleased, List<QuestionGuide> questions) {
    }

    /**
     * {@code classification} is null for an unclassified question; {@code classificationLabel} then reads "단원 미분류".
     * {@code reviewLinks} is null before release; after release an empty list comes with {@code reviewMessage}="연결된 복습 영상 없음".
     */
    public record QuestionGuide(long questionId, int position, Classification classification, String classificationLabel,
                                List<ReviewLink> reviewLinks, String reviewMessage) {
    }

    public record Classification(long subjectId, String subject, long unitId, String unit, long topicId, String topic) {
    }

    public record ReviewLink(long chapterId, String chapterTitle, int startSeconds, long videoId, String videoTitle,
                             LocalDate lectureDate, int periodNo, String embedUrl, String watchUrl) {
    }
}
