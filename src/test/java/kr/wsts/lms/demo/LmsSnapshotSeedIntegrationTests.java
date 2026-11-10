package kr.wsts.lms.demo;

import static org.junit.jupiter.api.Assertions.*;
import kr.wsts.lms.global.support.Db;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:lms-snapshot-tests;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.security.jwt-secret=lms-snapshot-test-only-key-at-least-32-bytes",
    "app.bootstrap.admin-email=admin@full-demo.test",
    "app.bootstrap.admin-password=DemoTestPassword!42",
    "app.full-demo.password=DemoTestPassword!42",
    "app.storage.directory=build/lms-snapshot-test-storage"
})
@ActiveProfiles({"test", "full-demo"})
class LmsSnapshotSeedIntegrationTests {
    @Autowired Db db;

    @Test
    void defaultUsesObservedContentWithoutInventingOriginalExamsOrAttachments() {
        assertEquals(2, db.count("SELECT COUNT(*) FROM users WHERE role='STUDENT'"));
        assertEquals(3, db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN'"));
        assertEquals(2, db.count("SELECT COUNT(*) FROM enrollments WHERE role='ADMIN'"));
        assertEquals(2, db.count("SELECT COUNT(*) FROM enrollments WHERE role='STUDENT'"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM enrollments e JOIN users u ON u.id=e.user_id WHERE e.role<>u.role"));
        assertEquals(26, db.count("SELECT COUNT(*) FROM schedule_events"));
        assertEquals(21, db.count("SELECT COUNT(*) FROM posts"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM assignments"));
        assertEquals(4, db.count("SELECT COUNT(*) FROM assessments"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM assessment_questions"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM assessment_attempts"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM submissions"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM files"));
        assertEquals(230, db.count("SELECT COUNT(*) FROM lecture_videos"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM lecture_chapters"));
        assertEquals(65, db.count("SELECT COUNT(*) FROM attendance_records WHERE status='PRESENT'"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM attendance_records WHERE status='LATE'"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM attendance_records WHERE status='ABSENT'"));
        assertEquals(2, db.count("SELECT COUNT(*) FROM attendance_corrections WHERE status='PENDING'"));
        assertEquals("2026-07-24", db.one("SELECT start_date FROM cohorts").get("startDate"));
        assertEquals("2027-01-27", db.one("SELECT end_date FROM cohorts").get("endDate"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM notice_deliveries"));
    }
}
