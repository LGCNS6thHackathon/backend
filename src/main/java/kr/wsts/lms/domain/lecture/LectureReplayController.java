package kr.wsts.lms.domain.lecture;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.DailyReplay;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.Playback;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.RecentReplays;
import kr.wsts.lms.domain.lecture.dto.LectureReplayDtos.ReplayDates;
import kr.wsts.lms.global.error.AppException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 강의 다시보기 조회 API (학생). 영상 등록·수정·삭제(관리자)는 이 컨트롤러 범위가 아니다. */
@RestController
@RequestMapping("/api/cohorts/{cohortId}")
public class LectureReplayController {
    private final LectureReplayService service;

    public LectureReplayController(LectureReplayService service) {
        this.service = service;
    }

    /** 캘린더: 기간 안에서 다시보기 영상이 있는 날짜 목록. 목록에 없는 날짜는 버튼을 표시하지 않는다. */
    @GetMapping("/lecture-replays/dates")
    public ReplayDates dates(@PathVariable long cohortId, @RequestParam String from, @RequestParam String to) {
        return service.replayDates(cohortId, date(from, "from"), date(to, "to"));
    }

    /** 다시보기 아카이브 카드: 오늘까지의 최근 강의일 (프론트 ReplayLecture 형태). */
    @GetMapping("/lecture-replays/recent")
    public RecentReplays recent(@PathVariable long cohortId, @RequestParam(defaultValue = "3") int limit) {
        return service.recent(cohortId, limit);
    }

    /** 날짜별 강의 제목·교시별 영상 목록. */
    @GetMapping("/lecture-replays")
    public DailyReplay daily(@PathVariable long cohortId, @RequestParam String date) {
        return service.dailyReplay(cohortId, date(date, "date"));
    }

    /** 교시 영상 재생 정보. start(초)가 없으면 처음부터. */
    @GetMapping("/lecture-videos/{videoId}/playback")
    public Playback video(@PathVariable long cohortId, @PathVariable long videoId, @RequestParam(defaultValue = "0") int start) {
        return service.videoPlayback(cohortId, videoId, start);
    }

    /** 복습하기: 소단원 시작 시점 재생 정보. */
    @GetMapping("/lecture-chapters/{chapterId}/playback")
    public Playback chapter(@PathVariable long cohortId, @PathVariable long chapterId) {
        return service.chapterPlayback(cohortId, chapterId);
    }

    private static LocalDate date(String value, String name) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw AppException.bad(name + "는 YYYY-MM-DD 형식이어야 합니다.");
        }
    }
}
