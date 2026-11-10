package kr.wsts.lms.foundation;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Exercises schema contracts directly; new feature business logic is intentionally absent. */
class FoundationSchemaTests {
    private final List<SingleConnectionDataSource> databases = new ArrayList<>();

    @AfterEach
    void closeDatabases() {
        databases.forEach(SingleConnectionDataSource::destroy);
    }

    @Test
    void roleUpgradePreservesAccountsAndHistoryAndRevokesOnlyPromotedAccountsTokens() {
        var dataSource = database();
        migration(dataSource, "5").migrate();
        var jdbc = new JdbcTemplate(dataSource);
        seedEnrollment(jdbc);
        String[] globalRoles = {"INSTRUCTOR", "STUDENT", "STUDENT", "STUDENT", "ADMIN", "STUDENT", "INSTRUCTOR", "STUDENT"};
        String[] cohortRoles = {"INSTRUCTOR", "MANAGER", "MANAGER", "STUDENT", "MANAGER", "INSTRUCTOR", "INSTRUCTOR", "MANAGER"};
        for (int i = 0; i < globalRoles.length; i++) {
            int id = i + 2;
            jdbc.update("INSERT INTO users(id,email,password_hash,name,role,status,token_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, "legacy-" + id + "@example.test", "preserved-password-" + id, "Legacy " + id, globalRoles[i], id >= 8 ? "DISABLED" : "ACTIVE", id);
            jdbc.update("INSERT INTO enrollments(cohort_id,user_id,role,status,created_at,updated_at) VALUES(1,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, cohortRoles[i], (id == 4 || id == 8) ? "WITHDRAWN" : "ACTIVE");
            jdbc.update("INSERT INTO refresh_tokens(user_id,token_hash,family_id,expires_at,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, String.valueOf(id).repeat(64), "legacy-family-" + id);
        }
        jdbc.update("INSERT INTO assignments(id,cohort_id,author_id,instructor_id,instructor_name,title,description,due_at,created_at,updated_at) VALUES(1,1,6,2,'Preserved instructor','Legacy assignment','Instructions',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO submissions(id,assignment_id,user_id,body,submitted_at,updated_at) VALUES(1,1,1,'Preserved submission',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO submission_reviews(submission_id,reviewer_id,score,feedback,created_at,updated_at) VALUES(1,2,90,'Preserved feedback',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        migration(dataSource, null).migrate();

        Set<Integer> promoted = Set.of(2, 3, 7, 8, 9);
        for (int id = 2; id <= 9; id++) {
            String expectedRole = promoted.contains(id) || id == 6 ? "ADMIN" : "STUDENT";
            assertEquals(expectedRole, jdbc.queryForObject("SELECT role FROM users WHERE id=?", String.class, id));
            assertEquals(expectedRole, jdbc.queryForObject("SELECT role FROM enrollments WHERE user_id=?", String.class, id));
            assertEquals(id + (promoted.contains(id) ? 1 : 0), jdbc.queryForObject("SELECT token_version FROM users WHERE id=?", Integer.class, id));
            assertEquals(promoted.contains(id), jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE user_id=?", Boolean.class, id));
            assertEquals("preserved-password-" + id, jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id));
            assertEquals(id >= 8 ? "DISABLED" : "ACTIVE", jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, id));
        }
        assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT status FROM enrollments WHERE user_id=4", String.class));
        assertEquals(2L, jdbc.queryForObject("SELECT instructor_id FROM assignments WHERE id=1", Long.class));
        assertEquals("Preserved instructor", jdbc.queryForObject("SELECT instructor_name FROM assignments WHERE id=1", String.class));
        assertEquals(2L, jdbc.queryForObject("SELECT reviewer_id FROM submission_reviews WHERE submission_id=1", Long.class));
        assertEquals(90, jdbc.queryForObject("SELECT score FROM submission_reviews WHERE submission_id=1", Integer.class));
        for (String retired : List.of("INSTRUCTOR", "MANAGER")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE users SET role=? WHERE id=1", retired));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE enrollments SET role=? WHERE user_id=1", retired));
        }
    }

    @Test
    void frontendScopeUpgradeRemovesUnusedFeaturesAndPreservesActiveScreenData() {
        var dataSource = database();
        migration(dataSource, "4").migrate();
        var jdbc = new JdbcTemplate(dataSource);
        seedEnrollment(jdbc);
        jdbc.update("INSERT INTO study_groups(id,cohort_id,owner_id,name,description,capacity,created_at,updated_at) VALUES(1,1,1,'Legacy study','Removed feature',5,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO study_group_members(group_id,user_id,joined_at) VALUES(1,1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO roadmap_phases(id,cohort_id,title,description,position,hours,start_date,end_date) VALUES(1,1,'Legacy roadmap','Removed feature',1,8,'2026-01-01','2026-01-02')");
        jdbc.update("INSERT INTO roadmap_modules(phase_id,title,hours,start_date,end_date,position) VALUES(1,'Legacy module',8,'2026-01-01','2026-01-02',1)");
        jdbc.update("INSERT INTO schedule_events(id,cohort_id,title,type,start_at,end_at,location,description,created_by,created_at) VALUES(1,1,'Kept calendar event','LECTURE','2026-01-01 09:00:00','2026-01-01 18:00:00','Classroom','Instructions',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO assignments(id,cohort_id,author_id,title,description,due_at,subject,created_at,updated_at) VALUES(1,1,1,'Kept assignment','Instructions',CURRENT_TIMESTAMP,'Java',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO submissions(id,assignment_id,user_id,body,deployment_url,submitted_at,updated_at) VALUES(1,1,1,'Kept submission','https://example.test/demo',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO assessments(id,cohort_id,title,description,domain,start_at,end_at,duration_minutes,status,created_by,created_at) VALUES(1,1,'Kept assessment','Instructions','Java','2026-01-01 09:00:00','2026-01-01 10:00:00',60,'CLOSED',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO assessment_questions(id,assessment_id,prompt,position,points,explanation) VALUES(1,1,'Kept question',1,1,'Kept explanation')");
        jdbc.update("INSERT INTO assessment_options(id,question_id,label,is_correct) VALUES(1,1,'Kept option',TRUE)");
        jdbc.update("INSERT INTO assessment_attempts(id,assessment_id,user_id,status,started_at,submitted_at,score) VALUES(1,1,1,'SUBMITTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,100)");
        jdbc.update("INSERT INTO assessment_answers(attempt_id,question_id,option_id,is_correct,earned_points) VALUES(1,1,1,TRUE,1)");
        jdbc.update("INSERT INTO notifications(user_id,cohort_id,type,title,body,resource_type,resource_id,created_at) VALUES(1,1,'STUDY_JOIN','Legacy study notice','Removed feature','STUDY_GROUP',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notifications(user_id,cohort_id,type,title,body,resource_type,resource_id,created_at) VALUES(1,1,'ASSIGNMENT_REVIEW','Kept notification','Active feature','SUBMISSION',1,CURRENT_TIMESTAMP)");

        migration(dataSource, null).migrate();

        var tables = tableNames(jdbc);
        assertEquals(31, tables.size());
        assertTrue(Collections.disjoint(tables, Set.of("study_groups", "study_group_members", "roadmap_phases", "roadmap_modules")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments", Integer.class));
        assertEquals("Kept calendar event", jdbc.queryForObject("SELECT title FROM schedule_events WHERE id=1", String.class));
        assertEquals("Java", jdbc.queryForObject("SELECT subject FROM assignments WHERE id=1", String.class));
        assertEquals("https://example.test/demo", jdbc.queryForObject("SELECT deployment_url FROM submissions WHERE id=1", String.class));
        assertEquals(100, jdbc.queryForObject("SELECT score FROM assessment_attempts WHERE id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assessment_answers WHERE attempt_id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
        assertEquals("SUBMISSION", jdbc.queryForObject("SELECT resource_type FROM notifications", String.class));
        assertTrue(tables.containsAll(Set.of("learning_subjects", "learning_units", "learning_topics", "lecture_videos", "review_sessions", "notice_deliveries")));
    }

    @Test
    void assignmentScreenUpgradePreservesExistingSubmissionsAndAddsOptionalFormFields() {
        var dataSource = database();
        migration(dataSource, "3").migrate();
        var jdbc = new JdbcTemplate(dataSource);
        seedEnrollment(jdbc);
        jdbc.update("INSERT INTO assignments(id,cohort_id,author_id,title,description,due_at,created_at,updated_at) VALUES(1,1,1,'Legacy assignment','Instructions',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO submissions(id,assignment_id,user_id,body,repository_url,submitted_at,updated_at) VALUES(1,1,1,'Legacy memo','https://github.com/example/legacy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        migration(dataSource, null).migrate();

        assertEquals("Legacy assignment", jdbc.queryForObject("SELECT title FROM assignments WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT subject FROM assignments WHERE id=1", String.class));
        assertEquals("Legacy memo", jdbc.queryForObject("SELECT body FROM submissions WHERE id=1", String.class));
        assertEquals("https://github.com/example/legacy", jdbc.queryForObject("SELECT repository_url FROM submissions WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT deployment_url FROM submissions WHERE id=1", String.class));
        jdbc.update("UPDATE assignments SET subject='웹 개발' WHERE id=1");
        jdbc.update("UPDATE submissions SET deployment_url='https://example.test/demo' WHERE id=1");
        assertEquals("웹 개발", jdbc.queryForObject("SELECT subject FROM assignments WHERE id=1", String.class));
        assertEquals("https://example.test/demo", jdbc.queryForObject("SELECT deployment_url FROM submissions WHERE id=1", String.class));
    }

    @Test
    void managedAccountUpgradePreservesLegacyAccountsAndTracksKnownIssuers() {
        var dataSource = database();
        migration(dataSource, "2").migrate();
        var jdbc = new JdbcTemplate(dataSource);
        seedEnrollment(jdbc);
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=1");
        jdbc.update("INSERT INTO users(id,email,password_hash,name,created_at,updated_at) VALUES(2,'legacy-student@example.test','legacy-hash','Legacy student',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        migration(dataSource, null).migrate();

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertEquals("legacy-hash", jdbc.queryForObject("SELECT password_hash FROM users WHERE id=2", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE user_id=1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE provisioned_by IS NULL AND provisioned_at IS NULL", Integer.class));
        jdbc.update("INSERT INTO users(id,email,password_hash,name,provisioned_by,provisioned_at,created_at,updated_at) VALUES(3,'issued@example.test','issued-hash','Issued student',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertEquals(1L, jdbc.queryForObject("SELECT provisioned_by FROM users WHERE id=3", Long.class));
        assertNotNull(jdbc.queryForObject("SELECT provisioned_at FROM users WHERE id=3", Object.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO users(email,password_hash,name,provisioned_by,provisioned_at,created_at,updated_at) VALUES('unknown-issuer@example.test','hash','Invalid issuer',999,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"));
        // A bootstrap administrator has no issuing user, even after V3 is installed.
        jdbc.update("INSERT INTO users(id,email,password_hash,name,role,created_at,updated_at) VALUES(4,'bootstrap@example.test','hash','Bootstrap','ADMIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertNull(jdbc.queryForObject("SELECT provisioned_by FROM users WHERE id=4", Long.class));
        assertNull(jdbc.queryForObject("SELECT provisioned_at FROM users WHERE id=4", Object.class));
    }

    @Test
    void upgradePreservesLegacyRowsAndSeparatesDailyAndSessionAttendance() {
        var dataSource = database();
        migration(dataSource, "1").migrate();
        var jdbc = new JdbcTemplate(dataSource);
        seedEnrollment(jdbc);
        jdbc.update("INSERT INTO attendance_records(id,enrollment_id,attendance_date,status,updated_at) VALUES(1,1,'2026-11-01','ABSENT',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO attendance_corrections(attendance_id,enrollment_id,requested_status,reason,status,created_at) VALUES(1,1,'PRESENT','Legacy request','PENDING',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO posts(cohort_id,author_id,category,title,body,created_at,updated_at) VALUES(1,1,'COURSE','Legacy notice','Content',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO assignments(cohort_id,author_id,title,description,due_at,created_at,updated_at) VALUES(1,1,'Legacy assignment','Content',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        migration(dataSource, null).migrate();

        assertEquals("DATE", jdbc.queryForObject("SELECT unit_type FROM attendance_records WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT session_no FROM attendance_records WHERE id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT version FROM attendance_records WHERE id=1", Integer.class));
        assertNull(jdbc.queryForObject("SELECT deleted_at FROM attendance_records WHERE id=1", Object.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM attendance_corrections", String.class));
        assertNull(jdbc.queryForObject("SELECT original_version FROM attendance_corrections", Integer.class));
        assertEquals("COHORT", jdbc.queryForObject("SELECT visibility FROM posts", String.class));
        assertNull(jdbc.queryForObject("SELECT instructor_id FROM assignments", Long.class));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO attendance_records(enrollment_id,attendance_date,status,updated_at) VALUES(1,'2026-11-01','PRESENT',CURRENT_TIMESTAMP)"));
        jdbc.update("INSERT INTO attendance_records(enrollment_id,attendance_date,status,unit_type,session_no,updated_at) VALUES(1,'2026-11-01','PRESENT','SESSION',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO attendance_records(enrollment_id,attendance_date,status,unit_type,session_no,updated_at) VALUES(1,'2026-11-01','LEAVE','SESSION',2,CURRENT_TIMESTAMP)");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM attendance_records", Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO attendance_records(enrollment_id,attendance_date,status,unit_type,session_no,updated_at) VALUES(1,'2026-11-01','PRESENT','SESSION',1,CURRENT_TIMESTAMP)"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO attendance_records(enrollment_id,attendance_date,status,unit_type,session_no,updated_at) VALUES(1,'2026-11-02','PRESENT','DATE',1,CURRENT_TIMESTAMP)"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO attendance_records(enrollment_id,attendance_date,status,unit_type,session_no,updated_at) VALUES(1,'2026-11-02','PRESENT','SESSION',0,CURRENT_TIMESTAMP)"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE attendance_records SET version=-1 WHERE id=1"));
    }

    @Test
    void reviewChildrenRequireParentsAndQuizEnforcesFourChoicesAndSingleChoiceRange() {
        var dataSource = database();
        migration(dataSource, null).migrate();
        var jdbc = new JdbcTemplate(dataSource);
        seedEnrollment(jdbc);
        jdbc.update("INSERT INTO learning_subjects(id,cohort_id,title,position,created_at,updated_at) VALUES(1,1,'Database',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO learning_units(id,subject_id,title,position,created_at,updated_at) VALUES(1,1,'Integrity',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO learning_topics(id,unit_id,title,description,position,created_at,updated_at) VALUES(1,1,'Foreign key','Relations',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO blank_reviews(session_id,content) VALUES(999,'No parent')"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO review_quizzes(session_id) VALUES(999)"));
        jdbc.update("INSERT INTO review_sessions(id,user_id,topic_id,mode,created_at,updated_at) VALUES(1,1,1,'BLANK',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO blank_reviews(session_id,content) VALUES(1,'My recollection')");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO blank_reviews(session_id,content) VALUES(1,'Duplicate child')"));
        jdbc.update("INSERT INTO review_sessions(id,user_id,topic_id,mode,created_at,updated_at) VALUES(2,1,1,'QUIZ',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO review_quizzes(session_id) VALUES(2)");
        jdbc.update("INSERT INTO review_quiz_questions(id,quiz_session_id,sequence_no,prompt,choice_a,choice_b,choice_c,choice_d,correct_choice,explanation,created_at) VALUES(1,2,1,'Question','A','B','C','D',2,'Explanation',CURRENT_TIMESTAMP)");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE review_quiz_questions SET correct_choice=0 WHERE id=1"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE review_quiz_questions SET selected_choice=5 WHERE id=1"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO review_quiz_questions(quiz_session_id,sequence_no,prompt,choice_a,choice_b,choice_c,correct_choice,explanation,created_at) VALUES(2,2,'Missing fourth choice','A','B','C',1,'Explanation',CURRENT_TIMESTAMP)"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO review_quiz_questions(quiz_session_id,sequence_no,prompt,choice_a,choice_b,choice_c,choice_d,correct_choice,explanation,created_at) VALUES(2,1,'Duplicate sequence','A','B','C','D',1,'Explanation',CURRENT_TIMESTAMP)"));
        jdbc.update("UPDATE review_quiz_questions SET selected_choice=2,is_correct=TRUE,answered_at=CURRENT_TIMESTAMP WHERE id=1");
        assertEquals(2, jdbc.queryForObject("SELECT selected_choice FROM review_quiz_questions WHERE id=1", Integer.class));
    }

    @Test
    void consolidatedSnapshotMatchesMigratedColumnsKeysUniquenessAndChecks() throws Exception {
        var migrated = database();
        migration(migrated, null).migrate();
        var snapshot = database();
        try (Connection connection = snapshot.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/erd/lms-mariadb.sql"));
        }
        assertEquals(31, tableNames(new JdbcTemplate(migrated)).size());
        assertEquals(schemaSignature(migrated), schemaSignature(snapshot), "The handoff SQL/ERD must describe the schema Flyway actually builds.");
        var jdbc = new JdbcTemplate(snapshot);
        assertEquals("NO", jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='blank_reviews' AND column_name='session_id'", String.class));
        assertEquals("NO", jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='review_quizzes' AND column_name='session_id'", String.class));
    }

    private SingleConnectionDataSource database() {
        // Keep the migration session alive while H2 evaluates CHECK expressions.
        var dataSource = new SingleConnectionDataSource("jdbc:h2:mem:foundation-" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "", true);
        databases.add(dataSource);
        return dataSource;
    }

    private static Flyway migration(DriverManagerDataSource dataSource, String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private static void seedEnrollment(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users(id,email,password_hash,name,created_at,updated_at) VALUES(1,'schema@example.test','test-hash','Schema test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO courses(id,title,description,created_at,updated_at) VALUES(1,'Test course','Content',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO cohorts(id,course_id,name,start_date,end_date,created_at,updated_at) VALUES(1,1,'Test cohort','2026-01-01','2026-12-31',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO enrollments(id,cohort_id,user_id,created_at,updated_at) VALUES(1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }

    private static List<String> tableNames(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_name", String.class);
    }

    private static Map<String, Object> schemaSignature(DriverManagerDataSource dataSource) throws SQLException {
        var jdbc = new JdbcTemplate(dataSource);
        var tables = tableNames(jdbc);
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("tables", tables);
        signature.put("columns", jdbc.queryForList("SELECT table_name,column_name,data_type,is_nullable,column_default,character_maximum_length,numeric_precision,numeric_scale,is_identity FROM information_schema.columns WHERE table_schema='public' AND table_name<>'flyway_schema_history' ORDER BY table_name,column_name"));
        List<String> primaryKeys = new ArrayList<>(), foreignKeys = new ArrayList<>(), uniqueKeys = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : tables) {
                Map<Integer, String> pk = new TreeMap<>();
                try (ResultSet rows = metadata.getPrimaryKeys(null, "public", table)) {
                    while (rows.next()) pk.put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
                }
                primaryKeys.add(table + pk.values());
                try (ResultSet rows = metadata.getImportedKeys(null, "public", table)) {
                    while (rows.next()) foreignKeys.add(table + "." + rows.getString("FKCOLUMN_NAME") + "->" + rows.getString("PKTABLE_NAME") + "." + rows.getString("PKCOLUMN_NAME") + ":" + rows.getInt("KEY_SEQ") + ":" + rows.getInt("UPDATE_RULE") + ":" + rows.getInt("DELETE_RULE"));
                }
                Map<String, Map<Integer, String>> indexes = new HashMap<>();
                try (ResultSet rows = metadata.getIndexInfo(null, "public", table, true, false)) {
                    while (rows.next()) {
                        String column = rows.getString("COLUMN_NAME");
                        if (column != null) indexes.computeIfAbsent(rows.getString("INDEX_NAME"), ignored -> new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"), column);
                    }
                }
                for (var columns : indexes.values()) uniqueKeys.add(table + columns.values());
            }
        }
        Collections.sort(primaryKeys); Collections.sort(foreignKeys); Collections.sort(uniqueKeys);
        signature.put("primaryKeys", primaryKeys);
        signature.put("foreignKeys", foreignKeys);
        signature.put("uniqueKeys", uniqueKeys);
        var checks = jdbc.queryForList("SELECT tc.table_name,cc.check_clause FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON tc.constraint_catalog=cc.constraint_catalog AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name WHERE tc.table_schema='public' AND tc.constraint_type='CHECK' ORDER BY tc.table_name,cc.check_clause");
        signature.put("checks", checks);
        return signature;
    }
}
