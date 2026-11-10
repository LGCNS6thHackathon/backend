package kr.wsts.lms.domain.lecture;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class LectureVideoController {
    private final LectureVideoService service;
    public LectureVideoController(LectureVideoService service) { this.service=service; }
    @GetMapping("/cohorts/{cohortId}/lecture-videos")
    public Map<String,Object> list(@PathVariable long cohortId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.list(cohortId,false,page,size); }
    @GetMapping("/lecture-videos/{videoId}")
    public Map<String,Object> get(@PathVariable long videoId) { return service.get(videoId,false); }
}
