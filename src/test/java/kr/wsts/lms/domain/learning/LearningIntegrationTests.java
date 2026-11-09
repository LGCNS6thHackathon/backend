package kr.wsts.lms.domain.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.time.*;
import java.util.*;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
        String response=call(null,post("/api/auth/register"),Map.of("email",email,"password",PASSWORD,"name","학습 테스트 사용자"),201);long id=id(response);
        if(administrator)db.update("UPDATE users SET role='ADMIN' WHERE id=?",id);
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
