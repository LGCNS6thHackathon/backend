package kr.wsts.lms.domain.integration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import java.util.*;
import java.time.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;
import kr.wsts.lms.global.support.Db;
import tools.jackson.databind.json.JsonMapper;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:portal-integration;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.bootstrap.admin-email=","app.bootstrap.admin-password=","app.discord.webhook-url=","app.ai.api-key=","app.ai.model="})
@ActiveProfiles("test") @AutoConfigureMockMvc
class PortalIntegrationTests {
 @MockitoSpyBean AiReviewController ai;
 @Autowired MockMvc mvc;@Autowired Db db;
 final JsonMapper json=JsonMapper.builder().build();long admin,student,outsider,cohort;
 String call(long actor,MockHttpServletRequestBuilder req,Object body,int status)throws Exception{if(actor>0)req.with(jwt().jwt(j->j.subject(String.valueOf(actor))));if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));return mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();}
 long id(String raw){return ((Number)JsonPath.read(raw,"$.id")).longValue();}
 long user(String role){return db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) VALUES(?,'unused','연동 테스트',?,'ACTIVE',0,?,?)",UUID.randomUUID()+"@example.test",role,Db.now(),Db.now());}
 @BeforeEach void fixture()throws Exception{admin=user("ADMIN");student=user("STUDENT");outsider=user("STUDENT");long course=id(call(admin,post("/api/courses"),Map.of("title","연동 검증 과정"),201));cohort=id(call(admin,post("/api/courses/"+course+"/cohorts"),Map.of("name","연동 검증 기수","startDate",LocalDate.now().minusDays(3).toString(),"endDate",LocalDate.now().plusDays(30).toString()),201));call(admin,post("/api/cohorts/"+cohort+"/enrollments"),Map.of("userId",student,"role","STUDENT"),201);}
 long create(String kind,Map<String,Object> body)throws Exception{return id(call(admin,post("/api/cohorts/"+cohort+"/"+kind),body,201));}
 @Test void curriculumVideoAndQuestionMappingRespectCohortAndRelease()throws Exception{
  long subject=create("subjects",Map.of("title","프론트엔드"));long unit=create("units",Map.of("subjectId",subject,"title","React"));long topic=create("topics",Map.of("unitId",unit,"title","상태 관리"));
  call(student,post("/api/cohorts/"+cohort+"/subjects"),Map.of("title","접근 차단"),403);
  call(outsider,get("/api/cohorts/"+cohort+"/topics"),null,403);
  call(admin,post("/api/cohorts/"+cohort+"/videos"),Map.of("title","잘못된 영상","lectureDate",LocalDate.now().toString(),"periodNo",1,"youtubeUrl","https://evil.example/watch?v=Tn6-PIqc4UM","visibility","PUBLIC"),400);
  long video=create("videos",Map.of("title","React 강의","lectureDate",LocalDate.now().toString(),"periodNo",1,"youtubeUrl","https://youtu.be/Tn6-PIqc4UM","visibility","PRIVATE"));
  assertEquals(0,((Number)JsonPath.read(call(student,get("/api/cohorts/"+cohort+"/videos"),null,200),"$.total")).intValue());
  long chapter=create("chapters",Map.of("videoId",video,"topicId",topic,"title","상태 배치","startSeconds",120));
  call(admin,patch("/api/videos/"+video),Map.of("visibility","PUBLIC"),200);
  String videos=call(student,get("/api/cohorts/"+cohort+"/videos"),null,200);assertEquals(1,((Number)JsonPath.read(videos,"$.total")).intValue());
  long assessment=create("assessments",Map.of("title","역량평가","domain","React","startAt",OffsetDateTime.now().minusHours(1).toString(),"endAt",OffsetDateTime.now().plusHours(2).toString()));
  long question=id(call(admin,post("/api/assessments/"+assessment+"/questions"),Map.of("prompt","상태 갱신 방법은?","options",List.of(Map.of("label","갱신 함수","isCorrect",true),Map.of("label","직접 변경","isCorrect",false)),"topicId",topic,"chapterId",chapter,"explanation","갱신 함수를 사용합니다."),201));
  assertEquals(topic,Db.id(db.one("SELECT topic_id FROM assessment_questions WHERE id=?",question),"topicId"));
  call(admin,patch("/api/assessments/"+assessment),Map.of("status","PUBLISHED"),200);
  long attempt=id(call(student,post("/api/assessments/"+assessment+"/attempts"),Map.of(),201));
  String hidden=call(student,get("/api/assessment-attempts/"+attempt),null,200);assertFalse(hidden.contains("reviewLinks"));assertFalse(hidden.contains("correctChoice"));assertFalse(hidden.contains("isCorrect"));
  call(student,post("/api/assessment-attempts/"+attempt+"/submit"),Map.of(),200);call(admin,patch("/api/assessments/"+assessment),Map.of("status","CLOSED"),200);
  String released=call(student,get("/api/assessment-attempts/"+attempt),null,200);assertTrue(released.contains("reviewLinks"));assertTrue(released.contains("startSeconds"));
  call(admin,delete("/api/subjects/"+subject),null,409);call(admin,delete("/api/chapters/"+chapter),null,204);assertEquals(0,db.count("SELECT COUNT(*) FROM assessment_question_review_links WHERE question_id=?",question));
 }
 @Test void publicNoticesAndProviderFailuresAreExplicitAndPersisted()throws Exception{
  String slug="notice-"+UUID.randomUUID();long post=create("posts",Map.of("title","공개 운영 공지","body","공개 가능한 운영 안내 내용","category","COURSE","visibility","PUBLIC","publicSlug",slug));
  call(0,get("/api/public/notices/"+slug),null,200);String failed=call(admin,post("/api/posts/"+post+"/discord"),Map.of(),200);assertEquals("FAILED",JsonPath.read(failed,"$.status"));assertEquals("DISCORD_NOT_CONFIGURED",JsonPath.read(failed,"$.errorCode"));
  call(student,post("/api/posts/"+post+"/discord"),Map.of(),403);call(admin,patch("/api/posts/"+post),Map.of("visibility","COHORT"),200);call(0,get("/api/public/notices/"+slug),null,404);
  long subject=create("subjects",Map.of("title","학습")),unit=create("units",Map.of("subjectId",subject,"title","기초")),topic=create("topics",Map.of("unitId",unit,"title","복습 개념"));
  long session=id(call(student,post("/api/cohorts/"+cohort+"/review-sessions"),Map.of("mode","BLANK","topicId",topic),201));
  call(outsider,get("/api/review-sessions/"+session),null,403);call(student,put("/api/review-sessions/"+session+"/blank"),Map.of("content",""),400);
  call(student,put("/api/review-sessions/"+session+"/blank"),Map.of("content","오늘 배운 상태 관리 개념을 정리합니다."),503);
  assertEquals("FAILED",db.one("SELECT status FROM blank_reviews WHERE session_id=?",session).get("status"));
 }
 @Test void aiFeedbackAndSequentialQuizPersistWithoutRevealingAnswersEarly()throws Exception{
  long subject=create("subjects",Map.of("title","복습 과목")),unit=create("units",Map.of("subjectId",subject,"title","복습 단원")),topic=create("topics",Map.of("unitId",unit,"title","복습 주제"));
  doReturn(Map.of("strengths","상태 개념을 이해했습니다.","gaps","배치 처리를 보완하세요.","recommendations","렌더링 영상을 다시 보세요.")).when(ai).generate(anyString(),anyMap());
  long blank=id(call(student,post("/api/cohorts/"+cohort+"/review-sessions"),Map.of("mode","BLANK","topicId",topic),201));
  String feedback=call(student,put("/api/review-sessions/"+blank+"/blank"),Map.of("content","상태 변경은 렌더링을 예약합니다."),200);
  assertEquals("COMPLETED",JsonPath.read(feedback,"$.status"));assertNotNull(JsonPath.read(feedback,"$.strengths"));
  doReturn(Map.of("prompt","상태 변경 방법은?","choiceA","갱신 함수","choiceB","직접 변경","choiceC","DOM 변경","choiceD","새로고침","correctChoice",1,"explanation","갱신 함수를 사용합니다.")).when(ai).generate(anyString(),anyMap());
  long quiz=id(call(student,post("/api/cohorts/"+cohort+"/review-sessions"),Map.of("mode","QUIZ","topicId",topic),201));
  String first=call(student,post("/api/review-sessions/"+quiz+"/questions"),Map.of(),200);long question=id(first);assertFalse(first.contains("correctChoice"));assertFalse(first.contains("explanation"));
  assertEquals(question,id(call(student,post("/api/review-sessions/"+quiz+"/questions"),Map.of(),200)));
  call(outsider,post("/api/review-questions/"+question+"/answer"),Map.of("selectedChoice",1),403);
  String answer=call(student,post("/api/review-questions/"+question+"/answer"),Map.of("selectedChoice",1),200);assertEquals(true,JsonPath.read(answer,"$.isCorrect"));assertEquals(1,((Number)JsonPath.read(answer,"$.correctChoice")).intValue());
  call(student,post("/api/review-questions/"+question+"/answer"),Map.of("selectedChoice",2),409);
  call(student,patch("/api/review-sessions/"+quiz),Map.of(),200);call(student,post("/api/review-sessions/"+quiz+"/questions"),Map.of(),409);
 }
}
