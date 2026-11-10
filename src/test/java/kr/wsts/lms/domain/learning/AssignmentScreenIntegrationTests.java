package kr.wsts.lms.domain.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.HttpMethod;
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
        "spring.datasource.url=jdbc:h2:mem:lms-assignment-screen-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.jwt-secret=assignment-screen-test-key-at-least-32-bytes",
        "app.bootstrap.admin-email=", "app.bootstrap.admin-password="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AssignmentScreenIntegrationTests {
    private static final String PASSWORD="AssignmentScreen!42";
    private static final String REPOSITORY="https://github.com/example/assignment";
    private static final String DEPLOYMENT="https://assignment.example.test";
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DISPLAY=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
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
        admin=account("ADMIN","과제 관리자");teacher=account("ADMIN","화면 담당 강사");
        student=account("STUDENT","과제 제출 학생");other=account("STUDENT","다른 학생");
        LocalDate today=LocalDate.now(SEOUL);
        long course=id(call(admin,post("/api/courses"),Map.of("title","과제 화면 검증 과정","totalHours",80),201));
        cohortId=id(call(admin,post("/api/courses/"+course+"/cohorts"),Map.of("name","과제 화면 검증 기수","startDate",today.minusDays(20).toString(),"endDate",today.plusDays(100).toString()),201));
        for(User user:List.of(teacher,student,other))
            call(admin,post("/api/cohorts/"+cohortId+"/enrollments"),Map.of("userId",user.id,"role",user==teacher?"ADMIN":"STUDENT"),201);
    }

    @Test
    void screenFieldsPersistWithoutRequiringAnOptionalMemoAndCanBeLoadedAgain() throws Exception {
        var body=assignmentBody();body.put("subject","고급 웹 프론트엔드");
        long assignment=id(call(admin,post("/api/cohorts/"+cohortId+"/assignments"),body,201));
        String saved=call(student,put(submissionPath(assignment)),Map.of("repoUrl",REPOSITORY,"deployUrl",DEPLOYMENT,"submitMemo",""),200);
        assertEquals(REPOSITORY,JsonPath.read(saved,"$.repositoryUrl"));
        assertEquals(DEPLOYMENT,JsonPath.read(saved,"$.deploymentUrl"));
        assertEquals("",JsonPath.read(saved,"$.body"));
        assertEquals(REPOSITORY,JsonPath.read(saved,"$.repoUrl"));
        assertEquals(DEPLOYMENT,JsonPath.read(saved,"$.deployUrl"));
        assertEquals("",JsonPath.read(saved,"$.submitMemo"));
        assertTrue(((List<?>)JsonPath.read(saved,"$.files")).isEmpty());

        String detail=call(student,get("/api/assignments/"+assignment),null,200);
        assertEquals("고급 웹 프론트엔드",JsonPath.read(detail,"$.subject"));
        assertEquals("화면 담당 강사",JsonPath.read(detail,"$.author"));
        assertEquals(REPOSITORY,JsonPath.read(detail,"$.mySubmission.repoUrl"));
        assertEquals(DEPLOYMENT,JsonPath.read(detail,"$.mySubmission.deployUrl"));
        assertEquals("제출완료",JsonPath.read(detail,"$.status"));
        assertEquals("SUBMITTED",JsonPath.read(detail,"$.reviewStatus"));
        assertNull(JsonPath.read(detail,"$.feedback"));
        assertNull(JsonPath.read(detail,"$.myReview"));
        assertEquals(true,JsonPath.read(detail,"$.canResubmit"));
        assertEquals(false,JsonPath.read(detail,"$.canSubmit"));
        assertEquals(false,JsonPath.read(detail,"$.submissionLocked"));
        assertEquals(display(number(saved,"$.id")),JsonPath.read(detail,"$.submissionDate"));

        call(admin,patch("/api/assignments/"+assignment),Map.of("subject","클라우드 실습"),200);
        assertEquals("클라우드 실습",JsonPath.read(call(student,get("/api/assignments/"+assignment),null,200),"$.subject"));
        String canonical=call(student,put(submissionPath(assignment)),Map.of("repositoryUrl",REPOSITORY+"-v2","deploymentUrl",DEPLOYMENT+"/v2","body","재제출 메모"),200);
        assertEquals("재제출 메모",JsonPath.read(canonical,"$.submitMemo"));
        assertEquals(DEPLOYMENT+"/v2",JsonPath.read(canonical,"$.deployUrl"));
        assertEquals(id(saved),id(canonical));
        String partial=call(student,put(submissionPath(assignment)),Map.of("submitMemo","메모만 바꿉니다."),200);
        assertEquals(REPOSITORY+"-v2",JsonPath.read(partial,"$.repoUrl"));
        assertEquals(DEPLOYMENT+"/v2",JsonPath.read(partial,"$.deployUrl"));
        call(student,put(submissionPath(assignment)),Map.of("repositoryUrl",REPOSITORY,"repoUrl",REPOSITORY+"-conflict"),400);
        assertEquals(REPOSITORY+"-v2",db.one("SELECT repository_url FROM submissions WHERE id=?",id(saved)).get("repositoryUrl"));
        Map<String,Object> clear=new LinkedHashMap<>();clear.put("deployUrl",null);
        assertEquals("",JsonPath.read(call(student,put(submissionPath(assignment)),clear,200),"$.deploymentUrl"));
    }

    @Test
    void assignmentCardsExposeOnlyTheCurrentStudentsSubmissionAndRealReview() throws Exception {
        long assignment=assignment();
        String untouched=call(student,get("/api/assignments/"+assignment),null,200);
        assertEquals("과제",JsonPath.read(untouched,"$.subject"));
        assertEquals("미제출",JsonPath.read(untouched,"$.status"));
        assertEquals("NOT_SUBMITTED",JsonPath.read(untouched,"$.reviewStatus"));
        assertEquals(true,JsonPath.read(untouched,"$.canSubmit"));
        assertNull(JsonPath.read(untouched,"$.mySubmission"));
        assertNull(JsonPath.read(untouched,"$.submissionDate"));
        assertEquals(Instant.parse((String)JsonPath.read(untouched,"$.dueAt")).atZone(SEOUL).format(DISPLAY),JsonPath.read(untouched,"$.dueDate"));

        long mine=id(call(student,put(submissionPath(assignment)),Map.of("body","나의 제출 내용","repositoryUrl",REPOSITORY),200));
        long theirs=id(call(other,put(submissionPath(assignment)),Map.of("body","다른 학생 비공개 내용","repositoryUrl",REPOSITORY+"-private"),200));
        call(teacher,patch("/api/submissions/"+theirs+"/review"),Map.of("score",10,"feedback","다른 학생 전용 피드백"),200);
        String beforeReview=call(student,get("/api/assignments/"+assignment),null,200);
        assertEquals(mine,number(beforeReview,"$.mySubmission.id"));
        assertNull(JsonPath.read(beforeReview,"$.feedback"));
        assertFalse(beforeReview.contains("다른 학생 비공개 내용"));
        assertFalse(beforeReview.contains("다른 학생 전용 피드백"));

        call(teacher,patch("/api/submissions/"+mine+"/review"),Map.of("score",90,"feedback","요구사항과 구현이 잘 맞습니다."),200);
        // Keep the current screen's submitted filter compatible even after a review is added.
        String reviewed=call(student,get("/api/assignments/"+assignment),null,200);
        assertEquals("제출완료",JsonPath.read(reviewed,"$.status"));
        assertEquals("REVIEWED",JsonPath.read(reviewed,"$.reviewStatus"));
        assertEquals("화면 담당 강사",JsonPath.read(reviewed,"$.feedback.mentor"));
        assertEquals("90 / 100",JsonPath.read(reviewed,"$.feedback.score"));
        assertEquals("요구사항과 구현이 잘 맞습니다.",JsonPath.read(reviewed,"$.feedback.comment"));
        assertEquals("화면 담당 강사",JsonPath.read(reviewed,"$.myReview.reviewerName"));
        assertEquals("REVIEWED",JsonPath.read(reviewed,"$.lockReason"));
        assertEquals(false,JsonPath.read(reviewed,"$.canResubmit"));

        assignment();
        String page=call(student,get("/api/cohorts/"+cohortId+"/assignments?page=0&size=1"),null,200);
        assertEquals(2,number(page,"$.total"));
        assertEquals(1,((List<?>)JsonPath.read(page,"$.items")).size());
        String all=call(student,get("/api/cohorts/"+cohortId+"/assignments?size=100"),null,200);
        assertFalse(all.contains("다른 학생 비공개 내용"));
        assertFalse(all.contains("다른 학생 전용 피드백"));
        assertEquals(2,((List<?>)JsonPath.read(call(teacher,get("/api/assignments/"+assignment+"/submissions"),null,200),"$.items")).size());
    }

    @Test
    void multipartSubmissionCanBeReloadedAndItsFilesAreDiscoverableOnlyByOwnerAndStaff() throws Exception {
        long assignment=assignment();
        byte[] content="실제 과제 산출물".getBytes(StandardCharsets.UTF_8);
        String saved=multipartSubmission(student,assignment,"최초 제출 메모",file("project.zip",content),200);
        long submission=id(saved),fileId=number(saved,"$.files[0].id");
        assertEquals("project.zip",JsonPath.read(saved,"$.files[0].originalName"));
        assertEquals("/api/files/"+fileId+"/download",JsonPath.read(saved,"$.files[0].downloadUrl"));
        assertEquals(DEPLOYMENT,JsonPath.read(saved,"$.deploymentUrl"));

        String reloaded=call(student,get("/api/assignments/"+assignment),null,200);
        assertEquals(fileId,number(reloaded,"$.mySubmission.files[0].id"));
        assertEquals("최초 제출 메모",JsonPath.read(reloaded,"$.mySubmission.submitMemo"));
        String teacherList=call(teacher,get("/api/assignments/"+assignment+"/submissions"),null,200);
        assertEquals(fileId,number(teacherList,"$.items[0].files[0].id"));
        assertEquals("과제 제출 학생",JsonPath.read(teacherList,"$.items[0].userName"));
        for(User reader:List.of(student,teacher,admin)) {
            String files=call(reader,get("/api/submissions/"+submission+"/files"),null,200);
            assertEquals(fileId,number(files,"$[0].id"));
            mvc.perform(get("/api/files/"+fileId+"/download").header("Authorization",bearer(reader)))
                    .andExpect(status().isOk()).andExpect(content().bytes(content));
        }
        call(other,get("/api/submissions/"+submission+"/files"),null,403);
        call(other,get("/api/files/"+fileId+"/download"),null,403);
        call(null,get("/api/submissions/"+submission+"/files"),null,401);

        call(student,delete("/api/files/"+fileId),null,204);
        assertTrue(((List<?>)JsonPath.read(call(student,get("/api/submissions/"+submission+"/files"),null,200),"$")).isEmpty());
    }

    @Test
    void submissionUploadsAllowMoreThanTenMiBButStillEnforceTheFiftyMiBLimit() throws Exception {
        long assignment=assignment();
        byte[] elevenMiB=new byte[11*1024*1024];
        String saved=multipartSubmission(student,assignment,"큰 파일 제출",file("report.zip",elevenMiB),200);
        assertEquals(elevenMiB.length,number(saved,"$.files[0].sizeBytes"));
        mvc.perform(multipart("/api/assignments/"+assignment+"/files").file(file("notice.zip",elevenMiB)).header("Authorization",bearer(admin)))
                .andExpect(status().isBadRequest());
        MockMultipartFile oversized=new MockMultipartFile("file","oversized.zip","application/zip",new byte[]{1}) {
            @Override public long getSize() { return 50L*1024*1024+1; }
        };
        Object originalTime=db.one("SELECT submitted_at FROM submissions WHERE id=?",id(saved)).get("submittedAt");
        long storedBefore=storedFiles();
        multipartSubmission(student,assignment,"거절되어야 하는 메모",oversized,400);
        assertEquals("큰 파일 제출",db.one("SELECT body FROM submissions WHERE id=?",id(saved)).get("body"));
        assertEquals(originalTime,db.one("SELECT submitted_at FROM submissions WHERE id=?",id(saved)).get("submittedAt"));
        assertEquals(storedBefore,storedFiles());
        assertEquals(1,db.count("SELECT COUNT(*) FROM files WHERE submission_id=?",id(saved)));
    }

    @Test
    void failedMultipartUploadRollsBackTheSubmissionAndAllNewFileData() throws Exception {
        long assignment=assignment(),storedBefore=storedFiles();
        multipartSubmission(student,assignment,"저장되면 안 되는 메모",file("empty.zip",new byte[0]),400);
        assertEquals(0,db.count("SELECT COUNT(*) FROM submissions WHERE assignment_id=?",assignment));
        assertEquals(storedBefore,storedFiles());

        String original=multipartSubmission(student,assignment,"원래 제출 메모",file("original.zip",new byte[]{1,2,3}),200);
        long submission=id(original);
        LocalDateTime originalTime=Db.now().minusDays(1).withNano(0);
        db.update("UPDATE submissions SET submitted_at=?,updated_at=? WHERE id=?",originalTime,originalTime,submission);
        storedBefore=storedFiles();
        String timestamp=originalTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        db.update("ALTER TABLE submissions ADD CONSTRAINT ck_assignment_screen_upload_failure CHECK (id <> "+submission+" OR submitted_at = TIMESTAMP '"+timestamp+"')");
        try { multipartSubmission(student,assignment,"롤백되어야 하는 변경",file("rollback.zip",new byte[]{4,5,6}),409); }
        finally { db.update("ALTER TABLE submissions DROP CONSTRAINT ck_assignment_screen_upload_failure"); }
        var unchanged=db.one("SELECT body,submitted_at,updated_at FROM submissions WHERE id=?",submission);
        assertEquals("원래 제출 메모",unchanged.get("body"));
        assertEquals(originalTime,LearningSupport.utc(unchanged.get("submittedAt")));
        assertEquals(originalTime,LearningSupport.utc(unchanged.get("updatedAt")));
        assertEquals(1,db.count("SELECT COUNT(*) FROM files WHERE submission_id=?",submission));
        assertEquals(storedBefore,storedFiles());
    }

    @Test
    void metadataChangesKeepTheLastSuccessfulFileUploadTimeWhileLinkOnlyResubmissionsAdvanceIt() throws Exception {
        long assignment=assignment();
        String saved=multipartSubmission(student,assignment,"파일 제출",file("source.zip",new byte[]{1}),200);
        long submission=id(saved);
        LocalDateTime original=Db.now().minusHours(1).withNano(0);
        db.update("UPDATE submissions SET submitted_at=?,updated_at=? WHERE id=?",original,original,submission);
        String changed=call(student,put(submissionPath(assignment)),Map.of("repositoryUrl",REPOSITORY,"deploymentUrl",DEPLOYMENT,"body","메모만 수정"),200);
        assertEquals(original,LearningSupport.utc(JsonPath.read(changed,"$.submittedAt")));
        assertEquals(original.toInstant(ZoneOffset.UTC).atZone(SEOUL).format(DISPLAY),JsonPath.read(call(student,get("/api/assignments/"+assignment),null,200),"$.submissionDate"));
        String uploaded=multipartSubmission(student,assignment,"새 파일로 재제출",file("latest.zip",new byte[]{2}),200);
        assertTrue(LearningSupport.utc(JsonPath.read(uploaded,"$.submittedAt")).isAfter(original));

        long linkAssignment=assignment();
        long linkSubmission=id(call(student,put(submissionPath(linkAssignment)),Map.of("repoUrl",REPOSITORY,"submitMemo",""),200));
        db.update("UPDATE submissions SET submitted_at=?,updated_at=? WHERE id=?",original,original,linkSubmission);
        String linkChanged=call(student,put(submissionPath(linkAssignment)),Map.of("repoUrl",REPOSITORY+"-new","submitMemo",""),200);
        assertTrue(LearningSupport.utc(JsonPath.read(linkChanged,"$.submittedAt")).isAfter(original));
    }

    @Test
    void multipartResubmissionCannotBypassDeadlineReviewOrDeletedAssignmentRules() throws Exception {
        long expired=assignment();
        long expiredSubmission=id(multipartSubmission(student,expired,"마감 전 제출",file("before.zip",new byte[]{1}),200));
        db.update("UPDATE assignments SET due_at=? WHERE id=?",Db.now().minusMinutes(1),expired);
        Object time=db.one("SELECT submitted_at FROM submissions WHERE id=?",expiredSubmission).get("submittedAt");
        multipartSubmission(student,expired,"마감 후 변경",file("late.zip",new byte[]{2}),409);
        assertEquals(time,db.one("SELECT submitted_at FROM submissions WHERE id=?",expiredSubmission).get("submittedAt"));
        String locked=call(student,get("/api/assignments/"+expired),null,200);
        assertEquals(false,JsonPath.read(locked,"$.canResubmit"));
        assertEquals(true,JsonPath.read(locked,"$.submissionLocked"));
        assertEquals("DEADLINE_PASSED",JsonPath.read(locked,"$.lockReason"));
        assertEquals(false,JsonPath.read(call(other,get("/api/assignments/"+expired),null,200),"$.canSubmit"));

        long reviewed=assignment();
        String saved=multipartSubmission(student,reviewed,"채점 전 제출",file("graded.zip",new byte[]{3}),200);
        long submission=id(saved),fileId=number(saved,"$.files[0].id");
        call(teacher,patch("/api/submissions/"+submission+"/review"),Map.of("score",95,"feedback","동작이 잘 구현되었습니다."),200);
        multipartSubmission(student,reviewed,"채점 후 변경",file("reviewed.zip",new byte[]{4}),409);
        call(student,put(submissionPath(reviewed)),Map.of("repoUrl",REPOSITORY,"submitMemo","채점 후 변경"),409);
        assertEquals("채점 전 제출",db.one("SELECT body FROM submissions WHERE id=?",submission).get("body"));
        assertEquals(1,db.count("SELECT COUNT(*) FROM files WHERE submission_id=?",submission));
        call(admin,delete("/api/assignments/"+reviewed),null,204);
        call(student,get("/api/submissions/"+submission+"/files"),null,404);
        call(teacher,get("/api/submissions/"+submission+"/files"),null,404);
        call(student,get("/api/files/"+fileId+"/download"),null,404);
        multipartSubmission(student,reviewed,"삭제 후 제출",file("deleted.zip",new byte[]{5}),404);
    }

    @Test
    void submissionValidatesOptionalUrlsAndRejectsCompletelyEmptyContent() throws Exception {
        long assignment=assignment();
        call(student,put(submissionPath(assignment)),Map.of("repoUrl","","submitMemo",""),400);
        call(student,put(submissionPath(assignment)),Map.of("repoUrl","http://github.com/example/repo","submitMemo",""),400);
        call(student,put(submissionPath(assignment)),Map.of("repoUrl",REPOSITORY,"deployUrl","https://user:secret@example.test","submitMemo",""),400);
        call(student,put(submissionPath(assignment)),Map.of("repoUrl",REPOSITORY,"deployUrl","javascript:alert(1)","submitMemo",""),400);
        assertEquals(0,db.count("SELECT COUNT(*) FROM submissions WHERE assignment_id=?",assignment));
        String textOnly=call(student,put(submissionPath(assignment)),Map.of("body","기존 본문 제출 방식도 지원합니다."),200);
        assertEquals("기존 본문 제출 방식도 지원합니다.",JsonPath.read(textOnly,"$.submitMemo"));
    }

    private Map<String,Object> assignmentBody() {
        return new LinkedHashMap<>(Map.of("title","화면 연결 과제","description","GitHub 저장소와 배포 결과를 제출하세요.","instructorId",teacher.id,"dueAt",Db.now().plusDays(2).atOffset(ZoneOffset.UTC).toString(),"maxScore",100));
    }
    private long assignment() throws Exception { return id(call(admin,post("/api/cohorts/"+cohortId+"/assignments"),assignmentBody(),201)); }
    private static String submissionPath(long assignment) { return "/api/assignments/"+assignment+"/submission"; }
    private String multipartSubmission(User user,long assignment,String memo,MockMultipartFile file,int expected) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT,submissionPath(assignment)).file(file)
                .param("repoUrl",REPOSITORY).param("deployUrl",DEPLOYMENT).param("submitMemo",memo)
                .header("Authorization",bearer(user)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private static MockMultipartFile file(String name,byte[] content) { return new MockMultipartFile("file",name,"application/zip",content); }
    private long storedFiles() throws Exception { if(!Files.exists(storage))return 0;try(var files=Files.list(storage)){return files.count();} }
    private String display(long submission) { return LearningSupport.utc(db.one("SELECT submitted_at FROM submissions WHERE id=?",submission).get("submittedAt")).atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL).format(DISPLAY); }
    private User account(String role,String name) throws Exception {
        String email="screen-"+UUID.randomUUID()+"@example.test";var now=Db.now();
        long id=db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?,?)",email,passwords.encode(PASSWORD),name,role,now,now);
        String session=call(null,post("/api/auth/login"),Map.of("email",email,"password",PASSWORD),200);
        return new User(id,JsonPath.read(session,"$.accessToken"));
    }
    private String call(User user,MockHttpServletRequestBuilder request,Object body,int expected) throws Exception {
        if(user!=null)request.header("Authorization",bearer(user));
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private static long id(String response) { return number(response,"$.id"); }
    private static long number(String response,String path) { return ((Number)JsonPath.read(response,path)).longValue(); }
    private static String bearer(User user) { return "Bearer "+user.access; }
    private record User(long id,String access) { }
}
