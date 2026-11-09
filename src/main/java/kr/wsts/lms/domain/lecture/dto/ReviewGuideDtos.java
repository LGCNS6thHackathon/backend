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

    /**
     * 역량평가 리포트의 "추천 복습 큐레이션": the caller's wrong answers grouped by review chapter, most wrong first.
     * {@code summary} is a ready-to-show sentence for the top item (null when nothing to recommend).
     */
    public record Recommendations(long assessmentId, boolean reviewReleased, int wrongCount, List<Recommendation> items, String summary) {
    }

    public record Recommendation(long chapterId, String chapterTitle, LocalDate lectureDate, int periodNo, int startSeconds,
                                 String embedUrl, String watchUrl, String matchType, List<Integer> questionPositions) {
    }

    /**
     * {@code matchType}: QUESTION = chapter linked to this question by the author;
     * TOPIC = no direct link, so the earliest chapter of the same core concept is suggested (label it e.g. "같은 개념 강의").
     */
    public record ReviewLink(long chapterId, String chapterTitle, int startSeconds, long videoId, String videoTitle,
                             LocalDate lectureDate, int periodNo, String embedUrl, String watchUrl, String matchType) {
    }
}
