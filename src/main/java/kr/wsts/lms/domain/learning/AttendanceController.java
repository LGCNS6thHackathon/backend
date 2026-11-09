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
    private static final String[] STATES={"PRESENT","LATE","EARLY","ABSENT","EXCUSED","LEAVE"};
    private final Db db;
    private final Actor actor;
    private final LearningSupport support;
    public AttendanceController(Db db,Actor actor,LearningSupport support) {this.db=db;this.actor=actor;this.support=support;}

    @GetMapping("/cohorts/{cohortId}/attendance")
    public Map<String,Object> records(@PathVariable long cohortId,@RequestParam(required=false) Long userId,@RequestParam(required=false) String month,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {
        actor.cohort(cohortId); LearningSupport.page(page); LearningSupport.size(size);
        boolean administrator=actor.admin();
        if(!administrator && userId!=null && userId!=actor.id()) throw AppException.forbidden();
        String where=" WHERE e.cohort_id=? AND r.deleted_at IS NULL"; List<Object> args=new ArrayList<>(List.of(cohortId));
        if(!administrator || userId!=null) {where+=" AND e.user_id=?";args.add(administrator?userId:actor.id());}
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
        actor.requireAdmin(); actor.cohort(cohortId); long userId=Input.id(body,"userId");
        Map<String,Object> enrollment=db.find("SELECT * FROM enrollments WHERE cohort_id=? AND user_id=? AND status='ACTIVE' FOR UPDATE",cohortId,userId)
                .orElseThrow(()->AppException.bad("해당 기수에 등록된 사용자가 아닙니다."));
        long enrollmentId=Db.id(enrollment,"id"); LocalDate date=Input.date(body,"date");
        support.withinCohort(cohortId,date,date);
        if(date.isAfter(LearningSupport.today())) throw AppException.bad("미래 출결은 기록할 수 없습니다.");
        String unitType=body.get("unitType")==null?"DATE":Input.choice(body,"unitType","DATE","SESSION");
        int sessionNo=Input.integer(body,"sessionNo",0,0,Integer.MAX_VALUE);
        if((unitType.equals("DATE")&&sessionNo!=0)||(unitType.equals("SESSION")&&sessionNo==0)) throw AppException.bad("일 단위 출결은 sessionNo=0, 회차 단위 출결은 양수 회차가 필요합니다.");
        Optional<Map<String,Object>> existing=db.find("SELECT * FROM attendance_records WHERE enrollment_id=? AND attendance_date=? AND session_no=? FOR UPDATE",enrollmentId,date,sessionNo);
        // Existing times survive forms that submit only status/note; explicit null clears a time.
        Map<String,Object> merged=LearningSupport.merged(existing.orElse(Map.of()),body);
        String state=Input.choice(body,"status",STATES);
        LocalDateTime in=Input.nullableDateTime(merged,"checkIn"),out=Input.nullableDateTime(merged,"checkOut");
        if(state.equals("ABSENT")&&(in!=null||out!=null)) throw AppException.bad("결석 기록에는 입퇴실 시각을 입력할 수 없습니다.");
        if(in!=null&&!in.plusHours(9).toLocalDate().equals(date)) throw AppException.bad("입실 날짜와 출결 날짜가 다릅니다.");
        if(out!=null&&(in==null||out.isBefore(in)||out.isAfter(in.plusDays(1)))) throw AppException.bad("퇴실 시각은 입실 이후 24시간 이내여야 합니다.");
        String note=Input.optional(body,"note","",1000);
        long id;
        if(existing.isPresent()) {
            Map<String,Object> current=existing.get();id=Db.id(current,"id");
            if(current.get("deletedAt")!=null) throw AppException.conflict("삭제된 출결 기록은 수정할 수 없습니다.");
            if(requiresCorrection(String.valueOf(current.get("status")),state)) throw AppException.conflict("해당 출결 변경은 교육생의 정정 요청 승인으로 처리해주세요.");
            db.update("UPDATE attendance_records SET unit_type=?,status=?,check_in=?,check_out=?,note=?,version=version+1,updated_at=? WHERE id=?",unitType,state,in,out,note,Db.now(),id);
            invalidatePending(id,"출결 기록이 변경되어 정정 요청이 무효화되었습니다.");
        }
        else id=db.insert("INSERT INTO attendance_records(enrollment_id,attendance_date,unit_type,session_no,status,check_in,check_out,note,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",enrollmentId,date,unitType,sessionNo,state,in,out,note,Db.now());
        return db.one("SELECT * FROM attendance_records WHERE id=?",id);
    }

    @DeleteMapping("/attendance/{attendanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteRecord(@PathVariable long attendanceId) {
        actor.requireAdmin();
        Map<String,Object> record=db.one("SELECT * FROM attendance_records WHERE id=? FOR UPDATE",attendanceId);
        if(record.get("deletedAt")!=null) throw AppException.notFound();
        var now=Db.now();
        db.update("UPDATE attendance_records SET deleted_at=?,version=version+1,updated_at=? WHERE id=?",now,now,attendanceId);
        invalidatePending(attendanceId,"출결 기록이 삭제되어 정정 요청이 무효화되었습니다.");
    }

    @PostMapping("/attendance/{attendanceId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> requestCorrection(@PathVariable long attendanceId,@RequestBody(required=false) Map<String,Object> body) {
        if(body==null)body=Map.of();
        Map<String,Object> record=db.one("SELECT * FROM attendance_records WHERE id=? FOR UPDATE",attendanceId);
        Map<String,Object> enrollment=db.one("SELECT * FROM enrollments WHERE id=?",Db.id(record,"enrollmentId"));
        actor.cohort(Db.id(enrollment,"cohortId"));
        if(actor.id()!=Db.id(enrollment,"userId")) throw AppException.forbidden();
        if(record.get("deletedAt")!=null) throw AppException.notFound();
        if(db.count("SELECT COUNT(*) FROM attendance_corrections WHERE attendance_id=? AND status='PENDING'",attendanceId)>0) throw AppException.conflict("이미 검토 중인 정정 요청이 있습니다.");
        String original=String.valueOf(record.get("status"));
        String requested=body.get("requestedStatus")==null?"PRESENT":Input.choice(body,"requestedStatus",STATES);
        if(!requiresCorrection(original,requested)) throw AppException.bad("해당 출결 상태 간 정정은 요청할 수 없습니다.");
        String reason=body.get("reason")==null?"실제 출결 내역 확인과 출석 상태 정정을 요청합니다.":Input.str(body,"reason",5,2000);
        long id=db.insert("INSERT INTO attendance_corrections(attendance_id,enrollment_id,original_status,original_version,requested_status,reason,status,created_at) VALUES(?,?,?,?,?,?,'PENDING',?)",
                attendanceId,Db.id(enrollment,"id"),original,record.get("version"),requested,reason,Db.now());
        return db.one("SELECT * FROM attendance_corrections WHERE id=?",id);
    }

    @GetMapping("/cohorts/{cohortId}/attendance/corrections")
    public Map<String,Object> corrections(@PathVariable long cohortId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        actor.cohort(cohortId);LearningSupport.page(page);LearningSupport.size(size);
        String where=" WHERE e.cohort_id=?";List<Object> args=new ArrayList<>(List.of(cohortId));
        if(!actor.admin()) {where+=" AND e.user_id=?";args.add(actor.id());}
        String from=" FROM attendance_corrections c JOIN enrollments e ON e.id=c.enrollment_id JOIN attendance_records a ON a.id=c.attendance_id JOIN users u ON u.id=e.user_id";
        return db.page("SELECT c.*,e.user_id,u.name AS user_name,a.attendance_date,a.unit_type,a.session_no"+from+where+" ORDER BY c.created_at DESC,c.id DESC","SELECT COUNT(*)"+from+where,page,size,args.toArray());
    }

    @PatchMapping("/attendance/corrections/{id}")
    @Transactional(noRollbackFor=AppException.class)
    public Map<String,Object> reviewCorrection(@PathVariable long id,@RequestBody Map<String,Object> body) {
        actor.requireAdmin();
        Map<String,Object> correction=db.one("SELECT * FROM attendance_corrections WHERE id=?",id);
        Map<String,Object> enrollment=db.one("SELECT * FROM enrollments WHERE id=?",Db.id(correction,"enrollmentId"));
        long cohortId=Db.id(enrollment,"cohortId");
        // Every attendance mutation takes the record lock before locking its requests.
        Map<String,Object> attendance=db.one("SELECT * FROM attendance_records WHERE id=? FOR UPDATE",Db.id(correction,"attendanceId"));
        correction=db.one("SELECT * FROM attendance_corrections WHERE id=? FOR UPDATE",id);
        if(!"PENDING".equals(correction.get("status"))) throw AppException.conflict("이미 처리된 정정 요청입니다.");
        String status=Input.choice(body,"status","APPROVED","REJECTED");
        String reviewNote=Input.optional(body,"reviewNote","",2000);
        long reviewerId=actor.id();
        boolean stale=attendance.get("deletedAt")!=null
                ||Db.id(attendance,"enrollmentId")!=Db.id(correction,"enrollmentId")
                ||!Objects.equals(attendance.get("status"),correction.get("originalStatus"))
                ||!(correction.get("originalVersion") instanceof Number snapshot)
                ||snapshot.longValue()!=Db.id(attendance,"version");
        if(stale) {
            db.update("UPDATE attendance_corrections SET status='INVALIDATED',reviewed_by=?,review_note=?,reviewed_at=? WHERE id=?",reviewerId,"원본 출결 기록이 변경되거나 삭제되어 정정 요청이 무효화되었습니다.",Db.now(),id);
            throw AppException.conflict("원본 출결 기록이 변경되거나 삭제되어 요청을 처리할 수 없습니다.");
        }
        if(status.equals("APPROVED")) {
            if(!requiresCorrection(String.valueOf(attendance.get("status")),String.valueOf(correction.get("requestedStatus")))) throw AppException.conflict("승인할 수 없는 출결 정정 요청입니다.");
            db.update("UPDATE attendance_records SET status=?,version=version+1,updated_at=? WHERE id=?",correction.get("requestedStatus"),Db.now(),Db.id(attendance,"id"));
        }
        db.update("UPDATE attendance_corrections SET status=?,reviewed_by=?,review_note=?,reviewed_at=? WHERE id=?",status,reviewerId,reviewNote,Db.now(),id);
        support.notify(Db.id(enrollment,"userId"),cohortId,"ATTENDANCE","출결 정정 요청이 처리되었습니다.",status.equals("APPROVED")?"정정 요청이 승인되었습니다.":"정정 요청이 반려되었습니다.","ATTENDANCE_CORRECTION",id);
        return db.one("SELECT * FROM attendance_corrections WHERE id=?",id);
    }

    static boolean requiresCorrection(String original,String requested) {
        return (original.equals("ABSENT")&&(requested.equals("LATE")||requested.equals("PRESENT")))
                ||(Set.of("LATE","EXCUSED","LEAVE","EARLY").contains(original)&&requested.equals("PRESENT"));
    }

    private void invalidatePending(long attendanceId,String reason) {
        db.update("UPDATE attendance_corrections SET status='INVALIDATED',reviewed_by=?,review_note=?,reviewed_at=? WHERE attendance_id=? AND status='PENDING'",actor.id(),reason,Db.now(),attendanceId);
    }
}
