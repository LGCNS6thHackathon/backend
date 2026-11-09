package kr.wsts.lms.domain.community;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api")
public class CommunityController {
    private final CommunityService service;

    public CommunityController(CommunityService service) { this.service=service; }
    @GetMapping("/cohorts/{cohortId}/posts")
    public Map<String,Object> posts(@PathVariable long cohortId,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.posts(cohortId,category,q,page,size); }
    @PostMapping("/cohorts/{cohortId}/posts") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable long cohortId,@RequestBody Map<String,Object> input) { return service.create(cohortId,input); }
    @GetMapping("/posts/{postId}")
    public Map<String,Object> get(@PathVariable long postId) { return service.get(postId); }
    @PatchMapping("/posts/{postId}")
    public Map<String,Object> update(@PathVariable long postId,@RequestBody Map<String,Object> input) { return service.update(postId,input); }
    @DeleteMapping("/posts/{postId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long postId) { service.delete(postId); }
    @GetMapping("/posts/{postId}/comments")
    public Map<String,Object> comments(@PathVariable long postId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.comments(postId,page,size); }
    @PostMapping("/posts/{postId}/comments") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> comment(@PathVariable long postId,@RequestBody Map<String,Object> input) { return service.comment(postId,input); }
    @PatchMapping("/comments/{commentId}")
    public Map<String,Object> editComment(@PathVariable long commentId,@RequestBody Map<String,Object> input) { return service.editComment(commentId,input); }
    @DeleteMapping("/comments/{commentId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable long commentId) { service.deleteComment(commentId); }
}
