-- Schema and domain foundation only. The feature owners implement API behavior.
-- All added columns on existing tables accept legacy INSERT statements.

ALTER TABLE posts ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'COHORT';
ALTER TABLE posts ADD COLUMN public_slug VARCHAR(160);
ALTER TABLE posts DROP CONSTRAINT ck_post_category;
ALTER TABLE posts ADD CONSTRAINT ck_post_category CHECK(category IN ('COURSE','SYSTEM','ATTENDANCE','QUESTION','FAQ'));
ALTER TABLE posts ADD CONSTRAINT ck_post_visibility CHECK(visibility IN ('COHORT','PUBLIC'));
ALTER TABLE posts ADD CONSTRAINT uq_post_public_slug UNIQUE(public_slug);

ALTER TABLE attendance_records ADD COLUMN unit_type VARCHAR(20) NOT NULL DEFAULT 'DATE';
ALTER TABLE attendance_records ADD COLUMN session_no INT NOT NULL DEFAULT 0;
ALTER TABLE attendance_records ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE attendance_records ADD COLUMN deleted_at DATETIME(6);
-- Add the replacement index before dropping the old one, so InnoDB's
-- enrollment foreign key always retains an index beginning with enrollment_id.
ALTER TABLE attendance_records ADD CONSTRAINT uq_attendance_unit UNIQUE(enrollment_id,attendance_date,session_no);
ALTER TABLE attendance_records DROP CONSTRAINT uq_attendance;
ALTER TABLE attendance_records DROP CONSTRAINT ck_attendance_status;
ALTER TABLE attendance_records ADD CONSTRAINT ck_attendance_status CHECK(status IN ('PRESENT','LATE','EARLY','ABSENT','EXCUSED','LEAVE'));
ALTER TABLE attendance_records ADD CONSTRAINT ck_attendance_unit CHECK((unit_type='DATE' AND session_no=0) OR (unit_type='SESSION' AND session_no>0));
ALTER TABLE attendance_records ADD CONSTRAINT ck_attendance_version CHECK(version >= 0);

ALTER TABLE attendance_corrections ADD COLUMN original_status VARCHAR(20);
ALTER TABLE attendance_corrections ADD COLUMN original_version INT;
ALTER TABLE attendance_corrections DROP CONSTRAINT ck_correction_status;
ALTER TABLE attendance_corrections ADD CONSTRAINT ck_correction_status CHECK(status IN ('PENDING','APPROVED','REJECTED','INVALIDATED'));
ALTER TABLE attendance_corrections ADD CONSTRAINT ck_correction_original_status CHECK(original_status IS NULL OR original_status IN ('PRESENT','LATE','EARLY','ABSENT','EXCUSED','LEAVE'));
ALTER TABLE attendance_corrections ADD CONSTRAINT ck_correction_original_version CHECK(original_version IS NULL OR original_version >= 0);

ALTER TABLE assignments ADD COLUMN instructor_id BIGINT;
ALTER TABLE assignments ADD COLUMN instructor_name VARCHAR(80);
ALTER TABLE assignments ADD COLUMN deleted_at DATETIME(6);
ALTER TABLE assignments ADD CONSTRAINT fk_assignment_instructor FOREIGN KEY(instructor_id) REFERENCES users(id);

CREATE TABLE learning_subjects (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL,
 position INT NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 CONSTRAINT uq_subject_position UNIQUE(cohort_id,position),
 CONSTRAINT fk_subject_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT ck_subject_position CHECK(position > 0)
);
CREATE TABLE learning_units (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, subject_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL,
 position INT NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 CONSTRAINT uq_unit_position UNIQUE(subject_id,position),
 CONSTRAINT fk_unit_subject FOREIGN KEY(subject_id) REFERENCES learning_subjects(id),
 CONSTRAINT ck_unit_position CHECK(position > 0)
);
CREATE TABLE learning_topics (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, unit_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL,
 description TEXT NOT NULL, position INT NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 CONSTRAINT uq_topic_position UNIQUE(unit_id,position),
 CONSTRAINT fk_topic_unit FOREIGN KEY(unit_id) REFERENCES learning_units(id),
 CONSTRAINT ck_topic_position CHECK(position > 0)
);
ALTER TABLE assessment_questions ADD COLUMN topic_id BIGINT;
ALTER TABLE assessment_questions ADD CONSTRAINT fk_question_topic FOREIGN KEY(topic_id) REFERENCES learning_topics(id);

CREATE TABLE lecture_videos (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cohort_id BIGINT NOT NULL, created_by BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL, youtube_url VARCHAR(2000) NOT NULL, youtube_video_id VARCHAR(32) NOT NULL,
 lecture_date DATE NOT NULL, period_no INT NOT NULL, duration_seconds INT, visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 CONSTRAINT uq_video_period UNIQUE(cohort_id,lecture_date,period_no),
 CONSTRAINT fk_video_cohort FOREIGN KEY(cohort_id) REFERENCES cohorts(id),
 CONSTRAINT fk_video_author FOREIGN KEY(created_by) REFERENCES users(id),
 CONSTRAINT ck_video_period CHECK(period_no > 0),
 CONSTRAINT ck_video_duration CHECK(duration_seconds IS NULL OR duration_seconds > 0),
 CONSTRAINT ck_video_visibility CHECK(visibility IN ('PUBLIC','PRIVATE'))
);
CREATE TABLE lecture_chapters (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, video_id BIGINT NOT NULL, topic_id BIGINT, title VARCHAR(160) NOT NULL,
 start_seconds INT NOT NULL DEFAULT 0, position INT NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_chapter_position UNIQUE(video_id,position),
 CONSTRAINT fk_chapter_video FOREIGN KEY(video_id) REFERENCES lecture_videos(id),
 CONSTRAINT fk_chapter_topic FOREIGN KEY(topic_id) REFERENCES learning_topics(id),
 CONSTRAINT ck_chapter_start CHECK(start_seconds >= 0),
 CONSTRAINT ck_chapter_position CHECK(position > 0)
);
CREATE TABLE assessment_question_review_links (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, question_id BIGINT NOT NULL, chapter_id BIGINT NOT NULL, position INT NOT NULL,
 CONSTRAINT uq_question_chapter UNIQUE(question_id,chapter_id),
 CONSTRAINT uq_question_review_position UNIQUE(question_id,position),
 CONSTRAINT fk_question_review_question FOREIGN KEY(question_id) REFERENCES assessment_questions(id),
 CONSTRAINT fk_question_review_chapter FOREIGN KEY(chapter_id) REFERENCES lecture_chapters(id),
 CONSTRAINT ck_question_review_position CHECK(position > 0)
);
CREATE TABLE review_sessions (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, topic_id BIGINT NOT NULL,
 mode VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, completed_at DATETIME(6),
 CONSTRAINT fk_review_session_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_review_session_topic FOREIGN KEY(topic_id) REFERENCES learning_topics(id),
 CONSTRAINT ck_review_session_mode CHECK(mode IN ('BLANK','QUIZ')),
 CONSTRAINT ck_review_session_status CHECK(status IN ('ACTIVE','COMPLETED','ABANDONED'))
);
CREATE INDEX ix_review_session_owner ON review_sessions(user_id,created_at);
CREATE TABLE blank_reviews (
 session_id BIGINT PRIMARY KEY, content TEXT NOT NULL, strengths TEXT, gaps TEXT, recommendations TEXT,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', submitted_at DATETIME(6), feedback_at DATETIME(6), error_code VARCHAR(80),
 CONSTRAINT fk_blank_review_session FOREIGN KEY(session_id) REFERENCES review_sessions(id),
 CONSTRAINT ck_blank_review_status CHECK(status IN ('DRAFT','PENDING','COMPLETED','FAILED'))
);
CREATE TABLE review_quizzes (
 session_id BIGINT PRIMARY KEY, next_sequence_no INT NOT NULL DEFAULT 1,
 CONSTRAINT fk_review_quiz_session FOREIGN KEY(session_id) REFERENCES review_sessions(id),
 CONSTRAINT ck_review_quiz_sequence CHECK(next_sequence_no > 0)
);
CREATE TABLE review_quiz_questions (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, quiz_session_id BIGINT NOT NULL, sequence_no INT NOT NULL,
 prompt TEXT NOT NULL, choice_a VARCHAR(2000) NOT NULL, choice_b VARCHAR(2000) NOT NULL,
 choice_c VARCHAR(2000) NOT NULL, choice_d VARCHAR(2000) NOT NULL,
 correct_choice INT NOT NULL, explanation TEXT NOT NULL, selected_choice INT, is_correct BOOLEAN,
 created_at DATETIME(6) NOT NULL, answered_at DATETIME(6),
 CONSTRAINT uq_review_quiz_question UNIQUE(quiz_session_id,sequence_no),
 CONSTRAINT fk_review_quiz_question_quiz FOREIGN KEY(quiz_session_id) REFERENCES review_quizzes(session_id),
 CONSTRAINT ck_review_quiz_question_sequence CHECK(sequence_no > 0),
 CONSTRAINT ck_review_quiz_correct_choice CHECK(correct_choice BETWEEN 1 AND 4),
 CONSTRAINT ck_review_quiz_selected_choice CHECK(selected_choice IS NULL OR selected_choice BETWEEN 1 AND 4)
);
CREATE TABLE notice_deliveries (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, post_id BIGINT NOT NULL, requested_by BIGINT NOT NULL,
 channel VARCHAR(20) NOT NULL DEFAULT 'DISCORD', status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 idempotency_key VARCHAR(128) NOT NULL UNIQUE, title_snapshot VARCHAR(160) NOT NULL,
 summary_snapshot VARCHAR(1000) NOT NULL, public_url VARCHAR(2000) NOT NULL,
 external_message_id VARCHAR(100), attempt_count INT NOT NULL DEFAULT 0, error_code VARCHAR(80),
 created_at DATETIME(6) NOT NULL, sent_at DATETIME(6),
 CONSTRAINT fk_notice_delivery_post FOREIGN KEY(post_id) REFERENCES posts(id),
 CONSTRAINT fk_notice_delivery_requester FOREIGN KEY(requested_by) REFERENCES users(id),
 CONSTRAINT ck_notice_delivery_channel CHECK(channel IN ('DISCORD')),
 CONSTRAINT ck_notice_delivery_status CHECK(status IN ('PENDING','SENT','FAILED')),
 CONSTRAINT ck_notice_delivery_attempts CHECK(attempt_count >= 0)
);
CREATE INDEX ix_notice_delivery_post ON notice_deliveries(post_id,created_at);
