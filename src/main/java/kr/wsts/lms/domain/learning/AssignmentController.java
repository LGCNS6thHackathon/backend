package kr.wsts.lms.domain.learning;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import kr.wsts.lms.domain.attachment.FileService;
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
    private final FileService files;
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DISPLAY_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public AssignmentController(Db db,Actor actor,LearningSupport support,FileService files) {this.db=db;this.actor=actor;this.support=support;this.files=files;}

    @GetMapping("/cohorts/{cohortId}/assignments")
    public Map<String,Object> list(@PathVariable long cohortId,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        actor.cohort(cohortId); LearningSupport.page(page); LearningSupport.size(size);
        if(q.length()>200) throw AppException.bad("검색어는 200자 이내로 입력해주세요.");
        String search="%"+q.toLowerCase(Locale.ROOT)+"%";
        var result=new LinkedHashMap<>(db.page("SELECT * FROM assignments WHERE cohort_id=? AND deleted_at IS NULL AND LOWER(title) LIKE ? ORDER BY due_at DESC,id DESC",
                "SELECT COUNT(*) FROM assignments WHERE cohort_id=? AND deleted_at IS NULL AND LOWER(title) LIKE ?",page,size,cohortId,search));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        result.put("items",items.stream().map(this::card).toList());
        return result;
    }

    @PostMapping("/cohorts/{cohortId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> create(@PathVariable long cohortId,@RequestBody Map<String,Object> body) {
        actor.requireAdmin(cohortId);LocalDateTime due=Input.dateTime(body,"dueAt");
        support.withinCohort(cohortId,due.plusHours(9).toLocalDate(),due.plusHours(9).toLocalDate());
        Instructor instructor=instructor(body,Map.of());
        long id=db.insert("INSERT INTO assignments(cohort_id,author_id,instructor_id,instructor_name,title,description,due_at,max_score,subject,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                cohortId,actor.id(),instructor.id(),instructor.name(),Input.str(body,"title",2,160),Input.str(body,"description",2,20000),due,Input.integer(body,"maxScore",100,1,1000),subject(body),Db.now(),Db.now());
        return db.one("SELECT * FROM assignments WHERE id=?",id);
    }

    @GetMapping("/assignments/{id}")
    public Map<String,Object> get(@PathVariable long id) {
        Map<String,Object> result=card(support.assignment(id));
        result.put("files",files.assignmentFiles(id));
        return result;
    }

    @PatchMapping("/assignments/{id}")
    @Transactional
    public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> old=db.one("SELECT * FROM assignments WHERE id=? AND deleted_at IS NULL FOR UPDATE",id);long cohortId=Db.id(old,"cohortId");actor.requireAdmin(cohortId);
        Map<String,Object> merged=LearningSupport.merged(old,body);
        Instructor instructor=instructor(body,old);
        LocalDateTime due=LearningSupport.dateTime(body,"dueAt",old);
        support.withinCohort(cohortId,due.plusHours(9).toLocalDate(),due.plusHours(9).toLocalDate());
        int maxScore=Input.integer(merged,"maxScore",100,1,1000);
        if(db.count("SELECT COUNT(*) FROM submission_reviews r JOIN submissions s ON s.id=r.submission_id WHERE s.assignment_id=? AND r.score>?",id,maxScore)>0)
            throw AppException.conflict("이미 부여한 점수보다 낮은 최대 점수로 바꿀 수 없습니다.");
        db.update("UPDATE assignments SET title=?,description=?,due_at=?,max_score=?,instructor_id=?,instructor_name=?,subject=?,updated_at=? WHERE id=?",Input.str(merged,"title",2,160),
                Input.str(merged,"description",2,20000),due,maxScore,instructor.id(),instructor.name(),subject(merged),Db.now(),id);
        return db.one("SELECT * FROM assignments WHERE id=?",id);
    }

    @DeleteMapping("/assignments/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable long id) {
        Map<String,Object> row=db.one("SELECT * FROM assignments WHERE id=? AND deleted_at IS NULL FOR UPDATE",id);actor.requireAdmin(Db.id(row,"cohortId"));
        var now=Db.now();
        db.update("UPDATE assignments SET deleted_at=?,updated_at=? WHERE id=?",now,now,id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/assignments/{assignmentId}/submissions")
    public Map<String,Object> submissions(@PathVariable long assignmentId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        Map<String,Object> assignment=support.assignment(assignmentId);boolean administrator=support.admin(Db.id(assignment,"cohortId"));
        LearningSupport.page(page);LearningSupport.size(size);
        String where=" WHERE s.assignment_id=?";List<Object> args=new ArrayList<>(List.of(assignmentId));
        if(!administrator) {where+=" AND s.user_id=?";args.add(actor.id());}
        String from=" FROM submissions s JOIN users u ON u.id=s.user_id LEFT JOIN submission_reviews r ON r.submission_id=s.id";
        var result=new LinkedHashMap<>(db.page("SELECT s.id"+from+where+" ORDER BY s.submitted_at DESC,s.id DESC",
                "SELECT COUNT(*)"+from+where,page,size,args.toArray()));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get("items");
        result.put("items",rows.stream().map(row->submissionView(Db.id(row,"id"))).toList());
        return result;
    }

    @PutMapping(value="/assignments/{assignmentId}/submission",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String,Object> saveSubmission(@PathVariable long assignmentId,@RequestBody Map<String,Object> body) {
        return save(assignmentId,body,false);
    }

    @PutMapping(value="/assignments/{assignmentId}/submission",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional(rollbackFor=IOException.class)
    public Map<String,Object> saveSubmissionWithFile(@PathVariable long assignmentId,@RequestParam Map<String,String> fields,
            @RequestPart(value="file",required=false) MultipartFile file) throws IOException {
        Map<String,Object> body=new LinkedHashMap<>(fields);
        var submission=save(assignmentId,body,false);
        long submissionId=Db.id(submission,"id");
        if(file!=null)files.upload("submission",submissionId,file);
        return submissionView(submissionId);
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
        Optional<Map<String,Object>> old=db.find("SELECT * FROM submissions WHERE assignment_id=? AND user_id=? FOR UPDATE",assignmentId,actor.id());
        var previous=old.orElse(Map.of());
        Map<String,Object> input=new LinkedHashMap<>();
        input.put("body",submissionField(body,"body","submitMemo",previous));
        input.put("repositoryUrl",submissionField(body,"repositoryUrl","repoUrl",previous));
        input.put("deploymentUrl",submissionField(body,"deploymentUrl","deployUrl",previous));
        String text=Input.optional(input,"body","",20000),url=Input.url(input,"repositoryUrl",false),deployment=Input.url(input,"deploymentUrl",false);
        if(text.isBlank()&&url.isBlank())throw AppException.bad("제출 내용 또는 GitHub 저장소 주소를 입력해주세요.");
        long id;
        if(old.isPresent()) {
            id=Db.id(old.get(),"id");
            if(createOnly) throw AppException.conflict("이미 제출한 과제입니다. 제출물 수정 API를 사용해주세요.");
            if(db.count("SELECT COUNT(*) FROM submission_reviews WHERE submission_id=?",id)>0) throw AppException.conflict("평가가 완료된 제출물은 수정할 수 없습니다.");
            boolean hasFiles=db.count("SELECT COUNT(*) FROM files WHERE submission_id=? AND deleted_at IS NULL",id)>0;
            var now=Db.now();var submitted=hasFiles?LearningSupport.utc(previous.get("submittedAt")):now;
            db.update("UPDATE submissions SET body=?,repository_url=?,deployment_url=?,submitted_at=?,updated_at=? WHERE id=?",text,url,deployment,submitted,now,id);
        } else id=db.insert("INSERT INTO submissions(assignment_id,user_id,body,repository_url,deployment_url,submitted_at,updated_at) VALUES(?,?,?,?,?,?,?)",assignmentId,actor.id(),text,url,deployment,Db.now(),Db.now());
        return submissionView(id);
    }

    @PatchMapping("/submissions/{submissionId}/review")
    @Transactional
    public Map<String,Object> review(@PathVariable long submissionId,@RequestBody Map<String,Object> body) {
        Map<String,Object> initial=db.one("SELECT * FROM submissions WHERE id=?",submissionId);
        Map<String,Object> assignment=db.one("SELECT * FROM assignments WHERE id=? AND deleted_at IS NULL FOR UPDATE",Db.id(initial,"assignmentId"));long cohortId=Db.id(assignment,"cohortId");actor.requireAdmin(cohortId);
        Map<String,Object> submission=db.one("SELECT * FROM submissions WHERE id=? FOR UPDATE",submissionId);
        int score=Input.integer(body,"score",-1,0,((Number)assignment.get("maxScore")).intValue());
        if(score<0) throw AppException.bad("score를 입력해주세요.");
        String feedback=Input.str(body,"feedback",2,10000);
        Optional<Map<String,Object>> old=db.find("SELECT * FROM submission_reviews WHERE submission_id=?",submissionId);long id;
        if(old.isPresent()) {id=Db.id(old.get(),"id");db.update("UPDATE submission_reviews SET reviewer_id=?,score=?,feedback=?,updated_at=? WHERE id=?",actor.id(),score,feedback,Db.now(),id);}
        else id=db.insert("INSERT INTO submission_reviews(submission_id,reviewer_id,score,feedback,created_at,updated_at) VALUES(?,?,?,?,?,?)",submissionId,actor.id(),score,feedback,Db.now(),Db.now());
        support.notify(Db.id(submission,"userId"),cohortId,"ASSIGNMENT_REVIEW","과제 피드백이 등록되었습니다.",String.valueOf(assignment.get("title")),"SUBMISSION",submissionId);
        return reviewView(submissionId).orElseThrow();
    }

    private Map<String,Object> card(Map<String,Object> assignment) {
        var result=new LinkedHashMap<>(assignment);long id=Db.id(assignment,"id");
        var submission=db.find("SELECT id FROM submissions WHERE assignment_id=? AND user_id=?",id,actor.id())
                .map(row->submissionView(Db.id(row,"id")));
        var review=submission.flatMap(row->reviewView(Db.id(row,"id")));
        boolean submitted=submission.isPresent(),reviewed=review.isPresent();
        var due=LearningSupport.utc(assignment.get("dueAt"));boolean expired=!Db.now().isBefore(due);
        String instructor=(String)assignment.get("instructorName");
        if(instructor==null||instructor.isBlank())instructor=(String)db.one("SELECT name FROM users WHERE id=?",Db.id(assignment,"authorId")).get("name");
        String classification=(String)assignment.get("subject");
        result.put("subject",classification==null||classification.isBlank()?"과제":classification);
        result.put("author",instructor);
        result.put("dueDate",displayTime(assignment.get("dueAt")));
        long days=ChronoUnit.DAYS.between(LocalDate.now(SEOUL),due.atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL).toLocalDate());
        result.put("dDay",submitted?"제출완료":expired?"마감":days==0?"오늘 마감":"D-"+days);
        result.put("status",submitted?"제출완료":"미제출");
        result.put("reviewStatus",reviewed?"REVIEWED":submitted?"SUBMITTED":"NOT_SUBMITTED");
        result.put("mySubmission",submission.orElse(null));result.put("myReview",review.orElse(null));
        result.put("submissionDate",submission.map(row->displayTime(row.get("submittedAt"))).orElse(null));
        result.put("feedback",review.map(row->Map.of("mentor",row.get("reviewerName"),"score",row.get("score")+" / "+assignment.get("maxScore"),"comment",row.get("feedback"))).orElse(null));
        result.put("canSubmit",!submitted&&!expired);
        result.put("canResubmit",submitted&&!expired&&!reviewed);
        result.put("submissionLocked",expired||reviewed);
        result.put("lockReason",reviewed?"REVIEWED":expired?"DEADLINE_PASSED":null);
        return result;
    }

    private Map<String,Object> submissionView(long id) {
        var row=db.one("SELECT s.*,u.name AS user_name,r.score,r.feedback,r.reviewer_id,reviewer.name AS reviewer_name FROM submissions s JOIN users u ON u.id=s.user_id LEFT JOIN submission_reviews r ON r.submission_id=s.id LEFT JOIN users reviewer ON reviewer.id=r.reviewer_id WHERE s.id=?",id);
        row.put("repoUrl",row.get("repositoryUrl"));row.put("deployUrl",row.get("deploymentUrl"));row.put("submitMemo",row.get("body"));
        var attachments=files.submissionFiles(id);row.put("files",attachments);
        row.put("fileName",attachments.isEmpty()?null:attachments.get(0).get("originalName"));
        row.put("status",row.get("score")==null?"SUBMITTED":"REVIEWED");
        return row;
    }

    private Optional<Map<String,Object>> reviewView(long submissionId) {
        return db.find("SELECT r.*,u.name AS reviewer_name FROM submission_reviews r JOIN users u ON u.id=r.reviewer_id WHERE r.submission_id=?",submissionId);
    }

    private static Object submissionField(Map<String,Object> input,String canonical,String alias,Map<String,Object> previous) {
        Object value=input.containsKey(canonical)?input.get(canonical):input.containsKey(alias)?input.get(alias):previous.get(canonical);
        if(input.containsKey(canonical)&&input.containsKey(alias)) {
            Object first=input.get(canonical),second=input.get(alias);
            if(!(first==null||first instanceof String)||!(second==null||second instanceof String)
                    ||!Objects.equals(first==null?"":((String)first).trim(),second==null?"":((String)second).trim()))
                throw AppException.bad(canonical+"와 "+alias+" 값이 다릅니다.");
        }
        return value;
    }

    private static String displayTime(Object value) {
        return LearningSupport.utc(value).atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL).format(DISPLAY_TIME);
    }

    private static String subject(Map<String,Object> input) {
        String subject=Input.optional(input,"subject","",100);
        return subject.isBlank()?null:subject;
    }

    private Instructor instructor(Map<String,Object> changes,Map<String,Object> existing) {
        Long id=existing.get("instructorId")==null?null:Db.id(existing,"instructorId");
        String name=(String)existing.get("instructorName");
        if(changes.containsKey("instructorId")) {
            id=changes.get("instructorId")==null?null:Input.id(changes,"instructorId");
            if(id==null) name=null;
            else name=(String)db.find("SELECT name FROM users WHERE id=? AND status='ACTIVE' AND role='ADMIN'",id)
                    .orElseThrow(()->AppException.bad("활성 관리자 계정을 담당자로 지정해주세요.")).get("name");
        }
        if(changes.containsKey("instructorName")) name=changes.get("instructorName")==null?null:Input.str(changes,"instructorName",2,80);
        return new Instructor(id,name);
    }

    private record Instructor(Long id,String name) { }
}
