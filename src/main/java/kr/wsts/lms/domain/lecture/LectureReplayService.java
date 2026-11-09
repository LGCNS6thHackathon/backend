package kr.wsts.lms.domain.lecture;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.Chapter;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.DailyReplay;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.PeriodLink;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.Playback;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.RecentReplays;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.ReplayCard;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.ReplayDate;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.ReplayDates;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.Video;
import kr.wsts.lms.domain.lecture.model.LectureChapter;
import kr.wsts.lms.domain.lecture.model.LectureVideo;
import kr.wsts.lms.domain.lecture.repository.LectureRepository;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import org.springframework.stereotype.Service;

/**
 * Student-facing replay reads (spec rows 17, 18, 21).
 * Every read first checks that the caller belongs to the cohort, so other cohorts' lectures are not reachable.
 */
@Service
public class LectureReplayService {
    static final String EMPTY_MESSAGE = "등록된 강의가 없습니다.";
    static final String AUTOPLAY_NOTICE = "자동 재생이 제한될 수 있습니다. 재생 버튼을 눌러 시청하세요.";
    static final String UNAVAILABLE_MESSAGE = "영상이 삭제·비공개되었거나 외부 재생이 제한되어 재생할 수 없습니다.";
    private static final int MAX_RANGE_DAYS = 62;

    private final LectureRepository lectures;
    private final Actor actor;

    public LectureReplayService(LectureRepository lectures, Actor actor) {
        this.lectures = lectures;
        this.actor = actor;
    }

    public ReplayDates replayDates(long cohortId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw AppException.bad("종료일은 시작일 이후여야 합니다.");
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) throw AppException.bad("조회 기간은 최대 " + MAX_RANGE_DAYS + "일입니다.");
        actor.cohort(cohortId);
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (LectureVideo video : lectures.findStudentVideosBetween(cohortId, from, to)) counts.merge(video.lectureDate(), 1, Integer::sum);
        List<ReplayDate> dates = counts.entrySet().stream().map(e -> new ReplayDate(e.getKey(), e.getValue())).toList();
        return new ReplayDates(cohortId, from, to, dates);
    }

    public DailyReplay dailyReplay(long cohortId, LocalDate date) {
        actor.cohort(cohortId);
        List<Video> videos = lectures.findStudentVideosForDate(cohortId, date).stream().map(video -> toVideo(cohortId, video)).toList();
        return new DailyReplay(cohortId, date, videos, videos.isEmpty() ? EMPTY_MESSAGE : null);
    }

    /** Latest lecture dates up to today (KST), newest first, shaped for the frontend replay archive cards. */
    public RecentReplays recent(long cohortId, int limit) {
        if (limit < 1 || limit > 30) throw AppException.bad("limit은 1~30 사이여야 합니다.");
        actor.cohort(cohortId);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        Map<LocalDate, List<LectureVideo>> byDate = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (LectureVideo video : lectures.findStudentVideosBetween(cohortId, LocalDate.of(2000, 1, 1), today))
            byDate.computeIfAbsent(video.lectureDate(), d -> new java.util.ArrayList<>()).add(video);
        List<ReplayCard> cards = byDate.values().stream().limit(limit).map(videos -> card(cohortId, videos)).toList();
        return new RecentReplays(cohortId, cards);
    }

    private ReplayCard card(long cohortId, List<LectureVideo> videos) {
        LectureVideo first = videos.get(0);
        List<String> topics = videos.stream().flatMap(v -> lectures.findChapters(cohortId, v.id()).stream().map(LectureChapter::title)).distinct().toList();
        Integer totalSeconds = videos.stream().allMatch(v -> v.durationSeconds() != null)
                ? videos.stream().mapToInt(LectureVideo::durationSeconds).sum() : null;
        String duration = totalSeconds == null ? null
                : (totalSeconds / 3600 > 0 ? totalSeconds / 3600 + "시간 " : "") + (totalSeconds % 3600) / 60 + "분";
        List<PeriodLink> periods = videos.stream().map(v -> new PeriodLink(v.id(), v.periodNo(),
                YouTubeLinks.embedUrl(v.youtubeVideoId(), 0), YouTubeLinks.watchUrl(v.youtubeVideoId(), 0))).toList();
        String title = first.title().replaceFirst("\\s*\\d+교시$", "");
        return new ReplayCard(first.id(), first.lectureDate().toString(), title, null, null, duration,
                periods.get(0).watchUrl(), periods.get(0).embedUrl(), topics, false, periods);
    }

    /** Playback position for a chapter (복습하기 → 소단원 시작 시점). */
    public Playback chapterPlayback(long cohortId, long chapterId) {
        actor.cohort(cohortId);
        LectureChapter chapter = lectures.findChapter(cohortId, chapterId).orElseThrow(AppException::notFound);
        LectureVideo video = lectures.findStudentVideo(cohortId, chapter.videoId()).orElseThrow(AppException::notFound);
        return playback(video, chapter);
    }

    /** Playback of a whole video from an optional start second (교시 선택 후 재생). */
    public Playback videoPlayback(long cohortId, long videoId, int startSeconds) {
        if (startSeconds < 0) throw AppException.bad("start는 0 이상이어야 합니다.");
        actor.cohort(cohortId);
        LectureVideo video = lectures.findStudentVideo(cohortId, videoId).orElseThrow(AppException::notFound);
        int start = video.durationSeconds() != null && startSeconds >= video.durationSeconds() ? 0 : startSeconds;
        return playback(video, null, start);
    }

    static Playback playback(LectureVideo video, LectureChapter chapter) {
        int start = chapter == null || chapter.startSeconds() == null ? 0 : chapter.startSeconds();
        return playback(video, chapter, start);
    }

    private static Playback playback(LectureVideo video, LectureChapter chapter, int start) {
        String id = video.youtubeVideoId();
        return new Playback(video.id(), video.title(), video.lectureDate(), video.periodNo(), id,
                chapter == null ? null : chapter.id(), chapter == null ? null : chapter.title(), start,
                YouTubeLinks.embedUrl(id, start), YouTubeLinks.watchUrl(id, start), AUTOPLAY_NOTICE, UNAVAILABLE_MESSAGE);
    }

    private Video toVideo(long cohortId, LectureVideo video) {
        List<Chapter> chapters = lectures.findChapters(cohortId, video.id()).stream()
                .map(c -> new Chapter(c.id(), c.title(), c.startSeconds() == null ? 0 : c.startSeconds(), c.position())).toList();
        String id = video.youtubeVideoId();
        return new Video(video.id(), video.title(), video.periodNo(), id, video.durationSeconds(),
                YouTubeLinks.embedUrl(id, 0), YouTubeLinks.watchUrl(id, 0), chapters);
    }
}
