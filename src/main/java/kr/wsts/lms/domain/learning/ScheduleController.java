package kr.wsts.lms.domain.learning;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import kr.wsts.lms.global.support.Input;

@RestController
@RequestMapping("/api")
public class ScheduleController {
    private final Db db;
    private final Actor actor;
    private final LearningSupport support;

    public ScheduleController(Db db, Actor actor, LearningSupport support) { this.db = db; this.actor = actor; this.support = support; }

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
        actor.requireAdmin(cohortId);
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
        long cohortId=Db.id(old,"cohortId"); actor.requireAdmin(cohortId);
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
        Map<String,Object> event=db.one("SELECT * FROM schedule_events WHERE id=? FOR UPDATE",id); actor.requireAdmin(Db.id(event,"cohortId"));
        db.update("DELETE FROM schedule_events WHERE id=?",id);
        return ResponseEntity.noContent().build();
    }

}
