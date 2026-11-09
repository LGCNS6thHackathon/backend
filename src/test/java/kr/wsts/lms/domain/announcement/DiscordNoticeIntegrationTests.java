package kr.wsts.lms.domain.announcement;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.wsts.lms.domain.announcement.model.NoticeDelivery;
import kr.wsts.lms.domain.announcement.repository.JdbcNoticeDeliveryRepository;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:lms-discord-isolated;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.security.jwt-secret=discord-test-only-secret-at-least-32-bytes",
    "app.bootstrap.admin-email=", "app.bootstrap.admin-password="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DiscordNoticeIntegrationTests {
    @Autowired Db db;
    @Autowired JdbcNoticeDeliveryRepository deliveries;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void savedNoticeSendsOnceAndStatusIsVisibleToAdmin() throws Exception {
        String password = "TestPassword!42";
        long admin = user("ADMIN", passwords.encode(password));
        long student = user("STUDENT", passwords.encode(password));
        long cohort = cohort();
        String adminToken = login(admin, password);
        String studentToken = login(student, password);
        String saved = mvc.perform(post("/api/cohorts/" + cohort + "/posts")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("category", "COURSE", "title", "MVP 공지",
                        "body", "@everyone 발표 안내", "pinned", false))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long postId = ((Number) JsonPath.read(saved, "$.id")).longValue();
        assertEquals(0, db.count("SELECT COUNT(*) FROM notice_deliveries WHERE post_id=?", postId));

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = webhookServer(calls, requestBody);
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
            var service = new DiscordNoticeService(db, deliveries, new DiscordWebhookClient(endpoint));
            NoticeDelivery sent = service.send(postId, admin);
            assertEquals(NoticeDelivery.Status.SENT, sent.status());
            assertEquals(sent.id(), service.send(postId, admin).id());
            assertEquals(1, calls.get());
            assertEquals("[공지] MVP 공지\n\n@everyone 발표 안내",
                    JSON.readTree(requestBody.get()).path("content").asText());
            assertEquals(0, JSON.readTree(requestBody.get()).path("allowed_mentions").path("parse").size());

            mvc.perform(get("/api/cohorts/" + cohort + "/discord-deliveries")
                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$." + postId).value("SENT"));
            mvc.perform(post("/api/posts/" + postId + "/discord-delivery")
                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SENT"));
            assertEquals(1, calls.get());
            mvc.perform(post("/api/posts/" + postId + "/discord-delivery")
                    .header("Authorization", "Bearer " + studentToken))
                    .andExpect(status().isForbidden());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonNoticeCategoryCannotBeSent() {
        long admin = user("ADMIN", "unused");
        long cohort = cohort();
        long postId = db.insert("INSERT INTO posts(cohort_id,author_id,category,title,body,created_at,updated_at) "
                + "VALUES(?,?,'FAQ','질문','답변',?,?)", cohort, admin, Db.now(), Db.now());
        var service = new DiscordNoticeService(db, deliveries,
                new DiscordWebhookClient(URI.create("http://127.0.0.1:1/webhook")));
        assertEquals(409, assertThrows(AppException.class, () -> service.send(postId, admin)).status());
    }

    private static HttpServer webhookServer(AtomicInteger calls, AtomicReference<String> requestBody)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"123456789\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        return server;
    }

    private long user(String role, String passwordHash) {
        return db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) "
                + "VALUES(?,?,?,?,'ACTIVE',0,?,?)", UUID.randomUUID() + "@example.test",
                passwordHash, "공지 테스트", role, Db.now(), Db.now());
    }

    private long cohort() {
        long course = db.insert("INSERT INTO courses(title,description,total_hours,created_at,updated_at) "
                + "VALUES(?,?,?,?,?)", "테스트 과정", "공지 검증", 1, Db.now(), Db.now());
        return db.insert("INSERT INTO cohorts(course_id,name,start_date,end_date,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?)", course, "테스트 기수", LocalDate.now(),
                LocalDate.now().plusDays(1), Db.now(), Db.now());
    }

    private String login(long userId, String password) throws Exception {
        String email = (String) db.one("SELECT email FROM users WHERE id=?", userId).get("email");
        String response = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
