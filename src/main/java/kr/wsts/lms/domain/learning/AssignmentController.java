package kr.wsts.lms.domain.learning;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import kr.wsts.lms.global.support.Input;

@RestController
@RequestMapping("/api")
public class AssignmentController {
    private final Db db;
    private final Actor actor;
    private final LearningSupport support;
    public AssignmentController(Db db,Actor actor,LearningSupport support) {this.db=db;this.actor=actor;this.support=support;}

    @GetMapping("/cohorts/{cohortId}/assignments")
    public Map<String,Object> list(@PathVariable long cohortId,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        actor.cohort(cohortId); LearningSupport.page(page); LearningSupport.size(size);
        if(q.length()>200) throw AppException.bad("검색어는 200자 이내로 입력해주세요.");
        String search="%"+q.toLowerCase(Locale.ROOT)+"%";
        return db.page("SELECT * FROM assignments WHERE cohort_id=? AND deleted_at IS NULL AND LOWER(title) LIKE ? ORDER BY due_at DESC,id DESC",
                "SELECT COUNT(*) FROM assignments WHERE cohort_id=? AND deleted_at IS NULL AND LOWER(title) LIKE ?",page,size,cohortId,search);
    }

    @PostMapping("/cohorts/{cohortId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> create(@PathVariable long cohortId,@RequestBody Map<String,Object> body) {
        actor.staff(cohortId);LocalDateTime due=Input.dateTime(body,"dueAt");
        support.withinCohort(cohortId,due.plusHours(9).toLocalDate(),due.plusHours(9).toLocalDate());
        Instructor instructor=instructor(cohortId,body,Map.of());
        long id=db.insert("INSERT INTO assignments(cohort_id,author_id,instructor_id,instructor_name,title,description,due_at,max_score,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                cohortId,actor.id(),instructor.id(),instructor.name(),Input.str(body,"title",2,160),Input.str(body,"description",2,20000),due,Input.integer(body,"maxScore",100,1,1000),Db.now(),Db.now());
        return db.one("SELECT * FROM assignments WHERE id=?",id);
    }

    @GetMapping("/assignments/{id}")
    public Map<String,Object> get(@PathVariable long id) {
        Map<String,Object> assignment=support.assignment(id);
        Map<String,Object> result=new LinkedHashMap<>(assignment);
        Optional<Map<String,Object>> submission=db.find("SELECT * FROM submissions WHERE assignment_id=? AND user_id=?",id,actor.id());
        result.put("mySubmission",submission.orElse(null));
        result.put("myReview",submission.flatMap(s->db.find("SELECT * FROM submission_reviews WHERE submission_id=?",Db.id(s,"id"))).orElse(null));
        result.put("files",db.list("SELECT id,original_name,content_type,size_bytes,created_at FROM files WHERE assignment_id=? AND deleted_at IS NULL ORDER BY id",id));
        return result;
    }

    @PatchMapping("/assignments/{id}")
    @Transactional
    public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> old=db.one("SELECT * FROM assignments WHERE id=? AND deleted_at IS NULL FOR UPDATE",id);long cohortId=Db.id(old,"cohortId");actor.staff(cohortId);
        Map<String,Object> merged=LearningSupport.merged(old,body);
        Instructor instructor=instructor(cohortId,body,old);
        LocalDateTime due=LearningSupport.dateTime(body,"dueAt",old);
        support.withinCohort(cohortId,due.plusHours(9).toLocalDate(),due.plusHours(9).toLocalDate());
        int maxScore=Input.integer(merged,"maxScore",100,1,1000);
        if(db.count("SELECT COUNT(*) FROM submission_reviews r JOIN submissions s ON s.id=r.submission_id WHERE s.assignment_id=? AND r.score>?",id,maxScore)>0)
            throw AppException.conflict("이미 부여한 점수보다 낮은 최대 점수로 바꿀 수 없습니다.");
        db.update("UPDATE assignments SET title=?,description=?,due_at=?,max_score=?,instructor_id=?,instructor_name=?,updated_at=? WHERE id=?",Input.str(merged,"title",2,160),
                Input.str(merged,"description",2,20000),due,maxScore,instructor.id(),instructor.name(),Db.now(),id);
        return db.one("SELECT * FROM assignments WHERE id=?",id);
    }

    @DeleteMapping("/assignments/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable long id) {
        Map<String,Object> row=db.one("SELECT * FROM assignments WHERE id=? AND deleted_at IS NULL FOR UPDATE",id);actor.staff(Db.id(row,"cohortId"));
        var now=Db.now();
        db.update("UPDATE assignments SET deleted_at=?,updated_at=? WHERE id=?",now,now,id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/assignments/{assignmentId}/submissions")
    public Map<String,Object> submissions(@PathVariable long assignmentId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        Map<String,Object> assignment=support.assignment(assignmentId);boolean staff=support.staff(Db.id(assignment,"cohortId"));
        LearningSupport.page(page);LearningSupport.size(size);
        String where=" WHERE s.assignment_id=?";List<Object> args=new ArrayList<>(List.of(assignmentId));
        if(!staff) {where+=" AND s.user_id=?";args.add(actor.id());}
        String from=" FROM submissions s JOIN users u ON u.id=s.user_id LEFT JOIN submission_reviews r ON r.submission_id=s.id";
        return db.page("SELECT s.*,u.name AS user_name,r.score,r.feedback,r.reviewer_id"+from+where+" ORDER BY s.submitted_at DESC,s.id DESC",
                "SELECT COUNT(*)"+from+where,page,size,args.toArray());
    }

    @PutMapping("/assignments/{assignmentId}/submission")
    @Transactional
    public Map<String,Object> saveSubmission(@PathVariable long assignmentId,@RequestBody Map<String,Object> body) {
        return save(assignmentId,body,false);
    }

    @PostMapping("/assignments/{assignmentId}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> createSubmission(@PathVariable long assignmentId,@RequestBody Map<String,Object> body) {
        return save(assignmentId,body,true);
    }

    private Map<String,Object> save(long assignmentId,Map<String,Object> body,boolean createOnly) {
        // All writes for an assignment lock the parent first, including grading.
        Map<String,Object> assignment=db.one("SELECT * FROM assignments WHERE id=? AND deleted_at IS NULL FOR UPDATE",assignmentId);long cohortId=Db.id(assignment,"cohortId");
        actor.cohort(cohortId);support.enrollment(cohortId,actor.id());
        if(!Db.now().isBefore(LearningSupport.utc(assignment.get("dueAt")))) throw AppException.conflict("제출 기한이 지났습니다.");
        String text=Input.str(body,"body",2,20000),url=Input.url(body,"repositoryUrl",false);
        Optional<Map<String,Object>> old=db.find("SELECT * FROM submissions WHERE assignment_id=? AND user_id=? FOR UPDATE",assignmentId,actor.id());
        long id;
        if(old.isPresent()) {
            id=Db.id(old.get(),"id");
            if(createOnly) throw AppException.conflict("이미 제출한 과제입니다. 제출물 수정 API를 사용해주세요.");
            if(db.count("SELECT COUNT(*) FROM submission_reviews WHERE submission_id=?",id)>0) throw AppException.conflict("평가가 완료된 제출물은 수정할 수 없습니다.");
            db.update("UPDATE submissions SET body=?,repository_url=?,submitted_at=?,updated_at=? WHERE id=?",text,url,Db.now(),Db.now(),id);
        } else id=db.insert("INSERT INTO submissions(assignment_id,user_id,body,repository_url,submitted_at,updated_at) VALUES(?,?,?,?,?,?)",assignmentId,actor.id(),text,url,Db.now(),Db.now());
        return db.one("SELECT * FROM submissions WHERE id=?",id);
    }

    @PatchMapping("/submissions/{submissionId}/review")
    @Transactional
    public Map<String,Object> review(@PathVariable long submissionId,@RequestBody Map<String,Object> body) {
        Map<String,Object> initial=db.one("SELECT * FROM submissions WHERE id=?",submissionId);
        Map<String,Object> assignment=db.one("SELECT * FROM assignments WHERE id=? AND deleted_at IS NULL FOR UPDATE",Db.id(initial,"assignmentId"));long cohortId=Db.id(assignment,"cohortId");actor.staff(cohortId);
        Map<String,Object> submission=db.one("SELECT * FROM submissions WHERE id=? FOR UPDATE",submissionId);
        int score=Input.integer(body,"score",-1,0,((Number)assignment.get("maxScore")).intValue());
        if(score<0) throw AppException.bad("score를 입력해주세요.");
        String feedback=Input.str(body,"feedback",2,10000);
        Optional<Map<String,Object>> old=db.find("SELECT * FROM submission_reviews WHERE submission_id=?",submissionId);long id;
        if(old.isPresent()) {id=Db.id(old.get(),"id");db.update("UPDATE submission_reviews SET reviewer_id=?,score=?,feedback=?,updated_at=? WHERE id=?",actor.id(),score,feedback,Db.now(),id);}
        else id=db.insert("INSERT INTO submission_reviews(submission_id,reviewer_id,score,feedback,created_at,updated_at) VALUES(?,?,?,?,?,?)",submissionId,actor.id(),score,feedback,Db.now(),Db.now());
        support.notify(Db.id(submission,"userId"),cohortId,"ASSIGNMENT_REVIEW","과제 피드백이 등록되었습니다.",String.valueOf(assignment.get("title")),"SUBMISSION",submissionId);
        return db.one("SELECT * FROM submission_reviews WHERE id=?",id);
    }

    private Instructor instructor(long cohortId,Map<String,Object> changes,Map<String,Object> existing) {
        Long id=existing.get("instructorId")==null?null:Db.id(existing,"instructorId");
        String name=(String)existing.get("instructorName");
        if(changes.containsKey("instructorId")) {
            id=changes.get("instructorId")==null?null:Input.id(changes,"instructorId");
            if(id==null) name=null;
            else name=(String)db.find("SELECT u.name FROM users u JOIN enrollments e ON e.user_id=u.id WHERE u.id=? AND u.status='ACTIVE' AND e.cohort_id=? AND e.status='ACTIVE' AND e.role='INSTRUCTOR'",id,cohortId)
                    .orElseThrow(()->AppException.bad("해당 기수의 활성 강사를 지정해주세요.")).get("name");
        }
        if(changes.containsKey("instructorName")) name=changes.get("instructorName")==null?null:Input.str(changes,"instructorName",2,80);
        return new Instructor(id,name);
    }

    private record Instructor(Long id,String name) { }
}
