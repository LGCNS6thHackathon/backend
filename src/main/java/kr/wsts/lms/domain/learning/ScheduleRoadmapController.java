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
public class ScheduleRoadmapController {
    private final Db db;
    private final Actor actor;
    private final LearningSupport support;

    public ScheduleRoadmapController(Db db, Actor actor, LearningSupport support) { this.db = db; this.actor = actor; this.support = support; }

    @GetMapping("/cohorts/{cohortId}/events")
    public Map<String,Object> events(@PathVariable long cohortId, @RequestParam(required=false) String month,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        actor.cohort(cohortId);
        LearningSupport.page(page); LearningSupport.size(size);
        String validMonth = LearningSupport.month(month);
        String where = " WHERE cohort_id=?";
        List<Object> args = new ArrayList<>(List.of(cohortId));
        if (validMonth != null) {
            YearMonth ym = YearMonth.parse(validMonth);
            where += " AND end_at>? AND start_at<?";
            args.add(ym.atDay(1).atStartOfDay().minusHours(9));
            args.add(ym.plusMonths(1).atDay(1).atStartOfDay().minusHours(9));
        }
        return db.page("SELECT * FROM schedule_events" + where + " ORDER BY start_at,id", "SELECT COUNT(*) FROM schedule_events" + where, page,size,args.toArray());
    }

    @PostMapping("/cohorts/{cohortId}/events")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> createEvent(@PathVariable long cohortId,@RequestBody Map<String,Object> body) {
        actor.staff(cohortId);
        LocalDateTime start = Input.dateTime(body,"startAt"), end=Input.dateTime(body,"endAt");
        LearningSupport.validRange(start,end);
        support.withinCohort(cohortId,start.plusHours(9).toLocalDate(),end.plusHours(9).toLocalDate());
        long id=db.insert("INSERT INTO schedule_events(cohort_id,title,type,start_at,end_at,location,description,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                cohortId,Input.str(body,"title",2,160),Input.choice(body,"type","LECTURE","PROJECT","SPECIAL","HOLIDAY"),start,end,
                Input.optional(body,"location","",200),Input.optional(body,"description","",10000),actor.id(),Db.now());
        return db.one("SELECT * FROM schedule_events WHERE id=?",id);
    }

    @PatchMapping("/events/{id}")
    @Transactional
    public Map<String,Object> updateEvent(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> old=db.one("SELECT * FROM schedule_events WHERE id=? FOR UPDATE",id);
        long cohortId=Db.id(old,"cohortId"); actor.staff(cohortId);
        Map<String,Object> merged=LearningSupport.merged(old,body);
        LocalDateTime start=LearningSupport.dateTime(body,"startAt",old),end=LearningSupport.dateTime(body,"endAt",old);
        LearningSupport.validRange(start,end);
        support.withinCohort(cohortId,start.plusHours(9).toLocalDate(),end.plusHours(9).toLocalDate());
        db.update("UPDATE schedule_events SET title=?,type=?,start_at=?,end_at=?,location=?,description=? WHERE id=?",
                Input.str(merged,"title",2,160),Input.choice(merged,"type","LECTURE","PROJECT","SPECIAL","HOLIDAY"),start,end,
                Input.optional(merged,"location","",200),Input.optional(merged,"description","",10000),id);
        return db.one("SELECT * FROM schedule_events WHERE id=?",id);
    }

    @DeleteMapping("/events/{id}")
    @Transactional
    public ResponseEntity<Void> deleteEvent(@PathVariable long id) {
        Map<String,Object> event=db.one("SELECT * FROM schedule_events WHERE id=? FOR UPDATE",id); actor.staff(Db.id(event,"cohortId"));
        db.update("DELETE FROM schedule_events WHERE id=?",id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cohorts/{cohortId}/roadmap")
    public List<Map<String,Object>> roadmap(@PathVariable long cohortId) {
        actor.cohort(cohortId);
        List<Map<String,Object>> phases=db.list("SELECT * FROM roadmap_phases WHERE cohort_id=? ORDER BY position,id",cohortId);
        for(Map<String,Object> phase:phases) {
            List<Map<String,Object>> modules=db.list("SELECT * FROM roadmap_modules WHERE phase_id=? ORDER BY position,id",Db.id(phase,"id"));
            phase.put("status",status(phase));
            for(Map<String,Object> module:modules) module.put("status",status(module));
            phase.put("modules",modules);
        }
        return phases;
    }

    private String status(Map<String,Object> row) {
        LocalDate today=LearningSupport.today();
        return today.isBefore(LearningSupport.day(row.get("startDate"))) ? "UPCOMING" : today.isAfter(LearningSupport.day(row.get("endDate"))) ? "COMPLETED" : "IN_PROGRESS";
    }

    @PostMapping("/cohorts/{cohortId}/roadmap")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> createPhase(@PathVariable long cohortId,@RequestBody Map<String,Object> body) {
        actor.staff(cohortId);
        LocalDate start=Input.date(body,"startDate"),end=Input.date(body,"endDate"); support.withinCohort(cohortId,start,end);
        long id=db.insert("INSERT INTO roadmap_phases(cohort_id,title,description,position,hours,start_date,end_date) VALUES(?,?,?,?,?,?,?)",cohortId,
                Input.str(body,"title",2,160),Input.optional(body,"description","",10000),Input.integer(body,"position",1,1,1000),Input.integer(body,"hours",0,0,100000),start,end);
        return db.one("SELECT * FROM roadmap_phases WHERE id=?",id);
    }

    @PatchMapping("/roadmap/phases/{id}")
    @Transactional
    public Map<String,Object> updatePhase(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> old=db.one("SELECT * FROM roadmap_phases WHERE id=? FOR UPDATE",id); long cohortId=Db.id(old,"cohortId"); actor.staff(cohortId);
        Map<String,Object> merged=LearningSupport.merged(old,body);
        LocalDate start=LearningSupport.date(body,"startDate",old),end=LearningSupport.date(body,"endDate",old); support.withinCohort(cohortId,start,end);
        for(Map<String,Object> module:db.list("SELECT start_date,end_date FROM roadmap_modules WHERE phase_id=?",id))
            if(LearningSupport.day(module.get("startDate")).isBefore(start)||LearningSupport.day(module.get("endDate")).isAfter(end)) throw AppException.conflict("하위 모듈의 교육 기간을 먼저 수정해주세요.");
        db.update("UPDATE roadmap_phases SET title=?,description=?,position=?,hours=?,start_date=?,end_date=? WHERE id=?",Input.str(merged,"title",2,160),
                Input.optional(merged,"description","",10000),Input.integer(merged,"position",1,1,1000),Input.integer(merged,"hours",0,0,100000),start,end,id);
        return db.one("SELECT * FROM roadmap_phases WHERE id=?",id);
    }

    @PostMapping("/roadmap/phases/{phaseId}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> createModule(@PathVariable long phaseId,@RequestBody Map<String,Object> body) {
        Map<String,Object> phase=db.one("SELECT * FROM roadmap_phases WHERE id=? FOR UPDATE",phaseId); actor.staff(Db.id(phase,"cohortId"));
        LocalDate start=Input.date(body,"startDate"),end=Input.date(body,"endDate"); moduleRange(phase,start,end);
        long id=db.insert("INSERT INTO roadmap_modules(phase_id,title,hours,start_date,end_date,position) VALUES(?,?,?,?,?,?)",phaseId,
                Input.str(body,"title",2,160),Input.integer(body,"hours",0,0,100000),start,end,Input.integer(body,"position",1,1,1000));
        return db.one("SELECT * FROM roadmap_modules WHERE id=?",id);
    }

    @PatchMapping("/roadmap/modules/{id}")
    @Transactional
    public Map<String,Object> updateModule(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> old=db.one("SELECT * FROM roadmap_modules WHERE id=? FOR UPDATE",id);
        Map<String,Object> phase=db.one("SELECT * FROM roadmap_phases WHERE id=?",Db.id(old,"phaseId")); actor.staff(Db.id(phase,"cohortId"));
        Map<String,Object> merged=LearningSupport.merged(old,body);
        LocalDate start=LearningSupport.date(body,"startDate",old),end=LearningSupport.date(body,"endDate",old); moduleRange(phase,start,end);
        db.update("UPDATE roadmap_modules SET title=?,hours=?,start_date=?,end_date=?,position=? WHERE id=?",Input.str(merged,"title",2,160),
                Input.integer(merged,"hours",0,0,100000),start,end,Input.integer(merged,"position",1,1,1000),id);
        return db.one("SELECT * FROM roadmap_modules WHERE id=?",id);
    }

    private void moduleRange(Map<String,Object> phase,LocalDate start,LocalDate end) {
        if(end.isBefore(start)||start.isBefore(LearningSupport.day(phase.get("startDate")))||end.isAfter(LearningSupport.day(phase.get("endDate"))))
            throw AppException.bad("모듈 기간은 단계의 교육 기간 안에 있어야 합니다.");
    }
}
