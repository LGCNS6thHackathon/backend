package kr.wsts.lms.domain.learning;

import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;

/** Monthly calendar for the authenticated member; never accepts another user's ID. */
@RestController
@RequestMapping("/api/cohorts/{cohortId}/attendance/calendar")
public class AttendanceCalendarController {
    private final Db db;
    private final Actor actor;

    public AttendanceCalendarController(Db db, Actor actor) { this.db=db; this.actor=actor; }

    @GetMapping
    @Transactional(readOnly=true)
    public CalendarResponse calendar(@PathVariable long cohortId, @RequestParam String month) {
        actor.cohort(cohortId);
        LocalDate start,end;
        try {
            if(!month.matches("[0-9]{4}-[0-9]{2}")) throw new DateTimeException("Invalid month");
            YearMonth selected=YearMonth.parse(month);
            if(selected.getYear()<1 || selected.getYear()>9998) throw new DateTimeException("Invalid year");
            start=selected.atDay(1);end=selected.plusMonths(1).atDay(1);
        } catch(DateTimeException ex) { throw AppException.bad("month는 0001~9998년의 YYYY-MM 형식이어야 합니다."); }
        List<Map<String,Object>> events=db.list("SELECT id,title,type,start_at,end_at,location,description FROM schedule_events WHERE cohort_id=? AND end_at>? AND start_at<? ORDER BY start_at,id",
                cohortId,start.atStartOfDay().minusHours(9),end.atStartOfDay().minusHours(9));
        List<Map<String,Object>> records=db.list("SELECT r.id,r.attendance_date,r.unit_type,r.session_no,r.status,r.note,r.check_in,r.check_out FROM attendance_records r JOIN enrollments e ON e.id=r.enrollment_id WHERE e.cohort_id=? AND e.user_id=? AND r.deleted_at IS NULL AND r.attendance_date>=? AND r.attendance_date<? ORDER BY r.attendance_date,r.session_no,r.id",
                cohortId,actor.id(),start,end);
        // One bulk query avoids one correction lookup per day/session.
        List<Map<String,Object>> corrections=db.list("SELECT c.id,c.attendance_id,c.status,c.requested_status,c.reason,c.review_note,c.created_at,c.reviewed_at FROM attendance_corrections c JOIN attendance_records r ON r.id=c.attendance_id JOIN enrollments e ON e.id=r.enrollment_id WHERE e.cohort_id=? AND e.user_id=? AND r.deleted_at IS NULL AND r.attendance_date>=? AND r.attendance_date<? ORDER BY c.created_at DESC,c.id DESC",
                cohortId,actor.id(),start,end);
        Map<Long,Correction> latest=new HashMap<>();
        for(var row:corrections) latest.putIfAbsent(Db.id(row,"attendanceId"),new Correction(Db.id(row,"id"),text(row,"status"),text(row,"requestedStatus"),text(row,"reason"),text(row,"reviewNote"),text(row,"createdAt"),text(row,"reviewedAt")));
        List<Attendance> attendance=new ArrayList<>();
        for(var row:records) {
            long id=Db.id(row,"id");String status=text(row,"status");Correction correction=latest.get(id);
            List<String> targets=correction!=null && correction.status().equals("PENDING") ? List.of() :
                    List.of("LATE","PRESENT").stream().filter(target->AttendanceController.requiresCorrection(status,target)).toList();
            attendance.add(new Attendance(id,text(row,"attendanceDate"),text(row,"unitType"),((Number)row.get("sessionNo")).intValue(),status,text(row,"note"),text(row,"checkIn"),text(row,"checkOut"),correction,targets));
        }
        return new CalendarResponse(month,events,attendance);
    }

    private static String text(Map<String,Object> row,String key) { return row.get(key)==null?null:String.valueOf(row.get(key)); }
    public record CalendarResponse(String month,List<Map<String,Object>> events,List<Attendance> attendance) { }
    public record Attendance(long id,String attendanceDate,String unitType,int sessionNo,String status,String note,String checkIn,String checkOut,Correction latestCorrection,List<String> availableRequestedStatuses) { }
    public record Correction(long id,String status,String requestedStatus,String reason,String reviewNote,String createdAt,String reviewedAt) { }
}
