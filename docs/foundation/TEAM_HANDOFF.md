# 기능별 개발 인수인계

기준은 2026-11-09 재확인한 [Notion 기능명세서](https://app.notion.com/p/c4f933854d4a45d5a545bf62cd8e2045?pvs=23)의 비어 있지 않은 26개 항목과 채팅에서 확정한 관리자 계정 발급·출결 정정 정책입니다. 전체 설계 결정과 컬럼은 [DOMAIN_PLAN.md](DOMAIN_PLAN.md), 실제 생성 스키마는 [MariaDB SQL](../erd/lms-mariadb.sql)을 확인하세요.

AI·영상·Discord 신규 기능의 결과물은 **스키마·ERD·도메인 모델·저장소 인터페이스 기반**입니다. 해당 기능의 API, 서비스, JDBC 저장소 구현, AI 요청, Discord 전송은 각 담당자가 구현합니다. 기존 JDBC API에는 공개 회원가입 제거·관리자 발급, 출결 단위·정정·논리 삭제, 과제 담당 강사·논리 삭제를 반영했습니다. 현재 과제 화면에 맞춘 본인 카드·제출/첨부 재조회·배포 URL·multipart 제출·평가자 이름 반환도 백엔드에 추가했습니다. 프런트엔드 화면과 호출 코드는 변경하지 않았으므로 실제 API 연결은 화면 담당자의 작업입니다. 신규 도메인 모델은 기존 JDBC API에 자동 연결되지 않습니다.

## 명세와 도메인 연결

번호는 기능명세서의 비어 있지 않은 행 순서입니다. Discord 다음 빈 행 3개는 제외합니다. 이전 출결 공지 행은 제거되었고 캘린더 소명 요청 행이 추가되었습니다. `ATTENDANCE` 게시판 분류는 호환용으로 유지하며 현재 메뉴 요구에 포함하지 않습니다. 코드 위치는 `src/main/java/kr/wsts/lms/domain/` 기준이며 각 기능의 `model/`, `repository/`를 사용합니다.

| 번호 | 명세 기능 | 테이블·주요 모델 | 코드 반영 범위·남은 연결 |
|---|---|---|---|
| 1 | 과정 공지 CRUD | `posts` / Post, category=COURSE | `community`: 관리자 권한, 제목·내용 필수, 삭제 숨김, 목록·상세 반영 |
| 2 | 시스템 공지 CRUD | `posts` / Post, category=SYSTEM | `community`: 관리자 권한과 공지 조회 범위 연결 |
| 3 | FAQ 질문·답변 CRUD | `posts` / Post, category=FAQ | `community`: title=질문, body=답변, 필수값·삭제 숨김 |
| 4 | 학습 질문 CRUD | `posts`, `comments` / Post, Comment | `community`: 제목·내용 검증. 명세의 관리자 CRUD와 기존 학생 QUESTION 작성 정책을 조율 |
| 5 | 게시판의 과제 안내 CRUD | `assignments` / Assignment | `assignment`: 과제 관리와 동일 원본을 사용해 게시글 형태 목록·상세 제공 |
| 6 | 강의 제목·YouTube URL CRUD | `lecture_videos` / LectureVideo | `lecture`: 관리자 CRUD, 지원 URL 검사, 삭제 영상 숨김 |
| 7 | 수강생별 일자/회차 출결 CRUD | `attendance_records`, `enrollments` / AttendanceRecord | 기존 `learning/AttendanceController`에 DATE/SESSION·여섯 상태·논리 삭제·버전 증가 코드 반영. 화면의 회차 선택·기록 표시 연결 |
| 8 | 출결 정정 요청 접수 | `attendance_corrections` / AttendanceCorrection | 기존 API에 본인 기록·확정된 여섯 전환·중복 대기 차단·상태/버전 스냅샷 코드 반영. 소명 버튼과 선택 목표·접수 상태 표시 연결 |
| 9 | 관리자 정정 요청 허용 | `attendance_corrections`, `attendance_records` | 기존 API에 잠금·재검증·승인 후 변경·반복 처리 차단·INVALIDATED 저장 및 409 코드 반영. 대기 목록·무효화 안내 연결 |
| 10 | Discord 공지 채널 게시 | `notice_deliveries`, `posts` / NoticeDelivery, Post | `announcement`: 공개 링크·제목·요약 한 메시지, Webhook 어댑터, 멘션 차단·길이 제한 |
| 11 | 복습 주제·방식 선택 | `learning_subjects`, `learning_units`, `learning_topics`, `review_sessions` / ReviewSession | `curriculum`, `review`: 주제 선택 필수, 소속 기수 검사, BLANK/QUIZ 세션 생성 |
| 12 | 백지 복습 작성·AI 제출 | `blank_reviews`, `review_sessions` / BlankReview | `review`: 빈 내용 차단, 본인 세션, AI 호출·요청 실패 응답 |
| 13 | 백지 복습 AI 피드백 | `blank_reviews` / BlankReview | `review`: strengths·gaps·recommendations 저장·조회, 실패 상태·재시도 |
| 14 | AI 객관식 문제 순차 생성·풀이 | `review_quizzes`, `review_quiz_questions` / ReviewQuiz, ReviewQuizQuestion | `review`: 한 번에 한 문제, 보기 4개, 단일 선택, 다음 문제 동의·종료 |
| 15 | AI 객관식 답안 확인·해설 | `review_quiz_questions` / ReviewQuizQuestion | `review`: 본인 문제 답안 저장·채점, 답안 제출 후 정답·해설 응답, 실패 재시도 |
| 16 | 캘린더 날짜별 다시보기 연결 | `lecture_videos`, `schedule_events` | `lecture`, `schedule`: 날짜별 존재 여부 조회, 영상 없는 날짜 버튼 숨김, 다른 기수 접근 차단 |
| 17 | 캘린더 지각·결석·공결·휴가·조퇴 소명 요청 | `attendance_records`, `attendance_corrections` / AttendanceRecord, AttendanceCorrection | 8번의 기존 정정 API 재사용. 공결·휴가·조퇴는 승인 시 PRESENT로 확정, 목표 생략 시 PRESENT·사유 생략 가능. 실제 파일 없이 캘린더 버튼·대기 표시 연결 |
| 18 | 날짜·교시별 영상 목록·재생 | `lecture_videos` / LectureVideo | `lecture`: 날짜 필터·교시 정렬, 빈 목록·재생 불가 안내용 응답 |
| 19 | 평가 문제 과목·단원·핵심 개념 | `assessment_questions`, `learning_topics`, `learning_units`, `learning_subjects` | `assessment`, `curriculum`: nullable topic 연결 조회, 미분류 표시, 본인 평가 결과 조회 |
| 20 | 평가 해설의 복습 영상 연결 | `assessment_question_review_links`, `lecture_chapters`, `lecture_videos` / AssessmentQuestionReviewLink | `assessment`, `lecture`: 공개된 정답·해설에 한해 날짜·교시·소단원·링크 조회 |
| 21 | 관련 영상 구간 바로 재생 | `lecture_chapters` / LectureChapter | `lecture`: start_seconds로 재생 링크 생성, 0초 기본값, 자동재생·재생 불가 안내 |
| 22 | 과제 제목·강사·기한 관리 | `assignments`, `users` / Assignment | CRUD·담당 강사·논리 삭제 구현. 선택 subject로 과목 뱃지 표시명 저장. 관리자 폼의 기존 API 연결 확인 |
| 23 | 과제 탭 목록·상세 | `assignments` / Assignment | 카드 표시 필드·본인 제출/첨부/평가·제출 가능 상태를 함께 반환. 샘플 데이터 대신 실제 목록/상세 API 호출 연결 |
| 24 | 과제 제출·파일 재업로드 | `submissions`, `files` / Submission, 파일 모델 | repoUrl/deployUrl/submitMemo 별칭, 최대50MiB 제출 첨부, JSON 또는 단일 multipart 저장 구현. 실제 파일 선택·저장 호출·서버 시각 재조회 연결 |
| 25 | 강사 점수·평가 등록·수정 | `submission_reviews`, `submissions` / SubmissionReview | 점수·평가 등록/수정·평가자 이름·첨부 목록/다운로드 경로 반환. 관리자/기수 강사/매니저의 채점 폼 연결 |
| 26 | 본인 제출 내역·평가 조회 | `submissions`, `submission_reviews`, `files` | 본인 제출·파일·최종 시각·점수·평가와 채점 전 null 피드백 반환. 기존 학생 소유자 및 첨부 접근 검사를 유지하고 화면에 실제 결과 표시 |

## 담당자들이 함께 지켜야 할 계약

### 관리자 계정 발급과 기수 배정

- 공개 회원가입 경로는 제공하지 않습니다. `ADMIN`이 인증 후 `POST /api/users`로 이름·이메일·초기 비밀번호·선택 전화번호를 입력해 `STUDENT` 계정을 발급합니다. 강사·관리자 역할은 기존 권한 변경 API에서 따로 지정합니다. 비밀번호는 해시로 저장하며 발급 응답에 원문·해시를 포함하지 않습니다.
- `users.provisioned_by`는 인증된 발급 관리자 ID, `provisioned_at`은 서버 UTC 시각입니다. 클라이언트가 발급 이력을 임의 지정하지 않습니다. 자기참조 FK는 발급자의 존재를 보장하고 관리자 역할은 API에서 검증합니다.
- V3는 기존 계정의 발급자를 추정하거나 데이터를 바꾸지 않습니다. 기존 계정과 환경 설정으로 생성하는 초기 관리자는 두 값이 NULL입니다. 초기 관리자가 이후 발급하는 계정에는 해당 초기 관리자의 ID를 기록합니다.
- 계정 생성 후 관리자가 기존 `POST /api/cohorts/{cohortId}/enrollments`로 수강생·강사 등을 기수에 배정합니다. 사용자 전역 역할과 수강 등록 역할은 각각 검증합니다. 계정 발급 자체가 자동 기수 배정을 뜻하지 않습니다.
- 메일 발송, CSV 일괄 발급, 강제 비밀번호 변경 절차는 이번 범위에 포함하지 않습니다.

### 게시판과 Discord

- 제목·본문이 비어 있으면 저장하지 않고, 논리 삭제된 공지·FAQ·질문·과제는 학생 목록과 상세에서 제외합니다.
- FAQ는 `posts.title`과 `posts.body`로 질문과 답변을 표현합니다. 과제 안내와 과제 관리는 `assignments`를 함께 사용하여 서로 다른 원본이 생기지 않게 합니다.
- 공지는 기본 `COHORT` 범위입니다. Discord의 공개 상세 링크는 공개하기로 정한 공지만 `visibility=PUBLIC`과 `public_slug`로 발급합니다. 공개 상세 라우트는 아직 구현 대상입니다.
- Discord에는 선택한 공지의 제목·짧은 요약·공개 상세 링크를 메시지 한 개로 보냅니다. 멘션을 비활성화하고 길이를 제한합니다. 전용 Webhook URL은 서버 비밀 설정에만 두며 DB나 응답에 넣지 않습니다.
- 전송 재시도는 `idempotency_key`와 전송 상태를 사용해 중복 게시를 방지합니다. 이 기반에는 실제 전송 구현이 없습니다.

### 출결

- `unit_type=DATE`는 `session_no=0`, `SESSION`은 `session_no>0`입니다. 같은 수강생·날짜·회차를 중복 저장하지 않습니다. 휴가는 `LEAVE`, 조퇴는 `EARLY`이며 둘 다 현재 기록 관리 요구입니다.
- 채팅에서 정정 전환을 **ABSENT→LATE, ABSENT→PRESENT, LATE→PRESENT, EXCUSED→PRESENT, LEAVE→PRESENT, EARLY→PRESENT**로 확정했습니다. 명세 8번의 세 전환에 공결·휴가·조퇴→출석을 더하여 17번 캘린더 요구를 연결합니다. 접수 시 원본 출결을 유지하고 `original_status`, `original_version`을 기록하는 동작을 기존 API에 코드 반영합니다.
- `POST /api/attendance/{attendanceId}/corrections`는 로그인 학생 본인의 기록만 접수합니다. `requestedStatus`를 생략하면 PRESENT, `reason`을 생략하면 기본 요청 문구를 사용하며 중복 대기 요청을 차단합니다. 결석→지각은 `requestedStatus=LATE`로 명시합니다.
- 정정 허용은 출결과 요청을 같은 트랜잭션에서 잠그고 원본 상태·버전을 재검증합니다. 요청 후 수정·삭제된 출결의 요청은 `INVALIDATED`로 저장하고 승인을 409로 차단합니다. 이미 처리한 요청은 다시 출결을 바꾸지 않습니다. 이 동작은 기존 API에 코드 반영하며 루트 작업에서 검증합니다.
- `DELETE /api/attendance/{attendanceId}`는 `deleted_at`과 증가한 version을 기록하고 대기 요청을 무효화합니다. 삭제 기록은 출결 목록·요약에서 제외합니다. 결석으로 바꾸거나 원본 행을 제거하지 않습니다.
- 명세의 **‘파일(소명자료) 제출’은 요청 정보 저장 버튼**입니다. 실제 파일 선택·전송·저장과 소명 파일용 DB는 범위에서 제외합니다.
- 기존 출결 저장 API는 `unitType`·`sessionNo`로 일자/회차를 구분하며 기본값 DATE/0으로 기존 날짜 요청과 호환합니다. 여섯 정정 전환의 직접 변경은 차단하고 승인 경로를 사용합니다. 일반 수정은 version을 올리고 대기 요청을 무효화합니다. 원본 스냅샷이 NULL인 기존 요청도 승인 재검증에서 INVALIDATED로 처리합니다. 프런트엔드는 회차 선택과 무효화·409 안내를 연결해야 합니다.

### 과제와 첨부

- `author_id`는 등록자이며 담당 강사는 `instructor_id`·`instructor_name`으로 표현합니다. 둘이 동일하다고 가정하지 않습니다. 기존 과제 생성·수정 API의 `instructorId`·`instructorName` 처리와 목록·상세 조회에 코드 반영합니다.
- 과제 삭제는 `deleted_at`을 기록하며 제출물·평가·첨부 이력을 보존합니다. 목록·상세·대시보드와 과제에 연결된 제출·파일 경로에서 삭제 과제를 제외하는 동작을 코드 반영합니다.
- `submissions.submitted_at`은 **마지막으로 성공한 제출/파일 업로드 시각**입니다. 제출 파일 저장과 메타데이터 저장이 성공한 뒤 갱신합니다. 파일이 이미 있는 제출물은 메모·URL만 바꾸면 최종 업로드 시각을 유지합니다. 실패한 업로드는 제출 시각을 바꾸지 않고 과제 안내 첨부 업로드도 학생 제출 시각을 바꾸지 않습니다.
- 학생 조회는 기존 API의 로그인 `user_id` SQL 조건과 첨부 조회·다운로드 소유자 검사를 재사용합니다. 다른 학생의 제출 시간·제출물·점수·강사 평가·첨부파일은 학생에게 노출하지 않습니다. 기존 평가 경로는 기수 강사/운영 권한과 배점을 확인합니다.
- V4는 `assignments.subject`와 `submissions.deployment_url`만 추가합니다. 과목은 단순 표시 문자열이며 별도 분류 관리 기능은 만들지 않습니다. 제출 팝업의 `repoUrl`, `deployUrl`, `submitMemo`는 각각 `repositoryUrl`, `deploymentUrl`, `body`의 별칭입니다. 별칭과 원래 필드를 함께 보내면 값이 같아야 합니다.
- 같은 PUT 제출 경로에서 JSON과 multipart/form-data를 지원합니다. 파일을 함께 보낼 때는 `file` 파트를 사용하고 본문·첨부를 한 트랜잭션으로 저장합니다. 기존 제출 생성 후 파일 업로드 방식도 유지합니다. 제출 첨부 한도는50MiB, 공지/과제 안내 첨부는10MiB입니다.
- 기존 마감 후 제출 제한과 검토 후 수정 잠금은 유지합니다. 프런트엔드는 실제 파일 선택·저장 호출과 재조회, 카드의 `canSubmit`/`canResubmit`/`feedback` 표시를 연결해야 합니다. 제출 파일 목록은 `mySubmission.files`, 제출물 목록의 `files`, `GET /api/submissions/{submissionId}/files`에서 조회하고 `downloadUrl`에 Bearer 인증으로 접근합니다.

### 영상과 평가 복습

- 과목→단원→핵심 개념은 기수에 속합니다. 주제, 영상, 평가 문제를 연결할 때 같은 기수인지 서비스에서 검증합니다. FK만으로 기수 일치가 보장되지는 않습니다.
- YouTube URL은 명세의 `youtube.com/watch?v=…`, `youtu.be/…` 형식을 검사합니다. 영상의 `PUBLIC` 표시는 수강생 목록에서의 공개 상태이며, 다른 기수나 외부 인터넷에 대한 열람 허용을 뜻하지 않습니다.
- 영상은 날짜·교시로 조회하고 소단원은 `start_seconds`로 이동합니다. 별도 영상 파일로 분할하지 않습니다. 시작 시점이 없으면 0초부터 재생합니다.
- 문제의 topic이 NULL이면 ‘단원 미분류’, 연결이 없으면 ‘연결된 복습 영상 없음’을 표시할 수 있도록 응답을 구분합니다.
- 평가 정답·해설 공개 전에는 관련 영상 링크도 숨깁니다. 영상 링크 조회에서도 본인 평가 결과·기수 권한을 다시 확인합니다.

### AI 복습

- 공통 `ReviewSession`과 `BlankReview`·`ReviewQuiz`는 공유 PK/FK로 연결됩니다. 생성 트랜잭션에서 부모 mode와 자식 종류가 일치하고 자식이 하나만 존재하도록 보장합니다.
- 주제를 선택하지 않으면 시작하지 않고, 백지 내용이 비면 제출하지 않습니다. 피드백은 잘 이해한 내용·부족하거나 잘못 이해한 내용·추가 복습 권고를 각각 저장합니다.
- 퀴즈는 문제를 한 개씩 생성하고 필수 보기 네 개와 정답 번호를 서버에 저장합니다. 학생 DTO에는 답안 제출 전 정답 번호·해설을 포함하지 않습니다.
- 로그인 소유자와 세션·문제 관계를 검증한 뒤 선택 번호 하나를 저장합니다. 정답·오답 및 해설을 제공한 후 ‘다음 문제’ 동의를 받아 같은 주제의 새 문제를 생성합니다. 거절하면 세션을 종료합니다.
- 동시에 ‘다음 문제’를 요청해 중복 문제가 생성되지 않도록 세션 잠금과 순번 유니크를 함께 사용합니다. AI 실패 시 오류·재시도 상태를 응답하고, AI 키와 토큰은 서버 설정으로 관리합니다.

## 작업 순서

1. 각 담당자는 해당 기능의 `model/`, `repository/` 계약과 DB 제약을 확인합니다.
2. 서비스와 JDBC 저장소 구현을 만들고 소유자·기수·역할 검사를 연결합니다. 미구현 저장소 인터페이스 자체를 Spring Bean으로 등록하지 않습니다.
3. 요청·응답 DTO와 컨트롤러를 구현합니다. 도메인 모델을 그대로 JSON으로 노출하지 않습니다. 회원 비밀번호 해시, 갱신 토큰 해시, 퀴즈 정답, 내부 오류 정보는 응답 대상에서 제외합니다.
4. 기능별 테스트와 기존 회귀 테스트를 통과시킨 뒤 실제 구현된 API만 명세에 추가합니다. DB를 확장할 때 적용된 V1/V2/V3를 수정하지 않고 후속 마이그레이션을 추가합니다.

인증·과정·기수·수강 등록, 일정·로드맵, 스터디, 개인 알림, 첨부파일의 기존 테이블과 모델도 유지합니다. 신규 기능 담당자는 이 공통 기반을 참조하되 다른 담당자의 비즈니스 구현을 대신 추가하지 않습니다.
