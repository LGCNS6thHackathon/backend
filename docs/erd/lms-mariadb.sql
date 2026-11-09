CREATE TABLE users (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL, name VARCHAR(80) NOT NULL, phone VARCHAR(30),
 role VARCHAR(20) NOT NULL DEFAULT 'STUDENT', status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 token_version INT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_users_role CHECK (role IN ('ADMIN','INSTRUCTOR','STUDENT')),
 CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','DISABLED'))
);
CREATE TABLE refresh_tokens (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE,
 family_id VARCHAR(36) NOT NULL, expires_at DATETIME(6) NOT NULL, revoked_at DATETIME(6), created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_refresh_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE INDEX ix_refresh_user ON refresh_tokens(user_id);
CREATE TABLE courses (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(160) NOT NULL, description TEXT NOT NULL,
 total_hours INT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_course_hours CHECK(total_hours >= 0)
);
CREATE TABLE cohorts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, course_id BIGINT NOT NULL, name VARCHAR(120) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, classroom_url VARCHAR(2000), materials_url VARCHAR(2000),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_cohort_course FOREIGN KEY(course_id) REFERENCES courses(id),
 CONSTRAINT ck_cohort_dates CHECK(end_date >= start_date)
);
CREATE INDEX ix_cohort_course ON cohorts(course_id);
CREATE TABLE enrollments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 role VARCHAR(20) NOT NULL DEFAULT 'STUDENT', status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_enrollment UNIQUE(cohort_id,user_id),
 CONSTRAINT fk_enrollment_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT fk_enrollment_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT ck_enrollment_role CHECK(role IN ('STUDENT','INSTRUCTOR','MANAGER')),
 CONSTRAINT ck_enrollment_status CHECK(status IN ('ACTIVE','WITHDRAWN'))
);
CREATE INDEX ix_enrollment_user ON enrollments(user_id,status);
CREATE TABLE schedule_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL,
 type VARCHAR(20) NOT NULL, start_at DATETIME(6) NOT NULL, end_at DATETIME(6) NOT NULL,
 location VARCHAR(200) NOT NULL, description TEXT NOT NULL, created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_event_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT fk_event_author FOREIGN KEY(created_by) REFERENCES users(id),
 CONSTRAINT ck_event_type CHECK(type IN ('LECTURE','PROJECT','SPECIAL','HOLIDAY')),
 CONSTRAINT ck_event_time CHECK(end_at > start_at)
);
CREATE INDEX ix_event_cohort_time ON schedule_events(cohort_id,start_at);
CREATE TABLE roadmap_phases (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL,
 description TEXT NOT NULL, position INT NOT NULL, hours INT NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL,
 CONSTRAINT uq_phase_position UNIQUE(cohort_id,position),
 CONSTRAINT fk_phase_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT ck_phase_hours CHECK(hours >= 0), CONSTRAINT ck_phase_dates CHECK(end_date >= start_date)
);
CREATE TABLE roadmap_modules (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, phase_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL,
 hours INT NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL, position INT NOT NULL,
 CONSTRAINT uq_module_position UNIQUE(phase_id,position),
 CONSTRAINT fk_module_phase FOREIGN KEY(phase_id) REFERENCES roadmap_phases(id),
 CONSTRAINT ck_module_hours CHECK(hours >= 0), CONSTRAINT ck_module_dates CHECK(end_date >= start_date)
);
CREATE TABLE attendance_records (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, enrollment_id BIGINT NOT NULL, attendance_date DATE NOT NULL,
 status VARCHAR(20) NOT NULL, check_in DATETIME(6), check_out DATETIME(6), note VARCHAR(1000), updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_attendance UNIQUE(enrollment_id,attendance_date),
 CONSTRAINT fk_attendance_enrollment FOREIGN KEY(enrollment_id) REFERENCES enrollments(id),
 CONSTRAINT ck_attendance_status CHECK(status IN ('PRESENT','LATE','EARLY','ABSENT','EXCUSED')),
 CONSTRAINT ck_attendance_time CHECK(check_out IS NULL OR check_in IS NULL OR check_out >= check_in)
);
CREATE TABLE attendance_corrections (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, attendance_id BIGINT NOT NULL, enrollment_id BIGINT NOT NULL,
 requested_status VARCHAR(20) NOT NULL, reason VARCHAR(2000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 reviewed_by BIGINT, review_note VARCHAR(2000), created_at DATETIME(6) NOT NULL, reviewed_at DATETIME(6),
 CONSTRAINT fk_correction_record FOREIGN KEY(attendance_id) REFERENCES attendance_records(id),
 CONSTRAINT fk_correction_enrollment FOREIGN KEY(enrollment_id) REFERENCES enrollments(id),
 CONSTRAINT fk_correction_reviewer FOREIGN KEY(reviewed_by) REFERENCES users(id),
 CONSTRAINT ck_correction_status CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 CONSTRAINT ck_correction_requested CHECK(requested_status IN ('PRESENT','LATE','EARLY','ABSENT','EXCUSED'))
);
CREATE INDEX ix_correction_record_status ON attendance_corrections(attendance_id,status);
CREATE TABLE posts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, author_id BIGINT NOT NULL,
 category VARCHAR(20) NOT NULL, title VARCHAR(160) NOT NULL, body TEXT NOT NULL, pinned BOOLEAN NOT NULL DEFAULT FALSE,
 views INT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 CONSTRAINT fk_post_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT fk_post_author FOREIGN KEY(author_id) REFERENCES users(id),
 CONSTRAINT ck_post_category CHECK(category IN ('COURSE','SYSTEM','QUESTION','FAQ'))
);
CREATE INDEX ix_post_cohort_category ON posts(cohort_id,category,created_at);
CREATE TABLE comments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, post_id BIGINT NOT NULL, author_id BIGINT NOT NULL, body TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 CONSTRAINT fk_comment_post FOREIGN KEY(post_id) REFERENCES posts(id),
 CONSTRAINT fk_comment_author FOREIGN KEY(author_id) REFERENCES users(id)
);
CREATE INDEX ix_comment_post ON comments(post_id,created_at);
CREATE TABLE assignments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, author_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL, description TEXT NOT NULL, due_at DATETIME(6) NOT NULL, max_score INT NOT NULL DEFAULT 100,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_assignment_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT fk_assignment_author FOREIGN KEY(author_id) REFERENCES users(id),
 CONSTRAINT ck_assignment_score CHECK(max_score BETWEEN 1 AND 1000)
);
CREATE INDEX ix_assignment_cohort ON assignments(cohort_id,due_at);
CREATE TABLE submissions (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, assignment_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 body TEXT NOT NULL, repository_url VARCHAR(2000), submitted_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_submission UNIQUE(assignment_id,user_id),
 CONSTRAINT fk_submission_assignment FOREIGN KEY(assignment_id) REFERENCES assignments(id),
 CONSTRAINT fk_submission_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE submission_reviews (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, submission_id BIGINT NOT NULL UNIQUE, reviewer_id BIGINT NOT NULL,
 score INT NOT NULL, feedback TEXT NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_review_submission FOREIGN KEY(submission_id) REFERENCES submissions(id),
 CONSTRAINT fk_review_author FOREIGN KEY(reviewer_id) REFERENCES users(id),
 CONSTRAINT ck_review_score CHECK(score >= 0)
);
CREATE TABLE assessments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL, description TEXT NOT NULL,
 domain VARCHAR(100) NOT NULL, start_at DATETIME(6) NOT NULL, end_at DATETIME(6) NOT NULL,
 duration_minutes INT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_assessment_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT fk_assessment_author FOREIGN KEY(created_by) REFERENCES users(id),
 CONSTRAINT ck_assessment_status CHECK(status IN ('DRAFT','PUBLISHED','CLOSED')),
 CONSTRAINT ck_assessment_duration CHECK(duration_minutes BETWEEN 1 AND 480),
 CONSTRAINT ck_assessment_time CHECK(end_at > start_at)
);
CREATE TABLE assessment_questions (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, assessment_id BIGINT NOT NULL, prompt TEXT NOT NULL,
 position INT NOT NULL, points INT NOT NULL DEFAULT 1, explanation TEXT NOT NULL,
 CONSTRAINT uq_question_position UNIQUE(assessment_id,position),
 CONSTRAINT fk_question_assessment FOREIGN KEY(assessment_id) REFERENCES assessments(id),
 CONSTRAINT ck_question_points CHECK(points BETWEEN 1 AND 100)
);
CREATE TABLE assessment_options (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, question_id BIGINT NOT NULL, label VARCHAR(2000) NOT NULL, is_correct BOOLEAN NOT NULL,
 CONSTRAINT fk_option_question FOREIGN KEY(question_id) REFERENCES assessment_questions(id)
);
CREATE INDEX ix_option_question ON assessment_options(question_id);
CREATE TABLE assessment_attempts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, assessment_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS', started_at DATETIME(6) NOT NULL, submitted_at DATETIME(6), score INT,
 CONSTRAINT uq_attempt UNIQUE(assessment_id,user_id),
 CONSTRAINT fk_attempt_assessment FOREIGN KEY(assessment_id) REFERENCES assessments(id),
 CONSTRAINT fk_attempt_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT ck_attempt_status CHECK(status IN ('IN_PROGRESS','SUBMITTED','EXPIRED')),
 CONSTRAINT ck_attempt_score CHECK(score IS NULL OR score BETWEEN 0 AND 100)
);
CREATE TABLE assessment_answers (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, attempt_id BIGINT NOT NULL, question_id BIGINT NOT NULL, option_id BIGINT NOT NULL,
 is_correct BOOLEAN, earned_points INT,
 CONSTRAINT uq_answer UNIQUE(attempt_id,question_id),
 CONSTRAINT fk_answer_attempt FOREIGN KEY(attempt_id) REFERENCES assessment_attempts(id),
 CONSTRAINT fk_answer_question FOREIGN KEY(question_id) REFERENCES assessment_questions(id),
 CONSTRAINT fk_answer_option FOREIGN KEY(option_id) REFERENCES assessment_options(id)
);
CREATE TABLE study_groups (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, owner_id BIGINT NOT NULL, name VARCHAR(120) NOT NULL,
 description TEXT NOT NULL, capacity INT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_group_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT fk_group_owner FOREIGN KEY(owner_id) REFERENCES users(id),
 CONSTRAINT ck_group_capacity CHECK(capacity BETWEEN 2 AND 30),
 CONSTRAINT ck_group_status CHECK(status IN ('OPEN','CLOSED'))
);
CREATE INDEX ix_group_cohort ON study_groups(cohort_id);
CREATE TABLE study_group_members (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, group_id BIGINT NOT NULL, user_id BIGINT NOT NULL, joined_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_group_member UNIQUE(group_id,user_id),
 CONSTRAINT fk_member_group FOREIGN KEY(group_id) REFERENCES study_groups(id),
 CONSTRAINT fk_member_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE notifications (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, cohort_id BIGINT, type VARCHAR(40) NOT NULL,
 title VARCHAR(160) NOT NULL, body VARCHAR(2000) NOT NULL, resource_type VARCHAR(40), resource_id BIGINT,
 read_at DATETIME(6), created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_notification_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_notification_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id)
);
CREATE INDEX ix_notification_user ON notifications(user_id,read_at,created_at);
CREATE TABLE files (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, uploader_id BIGINT NOT NULL,
 post_id BIGINT, assignment_id BIGINT, submission_id BIGINT, original_name VARCHAR(255) NOT NULL,
 stored_name VARCHAR(100) NOT NULL UNIQUE, content_type VARCHAR(160) NOT NULL, size_bytes BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 CONSTRAINT fk_file_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT fk_file_uploader FOREIGN KEY(uploader_id) REFERENCES users(id),
 CONSTRAINT fk_file_post FOREIGN KEY(post_id) REFERENCES posts(id),
 CONSTRAINT fk_file_assignment FOREIGN KEY(assignment_id) REFERENCES assignments(id),
 CONSTRAINT fk_file_submission FOREIGN KEY(submission_id) REFERENCES submissions(id),
 CONSTRAINT ck_file_size CHECK(size_bytes > 0),
 CONSTRAINT ck_file_parent CHECK((CASE WHEN post_id IS NOT NULL THEN 1 ELSE 0 END + CASE WHEN assignment_id IS NOT NULL THEN 1 ELSE 0 END + CASE WHEN submission_id IS NOT NULL THEN 1 ELSE 0 END) = 1)
);
