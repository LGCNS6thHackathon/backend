# 기능명세 기반 도메인 기반 설계

기준: [Notion 기능명세서](https://app.notion.com/p/c4f933854d4a45d5a545bf62cd8e2045?pvs=23), 2026-11-09 재확인. 비어 있지 않은 26개 행과 채팅에서 확정한 관리자 계정 발급·출결 정정 정책을 반영합니다. 빈 행 3개는 기능으로 계산하지 않습니다.

AI·영상·Discord 신규 기능의 범위는 **MariaDB 스키마·ERD·순수 Java 도메인 모델·저장소 인터페이스**입니다. 해당 기능의 신규 서비스·컨트롤러·저장소 구현·외부 API 호출은 각 담당자가 이 기반 위에 구현합니다. 기존 JDBC API에는 공개 회원가입 제거·관리자 계정 발급, 일자/회차 출결·정정·논리 삭제, 과제 담당 강사·논리 삭제, 제출 파일 업로드 시각 갱신을 반영했습니다. 전체 테스트 32개와 실제 MariaDB API 흐름 25개 검증을 통과했습니다.

## 공통 결정

- 적용된 V1/V2는 수정하지 않습니다. 기능 기반 확장은 V2, 관리자 계정 발급 이력은 V3 마이그레이션이며 기존 INSERT는 추가 컬럼의 NULL/default로 호환합니다.
- 공개 회원가입은 제공하지 않습니다. 관리자가 `POST /api/users`로 계정을 발급하고 기존 기수 수강 등록 API로 소속을 지정합니다. 발급자와 발급 시각은 서버가 기록하며 기존 계정과 초기 관리자에는 알 수 없는 발급자를 추정하지 않습니다.
- Java 17 record와 중첩 enum, 숫자 FK ID를 사용합니다. JPA/ORM/미구현 Spring Bean은 추가하지 않습니다.
- 공개 공지와 AI 복습 본인 조회는 향후 서비스와 소유자 범위를 받는 저장소 계약으로 구분합니다. 과제 제출·평가·첨부의 본인 조회는 기존 API의 소유자 조건과 권한 검사를 재사용합니다. 도메인 모델은 API 응답 DTO가 아닙니다.
- 출결 정정은 ABSENT→LATE, ABSENT→PRESENT, LATE→PRESENT, EXCUSED→PRESENT, LEAVE→PRESENT, EARLY→PRESENT 여섯 전환입니다. 공결·휴가·조퇴도 승인 시 출석으로 변경한다는 채팅 결정으로 명세의 두 소명 요청 행을 연결합니다.
- AI 학습 주제는 `learning_subjects → learning_units → learning_topics`로 과목·단원·핵심 개념을 표현합니다. 기수는 과목의 소속입니다.
- AI 복습은 `review_sessions`를 공통 부모로, `blank_reviews`와 `review_quizzes`를 공유 PK/FK 자식으로 둡니다. 부모 mode와 자식 종류 일치·자식 배타성은 구현 담당자가 트랜잭션에서 보장합니다.
- AI 퀴즈 한 문제는 필수 보기 4개 컬럼을 갖습니다. 하나의 선택 번호(1~4)와 응답 시각을 저장하므로 동일 문제에 복수 선택은 저장하지 않습니다.
- 시각은 UTC, 날짜는 한국 달력 기준입니다. Webhook URL·AI 키·인증 토큰 원문은 도메인/ERD에 저장하지 않습니다.

## 기존 테이블 확장

| 테이블 | 확장 |
|---|---|
| users | V3에서 provisioned_by(BIGINT NULL, users.id 자기참조 FK·인덱스), provisioned_at(DATETIME(6) NULL) 추가. 관리자가 발급한 계정은 발급자와 UTC 발급 시각을 기록하며 기존 계정·초기 관리자는 둘 다 NULL 유지 |
| posts | V2의 category=ATTENDANCE는 기존 스키마·데이터 호환용으로 유지하며 현재 메뉴 요구는 아님. visibility(COHORT/PUBLIC, 기본 COHORT), public_slug(NULL 허용, unique). FAQ의 질문은 title, 답변은 body로 표현 |
| attendance_records | PRESENT/LATE/ABSENT/EXCUSED/LEAVE/EARLY로 출석·지각·결석·공결·휴가·조퇴 표현. unit_type(DATE/SESSION, 기본 DATE), session_no(기본0), version(기본0), deleted_at. unique(enrollment_id, attendance_date, session_no). DATE=0, SESSION>0 |
| attendance_corrections | original_status, original_version(NULL 허용) 추가. status에 INVALIDATED 추가. 파일 소명 테이블은 만들지 않음 |
| assignments | instructor_id(NULL 허용, users FK), instructor_name(NULL 허용), deleted_at 추가. 등록자 author_id와 담당 강사를 분리 |
| assessment_questions | topic_id(NULL 허용, learning_topics FK) 추가 |

`submissions.submitted_at`은 마지막으로 성공한 제출/파일 업로드 시각입니다. 기존 제출 파일 업로드 API에 파일 저장과 메타데이터 저장 성공 후 시각을 갱신하는 동작을 코드 반영합니다. 실패한 업로드는 제출 시각을 갱신하지 않습니다. 학생 본인 조회와 다운로드 소유자 검사는 기존 경로를 재사용하며 프런트엔드에서 최종 제출 시각을 표시하도록 연결합니다.

기존 출결 API에 `unitType`·`sessionNo`, 여섯 출결 상태, 논리 삭제와 버전 증가, 여섯 정정 전환을 코드 반영합니다. 캘린더는 기존 정정 접수 API를 사용하며 목표 상태 생략 시 `PRESENT`, 사유 생략 시 기본 요청 문구를 사용합니다. 실제 소명 파일 업로드는 만들지 않습니다. 화면의 회차 선택·소명 버튼·무효화 안내와 API 응답 연결이 남습니다.

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

총 35개 테이블, 61개 FK입니다. V3는 `users` 자기참조 FK 하나만 추가하며 테이블 수를 늘리지 않습니다. 기존 기능의 테이블은 제거하지 않습니다. 새 AI/영상 기능의 관계를 따로 저장하기 때문에 기존 24개보다 늘어납니다.

## 담당자별 구현 경계

- 인증·계정: 공개 회원가입 제거, 관리자만 계정 발급, 발급자·발급 시각 서버 기록. 계정 발급과 기수 수강 등록은 기존 `users`·`enrollments`를 사용합니다. 메일 발송·CSV 일괄 발급·강제 비밀번호 변경 절차는 이번 요구에 포함하지 않습니다.
- 게시판: 관리자 CRUD, 삭제 숨김, 공지 공개 상세, 공개 링크 발급, FAQ/질문 권한. 최신 명세에서 출결 공지 행은 제거되었습니다. 기존 QUESTION 작성 정책과 명세의 관리자 권한 차이는 담당자가 확정합니다.
- 출결: 기존 `AttendanceController`에 일자/회차 구분, 여섯 상태, 논리 삭제·version 증가, 여섯 정정 전환, 중복 대기 거부, 접수 시 원본 유지와 상태·버전 스냅샷을 코드 반영합니다. 수정·삭제된 원본은 요청을 INVALIDATED로 저장하고 승인 시 409를 반환합니다. 화면·DTO 연결과 회귀 검증을 진행해야 하며 실제 파일 업로드·파일 DB는 제외합니다.
- 과제: 기존 `AssignmentController`·`FileController`와 공통 조회 경로에 담당 강사 ID/이름, 논리 삭제·삭제 과제 조회 제외, 성공한 제출 파일 업로드 시 submitted_at 갱신을 코드 반영합니다. 본인 조회와 점수·평가는 기존 API를 재사용합니다. 프런트엔드 표시·업로드 연결과 회귀 검증이 남습니다. 기존 검토 후 잠금/마감 정책은 유지하며 세부 변경은 담당자가 조율합니다.
- 강의/평가 복습: YouTube URL 형식 검사, 날짜·교시 목록, 다른 기수 접근 차단, 소단원 시작 시각, 미분류/연결 없음 안내. 정답·해설 공개 전에는 복습 링크도 감춥니다.
- AI 복습: 주제 선택 필수, 백지 내용 제출, 세 부분 피드백, 실패 시 재시도. 퀴즈는 한 번에 한 문제·보기4개·단일 선택, 답안 후 정답·해설 제공, 다음 문제 동의 후 새 문제 생성, 종료 처리. 정답/해설을 답안 제출 전 DTO에서 숨깁니다.
- Discord: 서버 설정의 전용 Webhook으로 제목·요약·공개 상세 링크 한 메시지 전송. 멘션 차단·길이 제한·실패 재시도/중복 전송 방지. 이번 작업에서는 전송하지 않습니다.

## 코드 패키지

기존 파일을 이동하지 않고, 기능별 `domain/<feature>/model`과 `domain/<feature>/repository`를 추가합니다. 기존 Map/JDBC 구현과 신규 typed 모델은 담당자가 연결해야 합니다.

`auth`, `catalog`, `schedule`, `attendance`, `community`, `assignment`, `assessment`, `study`, `notification`, `attachment`, `curriculum`, `lecture`, `review`, `announcement`.
