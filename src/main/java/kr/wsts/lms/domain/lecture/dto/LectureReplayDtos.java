package kr.wsts.lms.domain.lecture.dto;

import java.time.LocalDate;
import java.util.List;

/** Response DTOs for the lecture replay feature. Domain models are never serialized directly. */
public final class LectureReplayDtos {
    private LectureReplayDtos() {
    }

    /** Calendar: dates in the range that have at least one replay video. Dates absent here show no replay button. */
    public record ReplayDates(long cohortId, LocalDate from, LocalDate to, List<ReplayDate> dates) {
    }

    public record ReplayDate(LocalDate date, int videoCount) {
    }

    /** Replay page for one date. {@code videos} is empty when no lecture is registered; the client shows the empty-state message. */
    public record DailyReplay(long cohortId, LocalDate date, List<Video> videos, String emptyMessage) {
    }

    public record Video(long id, String title, int periodNo, String youtubeVideoId, Integer durationSeconds,
                        String embedUrl, String watchUrl, List<Chapter> chapters) {
    }

    public record Chapter(long id, String title, int startSeconds, int position) {
    }

    /**
     * One card of the frontend "강의 다시보기 아카이브" (shape of {@code ReplayLecture} in frontend lms-data.ts).
     * One card per lecture date; {@code videoUrl}/{@code embedUrl} open the first period, {@code periods} lists all.
     * {@code instructor}, {@code duration} are null when not registered. {@code hasAiSummary} is always false (AI summary is out of scope).
     */
    public record ReplayCard(long id, String date, String title, String subject, String instructor, String duration,
                             String videoUrl, String embedUrl, List<String> keyTopics, boolean hasAiSummary, List<PeriodLink> periods) {
    }

    public record PeriodLink(long videoId, int periodNo, String embedUrl, String watchUrl) {
    }

    public record RecentReplays(long cohortId, List<ReplayCard> items) {
    }

    /**
     * Where a "복습하기" link lands. The player starts at {@code startSeconds} (0 = from the beginning) with autoplay off,
     * so the user presses play. {@code unavailableMessage} is what the client shows if the embed fails (deleted, private, embed blocked).
     */
    public record Playback(long videoId, String videoTitle, LocalDate lectureDate, int periodNo, String youtubeVideoId,
                           Long chapterId, String chapterTitle, int startSeconds, String embedUrl, String watchUrl,
                           String autoplayNotice, String unavailableMessage) {
    }
}
