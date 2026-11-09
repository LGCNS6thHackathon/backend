package kr.wsts.lms.domain.learning;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import kr.wsts.lms.global.support.Input;

@RestController
@RequestMapping("/api")
public class AttendanceController {
    private static final String[] STATES={"PRESENT","LATE","EARLY","ABSENT","EXCUSED"};
    private final Db db;
    private final Actor actor;
    private final LearningSupport support;
    public AttendanceController(Db db,Actor actor,LearningSupport support) {this.db=db;this.actor=actor;this.support=support;}

    @GetMapping("/cohorts/{cohortId}/attendance")
    public Map<String,Object> records(@PathVariable long cohortId,@RequestParam(required=false) Long userId,@RequestParam(required=false) String month,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {
        actor.cohort(cohortId); LearningSupport.page(page); LearningSupport.size(size);
        boolean staff=support.staff(cohortId);
        if(!staff && userId!=null && userId!=actor.id()) throw AppException.forbidden();
        String where=" WHERE e.cohort_id=?"; List<Object> args=new ArrayList<>(List.of(cohortId));
        if(!staff || userId!=null) {where+=" AND e.user_id=?";args.add(staff?userId:actor.id());}
        String validMonth=LearningSupport.month(month);
        if(validMonth!=null) {YearMonth ym=YearMonth.parse(validMonth);where+=" AND r.attendance_date>=? AND r.attendance_date<?";args.add(ym.atDay(1));args.add(ym.plusMonths(1).atDay(1));}
        String from=" FROM attendance_records r JOIN enrollments e ON e.id=r.enrollment_id JOIN users u ON u.id=e.user_id";
        Map<String,Object> result=new LinkedHashMap<>(db.page("SELECT r.*,e.user_id,u.name AS user_name"+from+where+" ORDER BY r.attendance_date DESC,r.id DESC","SELECT COUNT(*)"+from+where,page,size,args.toArray()));
        result.put("summary",db.list("SELECT r.status,COUNT(*) AS record_count"+from+where+" GROUP BY r.status",args.toArray()));
        return result;
    }

    @PutMapping("/cohorts/{cohortId}/attendance")
    @Transactional
    public Map<String,Object> saveRecord(@PathVariable long cohortId,@RequestBody Map<String,Object> body) {
        actor.staff(cohortId); long userId=Input.id(body,"userId");
        Map<String,Object> enrollment=db.find("SELECT * FROM enrollments WHERE cohort_id=? AND user_id=? AND status='ACTIVE' FOR UPDATE",cohortId,userId)
                .orElseThrow(()->AppException.bad("해당 기수에 등록된 사용자가 아닙니다."));
        long enrollmentId=Db.id(enrollment,"id"); LocalDate date=Input.date(body,"date");
        support.withinCohort(cohortId,date,date);
        if(date.isAfter(LearningSupport.today())) throw AppException.bad("미래 출결은 기록할 수 없습니다.");
        String state=Input.choice(body,"status",STATES); LocalDateTime in=Input.nullableDateTime(body,"checkIn"),out=Input.nullableDateTime(body,"checkOut");
        if(state.equals("ABSENT")&&(in!=null||out!=null)) throw AppException.bad("결석 기록에는 입퇴실 시각을 입력할 수 없습니다.");
        if(in!=null&&!in.plusHours(9).toLocalDate().equals(date)) throw AppException.bad("입실 날짜와 출결 날짜가 다릅니다.");
        if(out!=null&&(in==null||out.isBefore(in)||out.isAfter(in.plusDays(1)))) throw AppException.bad("퇴실 시각은 입실 이후 24시간 이내여야 합니다.");
        String note=Input.optional(body,"note","",1000);
        Optional<Map<String,Object>> existing=db.find("SELECT * FROM attendance_records WHERE enrollment_id=? AND attendance_date=?",enrollmentId,date);
        long id;
        if(existing.isPresent()) {id=Db.id(existing.get(),"id");db.update("UPDATE attendance_records SET status=?,check_in=?,check_out=?,note=?,updated_at=? WHERE id=?",state,in,out,note,Db.now(),id);}
        else id=db.insert("INSERT INTO attendance_records(enrollment_id,attendance_date,status,check_in,check_out,note,updated_at) VALUES(?,?,?,?,?,?,?)",enrollmentId,date,state,in,out,note,Db.now());
        return db.one("SELECT * FROM attendance_records WHERE id=?",id);
    }

    @PostMapping("/attendance/{attendanceId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> requestCorrection(@PathVariable long attendanceId,@RequestBody Map<String,Object> body) {
        Map<String,Object> record=db.one("SELECT * FROM attendance_records WHERE id=? FOR UPDATE",attendanceId);
        Map<String,Object> enrollment=db.one("SELECT * FROM enrollments WHERE id=?",Db.id(record,"enrollmentId"));
        actor.cohort(Db.id(enrollment,"cohortId"));actor.requireOwner(Db.id(enrollment,"userId"));
        if(db.count("SELECT COUNT(*) FROM attendance_corrections WHERE attendance_id=? AND status='PENDING'",attendanceId)>0) throw AppException.conflict("이미 검토 중인 정정 요청이 있습니다.");
        String requested=Input.choice(body,"requestedStatus",STATES);
        if(requested.equals(record.get("status"))) throw AppException.bad("현재와 다른 출결 상태를 요청해주세요.");
        long id=db.insert("INSERT INTO attendance_corrections(attendance_id,enrollment_id,requested_status,reason,status,created_at) VALUES(?,?,?,?,'PENDING',?)",
                attendanceId,Db.id(enrollment,"id"),requested,Input.str(body,"reason",5,2000),Db.now());
        return db.one("SELECT * FROM attendance_corrections WHERE id=?",id);
    }

    @GetMapping("/cohorts/{cohortId}/attendance/corrections")
    public Map<String,Object> corrections(@PathVariable long cohortId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        actor.cohort(cohortId);LearningSupport.page(page);LearningSupport.size(size);
        String where=" WHERE e.cohort_id=?";List<Object> args=new ArrayList<>(List.of(cohortId));
        if(!support.staff(cohortId)) {where+=" AND e.user_id=?";args.add(actor.id());}
        String from=" FROM attendance_corrections c JOIN enrollments e ON e.id=c.enrollment_id JOIN attendance_records a ON a.id=c.attendance_id JOIN users u ON u.id=e.user_id";
        return db.page("SELECT c.*,e.user_id,u.name AS user_name,a.attendance_date"+from+where+" ORDER BY c.created_at DESC,c.id DESC","SELECT COUNT(*)"+from+where,page,size,args.toArray());
    }

    @PatchMapping("/attendance/corrections/{id}")
    @Transactional
    public Map<String,Object> reviewCorrection(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Map<String,Object> correction=db.one("SELECT * FROM attendance_corrections WHERE id=? FOR UPDATE",id);
        Map<String,Object> enrollment=db.one("SELECT * FROM enrollments WHERE id=?",Db.id(correction,"enrollmentId"));
        long cohortId=Db.id(enrollment,"cohortId");actor.staff(cohortId);
        if(!"PENDING".equals(correction.get("status"))) throw AppException.conflict("이미 처리된 정정 요청입니다.");
        String status=Input.choice(body,"status","APPROVED","REJECTED");
        if(status.equals("APPROVED")) {
            Map<String,Object> attendance=db.one("SELECT * FROM attendance_records WHERE id=? FOR UPDATE",Db.id(correction,"attendanceId"));
            if(Db.id(attendance,"enrollmentId")!=Db.id(correction,"enrollmentId")) throw AppException.conflict("출결 기록과 요청의 사용자가 다릅니다.");
            if("ABSENT".equals(correction.get("requestedStatus"))) db.update("UPDATE attendance_records SET status=?,check_in=NULL,check_out=NULL,updated_at=? WHERE id=?",correction.get("requestedStatus"),Db.now(),Db.id(attendance,"id"));
            else db.update("UPDATE attendance_records SET status=?,updated_at=? WHERE id=?",correction.get("requestedStatus"),Db.now(),Db.id(attendance,"id"));
        }
        db.update("UPDATE attendance_corrections SET status=?,reviewed_by=?,review_note=?,reviewed_at=? WHERE id=?",status,actor.id(),Input.optional(body,"reviewNote","",2000),Db.now(),id);
        support.notify(Db.id(enrollment,"userId"),cohortId,"ATTENDANCE","출결 정정 요청이 처리되었습니다.",status.equals("APPROVED")?"정정 요청이 승인되었습니다.":"정정 요청이 반려되었습니다.","ATTENDANCE_CORRECTION",id);
        return db.one("SELECT * FROM attendance_corrections WHERE id=?",id);
    }
}
