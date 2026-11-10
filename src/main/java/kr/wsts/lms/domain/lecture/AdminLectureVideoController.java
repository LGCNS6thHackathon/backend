package kr.wsts.lms.domain.lecture;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminLectureVideoController {
    private final LectureVideoService service;
    public AdminLectureVideoController(LectureVideoService service) { this.service=service; }

    @GetMapping("/cohorts/{cohortId}/lecture-videos")
    public Map<String,Object> list(@PathVariable long cohortId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(cohortId,true,page,size);
    }
    @PostMapping("/cohorts/{cohortId}/lecture-videos") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable long cohortId,@RequestBody Map<String,Object> input) { return service.create(cohortId,input); }
    @GetMapping("/lecture-videos/{videoId}")
    public Map<String,Object> get(@PathVariable long videoId) { return service.get(videoId,true); }
    @PatchMapping("/lecture-videos/{videoId}")
    public Map<String,Object> update(@PathVariable long videoId,@RequestBody Map<String,Object> input) { return service.update(videoId,input); }
    @DeleteMapping("/lecture-videos/{videoId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long videoId) { service.delete(videoId); }
}
