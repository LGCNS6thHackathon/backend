package kr.wsts.lms.domain.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lms-learning-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.jwt-secret=learning-test-only-fixed-key-at-least-32-bytes",
        "app.bootstrap.admin-email=", "app.bootstrap.admin-password="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LearningIntegrationTests {
    private static final String PASSWORD="LearningPassword!42";
    private final JsonMapper json=JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired PasswordEncoder passwords;
    private User admin,first,second;
    private long courseId,cohortId;
    private LocalDate today;

    @BeforeEach
    void fixture() throws Exception {
        admin=account(true);first=account(false);second=account(false);
        today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        courseId=id(call(admin,post("/api/courses"),Map.of("title","학습 기능 통합 검증","totalHours",80),201));
        cohortId=createCohort();
        for(User student:List.of(first,second))call(admin,post("/api/cohorts/"+cohortId+"/enrollments"),Map.of("userId",student.id,"role","STUDENT"),201);
    }

    @Test
    void studentsCanReadTheirScheduleAndRoadmapButCannotManageOrCrossCohortBoundary() throws Exception {
        long foreignCohort=createCohort();
        Map<String,Object> event=Map.of("title","실습 일정","type","LECTURE","startAt",iso(Db.now().plusHours(1)),"endAt",iso(Db.now().plusHours(2)),"location","교육장");
        long eventId=id(call(admin,post("/api/cohorts/"+cohortId+"/events"),event,201));
        mvc.perform(get("/api/cohorts/"+cohortId+"/events").header("Authorization",bearer(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(eventId));
        call(first,post("/api/cohorts/"+cohortId+"/events"),event,403);
        call(first,get("/api/cohorts/"+foreignCohort+"/events"),null,403);
        call(first,put("/api/cohorts/"+cohortId+"/attendance"),Map.of("userId",first.id,"date",today.toString(),"status","PRESENT"),403);
        long phase=id(call(admin,post("/api/cohorts/"+cohortId+"/roadmap"),Map.of("title","기본 역량","position",1,"hours",80,"startDate",today.minusDays(1).toString(),"endDate",today.plusDays(10).toString()),201));
        long module=id(call(admin,post("/api/roadmap/phases/"+phase+"/modules"),Map.of("title","API 실습","position",1,"hours",8,"startDate",today.toString(),"endDate",today.plusDays(1).toString()),201));
        mvc.perform(get("/api/cohorts/"+cohortId+"/roadmap").header("Authorization",bearer(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].modules[0].id").value(module));
        call(first,patch("/api/roadmap/modules/"+module),Map.of("title","권한 없는 수정"),403);
    }

    @Test
    void attendanceCorrectionBelongsToItsStudentAndCanOnlyBeReviewedOnce() throws Exception {
        long record=id(call(admin,put("/api/cohorts/"+cohortId+"/attendance"),Map.of("userId",first.id,"date",today.toString(),"status","ABSENT"),200));
        Map<String,Object> request=Map.of("requestedStatus","PRESENT","reason","입실 장비 오류로 실제 출석 기록 정정을 요청합니다.");
        call(second,post("/api/attendance/"+record+"/corrections"),request,403);
        long correction=id(call(first,post("/api/attendance/"+record+"/corrections"),request,201));
        call(first,post("/api/attendance/"+record+"/corrections"),request,409);
        mvc.perform(get("/api/cohorts/"+cohortId+"/attendance/corrections").header("Authorization",bearer(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call(first,patch("/api/attendance/corrections/"+correction),Map.of("status","APPROVED"),403);
        call(admin,patch("/api/attendance/corrections/"+correction),Map.of("status","APPROVED","reviewNote","입실 기록을 확인했습니다."),200);
        mvc.perform(get("/api/cohorts/"+cohortId+"/attendance").header("Authorization",bearer(first)).param("month",today.toString().substring(0,7)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("PRESENT"));
        call(admin,patch("/api/attendance/corrections/"+correction),Map.of("status","REJECTED"),409);
        call(second,get("/api/cohorts/"+cohortId+"/attendance").param("userId",String.valueOf(first.id)),null,403);
        assertEquals("PRESENT",db.one("SELECT status FROM attendance_records WHERE id=?",record).get("status"));
    }

    @Test
    void attendanceStoresDateAndDistinctSessionUnitsWithAllSixStates() throws Exception {
        long dateRecord=id(call(admin,put("/api/cohorts/"+cohortId+"/attendance"),Map.of("userId",first.id,"date",today.toString(),"status","PRESENT"),200));
        String dateView=call(first,get("/api/cohorts/"+cohortId+"/attendance"),null,200);
        assertEquals("DATE",JsonPath.read(dateView,"$.items[0].unitType"));
        assertEquals(0,((Number)JsonPath.read(dateView,"$.items[0].sessionNo")).intValue());
        List<String> states=List.of("PRESENT","LATE","EARLY","ABSENT","EXCUSED","LEAVE");
        for(int session=1;session<=states.size();session++)attendance(states.get(session-1),session);
        String records=call(first,get("/api/cohorts/"+cohortId+"/attendance"),null,200);
        assertEquals(7,((Number)JsonPath.read(records,"$.total")).intValue());
        assertEquals(new HashSet<>(states),new HashSet<>(JsonPath.<List<String>>read(records,"$.items[*].status")));
        long leave=Db.id(db.one("SELECT id FROM attendance_records WHERE enrollment_id=(SELECT id FROM enrollments WHERE cohort_id=? AND user_id=?) AND attendance_date=? AND session_no=6",cohortId,first.id,today),"id");
        long updated=attendance("EXCUSED",6);
        assertEquals(leave,updated);
        assertEquals(1,Db.id(db.one("SELECT version FROM attendance_records WHERE id=?",leave),"version"));
        assertEquals("PRESENT",db.one("SELECT status FROM attendance_records WHERE id=?",dateRecord).get("status"));
        for(Map<String,Object> invalid:List.<Map<String,Object>>of(Map.of("unitType","DATE","sessionNo",1),Map.of("unitType","SESSION","sessionNo",0),Map.of("unitType","SESSION","sessionNo",-1),Map.of("unitType","SESSION","sessionNo",2147483648L),Map.of("unitType","OTHER","sessionNo",1))) {
            var body=new HashMap<String,Object>(Map.of("userId",first.id,"date",today.toString(),"status","PRESENT"));body.putAll(invalid);
            call(admin,put("/api/cohorts/"+cohortId+"/attendance"),body,400);
        }
    }

    @Test
    void positiveAttendanceTransitionsRequireOwnerRequestAndStaffApproval() throws Exception {
        List<List<String>> transitions=List.of(List.of("ABSENT","LATE"),List.of("ABSENT","PRESENT"),List.of("LATE","PRESENT"),List.of("EXCUSED","PRESENT"),List.of("LEAVE","PRESENT"),List.of("EARLY","PRESENT"));
        for(int index=0;index<transitions.size();index++) {
            String original=transitions.get(index).get(0),requested=transitions.get(index).get(1);int session=index+1;
            long record=attendance(original,session);
            call(admin,put("/api/cohorts/"+cohortId+"/attendance"),attendanceBody(requested,session),409);
            assertEquals(original,db.one("SELECT status FROM attendance_records WHERE id=?",record).get("status"));
            assertEquals(0,Db.id(db.one("SELECT version FROM attendance_records WHERE id=?",record),"version"));
            call(second,post("/api/attendance/"+record+"/corrections"),null,403);
            call(admin,post("/api/attendance/"+record+"/corrections"),null,403);
            Object request=requested.equals("PRESENT")?null:Map.of("requestedStatus",requested);
            String response=call(first,post("/api/attendance/"+record+"/corrections"),request,201);long correction=id(response);
            assertEquals(original,JsonPath.read(response,"$.originalStatus"));
            assertEquals(0,((Number)JsonPath.read(response,"$.originalVersion")).intValue());
            assertEquals(requested,JsonPath.read(response,"$.requestedStatus"));
            assertTrue(JsonPath.<String>read(response,"$.reason").length()>=5);
            call(first,post("/api/attendance/"+record+"/corrections"),request,409);
            call(first,patch("/api/attendance/corrections/"+correction),Map.of("status","APPROVED"),403);
            call(admin,patch("/api/attendance/corrections/"+correction),Map.of("status","APPROVED"),200);
            call(admin,patch("/api/attendance/corrections/"+correction),Map.of("status","APPROVED"),409);
            Map<String,Object> stored=db.one("SELECT status,version FROM attendance_records WHERE id=?",record);
            assertEquals(requested,stored.get("status"));assertEquals(1,Db.id(stored,"version"));
            assertEquals(1,db.count("SELECT COUNT(*) FROM notifications WHERE resource_type='ATTENDANCE_CORRECTION' AND resource_id=?",correction));
        }
    }

    @Test
    void correctionsRejectUnsupportedTransitionsAndStillValidateProvidedReason() throws Exception {
        long present=attendance("PRESENT",1),late=attendance("LATE",2),absent=attendance("ABSENT",3);
        call(first,post("/api/attendance/"+present+"/corrections"),null,400);
        call(first,post("/api/attendance/"+present+"/corrections"),Map.of("requestedStatus","LATE"),400);
        call(first,post("/api/attendance/"+late+"/corrections"),Map.of("requestedStatus","EXCUSED"),400);
        call(first,post("/api/attendance/"+absent+"/corrections"),Map.of("requestedStatus","EXCUSED"),400);
        call(first,post("/api/attendance/"+absent+"/corrections"),Map.of("reason","짧음"),400);
        String requested=call(first,post("/api/attendance/"+absent+"/corrections"),Map.of("reason"," 입실 기록 확인을 요청합니다. "),201);
        assertEquals("입실 기록 확인을 요청합니다.",JsonPath.read(requested,"$.reason"));
    }

    @Test
    void concurrentCorrectionRequestsCannotCreateTwoPendingSnapshots() throws Exception {
        long record=attendance("ABSENT",1);
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<Integer>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(executor.submit(()->{ready.countDown();start.await();return mvc.perform(post("/api/attendance/"+record+"/corrections").header("Authorization",bearer(first))).andReturn().getResponse().getStatus();}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();List<Integer> statuses=new ArrayList<>();
            for(var future:futures)statuses.add(future.get(10,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(201,409),statuses);
            assertEquals(1,db.count("SELECT COUNT(*) FROM attendance_corrections WHERE attendance_id=? AND status='PENDING'",record));
        } finally {executor.shutdownNow();}
    }

    @Test
    void normalStaffCorrectionInvalidatesPendingRequestAndAdvancesRecordVersion() throws Exception {
        long record=attendance("ABSENT",1);
        long correction=id(call(first,post("/api/attendance/"+record+"/corrections"),Map.of(),201));
        assertEquals(record,attendance("EXCUSED",1));
        Map<String,Object> stored=db.one("SELECT status,version FROM attendance_records WHERE id=?",record);
        assertEquals("EXCUSED",stored.get("status"));assertEquals(1,Db.id(stored,"version"));
        assertEquals("INVALIDATED",db.one("SELECT status FROM attendance_corrections WHERE id=?",correction).get("status"));
        call(admin,patch("/api/attendance/corrections/"+correction),Map.of("status","APPROVED"),409);
        assertEquals("EXCUSED",db.one("SELECT status FROM attendance_records WHERE id=?",record).get("status"));
        String history=call(first,get("/api/cohorts/"+cohortId+"/attendance/corrections"),null,200);
        assertEquals("INVALIDATED",JsonPath.read(history,"$.items[0].status"));
        long replacement=id(call(first,post("/api/attendance/"+record+"/corrections"),Map.of(),201));
        assertEquals(1,Db.id(db.one("SELECT original_version FROM attendance_corrections WHERE id=?",replacement),"originalVersion"));
        var sameStatusEdit=new HashMap<>(attendanceBody("EXCUSED",1));sameStatusEdit.put("note","공결 근거를 추가로 확인했습니다.");
        call(admin,put("/api/cohorts/"+cohortId+"/attendance"),sameStatusEdit,200);
        assertEquals(2,Db.id(db.one("SELECT version FROM attendance_records WHERE id=?",record),"version"));
        assertEquals("INVALIDATED",db.one("SELECT status FROM attendance_corrections WHERE id=?",replacement).get("status"));
        long current=id(call(first,post("/api/attendance/"+record+"/corrections"),Map.of(),201));
        assertEquals(2,Db.id(db.one("SELECT original_version FROM attendance_corrections WHERE id=?",current),"originalVersion"));
        call(admin,patch("/api/attendance/corrections/"+current),Map.of("status","APPROVED"),200);
        assertEquals(3,Db.id(db.one("SELECT version FROM attendance_records WHERE id=?",record),"version"));
    }

    @Test
    void staleSnapshotReviewPersistsInvalidationWithoutChangingAttendance() throws Exception {
        for(int session=1;session<=4;session++) {
            long record=attendance("ABSENT",session);
            long correction=id(call(first,post("/api/attendance/"+record+"/corrections"),null,201));
            if(session==1)db.update("UPDATE attendance_records SET version=version+1 WHERE id=?",record);
            else if(session==2)db.update("UPDATE attendance_records SET status='EXCUSED' WHERE id=?",record);
            else if(session==3)db.update("UPDATE attendance_records SET deleted_at=? WHERE id=?",Db.now(),record);
            else db.update("UPDATE attendance_corrections SET original_status=NULL,original_version=NULL WHERE id=?",correction);
            Map<String,Object> before=db.one("SELECT status,version,deleted_at FROM attendance_records WHERE id=?",record);
            call(admin,patch("/api/attendance/corrections/"+correction),Map.of("status","APPROVED"),409);
            Map<String,Object> after=db.one("SELECT status,version,deleted_at FROM attendance_records WHERE id=?",record);
            assertEquals(before,after);
            Map<String,Object> invalidated=db.one("SELECT status,reviewed_by,reviewed_at FROM attendance_corrections WHERE id=?",correction);
            assertEquals("INVALIDATED",invalidated.get("status"));assertEquals(admin.id,Db.id(invalidated,"reviewedBy"));assertNotNull(invalidated.get("reviewedAt"));
        }
        String history=call(first,get("/api/cohorts/"+cohortId+"/attendance/corrections"),null,200);
        assertEquals(4,((Number)JsonPath.read(history,"$.total")).intValue());
        assertEquals(List.of("INVALIDATED","INVALIDATED","INVALIDATED","INVALIDATED"),JsonPath.read(history,"$.items[*].status"));
    }

    @Test
    void softDeletedAttendanceIsHiddenAndItsInvalidatedRequestRemainsInHistory() throws Exception {
        long record=attendance("LEAVE",1);
        long correction=id(call(first,post("/api/attendance/"+record+"/corrections"),null,201));
        call(first,delete("/api/attendance/"+record),null,403);
        call(admin,delete("/api/attendance/"+record),null,204);
        Map<String,Object> deleted=db.one("SELECT deleted_at,version FROM attendance_records WHERE id=?",record);
        assertNotNull(deleted.get("deletedAt"));assertEquals(1,Db.id(deleted,"version"));
        String records=call(first,get("/api/cohorts/"+cohortId+"/attendance"),null,200);
        assertEquals(0,((Number)JsonPath.read(records,"$.total")).intValue());assertEquals(List.of(),JsonPath.read(records,"$.summary"));
        call(first,post("/api/attendance/"+record+"/corrections"),null,404);
        call(admin,put("/api/cohorts/"+cohortId+"/attendance"),attendanceBody("ABSENT",1),409);
        call(admin,delete("/api/attendance/"+record),null,404);
        call(admin,patch("/api/attendance/corrections/"+correction),Map.of("status","APPROVED"),409);
        String history=call(first,get("/api/cohorts/"+cohortId+"/attendance/corrections"),null,200);
        assertEquals(1,((Number)JsonPath.read(history,"$.total")).intValue());assertEquals("INVALIDATED",JsonPath.read(history,"$.items[0].status"));
        assertEquals("SESSION",JsonPath.read(history,"$.items[0].unitType"));assertEquals(1,((Number)JsonPath.read(history,"$.items[0].sessionNo")).intValue());
    }

    @Test
    void submissionIsPrivateUpsertedOnceAndLockedAfterGradingOrDeadline() throws Exception {
        long assignment=assignment(Db.now().plusDays(2));
        long submission=id(call(first,put("/api/assignments/"+assignment+"/submission"),Map.of("body","첫 번째 제출 내용","repositoryUrl","https://example.com/repository"),200));
        long updated=id(call(first,put("/api/assignments/"+assignment+"/submission"),Map.of("body","수정한 제출 내용","repositoryUrl","https://example.com/repository"),200));
        assertEquals(submission,updated);
        assertEquals(1,db.count("SELECT COUNT(*) FROM submissions WHERE assignment_id=? AND user_id=?",assignment,first.id));
        mvc.perform(get("/api/assignments/"+assignment+"/submissions").header("Authorization",bearer(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/assignments/"+assignment+"/submissions").header("Authorization",bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        String otherView=call(second,get("/api/assignments/"+assignment),null,200);
        assertNull(JsonPath.read(otherView,"$.mySubmission"));
        call(second,patch("/api/submissions/"+submission+"/review"),Map.of("score",100,"feedback","권한 없는 채점"),403);
        call(admin,patch("/api/submissions/"+submission+"/review"),Map.of("score",101,"feedback","점수 범위 오류"),400);
        call(admin,patch("/api/submissions/"+submission+"/review"),Map.of("score",95,"feedback","근거가 명확한 설계입니다."),200);
        call(first,put("/api/assignments/"+assignment+"/submission"),Map.of("body","평가 후 수정 시도"),409);
        long expired=assignment(Db.now().minusMinutes(1));
        call(first,put("/api/assignments/"+expired+"/submission"),Map.of("body","마감 이후 제출 시도"),409);
    }

    @Test
    void assessmentHidesAnswersValidatesOptionOwnershipAndGradesOnServerWithDeadline() throws Exception {
        long assessment=assessment();
        Question q1=question(assessment,1,2),q2=question(assessment,2,1);
        long foreignAssessment=assessment();Question foreign=question(foreignAssessment,1,1);
        call(first,get("/api/assessments/"+assessment),null,404);
        call(admin,patch("/api/assessments/"+assessment),Map.of("status","PUBLISHED"),200);
        mvc.perform(get("/api/assessments/"+assessment).header("Authorization",bearer(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questions").doesNotExist());
        String started=call(first,post("/api/assessments/"+assessment+"/attempts"),null,201);long attempt=id(started);
        assertNull(JsonPath.read(started,"$.score"));
        assertFalse(started.contains("isCorrect"));assertFalse(started.contains("explanation"));
        call(first,post("/api/assessments/"+assessment+"/attempts"),null,409);
        call(second,get("/api/assessment-attempts/"+attempt),null,403);
        call(first,put("/api/assessment-attempts/"+attempt+"/answers"),answers(foreign.id,foreign.correct),400);
        call(first,put("/api/assessment-attempts/"+attempt+"/answers"),answers(q1.id,q2.correct),400);
        call(first,put("/api/assessment-attempts/"+attempt+"/answers"),Map.of("answers",List.of(answer(q1.id,q1.correct),answer(q2.id,q2.correct))),200);
        String graded=call(first,post("/api/assessment-attempts/"+attempt+"/submit"),Map.of("score",0),200);
        assertEquals(100,((Number)JsonPath.read(graded,"$.score")).intValue());
        assertEquals("SUBMITTED",JsonPath.read(graded,"$.status"));
        assertFalse(((Map<?,?>)JsonPath.read(graded,"$.questions[0].options[0]")).containsKey("isCorrect"));
        assertFalse(((Map<?,?>)JsonPath.read(graded,"$.questions[0]")).containsKey("explanation"));
        call(first,put("/api/assessment-attempts/"+attempt+"/answers"),answers(q1.id,q1.wrong),409);
        String submittedAgain=call(first,post("/api/assessment-attempts/"+attempt+"/submit"),null,200);
        assertEquals(100,((Number)JsonPath.read(submittedAgain,"$.score")).intValue());
        long timed=id(call(second,post("/api/assessments/"+assessment+"/attempts"),null,201));
        call(second,put("/api/assessment-attempts/"+timed+"/answers"),Map.of("answers",List.of(answer(q1.id,q1.wrong),answer(q2.id,q2.correct))),200);
        db.update("UPDATE assessment_attempts SET started_at=? WHERE id=?",Db.now().minusMinutes(31),timed);
        call(second,put("/api/assessment-attempts/"+timed+"/answers"),answers(q1.id,q1.correct),409);
        String late=call(second,post("/api/assessment-attempts/"+timed+"/submit"),null,200);
        assertEquals("EXPIRED",JsonPath.read(late,"$.status"));
        assertEquals(33,((Number)JsonPath.read(late,"$.score")).intValue());
        assertEquals(2,db.count("SELECT COUNT(*) FROM assessment_answers WHERE attempt_id=?",timed));
        mvc.perform(get("/api/cohorts/"+cohortId+"/assessment-report").header("Authorization",bearer(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].score").value(100));
    }

    private long createCohort() throws Exception {
        return id(call(admin,post("/api/courses/"+courseId+"/cohorts"),Map.of("name","통합 테스트 기수","startDate",today.minusDays(20).toString(),"endDate",today.plusDays(100).toString()),201));
    }
    private Map<String,Object> attendanceBody(String status,int session) {
        return Map.of("userId",first.id,"date",today.toString(),"unitType","SESSION","sessionNo",session,"status",status);
    }
    private long attendance(String status,int session) throws Exception {
        return id(call(admin,put("/api/cohorts/"+cohortId+"/attendance"),attendanceBody(status,session),200));
    }
    private long assignment(LocalDateTime due) throws Exception {
        return id(call(admin,post("/api/cohorts/"+cohortId+"/assignments"),Map.of("title","서비스 설계 과제","description","문제와 해결 과정을 설명해주세요.","dueAt",iso(due),"maxScore",100),201));
    }
    private long assessment() throws Exception {
        return id(call(admin,post("/api/cohorts/"+cohortId+"/assessments"),Map.of("title","API 역량평가","domain","백엔드","startAt",iso(Db.now().minusMinutes(5)),"endAt",iso(Db.now().plusHours(2)),"durationMinutes",30),201));
    }
    private Question question(long assessment,int position,int points) throws Exception {
        String response=call(admin,post("/api/assessments/"+assessment+"/questions"),Map.of("prompt","올바른 선택지를 고르세요.","position",position,"points",points,"explanation","정답 공개 전에는 이 해설이 노출되면 안 됩니다.","options",List.of(Map.of("label","정답 선택지","isCorrect",true),Map.of("label","오답 선택지","isCorrect",false))),201);
        return new Question(id(response),number(response,"$.options[0].id"),number(response,"$.options[1].id"));
    }
    private static Map<String,Object> answers(long question,long option) {return Map.of("answers",List.of(answer(question,option)));}
    private static Map<String,Object> answer(long question,long option) {return Map.of("questionId",question,"optionId",option);}
    private User account(boolean administrator) throws Exception {
        String email="learning-"+UUID.randomUUID()+"@example.test";
        var now=Db.now();
        long id=db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?,?)",
                email,passwords.encode(PASSWORD),"학습 테스트 사용자",administrator?"ADMIN":"STUDENT",now,now);
        String session=call(null,post("/api/auth/login"),Map.of("email",email,"password",PASSWORD),200);
        return new User(id,JsonPath.read(session,"$.accessToken"));
    }
    private String call(User user,MockHttpServletRequestBuilder builder,Object body,int expected) throws Exception {
        if(user!=null)builder.header("Authorization",bearer(user));
        if(body!=null)builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private static long id(String response) {return number(response,"$.id");}
    private static long number(String response,String path) {return ((Number)JsonPath.read(response,path)).longValue();}
    private static String iso(LocalDateTime date) {return date.atOffset(ZoneOffset.UTC).toString();}
    private static String bearer(User user) {return "Bearer "+user.access;}
    private record User(long id,String access) { }
    private record Question(long id,long correct,long wrong) { }
}
