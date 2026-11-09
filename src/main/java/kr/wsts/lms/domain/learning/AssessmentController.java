package kr.wsts.lms.domain.learning;

import java.time.*;
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
public class AssessmentController {
    private final Db db;
    private final Actor actor;
    private final LearningSupport support;
    public AssessmentController(Db db,Actor actor,LearningSupport support) {this.db=db;this.actor=actor;this.support=support;}

    @GetMapping("/cohorts/{cohortId}/assessments")
    public Map<String,Object> assessments(@PathVariable long cohortId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        actor.cohort(cohortId);LearningSupport.page(page);LearningSupport.size(size);
        String where=" WHERE a.cohort_id=?";
        if(!support.staff(cohortId)) where+=" AND a.status<>'DRAFT'";
        return db.page("SELECT a.* FROM assessments a"+where+" ORDER BY a.start_at DESC,a.id DESC","SELECT COUNT(*) FROM assessments a"+where,page,size,cohortId);
    }

    @PostMapping("/cohorts/{cohortId}/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> create(@PathVariable long cohortId,@RequestBody Map<String,Object> body) {
        actor.staff(cohortId);LocalDateTime start=Input.dateTime(body,"startAt"),end=Input.dateTime(body,"endAt");
        LearningSupport.validRange(start,end);support.withinCohort(cohortId,start.plusHours(9).toLocalDate(),end.plusHours(9).toLocalDate());
        long id=db.insert("INSERT INTO assessments(cohort_id,title,description,domain,start_at,end_at,duration_minutes,status,created_by,created_at) VALUES(?,?,?,?,?,?,?,'DRAFT',?,?)",
                cohortId,Input.str(body,"title",2,160),Input.optional(body,"description","",20000),Input.str(body,"domain",1,100),start,end,
                Input.integer(body,"durationMinutes",40,1,480),actor.id(),Db.now());
        return db.one("SELECT * FROM assessments WHERE id=?",id);
    }

    @GetMapping("/assessments/{id}")
    public Map<String,Object> get(@PathVariable long id) {
        Map<String,Object> assessment=support.assessment(id);long cohortId=Db.id(assessment,"cohortId");boolean staff=support.staff(cohortId);
        if(!staff && "DRAFT".equals(assessment.get("status"))) throw AppException.notFound();
        Map<String,Object> response=new LinkedHashMap<>(assessment);
        response.put("questionCount",db.count("SELECT COUNT(*) FROM assessment_questions WHERE assessment_id=?",id));
        Optional<Map<String,Object>> attempt=db.find("SELECT * FROM assessment_attempts WHERE assessment_id=? AND user_id=?",id,actor.id());
        response.put("myAttempt",attempt.orElse(null));
        if(staff || attempt.isPresent()) response.put("questions",questions(id,staff || (attempt.map(this::finished).orElse(false) && answersReleased(assessment))));
        return response;
    }

    @PatchMapping("/assessments/{id}")
    @Transactional
    public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> old=db.one("SELECT * FROM assessments WHERE id=? FOR UPDATE",id);long cohortId=Db.id(old,"cohortId");actor.staff(cohortId);
        Map<String,Object> merged=LearningSupport.merged(old,body);
        LocalDateTime start=LearningSupport.dateTime(body,"startAt",old),end=LearningSupport.dateTime(body,"endAt",old);
        LearningSupport.validRange(start,end);support.withinCohort(cohortId,start.plusHours(9).toLocalDate(),end.plusHours(9).toLocalDate());
        int duration=Input.integer(merged,"durationMinutes",40,1,480);
        String state=body.containsKey("status")?Input.choice(body,"status","DRAFT","PUBLISHED","CLOSED"):String.valueOf(old.get("status"));
        if("CLOSED".equals(old.get("status")) && !state.equals("CLOSED")) throw AppException.conflict("종료한 평가는 다시 열 수 없습니다.");
        long attempts=db.count("SELECT COUNT(*) FROM assessment_attempts WHERE assessment_id=?",id);
        if(attempts>0 && (body.containsKey("startAt") || body.containsKey("endAt") || body.containsKey("durationMinutes") || body.containsKey("domain") || state.equals("DRAFT")))
            throw AppException.conflict("응시 기록이 생긴 평가의 일정, 제한 시간, 영역은 변경할 수 없습니다.");
        if(state.equals("PUBLISHED")) validateQuestions(id);
        db.update("UPDATE assessments SET title=?,description=?,domain=?,start_at=?,end_at=?,duration_minutes=?,status=? WHERE id=?",
                Input.str(merged,"title",2,160),Input.optional(merged,"description","",20000),Input.str(merged,"domain",1,100),start,end,duration,state,id);
        return db.one("SELECT * FROM assessments WHERE id=?",id);
    }

    @DeleteMapping("/assessments/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable long id) {
        Map<String,Object> assessment=editable(id);
        for(Map<String,Object> question:db.list("SELECT id FROM assessment_questions WHERE assessment_id=?",id)) db.update("DELETE FROM assessment_options WHERE question_id=?",Db.id(question,"id"));
        db.update("DELETE FROM assessment_questions WHERE assessment_id=?",id);
        db.update("DELETE FROM assessments WHERE id=?",Db.id(assessment,"id"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assessments/{assessmentId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> createQuestion(@PathVariable long assessmentId,@RequestBody Map<String,Object> body) {
        editable(assessmentId);List<Map<String,Object>> options=validatedOptions(body);
        long id=db.insert("INSERT INTO assessment_questions(assessment_id,prompt,position,points,explanation) VALUES(?,?,?,?,?)",assessmentId,
                Input.str(body,"prompt",2,10000),Input.integer(body,"position",1,1,1000),Input.integer(body,"points",1,1,100),Input.optional(body,"explanation","",10000));
        insertOptions(id,options);
        return question(id,true);
    }

    @PatchMapping("/assessment-questions/{id}")
    @Transactional
    public Map<String,Object> updateQuestion(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> old=db.one("SELECT * FROM assessment_questions WHERE id=?",id);editable(Db.id(old,"assessmentId"));
        Map<String,Object> merged=LearningSupport.merged(old,body);
        if(body.containsKey("options")) {List<Map<String,Object>> options=validatedOptions(body);db.update("DELETE FROM assessment_options WHERE question_id=?",id);insertOptions(id,options);}
        db.update("UPDATE assessment_questions SET prompt=?,position=?,points=?,explanation=? WHERE id=?",Input.str(merged,"prompt",2,10000),
                Input.integer(merged,"position",1,1,1000),Input.integer(merged,"points",1,1,100),Input.optional(merged,"explanation","",10000),id);
        return question(id,true);
    }

    @DeleteMapping("/assessment-questions/{id}")
    @Transactional
    public ResponseEntity<Void> deleteQuestion(@PathVariable long id) {
        Map<String,Object> old=db.one("SELECT * FROM assessment_questions WHERE id=?",id);editable(Db.id(old,"assessmentId"));
        db.update("DELETE FROM assessment_options WHERE question_id=?",id);db.update("DELETE FROM assessment_questions WHERE id=?",id);
        return ResponseEntity.noContent().build();
    }

    private Map<String,Object> editable(long assessmentId) {
        Map<String,Object> assessment=db.one("SELECT * FROM assessments WHERE id=? FOR UPDATE",assessmentId);actor.staff(Db.id(assessment,"cohortId"));
        if(!"DRAFT".equals(assessment.get("status"))||db.count("SELECT COUNT(*) FROM assessment_attempts WHERE assessment_id=?",assessmentId)>0)
            throw AppException.conflict("문항은 응시 기록이 없는 초안 평가에서만 변경할 수 있습니다.");
        return assessment;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> validatedOptions(Map<String,Object> body) {
        if(!(body.get("options") instanceof List<?> list)||list.size()<2||list.size()>10) throw AppException.bad("선택지는 2~10개여야 합니다.");
        List<Map<String,Object>> result=new ArrayList<>();int correct=0;
        for(Object value:list) {
            if(!(value instanceof Map<?,?> raw)) throw AppException.bad("선택지는 label, isCorrect 항목이 있는 객체여야 합니다.");
            Map<String,Object> option=(Map<String,Object>)raw;String label=Input.str(option,"label",1,2000);boolean isCorrect=Input.bool(option,"isCorrect",false);
            if(isCorrect)correct++;result.add(Map.of("label",label,"isCorrect",isCorrect));
        }
        if(correct!=1)throw AppException.bad("정답 선택지는 정확히 하나여야 합니다.");
        return result;
    }

    private void insertOptions(long questionId,List<Map<String,Object>> options) {
        for(Map<String,Object> option:options)db.insert("INSERT INTO assessment_options(question_id,label,is_correct) VALUES(?,?,?)",questionId,option.get("label"),option.get("isCorrect"));
    }

    private void validateQuestions(long assessmentId) {
        List<Map<String,Object>> questions=db.list("SELECT id FROM assessment_questions WHERE assessment_id=?",assessmentId);
        if(questions.isEmpty())throw AppException.bad("게시하려면 문항이 하나 이상 필요합니다.");
        for(Map<String,Object> question:questions) {
            long id=Db.id(question,"id");
            if(db.count("SELECT COUNT(*) FROM assessment_options WHERE question_id=?",id)<2||db.count("SELECT COUNT(*) FROM assessment_options WHERE question_id=? AND is_correct=TRUE",id)!=1)
                throw AppException.bad("각 문항에는 선택지 2개 이상과 정답 1개가 필요합니다.");
        }
    }

    private List<Map<String,Object>> questions(long assessmentId,boolean reveal) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> question:db.list("SELECT id FROM assessment_questions WHERE assessment_id=? ORDER BY position,id",assessmentId))result.add(question(Db.id(question,"id"),reveal));
        return result;
    }

    private Map<String,Object> question(long questionId,boolean reveal) {
        Map<String,Object> question=new LinkedHashMap<>(db.one("SELECT * FROM assessment_questions WHERE id=?",questionId));
        List<Map<String,Object>> options=db.list("SELECT * FROM assessment_options WHERE question_id=? ORDER BY id",questionId);
        if(!reveal) {question.remove("explanation");for(Map<String,Object> option:options)option.remove("isCorrect");}
        question.put("options",options);return question;
    }

    @PostMapping("/assessments/{assessmentId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> startAttempt(@PathVariable long assessmentId) {
        Map<String,Object> assessment=db.one("SELECT * FROM assessments WHERE id=? FOR UPDATE",assessmentId);
        long cohortId=Db.id(assessment,"cohortId");actor.cohort(cohortId);support.enrollment(cohortId,actor.id());
        LocalDateTime now=Db.now();
        if(!"PUBLISHED".equals(assessment.get("status"))||now.isBefore(LearningSupport.utc(assessment.get("startAt")))||!now.isBefore(LearningSupport.utc(assessment.get("endAt"))))
            throw AppException.conflict("현재 응시할 수 있는 평가가 아닙니다.");
        if(db.count("SELECT COUNT(*) FROM assessment_attempts WHERE assessment_id=? AND user_id=?",assessmentId,actor.id())>0)throw AppException.conflict("이미 응시를 시작한 평가입니다.");
        validateQuestions(assessmentId);
        long id=db.insert("INSERT INTO assessment_attempts(assessment_id,user_id,status,started_at) VALUES(?,?,'IN_PROGRESS',?)",assessmentId,actor.id(),now);
        return attemptResponse(db.one("SELECT * FROM assessment_attempts WHERE id=?",id),assessment);
    }

    @GetMapping("/assessment-attempts/{id}")
    public Map<String,Object> getAttempt(@PathVariable long id) {
        Map<String,Object> attempt=db.one("SELECT * FROM assessment_attempts WHERE id=?",id);
        Map<String,Object> assessment=support.assessment(Db.id(attempt,"assessmentId"));
        permitAttempt(attempt,assessment,false);
        return attemptResponse(attempt,assessment);
    }

    @PutMapping("/assessment-attempts/{id}/answers")
    @Transactional
    public Map<String,Object> saveAnswers(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> attempt=db.one("SELECT * FROM assessment_attempts WHERE id=? FOR UPDATE",id);
        Map<String,Object> assessment=support.assessment(Db.id(attempt,"assessmentId"));permitAttempt(attempt,assessment,true);
        if(finished(attempt)||!"PUBLISHED".equals(assessment.get("status"))||!Db.now().isBefore(deadline(attempt,assessment)))
            throw AppException.conflict("응시 시간이 종료되었거나 이미 제출한 평가입니다.");
        if(!(body.get("answers") instanceof List<?> answers)||answers.isEmpty()||answers.size()>200) throw AppException.bad("답안은 1~200개 배열로 입력해주세요.");
        Set<Long> seen=new HashSet<>();List<long[]> validated=new ArrayList<>();
        for(Object value:answers) {
            if(!(value instanceof Map<?,?> raw))throw AppException.bad("답안에는 questionId, optionId가 필요합니다.");
            Map<String,Object> answer=new HashMap<>();raw.forEach((key,item)->answer.put(String.valueOf(key),item));
            long questionId=Input.id(answer,"questionId"),optionId=Input.id(answer,"optionId");
            if(!seen.add(questionId))throw AppException.bad("같은 문항의 답안이 중복되었습니다.");
            if(db.count("SELECT COUNT(*) FROM assessment_options o JOIN assessment_questions q ON q.id=o.question_id WHERE o.id=? AND q.id=? AND q.assessment_id=?",optionId,questionId,Db.id(assessment,"id"))!=1)
                throw AppException.bad("해당 평가 문항에 속한 선택지를 입력해주세요.");
            validated.add(new long[]{questionId,optionId});
        }
        for(long[] answer:validated) {
            Optional<Map<String,Object>> old=db.find("SELECT id FROM assessment_answers WHERE attempt_id=? AND question_id=?",id,answer[0]);
            if(old.isPresent())db.update("UPDATE assessment_answers SET option_id=?,is_correct=NULL,earned_points=NULL WHERE id=?",answer[1],Db.id(old.get(),"id"));
            else db.insert("INSERT INTO assessment_answers(attempt_id,question_id,option_id) VALUES(?,?,?)",id,answer[0],answer[1]);
        }
        return attemptResponse(attempt,assessment);
    }

    @PostMapping("/assessment-attempts/{id}/submit")
    @Transactional
    public Map<String,Object> submit(@PathVariable long id) {
        Map<String,Object> attempt=db.one("SELECT * FROM assessment_attempts WHERE id=? FOR UPDATE",id);
        Map<String,Object> assessment=support.assessment(Db.id(attempt,"assessmentId"));permitAttempt(attempt,assessment,true);
        if(finished(attempt))return attemptResponse(attempt,assessment);
        long totalPoints=db.count("SELECT COALESCE(SUM(points),0) FROM assessment_questions WHERE assessment_id=?",Db.id(assessment,"id"));
        if(totalPoints<=0)throw AppException.conflict("평가 문항 정보가 올바르지 않습니다.");
        long earned=0;
        for(Map<String,Object> answer:db.list("SELECT a.id,o.is_correct,q.points FROM assessment_answers a JOIN assessment_questions q ON q.id=a.question_id AND q.assessment_id=? JOIN assessment_options o ON o.id=a.option_id AND o.question_id=q.id WHERE a.attempt_id=?",Db.id(assessment,"id"),id)) {
            boolean correct=Boolean.TRUE.equals(answer.get("isCorrect"));int points=correct?((Number)answer.get("points")).intValue():0;earned+=points;
            db.update("UPDATE assessment_answers SET is_correct=?,earned_points=? WHERE id=?",correct,points,Db.id(answer,"id"));
        }
        int score=(int)Math.round(earned*100.0/totalPoints);
        String status=Db.now().isBefore(deadline(attempt,assessment))?"SUBMITTED":"EXPIRED";
        db.update("UPDATE assessment_attempts SET status=?,submitted_at=?,score=? WHERE id=?",status,Db.now(),score,id);
        support.notify(actor.id(),Db.id(assessment,"cohortId"),"ASSESSMENT_RESULT","역량평가 결과가 준비되었습니다.",String.valueOf(assessment.get("title")),"ASSESSMENT_ATTEMPT",id);
        return attemptResponse(db.one("SELECT * FROM assessment_attempts WHERE id=?",id),assessment);
    }

    @GetMapping("/cohorts/{cohortId}/assessment-report")
    public Map<String,Object> report(@PathVariable long cohortId,@RequestParam(required=false) Long userId) {
        actor.cohort(cohortId);long target=userId==null?actor.id():userId;
        if(target!=actor.id()&&!support.staff(cohortId))throw AppException.forbidden();
        if(target!=actor.id())support.enrollment(cohortId,target);
        List<Map<String,Object>> results=db.list("SELECT a.id,a.title,a.domain,a.start_at,t.id AS attempt_id,t.status,t.score,t.submitted_at,(SELECT AVG(other.score) FROM assessment_attempts other WHERE other.assessment_id=a.id AND other.status IN ('SUBMITTED','EXPIRED')) AS cohort_average FROM assessments a JOIN assessment_attempts t ON t.assessment_id=a.id WHERE a.cohort_id=? AND t.user_id=? AND t.status IN ('SUBMITTED','EXPIRED') ORDER BY a.start_at,a.id",cohortId,target);
        List<Map<String,Object>> domains=db.list("SELECT a.domain,AVG(t.score) AS average_score,COUNT(*) AS completed_count FROM assessments a JOIN assessment_attempts t ON t.assessment_id=a.id WHERE a.cohort_id=? AND t.user_id=? AND t.status IN ('SUBMITTED','EXPIRED') GROUP BY a.domain ORDER BY a.domain",cohortId,target);
        return Map.of("userId",target,"cohortId",cohortId,"results",results,"domains",domains);
    }

    private void permitAttempt(Map<String,Object> attempt,Map<String,Object> assessment,boolean write) {
        long cohortId=Db.id(assessment,"cohortId");actor.cohort(cohortId);
        if(Db.id(attempt,"userId")!=actor.id()&&(write||!support.staff(cohortId)))throw AppException.forbidden();
    }

    private LocalDateTime deadline(Map<String,Object> attempt,Map<String,Object> assessment) {
        LocalDateTime timeLimit=LearningSupport.utc(attempt.get("startedAt")).plusMinutes(((Number)assessment.get("durationMinutes")).longValue());
        LocalDateTime end=LearningSupport.utc(assessment.get("endAt"));return timeLimit.isBefore(end)?timeLimit:end;
    }

    private Map<String,Object> attemptResponse(Map<String,Object> attempt,Map<String,Object> assessment) {
        Map<String,Object> response=new LinkedHashMap<>(attempt);boolean done=finished(attempt);
        LocalDateTime deadline=deadline(attempt,assessment);
        response.put("expiresAt",deadline.toInstant(ZoneOffset.UTC).toString());
        response.put("remainingSeconds",done?0:Math.max(0,Duration.between(Db.now(),deadline).getSeconds()));
        response.put("expired",!Db.now().isBefore(deadline));
        response.put("questions",questions(Db.id(assessment,"id"),support.staff(Db.id(assessment,"cohortId"))||(done&&answersReleased(assessment))));
        List<Map<String,Object>> answers=db.list("SELECT * FROM assessment_answers WHERE attempt_id=? ORDER BY question_id",Db.id(attempt,"id"));
        if(!done)for(Map<String,Object> answer:answers){answer.remove("isCorrect");answer.remove("earnedPoints");}
        response.put("answers",answers);return response;
    }

    private boolean finished(Map<String,Object> attempt) {return !"IN_PROGRESS".equals(attempt.get("status"));}
    private boolean answersReleased(Map<String,Object> assessment) {return "CLOSED".equals(assessment.get("status"))||!Db.now().isBefore(LearningSupport.utc(assessment.get("endAt")));}
}
