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
    "spring.datasource.url=jdbc:h2:mem:lms-discord-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.security.jwt-secret=discord-test-only-secret-at-least-32-bytes",
    "app.bootstrap.admin-email=", "app.bootstrap.admin-password=",
    "app.discord.public-base-url=http://localhost:8080"
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
    void sendsFullBodyOnceAndSavesDiscordMessageId() throws Exception {
        long admin = user("ADMIN", "unused");
        String slug = UUID.randomUUID().toString();
        String body = "@everyone 공지 전문\n둘째 줄까지 보냅니다.";
        long postId = createPost(admin, "PUBLIC", slug, body);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = webhookServer(200, "{\"id\":\"123456789\"}", calls, requestBody);
        try {
            var service = service(server);
            NoticeDelivery sent = service.send(postId, admin);
            assertEquals(NoticeDelivery.Status.SENT, sent.status());
            assertEquals("123456789", sent.externalMessageId());
            assertEquals(sent.id(), service.send(postId, admin).id());
            assertEquals(1, calls.get());
            assertEquals(1, db.count("SELECT COUNT(*) FROM notice_deliveries WHERE post_id=?", postId));
            String content = JSON.readTree(requestBody.get()).path("content").asText();
            assertEquals("[공지] 테스트 공지\n\n" + body + "\n\nhttp://localhost:8080/public/notices/" + slug,
                    content);
            assertTrue(requestBody.get().contains("\"allowed_mentions\""));
            assertEquals(0, JSON.readTree(requestBody.get()).path("allowed_mentions").path("parse").size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesUnpublishedOrOverlongNoticeBeforeSending() throws Exception {
        long admin = user("ADMIN", "unused");
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = webhookServer(200, "{\"id\":\"123\"}", calls, new AtomicReference<>());
        try {
            var service = service(server);
            long privatePost = createPost(admin, "COHORT", null, "비공개 공지");
            assertEquals(409, assertThrows(AppException.class,
                    () -> service.send(privatePost, admin)).status());
            long longPost = createPost(admin, "PUBLIC", UUID.randomUUID().toString(), "가".repeat(2000));
            assertEquals(400, assertThrows(AppException.class,
                    () -> service.send(longPost, admin)).status());
            assertEquals(0, calls.get());
            assertEquals(0, db.count("SELECT COUNT(*) FROM notice_deliveries WHERE post_id IN (?,?)",
                    privatePost, longPost));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recordsUncertainFailureWithoutResending() throws Exception {
        long admin = user("ADMIN", "unused");
        long postId = createPost(admin, "PUBLIC", UUID.randomUUID().toString(), "실패 확인 본문");
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = webhookServer(500, "{}", calls, new AtomicReference<>());
        try {
            var service = service(server);
            assertEquals(NoticeDelivery.Status.FAILED, service.send(postId, admin).status());
            assertEquals("UNCERTAIN", service.send(postId, admin).errorCode());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void adminApiRequiresAdminRole() throws Exception {
        String password = "TestPassword!42";
        long student = user("STUDENT", passwords.encode(password));
        long admin = user("ADMIN", passwords.encode(password));
        String studentToken = login(student, password);
        String adminToken = login(admin, password);
        String route = "/api/posts/" + Long.MAX_VALUE + "/discord-delivery";
        mvc.perform(post(route)).andExpect(status().isUnauthorized());
        mvc.perform(post(route).header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mvc.perform(post(route).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void invalidWebhookUrlDoesNotRevealSecret() {
        String secret = "private-webhook-token";
        var error = assertThrows(IllegalArgumentException.class,
                () -> new DiscordWebhookClient("https://example.com/api/webhooks/123/" + secret));
        assertFalse(error.getMessage().contains(secret));
    }

    private DiscordNoticeService service(HttpServer server) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
        return new DiscordNoticeService(new PublishedNoticeSource(db, "http://localhost:8080"),
                deliveries, new DiscordWebhookClient(endpoint));
    }

    private static HttpServer webhookServer(int status, String response, AtomicInteger calls,
            AtomicReference<String> requestBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private long user(String role, String passwordHash) {
        var now = Db.now();
        return db.insert("INSERT INTO users(email,password_hash,name,role,status,token_version,created_at,updated_at) "
                + "VALUES(?,?,?,?,'ACTIVE',0,?,?)", UUID.randomUUID() + "@example.test",
                passwordHash, "공지 테스트", role, now, now);
    }

    private long createPost(long author, String visibility, String slug, String body) {
        var now = Db.now();
        long course = db.insert("INSERT INTO courses(title,description,total_hours,created_at,updated_at) "
                + "VALUES(?,?,?,?,?)", "테스트 과정", "공지 검증", 1, now, now);
        long cohort = db.insert("INSERT INTO cohorts(course_id,name,start_date,end_date,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?)", course, "테스트 기수", LocalDate.now(),
                LocalDate.now().plusDays(1), now, now);
        return db.insert("INSERT INTO posts(cohort_id,author_id,category,title,body,visibility,public_slug,"
                + "created_at,updated_at) VALUES(?,?,'COURSE',?,?,?, ?,?,?)", cohort, author,
                "테스트 공지", body, visibility, slug, now, now);
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
