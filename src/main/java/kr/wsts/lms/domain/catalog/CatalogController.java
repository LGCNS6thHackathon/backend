package kr.wsts.lms.domain.catalog;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/api/courses")
    public Map<String, Object> courses(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return catalog.courses(page, size); }

    @PostMapping("/api/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCourse(@RequestBody Map<String, Object> input) { return catalog.createCourse(input); }

    @GetMapping("/api/courses/{courseId}")
    public Map<String, Object> course(@PathVariable long courseId) { return catalog.course(courseId); }

    @PatchMapping("/api/courses/{courseId}")
    public Map<String, Object> updateCourse(@PathVariable long courseId, @RequestBody Map<String, Object> input) { return catalog.updateCourse(courseId, input); }

    @GetMapping("/api/courses/{courseId}/cohorts")
    public Map<String, Object> courseCohorts(@PathVariable long courseId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return catalog.cohorts(courseId, page, size); }

    @PostMapping("/api/courses/{courseId}/cohorts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCohort(@PathVariable long courseId, @RequestBody Map<String, Object> input) { return catalog.createCohort(courseId, input); }

    @GetMapping("/api/cohorts")
    public Map<String, Object> cohorts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return catalog.cohorts(null, page, size); }

    @GetMapping("/api/cohorts/{cohortId}")
    public Map<String, Object> cohort(@PathVariable long cohortId) { return catalog.cohort(cohortId); }

    @PatchMapping("/api/cohorts/{cohortId}")
    public Map<String, Object> updateCohort(@PathVariable long cohortId, @RequestBody Map<String, Object> input) { return catalog.updateCohort(cohortId, input); }

    @GetMapping("/api/cohorts/{cohortId}/enrollments")
    public Map<String, Object> enrollments(@PathVariable long cohortId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return catalog.enrollments(cohortId, page, size); }

    @PostMapping("/api/cohorts/{cohortId}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> enroll(@PathVariable long cohortId, @RequestBody Map<String, Object> input) { return catalog.enroll(cohortId, input); }

    @PatchMapping("/api/cohorts/{cohortId}/enrollments/{enrollmentId}")
    public Map<String, Object> updateEnrollment(@PathVariable long cohortId, @PathVariable long enrollmentId, @RequestBody Map<String, Object> input) { return catalog.updateEnrollment(cohortId, enrollmentId, input); }

    @GetMapping("/api/cohorts/{cohortId}/dashboard")
    public Map<String, Object> dashboard(@PathVariable long cohortId) { return catalog.dashboard(cohortId); }
}
