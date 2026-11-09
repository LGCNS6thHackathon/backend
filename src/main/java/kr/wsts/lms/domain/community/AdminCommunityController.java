package kr.wsts.lms.domain.community;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import kr.wsts.lms.global.support.Actor;

@RestController
@RequestMapping("/api/admin")
public class AdminCommunityController {
    private final CommunityService service;
    private final Actor actor;
    public AdminCommunityController(CommunityService service,Actor actor) { this.service=service; this.actor=actor; }
    @GetMapping("/cohorts/{cohortId}/posts")
    public Map<String,Object> posts(@PathVariable long cohortId,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { actor.requireAdmin(); return service.posts(cohortId,category,q,page,size); }
    @PostMapping("/cohorts/{cohortId}/posts") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable long cohortId,@RequestBody Map<String,Object> input) { actor.requireAdmin(); return service.create(cohortId,input); }
    @GetMapping("/posts/{postId}")
    public Map<String,Object> get(@PathVariable long postId) { actor.requireAdmin(); return service.get(postId); }
    @PatchMapping("/posts/{postId}")
    public Map<String,Object> update(@PathVariable long postId,@RequestBody Map<String,Object> input) { actor.requireAdmin(); return service.update(postId,input); }
    @DeleteMapping("/posts/{postId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long postId) { actor.requireAdmin(); service.delete(postId); }
}
