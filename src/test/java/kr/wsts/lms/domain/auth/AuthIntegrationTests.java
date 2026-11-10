package kr.wsts.lms.domain.auth;

import static org.junit.jupiter.api.Assertions.*;
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
        "app.bootstrap.admin-email=auth-admin@example.test", "app.bootstrap.admin-password=TestPassword!42"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthIntegrationTests {
    private static final String PASSWORD = "TestPassword!42";
    private static final String ADMIN_EMAIL = "auth-admin@example.test";
    @Autowired MockMvc mvc;
    @Autowired Db db;

    @Test
    void administratorCreatesPublicStudentAndStudentCanLogIn() throws Exception {
        var account = createAccount();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + account.email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        var session = login(account.email, PASSWORD);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + session.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(account.id))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.provisionedBy").doesNotExist())
                .andExpect(jsonPath("$.provisionedAt").doesNotExist());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAndStudentRequestsCannotCreateAccounts() throws Exception {
        String anonymousEmail = uniqueEmail();
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(accountRequest(anonymousEmail)))
                .andExpect(status().isUnauthorized());
        var student = createAccount();
        var session = login(student.email, PASSWORD);
        String studentEmail = uniqueEmail();
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + session.access)
                .contentType(MediaType.APPLICATION_JSON).content(accountRequest(studentEmail)))
                .andExpect(status().isForbidden());
        assertEquals(0, db.count("SELECT COUNT(*) FROM users WHERE email IN (?,?)", anonymousEmail, studentEmail));
    }

    @Test
    void removedRegistrationEndpointCannotCreateAccounts() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(accountRequest(email)))
                .andExpect(status().isUnauthorized());
        var administrator = login(ADMIN_EMAIL, PASSWORD);
        mvc.perform(post("/api/auth/register").header("Authorization", "Bearer " + administrator.access)
                .contentType(MediaType.APPLICATION_JSON).content(accountRequest(email)))
                .andExpect(status().isNotFound());
        assertEquals(0, db.count("SELECT COUNT(*) FROM users WHERE email=?", email));
    }

    @Test
    void accountCreationStoresAdministratorAuditAndRejectsCaseInsensitiveDuplicate() throws Exception {
        var administrator = login(ADMIN_EMAIL, PASSWORD);
        String email = uniqueEmail();
        var account = createAccount(administrator, email.toUpperCase(java.util.Locale.ROOT));
        var stored = db.one("SELECT email,provisioned_by,provisioned_at FROM users WHERE id=?", account.id);
        long administratorId = Db.id(db.one("SELECT id FROM users WHERE email=?", ADMIN_EMAIL), "id");
        assertEquals(email, stored.get("email"));
        assertEquals(administratorId, Db.id(stored, "provisionedBy"));
        assertNotNull(stored.get("provisionedAt"));
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + administrator.access)
                .contentType(MediaType.APPLICATION_JSON).content(accountRequest(email)))
                .andExpect(status().isConflict());
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE email=?", email));
        var bootstrap = db.one("SELECT provisioned_by,provisioned_at FROM users WHERE id=?", administratorId);
        assertNull(bootstrap.get("provisionedBy"));
        assertNull(bootstrap.get("provisionedAt"));
    }

    @Test
    void rotatingRefreshTokenCannotBeReplayedAndRevokesItsFamily() throws Exception {
        var account = createAccount();
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
        var account = createAccount();
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
        var account = createAccount();
        var session = login(account.email, PASSWORD);
        db.update("UPDATE users SET status='DISABLED' WHERE id=?", account.id);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + session.access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentCannotReadUserDirectoryOrCreateGlobalCourses() throws Exception {
        var account = createAccount();
        var session = login(account.email, PASSWORD);
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + session.access))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/courses").header("Authorization", "Bearer " + session.access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"권한 없는 과정\",\"totalHours\":80}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountRolesControlEnrollmentRolesAndChangingRoleRevokesSessions() throws Exception {
        var administrator = login(ADMIN_EMAIL, PASSWORD);
        var account = createAccount();
        var originalSession = login(account.email, PASSWORD);
        long course = db.insert("INSERT INTO courses(title,description,created_at,updated_at) VALUES('Role test','Role test',?,?)", Db.now(), Db.now());
        long cohort = db.insert("INSERT INTO cohorts(course_id,name,start_date,end_date,created_at,updated_at) VALUES(?,'Role test','2026-01-01','2027-12-31',?,?)", course, Db.now(), Db.now());
        String enrollment = mvc.perform(post("/api/cohorts/" + cohort + "/enrollments")
                .header("Authorization", "Bearer " + administrator.access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + account.id + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("STUDENT"))
                .andReturn().getResponse().getContentAsString();
        long enrollmentId = ((Number) JsonPath.read(enrollment, "$.id")).longValue();
        for (String legacyRole : new String[]{"INSTRUCTOR", "MANAGER"}) {
            mvc.perform(patch("/api/users/" + account.id).header("Authorization", "Bearer " + administrator.access)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + legacyRole + "\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(patch("/api/cohorts/" + cohort + "/enrollments/" + enrollmentId)
                    .header("Authorization", "Bearer " + administrator.access).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"role\":\"" + legacyRole + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/cohorts/" + cohort + "/enrollments/" + enrollmentId)
                .header("Authorization", "Bearer " + administrator.access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/users/" + account.id).header("Authorization", "Bearer " + administrator.access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        assertEquals("ADMIN", db.one("SELECT role FROM enrollments WHERE id=?", enrollmentId).get("role"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + originalSession.access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + originalSession.refresh + "\"}"))
                .andExpect(status().isUnauthorized());
        var promotedSession = login(account.email, PASSWORD);
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + promotedSession.access)).andExpect(status().isOk());

        mvc.perform(patch("/api/users/" + account.id).header("Authorization", "Bearer " + administrator.access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isOk());
        assertEquals("STUDENT", db.one("SELECT role FROM enrollments WHERE id=?", enrollmentId).get("role"));
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + promotedSession.access)).andExpect(status().isUnauthorized());
        var demotedSession = login(account.email, PASSWORD);
        mvc.perform(get("/api/cohorts/" + cohort + "/enrollments").header("Authorization", "Bearer " + demotedSession.access))
                .andExpect(status().isForbidden());
    }

    private Account createAccount() throws Exception {
        return createAccount(login(ADMIN_EMAIL, PASSWORD), uniqueEmail());
    }

    private Account createAccount(Session administrator, String email) throws Exception {
        String response = mvc.perform(post("/api/users").header("Authorization", "Bearer " + administrator.access)
                .contentType(MediaType.APPLICATION_JSON).content(accountRequest(email)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.provisionedBy").doesNotExist()).andExpect(jsonPath("$.provisionedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id");
        return new Account(id.longValue(), email);
    }

    private static String uniqueEmail() { return "auth-" + UUID.randomUUID() + "@example.test"; }

    private static String accountRequest(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"테스트 사용자\",\"phone\":\"010-1234-5678\"}";
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
