package kr.wsts.lms.domain.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lms-assignment-spec-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.jwt-secret=assignment-spec-test-key-at-least-32-bytes",
        "app.bootstrap.admin-email=", "app.bootstrap.admin-password="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AssignmentSpecIntegrationTests {
    private static final String PASSWORD="AssignmentPassword!42";
    @TempDir static Path storage;
    @DynamicPropertySource
    static void storageDirectory(DynamicPropertyRegistry properties) { properties.add("app.storage.directory",()->storage.toString()); }
    private final JsonMapper json=JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired PasswordEncoder passwords;
    private User admin,teacher,student,other;
    private long cohortId;

    @BeforeEach
    void fixture() throws Exception {
        admin=account("ADMIN","과제 운영자");teacher=account("INSTRUCTOR","담당 강사");
        student=account("STUDENT","제출 교육생");other=account("STUDENT","다른 교육생");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        long course=id(call(admin,post("/api/courses"),Map.of("title","과제 명세 검증 과정","totalHours",80),201));
        cohortId=id(call(admin,post("/api/courses/"+course+"/cohorts"),Map.of("name","과제 명세 검증 기수","startDate",today.minusDays(20).toString(),"endDate",today.plusDays(100).toString()),201));
        for(User user:List.of(teacher,student,other))
            call(admin,post("/api/cohorts/"+cohortId+"/enrollments"),Map.of("userId",user.id,"role",user==teacher?"INSTRUCTOR":"STUDENT"),201);
    }

    @Test
    void instructorFieldsPersistIndependentlyOfAuthorAndRequireAnActiveCohortInstructor() throws Exception {
        var body=assignmentBody();body.put("instructorId",teacher.id);body.put("instructorName","강사 표시 이름");
        String created=call(admin,post("/api/cohorts/"+cohortId+"/assignments"),body,201);
        long assignment=id(created);
        assertEquals(admin.id,number(created,"$.authorId"));
        assertEquals(teacher.id,number(created,"$.instructorId"));
        assertEquals("강사 표시 이름",JsonPath.read(created,"$.instructorName"));
        String renamed=call(admin,patch("/api/assignments/"+assignment),Map.of("instructorName","수정 표시 이름"),200);
        assertEquals(teacher.id,number(renamed,"$.instructorId"));
        assertEquals("수정 표시 이름",JsonPath.read(renamed,"$.instructorName"));
        String preserved=call(admin,patch("/api/assignments/"+assignment),Map.of("title","수정한 과제 제목"),200);
        assertEquals("수정 표시 이름",JsonPath.read(preserved,"$.instructorName"));
        String linked=call(admin,patch("/api/assignments/"+assignment),Map.of("instructorId",teacher.id),200);
        assertEquals("담당 강사",JsonPath.read(linked,"$.instructorName"));
        mvc.perform(get("/api/assignments/"+assignment).header("Authorization",bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authorId").value(admin.id))
                .andExpect(jsonPath("$.instructorId").value(teacher.id)).andExpect(jsonPath("$.instructorName").value("담당 강사"));
        mvc.perform(get("/api/cohorts/"+cohortId+"/assignments").header("Authorization",bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].instructorId").value(teacher.id));
        Map<String,Object> clearInstructor=new LinkedHashMap<>();clearInstructor.put("instructorId",null);
        String cleared=call(admin,patch("/api/assignments/"+assignment),clearInstructor,200);
        assertNull(JsonPath.read(cleared,"$.instructorId"));assertNull(JsonPath.read(cleared,"$.instructorName"));
        assertEquals(admin.id,number(cleared,"$.authorId"));

        var snapshot=assignmentBody();snapshot.put("instructorName","외부 강사 기록");
        String named=call(admin,post("/api/cohorts/"+cohortId+"/assignments"),snapshot,201);
        assertNull(JsonPath.read(named,"$.instructorId"));assertEquals("외부 강사 기록",JsonPath.read(named,"$.instructorName"));
        String legacy=call(admin,post("/api/cohorts/"+cohortId+"/assignments"),assignmentBody(),201);
        assertNull(JsonPath.read(legacy,"$.instructorId"));assertNull(JsonPath.read(legacy,"$.instructorName"));

        db.update("UPDATE users SET role='INSTRUCTOR' WHERE id=?",other.id);
        call(admin,patch("/api/assignments/"+assignment),Map.of("instructorId",other.id),400);
        db.update("UPDATE enrollments SET status='WITHDRAWN' WHERE cohort_id=? AND user_id=?",cohortId,teacher.id);
        call(admin,patch("/api/assignments/"+assignment),Map.of("instructorId",teacher.id),400);
        db.update("UPDATE enrollments SET status='ACTIVE' WHERE cohort_id=? AND user_id=?",cohortId,teacher.id);
        db.update("UPDATE users SET status='DISABLED' WHERE id=?",teacher.id);
        call(admin,patch("/api/assignments/"+assignment),Map.of("instructorId",teacher.id),400);
    }

    @Test
    void softDeletionHidesAssignmentAndItsFilesWhileRetainingSubmissionAndReviewHistory() throws Exception {
        long assignment=assignment(),submission=submission(assignment);
        long assignmentFile=id(upload(admin,"assignments",assignment,"assignment.txt",201));
        long submissionFile=id(upload(student,"submissions",submission,"submission.txt",201));
        call(teacher,patch("/api/submissions/"+submission+"/review"),Map.of("score",90,"feedback","설계 근거가 명확합니다."),200);
        call(admin,delete("/api/assignments/"+assignment),null,204);
        assertNotNull(db.one("SELECT deleted_at FROM assignments WHERE id=?",assignment).get("deletedAt"));
        assertEquals(1,db.count("SELECT COUNT(*) FROM submissions WHERE id=?",submission));
        assertEquals(1,db.count("SELECT COUNT(*) FROM submission_reviews WHERE submission_id=?",submission));
        assertEquals(2,db.count("SELECT COUNT(*) FROM files WHERE assignment_id=? OR submission_id=?",assignment,submission));
        mvc.perform(get("/api/cohorts/"+cohortId+"/assignments").header("Authorization",bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        for(User user:List.of(admin,student))call(user,get("/api/assignments/"+assignment),null,404);
        call(teacher,get("/api/assignments/"+assignment+"/submissions"),null,404);
        call(admin,patch("/api/assignments/"+assignment),Map.of("title","삭제 후 과제 수정"),404);
        call(student,put("/api/assignments/"+assignment+"/submission"),Map.of("body","삭제 후 제출 수정"),404);
        call(teacher,patch("/api/submissions/"+submission+"/review"),Map.of("score",80,"feedback","삭제 후 평가 시도"),404);
        upload(admin,"assignments",assignment,"hidden.txt",404);upload(student,"submissions",submission,"hidden.txt",404);
        for(long file:List.of(assignmentFile,submissionFile)) {
            call(admin,get("/api/files/"+file),null,404);call(student,get("/api/files/"+file+"/download"),null,404);
        }
    }

    @Test
    void eachSuccessfulSubmissionFileUploadUpdatesSubmissionTime() throws Exception {
        long assignment=assignment(),submission=submission(assignment);
        LocalDateTime original=Db.now().minusDays(1).withNano(0);
        db.update("UPDATE submissions SET submitted_at=?,updated_at=? WHERE id=?",original,original,submission);
        upload(student,"submissions",submission,"first.txt",201);
        var first=db.one("SELECT submitted_at,updated_at FROM submissions WHERE id=?",submission);
        assertTrue(LearningSupport.utc(first.get("submittedAt")).isAfter(original));assertEquals(first.get("submittedAt"),first.get("updatedAt"));
        // Model elapsed time explicitly: two fast uploads can share the same OS clock tick.
        LocalDateTime previousUpload=LearningSupport.utc(first.get("submittedAt")).minusSeconds(1);
        db.update("UPDATE submissions SET submitted_at=?,updated_at=? WHERE id=?",previousUpload,previousUpload,submission);
        upload(student,"submissions",submission,"latest.txt",201);
        var latest=db.one("SELECT submitted_at,updated_at FROM submissions WHERE id=?",submission);
        assertTrue(LearningSupport.utc(latest.get("submittedAt")).isAfter(previousUpload));
        assertEquals(latest.get("submittedAt"),latest.get("updatedAt"));
        assertEquals(2,db.count("SELECT COUNT(*) FROM files WHERE submission_id=?",submission));
    }

    @Test
    void failedUploadRollsBackFileMetadataAndSubmissionTimeAndRemovesStoredFile() throws Exception {
        long assignment=assignment(),submission=submission(assignment);
        LocalDateTime original=Db.now().minusDays(1).withNano(0);
        db.update("UPDATE submissions SET submitted_at=?,updated_at=? WHERE id=?",original,original,submission);
        long storedBefore=storedFiles();
        mvc.perform(multipart("/api/submissions/"+submission+"/files")
                .file(new MockMultipartFile("file","empty.txt","text/plain",new byte[0])).header("Authorization",bearer(student)))
                .andExpect(status().isBadRequest());
        // Force the timestamp write to fail after the file metadata INSERT succeeds.
        String timestamp=original.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        db.update("ALTER TABLE submissions ADD CONSTRAINT ck_assignment_spec_upload_failure CHECK (id <> "+submission+" OR submitted_at = TIMESTAMP '"+timestamp+"')");
        try { upload(student,"submissions",submission,"rollback.txt",409); }
        finally { db.update("ALTER TABLE submissions DROP CONSTRAINT ck_assignment_spec_upload_failure"); }
        var unchanged=db.one("SELECT submitted_at,updated_at FROM submissions WHERE id=?",submission);
        assertEquals(original,LearningSupport.utc(unchanged.get("submittedAt")));assertEquals(original,LearningSupport.utc(unchanged.get("updatedAt")));
        assertEquals(0,db.count("SELECT COUNT(*) FROM files WHERE submission_id=?",submission));
        assertEquals(storedBefore,storedFiles());
    }

    @Test
    void submissionFilePrivacyAndDeadlineAndReviewLocksRemainEnforced() throws Exception {
        long assignment=assignment(),submission=submission(assignment);
        long file=id(upload(student,"submissions",submission,"private.txt",201));
        call(other,get("/api/files/"+file),null,403);upload(other,"submissions",submission,"foreign.txt",403);
        mvc.perform(get("/api/assignments/"+assignment+"/submissions").header("Authorization",bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/assignments/"+assignment+"/submissions").header("Authorization",bearer(teacher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        call(teacher,get("/api/files/"+file),null,200);
        call(teacher,patch("/api/submissions/"+submission+"/review"),Map.of("score",101,"feedback","점수 범위 검증"),400);
        call(teacher,patch("/api/submissions/"+submission+"/review"),Map.of("score",95,"feedback","근거가 명확한 설계입니다."),200);
        Object reviewedTime=db.one("SELECT submitted_at FROM submissions WHERE id=?",submission).get("submittedAt");
        upload(student,"submissions",submission,"reviewed.txt",409);
        assertEquals(reviewedTime,db.one("SELECT submitted_at FROM submissions WHERE id=?",submission).get("submittedAt"));
        long expired=assignment(),expiredSubmission=submission(expired);
        db.update("UPDATE assignments SET due_at=? WHERE id=?",Db.now().minusMinutes(1),expired);
        Object expiredTime=db.one("SELECT submitted_at FROM submissions WHERE id=?",expiredSubmission).get("submittedAt");
        upload(student,"submissions",expiredSubmission,"late.txt",409);
        assertEquals(expiredTime,db.one("SELECT submitted_at FROM submissions WHERE id=?",expiredSubmission).get("submittedAt"));
    }

    private Map<String,Object> assignmentBody() {
        return new LinkedHashMap<>(Map.of("title","서비스 설계 과제","description","문제와 해결 과정을 설명해주세요.","dueAt",Db.now().plusDays(2).atOffset(ZoneOffset.UTC).toString(),"maxScore",100));
    }
    private long assignment() throws Exception {return id(call(admin,post("/api/cohorts/"+cohortId+"/assignments"),assignmentBody(),201));}
    private long submission(long assignment) throws Exception {return id(call(student,put("/api/assignments/"+assignment+"/submission"),Map.of("body","과제 제출 내용입니다."),200));}
    private String upload(User user,String type,long id,String filename,int expected) throws Exception {
        return mvc.perform(multipart("/api/"+type+"/"+id+"/files")
                .file(new MockMultipartFile("file",filename,"text/plain","file contents".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization",bearer(user)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private long storedFiles() throws Exception {try(var files=Files.list(storage)){return files.count();}}
    private User account(String role,String name) throws Exception {
        String email="assignment-"+UUID.randomUUID()+"@example.test";var now=Db.now();
        long id=db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?,?)",email,passwords.encode(PASSWORD),name,role,now,now);
        String session=call(null,post("/api/auth/login"),Map.of("email",email,"password",PASSWORD),200);
        return new User(id,JsonPath.read(session,"$.accessToken"));
    }
    private String call(User user,MockHttpServletRequestBuilder request,Object body,int expected) throws Exception {
        if(user!=null)request.header("Authorization",bearer(user));
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private static long id(String response) {return number(response,"$.id");}
    private static long number(String response,String path) {return ((Number)JsonPath.read(response,path)).longValue();}
    private static String bearer(User user) {return "Bearer "+user.access;}
    private record User(long id,String access) { }
}
