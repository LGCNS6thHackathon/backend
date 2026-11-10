package kr.wsts.lms.domain.community;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:lms-community-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.security.jwt-secret=community-test-only-secret-at-least-32-bytes",
    "app.bootstrap.admin-email=","app.bootstrap.admin-password="
})
@ActiveProfiles("test") @AutoConfigureMockMvc
class CommunityIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired PasswordEncoder passwords;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String PASSWORD="TestPassword!42";
    record User(long id,String token){}
    record Context(User admin,User student,User other,User outsider,long cohort){}
    private String send(MockHttpServletRequestBuilder request,User user,Object body,int status)throws Exception{
        if(user!=null)request.header("Authorization","Bearer "+user.token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        return mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    private static long id(String json){return ((Number)JsonPath.read(json,"$.id")).longValue();}
    private User user(boolean admin)throws Exception{
        String email="case-"+UUID.randomUUID()+"@example.test";
        var now=Db.now();
        long id=db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?,?)",
                email,passwords.encode(PASSWORD),"테스트사용자",admin?"ADMIN":"STUDENT",now,now);
        String json=send(post("/api/auth/login"),null,Map.of("email",email,"password",PASSWORD),200);
        return new User(id,JsonPath.read(json,"$.accessToken"));
    }
    private Context context()throws Exception{
        User admin=user(true),student=user(false),other=user(false),outsider=user(false);
        long course=id(send(post("/api/courses"),admin,Map.of("title","검증용과정","description","통합검증","totalHours",100),201));
        long cohort=id(send(post("/api/courses/"+course+"/cohorts"),admin,Map.of("name","테스트기수","startDate",LocalDate.now().minusDays(10).toString(),"endDate",LocalDate.now().plusDays(100).toString()),201));
        for(User u:List.of(student,other))send(post("/api/cohorts/"+cohort+"/enrollments"),admin,Map.of("userId",u.id,"role","STUDENT"),201);
        return new Context(admin,student,other,outsider,cohort);
    }
    @Test void postsCommentsAndNotificationsRespectOwnershipAndCohort()throws Exception{
        var c=context();String base="/api/cohorts/"+c.cohort+"/posts";
        send(get(base),c.outsider,null,403);
        send(post(base),c.student,Map.of("category","COURSE","title","권한 검증","body","작성 불가"),403);
        long post=id(send(post(base),c.admin,Map.of("category","QUESTION","title","질문을 나눕니다","body","도움이 필요한 학습 내용입니다."),201));
        send(patch("/api/posts/"+post),c.other,Map.of("title","침범 수정"),403);
        send(patch("/api/posts/"+post),c.admin,Map.of("title","질문을 수정합니다"),200);
        String detail=send(get("/api/posts/"+post),c.other,null,200);
        assertEquals("질문을 수정합니다",JsonPath.read(detail,"$.title"));
        long comment=id(send(post("/api/posts/"+post+"/comments"),c.other,Map.of("body","동료의 답변"),201));
        send(patch("/api/comments/"+comment),c.student,Map.of("body","다른 사람 댓글 변경"),403);
        send(delete("/api/comments/"+comment),c.other,null,204);
        String notifications=send(get("/api/notifications"),c.admin,null,200);
        assertTrue(((Number)JsonPath.read(notifications,"$.total")).intValue()>0);
        send(post("/api/notifications/read-all"),c.admin,null,200);
        assertEquals(0L,db.count("SELECT COUNT(*) FROM notifications WHERE user_id=? AND read_at IS NULL",c.admin.id));
        send(delete("/api/posts/"+post),c.admin,null,204);
        send(get("/api/posts/"+post),c.other,null,404);
    }
    @Test void adminBoardChangesAreVisibleToStudents()throws Exception{
        var c=context();String base="/api/admin/cohorts/"+c.cohort+"/posts";
        send(get(base),null,null,401);
        send(get(base),c.student,null,403);
        send(post("/api/cohorts/"+c.cohort+"/posts"),c.student,Map.of("category","QUESTION","title","질문","body","내용"),403);
        for(String category:List.of("COURSE","SYSTEM","FAQ","QUESTION")) {
            send(post(base),c.admin,Map.of("category",category,"body","내용"),400);
            send(post(base),c.admin,Map.of("category",category,"title"," ","body","내용"),400);
            send(post(base),c.admin,Map.of("category",category,"title","제목","body"," "),400);
            long post=id(send(post(base),c.admin,Map.of("category",category,"title","문","body","답"),201));
            String path="/api/admin/posts/"+post;
            send(get(path),c.student,null,403);
            send(patch(path),c.student,Map.of("title","수정"),403);
            send(delete(path),c.student,null,403);
            send(patch("/api/posts/"+post),c.student,Map.of("title","수정"),403);
            send(delete("/api/posts/"+post),c.student,null,403);
            send(patch(path),c.admin,Map.of("body"," "),400);
            send(patch(path),c.admin,Map.of("title","수정 제목","body","수정 내용"),200);
            assertEquals("수정 내용",JsonPath.read(send(get("/api/posts/"+post),c.student,null,200),"$.body"));
            assertTrue(send(get("/api/cohorts/"+c.cohort+"/posts").param("category",category),c.student,null,200).contains("수정 제목"));
            send(delete(path),c.admin,null,204);
            send(get(path),c.admin,null,404);
            send(get("/api/posts/"+post),c.student,null,404);
            assertEquals(0,((Number)JsonPath.read(send(get("/api/cohorts/"+c.cohort+"/posts").param("category",category),c.student,null,200),"$.total")).intValue());
        }
    }
    @Test void attachmentRoundTripUsesSafeFilenameAndChecksMembership()throws Exception{
        var c=context();long post=id(send(post("/api/cohorts/"+c.cohort+"/posts"),c.admin,Map.of("category","QUESTION","title","첨부파일 테스트","body","파일 권한을 확인합니다."),201));
        byte[] contents="샘플 학습 자료".getBytes(StandardCharsets.UTF_8);
        String uploaded=mvc.perform(multipart("/api/posts/"+post+"/files").file(new MockMultipartFile("file","../../guide.txt","text/plain",contents)).header("Authorization","Bearer "+c.admin.token)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long file=id(uploaded);assertEquals("guide.txt",JsonPath.read(uploaded,"$.originalName"));assertFalse(uploaded.contains("storedName"));
        send(get("/api/files/"+file+"/download"),c.outsider,null,403);
        byte[] downloaded=mvc.perform(get("/api/files/"+file+"/download").header("Authorization","Bearer "+c.other.token)).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(contents,downloaded);
        send(delete("/api/files/"+file),c.admin,null,204);
        send(get("/api/files/"+file+"/download"),c.student,null,404);
    }
    @Test void removedStudyRoutesAreUnavailableToStudentsAndAdministrators()throws Exception{
        for(User actor:List.of(user(true),user(false))){
            String collection="/api/cohorts/1/study-groups",item="/api/study-groups/1";
            send(get(collection),actor,null,404);
            send(post(collection),actor,Map.of("name","삭제된 스터디","description","더 이상 제공하지 않는 기능","capacity",2),404);
            send(get(item),actor,null,404);
            send(patch(item),actor,Map.of("capacity",3),404);
            send(delete(item),actor,null,404);
            send(get(item+"/members"),actor,null,404);
            send(post(item+"/members/me"),actor,null,404);
            send(delete(item+"/members/me"),actor,null,404);
        }
    }
}
