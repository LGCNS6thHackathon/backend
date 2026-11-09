package kr.wsts.lms.domain.lecture;

import kr.wsts.lms.domain.lecture.dto.ReviewGuideDtos.ReviewGuide;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 역량평가 문제풀이 화면 보조 API: 문제별 단원 표시와 복습 영상 링크.
 * 기존 평가 응시·채점 API(AssessmentController)는 변경하지 않는다.
 */
@RestController
public class AssessmentReviewGuideController {
    private final AssessmentReviewGuideService service;

    public AssessmentReviewGuideController(AssessmentReviewGuideService service) {
        this.service = service;
    }

    @GetMapping("/api/assessments/{assessmentId}/review-guide")
    public ReviewGuide guide(@PathVariable long assessmentId) {
        return service.guide(assessmentId);
    }
}
