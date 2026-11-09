package kr.wsts.lms.domain.lecture;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
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
    "spring.datasource.url=jdbc:h2:mem:lms-video-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.security.jwt-secret=video-test-only-secret-at-least-32-bytes",
    "app.bootstrap.admin-email=","app.bootstrap.admin-password="
})
@ActiveProfiles("test") @AutoConfigureMockMvc
class LectureVideoIntegrationTests {
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
    private Map<String,Object> video(int period) {
        return Map.of("title","강의","youtubeUrl","https://youtu.be/dQw4w9WgXcQ","lectureDate",LocalDate.now().toString(),"periodNo",period,"visibility","PUBLIC");
    }
    @Test void adminCrudReflectsInStudentReadsAndSoftDeleteHidesVideo()throws Exception {
        var c=context();String base="/api/admin/cohorts/"+c.cohort+"/lecture-videos";
        send(get(base),null,null,401);
        send(get(base),c.student,null,403);
        send(post(base),c.student,video(1),403);
        long video=id(send(post(base),c.admin,video(1),201));
        String path="/api/admin/lecture-videos/"+video, studentPath="/api/lecture-videos/"+video;
        send(get(path),c.student,null,403);
        send(patch(path),c.student,Map.of("title","변경"),403);
        send(delete(path),c.student,null,403);
        send(get(studentPath),c.outsider,null,403);
        String detail=send(get(studentPath),c.student,null,200);
        assertEquals("dQw4w9WgXcQ",JsonPath.read(detail,"$.youtubeVideoId"));
        send(patch(path),c.admin,Map.of("title","수정 강의","youtubeUrl","https://www.youtube.com/watch?v=abcdefghijk&t=10"),200);
        detail=send(get(studentPath),c.student,null,200);
        assertEquals("abcdefghijk",JsonPath.read(detail,"$.youtubeVideoId"));
        assertEquals("수정 강의",JsonPath.read(detail,"$.title"));
        String list="/api/cohorts/"+c.cohort+"/lecture-videos";
        assertTrue(send(get(list),c.student,null,200).contains("수정 강의"));
        send(patch(path),c.admin,Map.of("visibility","PRIVATE"),200);
        send(get(studentPath),c.student,null,404);
        assertEquals(0,((Number)JsonPath.read(send(get(list),c.student,null,200),"$.total")).intValue());
        send(get(path),c.admin,null,200);
        send(patch(path),c.admin,Map.of("visibility","PUBLIC"),200);
        send(delete(path),c.admin,null,204);
        send(get(path),c.admin,null,404);
        send(get(studentPath),c.student,null,404);
        send(patch(path),c.admin,Map.of("title","삭제 수정"),404);
        assertEquals(0,((Number)JsonPath.read(send(get(base),c.admin,null,200),"$.total")).intValue());
        assertEquals(0,((Number)JsonPath.read(send(get(list),c.student,null,200),"$.total")).intValue());
        assertNotNull(db.one("SELECT deleted_at FROM lecture_videos WHERE id=?",video).get("deletedAt"));
        send(post(base),c.admin,video(1),409);
    }
    @Test void requiredFieldsDuplicatesAndPaginationAreValidated()throws Exception {
        var c=context();String base="/api/admin/cohorts/"+c.cohort+"/lecture-videos";
        for(String field:List.of("title","youtubeUrl","lectureDate","periodNo")) {
            var input=new HashMap<String,Object>(video(1)); input.remove(field);
            send(post(base),c.admin,input,400);
            input.put(field," ");send(post(base),c.admin,input,400);
            input.put(field,null);send(post(base),c.admin,input,400);
        }
        var input=new HashMap<String,Object>(video(1));input.remove("visibility");
        long video=id(send(post(base),c.admin,input,201));
        assertEquals("PRIVATE",JsonPath.read(send(get("/api/admin/lecture-videos/"+video),c.admin,null,200),"$.visibility"));
        send(post(base),c.admin,input,409);
        String path="/api/admin/lecture-videos/"+video;
        for(String field:List.of("title","youtubeUrl","lectureDate","periodNo","visibility")) {
            send(patch(path),c.admin,Map.of(field," "),400);
        }
        long second=id(send(post(base),c.admin,video(2),201));
        send(patch("/api/admin/lecture-videos/"+second),c.admin,Map.of("periodNo",1),409);
        assertEquals(2,((Number)JsonPath.read(send(get("/api/admin/lecture-videos/"+second),c.admin,null,200),"$.periodNo")).intValue());
        send(get(base).param("page","-1"),c.admin,null,400);
        send(get(base).param("size","101"),c.admin,null,400);
        send(get("/api/cohorts/"+c.cohort+"/lecture-videos"),c.outsider,null,403);
    }
    @Test void invalidYoutubeLinkDoesNotSaveOrChangeVideo()throws Exception {
        var c=context();String base="/api/admin/cohorts/"+c.cohort+"/lecture-videos";
        long video=id(send(post(base),c.admin,video(1),201));
        for(String url:List.of("https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ","https://youtube.com@evil.test/watch?v=dQw4w9WgXcQ",
                "http://youtu.be/dQw4w9WgXcQ","https://youtube.com/watch","https://youtube.com/watch?v=short",
                "https://youtube.com/watch?v=dQw4w9WgXcQ&v=abcdefghijk","https://youtu.be/dQw4w9WgXcQ/extra",
                "https://youtube.com/shorts/dQw4w9WgXcQ","https://youtu.be:444/dQw4w9WgXcQ","not a url")) {
            var input=new HashMap<String,Object>(video(2));input.put("youtubeUrl",url);
            send(post(base),c.admin,input,400);
            send(patch("/api/admin/lecture-videos/"+video),c.admin,Map.of("youtubeUrl",url),400);
        }
        assertEquals("dQw4w9WgXcQ",JsonPath.read(send(get("/api/lecture-videos/"+video),c.student,null,200),"$.youtubeVideoId"));
        assertEquals(1,((Number)JsonPath.read(send(get(base),c.admin,null,200),"$.total")).intValue());
    }
}
