package kr.wsts.lms.domain.auth;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lms-auth-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.jwt-secret=auth-test-only-fixed-key-at-least-32-bytes",
        "app.bootstrap.admin-email=", "app.bootstrap.admin-password="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthIntegrationTests {
    private static final String PASSWORD = "TestPassword!42";
    @Autowired MockMvc mvc;
    @Autowired Db db;

    @Test
    void registrationReturnsPublicStudentAndLoginAuthenticates() throws Exception {
        var account = register();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + account.email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        var session = login(account.email, PASSWORD);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + session.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(account.id))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void rotatingRefreshTokenCannotBeReplayedAndRevokesItsFamily() throws Exception {
        var account = register();
        var first = login(account.email, PASSWORD);
        String rotated = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + first.refresh + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String nextRefresh = JsonPath.read(rotated, "$.refreshToken");
        assertNotEquals(first.refresh, nextRefresh);
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + first.refresh + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + nextRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingPasswordImmediatelyInvalidatesOldAccessAndRefreshTokens() throws Exception {
        var account = register();
        var session = login(account.email, PASSWORD);
        mvc.perform(patch("/api/me/password").header("Authorization", "Bearer " + session.access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"ChangedPassword!43\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + session.access))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + session.refresh + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + account.email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        login(account.email, "ChangedPassword!43");
    }

    @Test
    void disabledAccountCannotContinueUsingAnAlreadyIssuedAccessToken() throws Exception {
        var account = register();
        var session = login(account.email, PASSWORD);
        db.update("UPDATE users SET status='DISABLED' WHERE id=?", account.id);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + session.access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentCannotReadUserDirectoryOrCreateGlobalCourses() throws Exception {
        var account = register();
        var session = login(account.email, PASSWORD);
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + session.access))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/courses").header("Authorization", "Bearer " + session.access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"권한 없는 과정\",\"totalHours\":80}"))
                .andExpect(status().isForbidden());
    }

    private Account register() throws Exception {
        String email = "auth-" + UUID.randomUUID() + "@example.test";
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"테스트 사용자\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id");
        return new Account(id.longValue(), email);
    }

    private Session login(String email, String password) throws Exception {
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900)).andExpect(jsonPath("$.refreshExpiresIn").value(604800))
                .andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(response, "$.accessToken"), JsonPath.read(response, "$.refreshToken"));
    }

    private record Account(long id, String email) { }
    private record Session(String access, String refresh) { }
}
