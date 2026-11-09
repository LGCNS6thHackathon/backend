# 기능명세 기반 도메인 기반 설계

기준: [Notion 기능명세서](https://app.notion.com/p/41382c18756e807c8646c002f9e7ebcd?pvs=21), 2026-11-09 확인. 채팅에서 확정한 과제 본인 조회 요구도 포함합니다.

이번 범위는 **MariaDB 스키마·ERD·순수 Java 도메인 모델·저장소 인터페이스**입니다. 기존 JDBC와 적용된 V1, 기존 컨트롤러를 유지하며 신규 서비스·컨트롤러·저장소 구현·외부 API 호출을 만들지 않습니다. 각 담당자가 이 기반 위에 구현합니다.

## 공통 결정

- V1을 수정하지 않고 V2 마이그레이션으로 확장합니다. 기존 INSERT는 추가 컬럼의 NULL/default로 호환합니다.
- Java 17 record와 중첩 enum, 숫자 FK ID를 사용합니다. JPA/ORM/미구현 Spring Bean은 추가하지 않습니다.
- 공개 공지, 과제 본인 조회, AI 복습 본인 조회는 향후 서비스와 소유자 범위를 받는 저장소 계약으로 구분합니다. 도메인 모델은 API 응답 DTO가 아닙니다.
- AI 학습 주제는 `learning_subjects → learning_units → learning_topics`로 과목·단원·핵심 개념을 표현합니다. 기수는 과목의 소속입니다.
- AI 복습은 `review_sessions`를 공통 부모로, `blank_reviews`와 `review_quizzes`를 공유 PK/FK 자식으로 둡니다. 부모 mode와 자식 종류 일치·자식 배타성은 구현 담당자가 트랜잭션에서 보장합니다.
- AI 퀴즈 한 문제는 필수 보기 4개 컬럼을 갖습니다. 하나의 선택 번호(1~4)와 응답 시각을 저장하므로 동일 문제에 복수 선택은 저장하지 않습니다.
- 시각은 UTC, 날짜는 한국 달력 기준입니다. Webhook URL·AI 키·인증 토큰 원문은 도메인/ERD에 저장하지 않습니다.

## 기존 테이블 확장

| 테이블 | 확장 |
|---|---|
| posts | category에 ATTENDANCE 추가. visibility(COHORT/PUBLIC, 기본 COHORT), public_slug(NULL 허용, unique) 추가. FAQ의 질문은 title, 답변은 body로 표현 |
| attendance_records | 상태에 LEAVE 추가(EARLY 유지). unit_type(DATE/SESSION, 기본 DATE), session_no(기본0), version(기본0), deleted_at 추가. unique(enrollment_id, attendance_date, session_no). DATE=0, SESSION>0 |
| attendance_corrections | original_status, original_version(NULL 허용) 추가. status에 INVALIDATED 추가. 파일 소명 테이블은 만들지 않음 |
| assignments | instructor_id(NULL 허용, users FK), instructor_name(NULL 허용), deleted_at 추가. 등록자 author_id와 담당 강사를 분리 |
| assessment_questions | topic_id(NULL 허용, learning_topics FK) 추가 |

`submissions.submitted_at`은 최종 제출/업로드 시각으로 사용합니다. 파일 재업로드 후 이 값을 갱신하는 작업은 과제 담당자의 구현 범위입니다. 기존 파일 업로드 API는 아직 이 동작을 구현하지 않았습니다.

기존 출결 API는 날짜만으로 기록을 찾습니다. 회차별 기록을 실제 입력하기 전에 담당자가 해당 SQL/DTO를 unit_type·session_no 기준으로 변경해야 합니다. 이번 기반 작업이 신규 API 동작을 제공하는 것은 아닙니다.

## 신규 테이블 11개

| 테이블 | 핵심 필드·관계 |
|---|---|
| learning_subjects | id, cohort_id FK, title, position, created_at, updated_at, deleted_at. unique(cohort_id, position) |
| learning_units | id, subject_id FK, title, position, created_at, updated_at, deleted_at. unique(subject_id, position) |
| learning_topics | id, unit_id FK, title, description, position, created_at, updated_at, deleted_at. unique(unit_id, position) |
| lecture_videos | id, cohort_id FK, created_by FK users, title, youtube_url, youtube_video_id, lecture_date, period_no(>0), duration_seconds(NULL 허용), visibility(PUBLIC/PRIVATE), created_at, updated_at, deleted_at. unique(cohort_id, lecture_date, period_no) |
| lecture_chapters | id, video_id FK, topic_id(NULL 허용 FK), title, start_seconds(>=0, 기본0), position, created_at, updated_at. unique(video_id, position) |
| assessment_question_review_links | id, question_id FK assessment_questions, chapter_id FK, position. unique(question_id, chapter_id), unique(question_id, position) |
| review_sessions | id, user_id FK, topic_id FK, mode(BLANK/QUIZ), status(ACTIVE/COMPLETED/ABANDONED), created_at, updated_at, completed_at(NULL 허용) |
| blank_reviews | session_id PK/FK review_sessions, content(TEXT), strengths(TEXT NULL), gaps(TEXT NULL), recommendations(TEXT NULL), status(DRAFT/PENDING/COMPLETED/FAILED), submitted_at(NULL), feedback_at(NULL), error_code(NULL) |
| review_quizzes | session_id PK/FK review_sessions, next_sequence_no(>0, 기본1) |
| review_quiz_questions | id, quiz_session_id FK review_quizzes(session_id), sequence_no(>0), prompt, choice_a/b/c/d(필수), correct_choice(1~4), explanation, selected_choice(NULL 또는1~4), is_correct(NULL), created_at, answered_at(NULL). unique(quiz_session_id, sequence_no) |
| notice_deliveries | id, post_id FK, requested_by FK users, channel(DISCORD), status(PENDING/SENT/FAILED), idempotency_key unique, title_snapshot, summary_snapshot, public_url, external_message_id(NULL), attempt_count(기본0), error_code(NULL), created_at, sent_at(NULL) |

총 35개 테이블입니다. 기존 기능의 테이블은 제거하지 않습니다. 새 AI/영상 기능의 관계를 따로 저장하기 때문에 기존 24개보다 늘어납니다.

## 담당자별 구현 경계

- 게시판: 관리자 CRUD, 삭제 숨김, 공지 공개 상세, 공개 링크 발급, FAQ/질문 권한. 기존 QUESTION 작성 정책과 명세의 관리자 권한 차이는 담당자가 확정합니다.
- 출결: 일자/회차 구분, 휴가, 논리 삭제, version 증가. ABSENT→LATE/PRESENT와 LATE→PRESENT만 정정 접수, 중복 대기 거부, 요청 당시 version/status가 달라지면 INVALIDATED. 실제 파일 업로드·파일 DB는 제외합니다.
- 과제: 담당 강사·기한 표시, 논리 삭제, 파일 업로드 시 submitted_at 갱신, 본인 조회, 강사 점수·평가 반영. 기존 검토 후 잠금/마감 정책은 명세에서 확정하지 않았으므로 담당자가 조율합니다.
- 강의/평가 복습: YouTube URL 형식 검사, 날짜·교시 목록, 다른 기수 접근 차단, 소단원 시작 시각, 미분류/연결 없음 안내. 정답·해설 공개 전에는 복습 링크도 감춥니다.
- AI 복습: 주제 선택 필수, 백지 내용 제출, 세 부분 피드백, 실패 시 재시도. 퀴즈는 한 번에 한 문제·보기4개·단일 선택, 답안 후 정답·해설 제공, 다음 문제 동의 후 새 문제 생성, 종료 처리. 정답/해설을 답안 제출 전 DTO에서 숨깁니다.
- Discord: 서버 설정의 전용 Webhook으로 제목·요약·공개 상세 링크 한 메시지 전송. 멘션 차단·길이 제한·실패 재시도/중복 전송 방지. 이번 작업에서는 전송하지 않습니다.

## 코드 패키지

기존 파일을 이동하지 않고, 기능별 `domain/<feature>/model`과 `domain/<feature>/repository`를 추가합니다. 기존 Map/JDBC 구현과 신규 typed 모델은 담당자가 연결해야 합니다.

`auth`, `catalog`, `schedule`, `attendance`, `community`, `assignment`, `assessment`, `study`, `notification`, `attachment`, `curriculum`, `lecture`, `review`, `announcement`.
