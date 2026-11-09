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
        assertEquals(35, tableNames(new JdbcTemplate(migrated)).size());
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
