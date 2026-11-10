package kr.wsts.lms.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.*;
import java.util.Map;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:full-demo-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.security.jwt-secret=full-demo-test-only-key-at-least-32-bytes",
    "app.bootstrap.admin-email=admin@full-demo.test",
    "app.bootstrap.admin-password=DemoTestPassword!42",
    "app.full-demo.password=DemoTestPassword!42",
    "app.full-demo.synthetic-scenarios=true",
    "app.storage.directory=build/full-demo-test-storage"
})
@ActiveProfiles({"test", "full-demo"})
@AutoConfigureMockMvc
class FullDemoSeedIntegrationTests {
    @Autowired Db db;
    @Autowired MockMvc mvc;
    @Autowired FullDemoSeedRunner seed;

    private String token(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"DemoTestPassword!42\"}";
        String result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(result, "$.accessToken");
    }
    private long cohort() { return Db.id(db.one("SELECT id FROM cohorts WHERE name=?", FullDemoSeedRunner.COHORT_NAME), "id"); }

    @Test
    void seededFeaturesAreReadableAndPendingCorrectionCanBeApproved() throws Exception {
        long cohort = cohort();
        String student = token("student@full-demo.test"), admin = token("admin@full-demo.test");
        for (String email : new String[]{"instructor@full-demo.test", "manager@full-demo.test"})
            mvc.perform(get("/api/users").header("Authorization", "Bearer " + token(email))).andExpect(status().isOk());
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
        assertEquals(0, db.count("SELECT COUNT(*) FROM enrollments e JOIN users u ON u.id=e.user_id WHERE e.role<>u.role"));
        for (String path : new String[]{"/attendance/calendar?month=2026-11", "/events?month=2026-11", "/posts", "/assignments", "/assessments", "/assessment-report", "/lecture-replays/dates?from=2026-07-01&to=2026-08-31"})
            mvc.perform(get("/api/cohorts/" + cohort + path).header("Authorization", "Bearer " + student)).andExpect(status().isOk());
        assertEquals(67, db.count("SELECT COUNT(*) FROM attendance_records"));
        assertEquals(65, db.count("SELECT COUNT(*) FROM attendance_records WHERE status='PRESENT'"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM attendance_records WHERE status='LATE'"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM attendance_records WHERE status='ABSENT'"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM attendance_records WHERE attendance_date='2026-11-06'"));
        assertEquals(21, db.count("SELECT COUNT(*) FROM posts WHERE cohort_id=?", cohort));
        assertEquals("[CNS 6기]_에자일 특강 자료 공유", db.one("SELECT title FROM posts WHERE cohort_id=? ORDER BY created_at DESC,id LIMIT 1", cohort).get("title"));
        assertEquals("2026-11-08T15:00:00Z", db.one("SELECT start_at FROM schedule_events WHERE cohort_id=? AND title='해커톤'", cohort).get("startAt"));
        assertEquals("2026-11-10T15:00:00Z", db.one("SELECT end_at FROM schedule_events WHERE cohort_id=? AND title='해커톤'", cohort).get("endAt"));
        assertEquals("2026-09-20T06:34:00Z", db.one("SELECT due_at FROM assignments WHERE cohort_id=? AND title='0917'", cohort).get("dueAt"));
        assertEquals(230, db.count("SELECT COUNT(*) FROM lecture_videos WHERE cohort_id=?", cohort));
        assertEquals(4, db.count("SELECT COUNT(*) FROM enrollments WHERE cohort_id=?", cohort));
        for (var assessment : db.list("SELECT id FROM assessments WHERE cohort_id=? AND status='CLOSED' AND description LIKE '[가상 시연]%'", cohort))
            mvc.perform(get("/api/assessments/" + assessment.get("id") + "/review-recommendations").header("Authorization", "Bearer " + student))
                    .andExpect(status().isOk());
        for (var file : db.list("SELECT id FROM files WHERE cohort_id=?", cohort))
            mvc.perform(get("/api/files/" + file.get("id") + "/download").header("Authorization", "Bearer " + student))
                    .andExpect(status().isOk());
        var pending = db.one("SELECT ac.id,ac.attendance_id FROM attendance_corrections ac JOIN attendance_records ar ON ar.id=ac.attendance_id JOIN enrollments e ON e.id=ar.enrollment_id WHERE e.cohort_id=? AND ac.status='PENDING' ORDER BY ac.id LIMIT 1", cohort);
        mvc.perform(patch("/api/attendance/corrections/" + pending.get("id")).header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\",\"reviewNote\":\"시연 승인\"}"))
                .andExpect(status().isOk());
        assertEquals("PRESENT", db.one("SELECT status FROM attendance_records WHERE id=?", pending.get("attendanceId")).get("status"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM notice_deliveries"));
    }

    @Test
    void rerunDoesNotDuplicateDataOrResetPresentationProgress() throws Exception {
        var before = db.list("SELECT id,status,version FROM attendance_records ORDER BY id");
        long users = db.count("SELECT COUNT(*) FROM users"), cohorts = db.count("SELECT COUNT(*) FROM cohorts");
        long files = db.count("SELECT COUNT(*) FROM files"), requests = db.count("SELECT COUNT(*) FROM attendance_corrections");
        seed.run(new DefaultApplicationArguments());
        assertEquals(before, db.list("SELECT id,status,version FROM attendance_records ORDER BY id"));
        assertEquals(users, db.count("SELECT COUNT(*) FROM users"));
        assertEquals(cohorts, db.count("SELECT COUNT(*) FROM cohorts"));
        assertEquals(files, db.count("SELECT COUNT(*) FROM files"));
        assertEquals(requests, db.count("SELECT COUNT(*) FROM attendance_corrections"));
        for (Map<String,Object> file : db.list("SELECT stored_name FROM files"))
            assertTrue(Files.isRegularFile(Path.of("build/full-demo-test-storage", (String)file.get("storedName"))));
    }
}
