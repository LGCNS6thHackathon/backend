# LMS API 명세

91개 실제 HTTP 작업의 상세 요청·응답 스키마는 [openapi.json](openapi.json)에 있습니다. 실행 중에는 `GET /api-docs`로 동일 명세를 받을 수 있습니다. Swagger Editor 또는 OpenAPI를 지원하는 Postman에 가져올 수 있습니다.

## 공통 규칙

- 기본 주소: `http://localhost:8080`. 운영 주소는 배포 환경에 맞게 교체합니다.
- 인증: 공개 API를 제외하고 `Authorization: Bearer <accessToken>`. 토큰은 응답 JSON으로 반환하며 쿠키를 사용하지 않습니다.
- JSON 요청: `Content-Type: application/json`. 파일은 `multipart/form-data`, 단일 파트 이름은 `file`입니다.
- 응답은 객체·배열을 직접 반환합니다. 목록은 보통 `{items,page,size,total}`이며 `page=0`, `size=20`, 최대100입니다. 일정·출결 기본 크기는50입니다. 로드맵·스터디 회원은 배열입니다.
- 날짜는 `YYYY-MM-DD`, 월은 `YYYY-MM`. 시각은 `2026-11-09T09:00:00+09:00`처럼 오프셋을 반드시 포함합니다. 응답 시각은 UTC입니다. 월 필터·교육 날짜·오늘 기준은 한국 시간입니다.
- PATCH는 제공한 필드만 변경합니다. PUT 출결은 날짜별 생성/갱신, PUT 제출은 본인 제출의 생성/재제출, PUT 평가 답안은 제공 문항만 갱신합니다.
- 생성은201, 일반 조회·수정은200, 삭제·로그아웃·비밀번호 변경은204(본문 없음)입니다. 제출물 PUT은 생성일 때도200입니다.

## 인증과 권한

접근 토큰은15분, 갱신 토큰은7일입니다. 갱신할 때 새 토큰 쌍으로 교체하고 이전 갱신 토큰을 폐기합니다. 사용한 갱신 토큰을 재사용하면 같은 토큰 패밀리를 폐기합니다. 로그아웃은 갱신 토큰 패밀리를 폐기하며 기존 접근 토큰은 남은 유효시간까지 유효합니다. 비밀번호·전역 역할·계정 상태 변경은 모든 기존 토큰을 무효화합니다.

공개 회원가입은 없습니다. 운영자가 전역 ADMIN 권한으로 `POST /api/users`에 등록자의 이메일·초기 비밀번호·이름을 입력해 STUDENT 계정을 발급합니다. 초기 비밀번호 전달은 운영 절차이며 이 API는 메일·문자를 발송하지 않습니다. 발급 후 기수별 수강 등록 API로 배정합니다. 관리자 계정은 `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`를 최초 실행 환경에 지정해 준비합니다. 고정 기본 관리자 계정은 없습니다. 전역 ADMIN은 전체 관리 권한을 가지며, 기수의 INSTRUCTOR/MANAGER 권한은 해당 ACTIVE 수강 등록에서 확인합니다. 전역 INSTRUCTOR 역할만으로 다른 기수를 관리할 수 없습니다.

## 오류

```json
{"code":"INVALID_REQUEST","message":"입력 값을 확인해주세요.","timestamp":"2026-11-09T00:00:00Z"}
```

| HTTP | code 예 | 의미 |
|---|---|---|
| 400 | INVALID_REQUEST | 형식·필수값·기간·선택지 검증 실패 |
| 401 | UNAUTHORIZED | 로그인 필요·만료·인증 실패 |
| 403 | FORBIDDEN | 기수·작성자·스태프 권한 없음 |
| 404 | NOT_FOUND | 존재하지 않거나 숨겨진 리소스 |
| 409 | CONFLICT, DATA_CONFLICT | 중복 등록·종료 상태·참조 무결성 충돌 |
| 413 | FILE_TOO_LARGE | 첨부 요청 크기 제한 초과 |
| 500 | INTERNAL_ERROR | 내부 처리 실패 |

## 엔드포인트

상세한 필수값·길이·범위·enum·응답 필드는 OpenAPI의 각 `requestBody`, `parameters`, `responses`를 기준으로 연결하세요.

### 인증

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| POST | `/api/auth/login` | 로그인 | 200 | 권한: 공개; ACTIVE 계정. |
| POST | `/api/auth/refresh` | 토큰 갱신·회전 | 200 | 권한: 공개; 유효한 갱신 토큰. 한 번 사용한 갱신 토큰은 폐기됩니다. 재사용은 같은 토큰 패밀리를 폐기합니다. |
| POST | `/api/auth/logout` | 로그아웃 | 204 | 권한: 인증된 토큰 소유자. 갱신 토큰 패밀리를 폐기합니다. 이미 발급한 접근 토큰은 남은 유효시간 동안 유효합니다. |

### 회원

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| POST | `/api/users` | 수강생 계정 발급 | 201 | 권한: 전역 ADMIN. 부트캠프 등록자에게 운영자가 계정을 발급합니다. STUDENT로 생성하며 발급자·시각을 DB에 기록합니다. 비밀번호 원문은 응답하지 않습니다. 기수 배정은 기존 수강 등록 API를 사용합니다. |
| GET | `/api/users` | 회원 검색 | 200 | 권한: 전역 ADMIN. |
| GET | `/api/me` | 내 정보 | 200 | 권한: 인증 필요. |
| PATCH | `/api/me` | 내 정보 수정 | 200 | 권한: 인증 필요. |
| PATCH | `/api/me/password` | 비밀번호 변경 | 204 | 권한: 인증 필요. 전체 갱신 토큰과 기존 접근 토큰을 무효화합니다. 다시 로그인해야 합니다. |
| PATCH | `/api/users/{userId}` | 회원 권한·상태 변경 | 200 | 권한: 전역 ADMIN. 마지막 활성 ADMIN의 권한은 제거할 수 없습니다. 변경 시 사용자의 모든 토큰을 무효화합니다. |

### 과정·기수

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/courses` | 과정 목록 | 200 | 권한: ADMIN 전체; 그 외 ACTIVE 등록 과정. |
| POST | `/api/courses` | 과정 생성 | 201 | 권한: 전역 ADMIN. |
| GET | `/api/courses/{courseId}` | 과정 상세 | 200 | 권한: ADMIN 또는 해당 과정 ACTIVE 등록 회원. |
| PATCH | `/api/courses/{courseId}` | 과정 수정 | 200 | 권한: 전역 ADMIN. |
| GET | `/api/cohorts` | 기수 목록 | 200 | 권한: ADMIN 전체; 그 외 본인 ACTIVE 등록 기수. |
| GET | `/api/courses/{courseId}/cohorts` | 기수 목록 | 200 | 권한: ADMIN 전체; 그 외 본인 ACTIVE 등록 기수. |
| POST | `/api/courses/{courseId}/cohorts` | 기수 생성 | 201 | 권한: 전역 ADMIN. endDate >= startDate |
| GET | `/api/cohorts/{cohortId}` | 기수 상세 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. |
| PATCH | `/api/cohorts/{cohortId}` | 기수 수정 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE MANAGER. 기존 로드맵·일정·과제 마감·평가·출결 기록을 포함하는 교육 기간이어야 합니다. |
| GET | `/api/cohorts/{cohortId}/enrollments` | 수강 등록 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. |
| POST | `/api/cohorts/{cohortId}/enrollments` | 수강 등록 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE MANAGER. INSTRUCTOR/MANAGER 배정은 전역 ADMIN만 가능합니다. 중복 등록은409입니다. |
| PATCH | `/api/cohorts/{cohortId}/enrollments/{enrollmentId}` | 수강 등록 수정 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE MANAGER. 기수 MANAGER는 STUDENT 등록만 수정할 수 있습니다. |
| GET | `/api/cohorts/{cohortId}/dashboard` | 대시보드 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 진행률은 달력 날짜 기준. 출석률은 등록된 출결 기록 기준이며 EXCUSED를 출석에 포함합니다. |

### 일정·로드맵

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/cohorts/{cohortId}/events` | 일정 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. |
| POST | `/api/cohorts/{cohortId}/events` | 일정 생성 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. endAt > startAt. 한국 날짜 기준 기수 교육 기간 안에 있어야 합니다. |
| PATCH | `/api/events/{id}` | 일정 수정 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. |
| DELETE | `/api/events/{id}` | 일정 삭제 | 204 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. |
| GET | `/api/cohorts/{cohortId}/roadmap` | 로드맵 조회 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 단계별 modules 배열을 반환합니다. status는 한국의 오늘 날짜로 계산합니다. |
| POST | `/api/cohorts/{cohortId}/roadmap` | 로드맵 단계 생성 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. |
| PATCH | `/api/roadmap/phases/{id}` | 로드맵 단계 수정 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 기존 모듈 기간을 포함해야 합니다. |
| POST | `/api/roadmap/phases/{phaseId}/modules` | 로드맵 모듈 생성 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 기간은 상위 단계 안에 있어야 합니다. |
| PATCH | `/api/roadmap/modules/{id}` | 로드맵 모듈 수정 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. |

### 출결

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/cohorts/{cohortId}/attendance` | 출결 목록·요약 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 수강생은 본인 기록만 조회. summary는 현재 필터 전체의 상태별 건수입니다. |
| PUT | `/api/cohorts/{cohortId}/attendance` | 출결 생성·갱신 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. DATE는 sessionNo=0, SESSION은 양수입니다. 미래 출결 불가. ABSENT는 입퇴실 시각 불가. 퇴실은 입실 이후24시간 이내. 같은 회원·날짜·회차는 갱신합니다. 소명 허용 대상 전환을 직접 수정으로 우회할 수 없습니다. 변경 시 version을 증가시키고 대기 요청을 무효화합니다. |
| DELETE | `/api/attendance/{attendanceId}` | 출결 논리 삭제 | 204 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. deletedAt과 변경 version을 기록하고 대기 중인 정정 요청을 INVALIDATED로 처리합니다. 삭제 기록은 출결 조회·대시보드에서 제외합니다. |
| POST | `/api/attendance/{attendanceId}/corrections` | 출결 정정 요청 | 201 | 권한: 해당 출결 본인. ABSENT→LATE/PRESENT, LATE/EXCUSED/LEAVE/EARLY→PRESENT만 허용합니다. 목표 상태를 생략하면 PRESENT, 사유를 생략하면 기본 소명 문구를 사용합니다. 원본 상태·version을 저장하며 접수 시 출결을 바꾸지 않습니다. PENDING 중복 불가. 파일은 받지 않습니다. |
| GET | `/api/cohorts/{cohortId}/attendance/corrections` | 출결 정정 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 수강생은 본인 요청만 조회합니다. |
| PATCH | `/api/attendance/corrections/{id}` | 출결 정정 승인·반려 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. PENDING만 처리 가능. 원본 상태·version·삭제 여부를 재검증합니다. 달라졌다면 INVALIDATED를 저장하고409를 반환합니다. 승인과 실제 출결 변경은 한 트랜잭션입니다. |

### 게시판

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/cohorts/{cohortId}/posts` | 게시글 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. |
| POST | `/api/cohorts/{cohortId}/posts` | 게시글 작성 | 201 | 권한: 전역 ADMIN. 관리자만 작성 가능. 공지 작성 시 기수 회원에게 알림 생성. |
| GET | `/api/posts/{postId}` | 게시글 상세 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 조회 시 views가 증가합니다. |
| PATCH | `/api/posts/{postId}` | 게시글 수정 | 200 | 권한: 전역 ADMIN. 생략한 필드는 유지하고 빈 값은 거부합니다. |
| DELETE | `/api/posts/{postId}` | 게시글 삭제 | 204 | 권한: 전역 ADMIN. 논리 삭제합니다. |
| GET | `/api/admin/cohorts/{cohortId}/posts` | 관리자 게시글 목록 | 200 | 권한: 전역 ADMIN. 기존 사용자 게시판과 동일 데이터를 사용합니다. |
| POST | `/api/admin/cohorts/{cohortId}/posts` | 관리자 게시글 작성 | 201 | 권한: 전역 ADMIN. 기존 사용자 게시판과 동일 데이터를 사용합니다. |
| GET | `/api/admin/posts/{postId}` | 관리자 게시글 상세 | 200 | 권한: 전역 ADMIN. 기존 사용자 게시판과 동일 데이터를 사용합니다. |
| PATCH | `/api/admin/posts/{postId}` | 관리자 게시글 수정 | 200 | 권한: 전역 ADMIN. 기존 사용자 게시판과 동일 데이터를 사용합니다. |
| DELETE | `/api/admin/posts/{postId}` | 관리자 게시글 삭제 | 204 | 권한: 전역 ADMIN. 기존 사용자 게시판과 동일 데이터를 사용합니다. |
| GET | `/api/posts/{postId}/comments` | 댓글 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. |
| POST | `/api/posts/{postId}/comments` | 댓글 작성 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 게시글 작성자에게 알림. 본인 댓글은 알림 생략. |
| PATCH | `/api/comments/{commentId}` | 댓글 수정 | 200 | 권한: 댓글 작성자 또는 기수 스태프. |
| DELETE | `/api/comments/{commentId}` | 댓글 삭제 | 204 | 권한: 댓글 작성자 또는 기수 스태프. 논리 삭제합니다. |

### 과제

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/cohorts/{cohortId}/assignments` | 과제 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. |
| POST | `/api/cohorts/{cohortId}/assignments` | 과제 생성 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 담당 강사 ID는 해당 기수의 ACTIVE INSTRUCTOR 등록이어야 합니다. ID 없이 강사 이름만 저장할 수 있습니다. 등록자와 담당 강사는 별개입니다. |
| GET | `/api/assignments/{id}` | 과제 상세·내 제출·피드백 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. |
| PATCH | `/api/assignments/{id}` | 과제 수정 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 이미 부여한 점수보다 낮은 maxScore로 변경할 수 없습니다. |
| DELETE | `/api/assignments/{id}` | 과제 논리 삭제 | 204 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 제출물·첨부 기록을 보존하고 과제를 논리 삭제합니다. 삭제 후 목록·상세·제출·평가·첨부 접근에서 제외합니다. |
| GET | `/api/assignments/{assignmentId}/submissions` | 제출물 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 수강생은 본인 제출만, 스태프는 전체 제출을 조회합니다. |
| POST | `/api/assignments/{assignmentId}/submissions` | 과제 최초 제출 | 201 | 권한: 해당 기수 ACTIVE 등록 회원. 중복 제출은409. 기한 전만 가능합니다. |
| PUT | `/api/assignments/{assignmentId}/submission` | 내 과제 제출·재제출 | 200 | 권한: 해당 기수 ACTIVE 등록 회원. 없으면 생성, 있으면 수정. 기한 만료 또는 채점 완료 시409. |
| PATCH | `/api/submissions/{submissionId}/review` | 과제 채점·피드백 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 피드백을 생성하거나 갱신하고 제출자에게 알림을 생성합니다. |

### 평가

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/cohorts/{cohortId}/assessments` | 평가 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 수강생에게 DRAFT는 노출하지 않습니다. |
| POST | `/api/cohorts/{cohortId}/assessments` | 평가 초안 생성 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. |
| GET | `/api/assessments/{id}` | 평가 상세 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 학생에게 DRAFT는404. questions는 스태프 또는 본인 응시 시작 후 제공됩니다. |
| PATCH | `/api/assessments/{id}` | 평가 수정·게시·종료 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 게시에는 유효한 문항이 필요합니다. 응시 후 일정·시간·영역 변경과 DRAFT 전환 불가. CLOSED 재개 불가. |
| DELETE | `/api/assessments/{id}` | 평가 초안 삭제 | 204 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 응시 기록이 없는 DRAFT만 가능합니다. |
| POST | `/api/assessments/{assessmentId}/questions` | 객관식 문항 생성 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 응시 없는 DRAFT만. 선택지2~10개, 정확히 하나의 정답. |
| PATCH | `/api/assessment-questions/{id}` | 객관식 문항 수정 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 응시 없는 DRAFT만. options 제공 시 선택지를 모두 교체합니다. |
| DELETE | `/api/assessment-questions/{id}` | 객관식 문항 삭제 | 204 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. 응시 없는 DRAFT만. |
| POST | `/api/assessments/{assessmentId}/attempts` | 평가 응시 시작 | 201 | 권한: 해당 기수 ACTIVE 등록 회원. 회원당 한 번. PUBLISHED이면서 시작 시각 이상, 종료 시각 미만. 마감은 startedAt+durationMinutes와 endAt 중 빠른 시각. |
| GET | `/api/assessment-attempts/{id}` | 응시 상세·남은 시간 | 200 | 권한: 응시자 본인 또는 해당 기수 스태프. 시간 경과 자체는 상태를 변경하지 않습니다. 제출 시 만료 여부에 따라 EXPIRED로 확정합니다. |
| PUT | `/api/assessment-attempts/{id}/answers` | 응시 답안 저장 | 200 | 권한: 응시자 본인. 주어진 문항만 생성·갱신하고 나머지 저장 답안은 유지합니다. 중복 문항·다른 평가 선택지는400. 마감 후409. |
| POST | `/api/assessment-attempts/{id}/submit` | 평가 제출·자동 채점 | 200 | 권한: 응시자 본인. 저장한 답안을0~100점으로 정규화. 마감 후에는 EXPIRED로 저장 답안을 채점. 재호출은 기존 결과 반환. 정답·해설 공개 시점은 Question 스키마 참조. |
| GET | `/api/cohorts/{cohortId}/assessment-report` | 평가 결과·영역별 통계 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 완료·만료 응시만 집계. 본인 또는 스태프가 지정한 회원. cohortAverage는 동일 평가의 기수 평균입니다. |

### 스터디

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/cohorts/{cohortId}/study-groups` | 스터디 목록 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. |
| POST | `/api/cohorts/{cohortId}/study-groups` | 스터디 개설 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 상태 OPEN. 개설자는 자동으로 첫 회원이 됩니다. |
| GET | `/api/study-groups/{groupId}` | 스터디 상세 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. memberCount와 본인 joined를 포함합니다. |
| PATCH | `/api/study-groups/{groupId}` | 스터디 수정 | 200 | 권한: 운영자 또는 기수 스태프. 현재 회원 수보다 작은 정원은409입니다. |
| DELETE | `/api/study-groups/{groupId}` | 스터디 삭제 | 204 | 권한: 운영자 또는 기수 스태프. 참여 기록도 삭제합니다. |
| POST | `/api/study-groups/{groupId}/members/me` | 스터디 참여 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. 중복·모집 종료·정원 초과는409. 동시 참여는 부모 행 잠금으로 처리합니다. |
| DELETE | `/api/study-groups/{groupId}/members/me` | 스터디 탈퇴 | 204 | 권한: 본인 참여 회원. 운영자는 탈퇴할 수 없으며 스터디 삭제를 사용합니다. |
| GET | `/api/study-groups/{groupId}/members` | 스터디 회원 조회 | 200 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE 회원. |

### 알림

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/notifications` | 내 알림 목록 | 200 | 권한: 본인. |
| PATCH | `/api/notifications/{id}/read` | 알림 읽음 처리 | 200 | 권한: 본인. 다른 사용자의 알림은404입니다. 이미 읽은 시각은 유지합니다. |
| POST | `/api/notifications/read-all` | 전체 알림 읽음 처리 | 200 | 권한: 본인. |

### 첨부파일

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| POST | `/api/posts/{postId}/files` | 첨부파일 업로드 | 201 | 권한: 게시글 작성자 또는 기수 스태프. multipart/form-data의 file 파트로 전송합니다. 파일 이름은 최대255자. 파일 크기 제한은 요청 파싱 시413, 서비스 검증 시400입니다. |
| POST | `/api/assignments/{assignmentId}/files` | 첨부파일 업로드 | 201 | 권한: 전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER. multipart/form-data의 file 파트로 전송합니다. 파일 이름은 최대255자. 파일 크기 제한은 요청 파싱 시413, 서비스 검증 시400입니다. |
| POST | `/api/submissions/{submissionId}/files` | 첨부파일 업로드 | 201 | 권한: 제출자 본인(ADMIN 예외); 기한 전·검토 전. multipart/form-data의 file 파트로 전송합니다. 파일 이름은 최대255자. 파일 크기 제한은 요청 파싱 시413, 서비스 검증 시400입니다. |
| GET | `/api/files/{fileId}` | 첨부파일 메타데이터 | 200 | 권한: 기수 회원; 제출물 첨부는 제출자 또는 기수 스태프. |
| DELETE | `/api/files/{fileId}` | 첨부파일 삭제 | 204 | 권한: 부모 리소스 수정 권한 및 업로더/스태프 권한. 논리 삭제. 제출물 첨부는 기한 전·검토 전만 변경할 수 있습니다. 물리 파일 정리는 별도 운영 정책이 필요합니다. |
| GET | `/api/files/{fileId}/download` | 첨부파일 다운로드 | 200 | 권한: 기수 회원; 제출물 첨부는 제출자 또는 기수 스태프. |

### 상태

| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |
|---|---|---|---|---|
| GET | `/api/health` | 서비스 상태 | 200 | 권한: 공개. |

## 연결 예시

### 로그인 → 기수 → 대시보드

```http
POST /api/auth/login
Content-Type: application/json

{"email":"student@example.com","password":"your-password"}
```

응답의 `accessToken`을 다음 요청의 Bearer 헤더로 전달합니다.

```http
GET /api/cohorts?page=0&size=20
Authorization: Bearer <accessToken>
```

선택한 기수 ID로 `GET /api/cohorts/{cohortId}/dashboard`를 호출합니다. 아직 수강 등록이 없으면 기수 목록이 비어 있으므로 ADMIN/MANAGER가 수강 등록을 먼저 생성해야 합니다.

### 과제 제출 → 첨부 → 피드백

```http
PUT /api/assignments/1/submission
Authorization: Bearer <accessToken>
Content-Type: application/json

{"body":"과제 구현 내용입니다.","repositoryUrl":"https://github.com/example/project"}
```

반환된 제출 ID를 사용해 `POST /api/submissions/{submissionId}/files`에 파일을 첨부합니다. 스태프는 `PATCH /api/submissions/{submissionId}/review`에 `{"score":90,"feedback":"구현과 검증이 완료되었습니다."}`를 전달합니다. 기한이 지나거나 채점되면 재제출·첨부 변경이 제한됩니다.

### 평가 생성 → 게시 → 응시 → 채점

1. 스태프가 평가를 생성합니다(DRAFT).
2. 문항과2~10개 선택지를 등록합니다. 선택지마다 `label`, `isCorrect`를 지정하고 정답은 정확히 하나입니다.
3. `PATCH /api/assessments/{id}`에 `{"status":"PUBLISHED"}`를 보냅니다.
4. 학생이 `POST /api/assessments/{assessmentId}/attempts`로 응시를 시작합니다. 학생당 한 번입니다.
5. `PUT /api/assessment-attempts/{id}/answers`에 `{"answers":[{"questionId":1,"optionId":2}]}`를 보냅니다.
6. `POST /api/assessment-attempts/{id}/submit`로 저장한 답안을 채점합니다. 반복 제출은 같은 결과를 반환합니다.
7. 본인 응시 완료 후 점수·본인 답안의 채점 결과가 보입니다. 정답 선택지·해설은 평가 전체 종료 또는 CLOSED 이후에 공개합니다.

응시 마감은 전체 종료 시각과 개인 제한 시간 중 빠른 시각입니다. 만료 뒤 답안 저장은 거부되고, 제출을 호출하면 기존 저장 답안을 EXPIRED 상태로 채점합니다. 자동 스케줄러로 만료 상태를 확정하지 않으므로 프론트는 `expiresAt`, `remainingSeconds`, `expired`를 함께 사용해야 합니다.

## 구현 범위

실제 DB CRUD, JWT 인증·권한, 출결 정정 승인, 과제 제출·채점, 객관식 평가 자동 채점, 파일 저장·권한별 다운로드, 스터디 정원 제어, 서비스 이벤트 알림을 포함합니다. 메일·문자 발송, 비밀번호 찾기, 실시간 강의/채팅, 바이러스 검사, 외부 LMS 데이터 이전은 포함하지 않습니다.

## 재생성 및 검증

```powershell
python scripts/generate-docs.py
```

생성기는 실제 컨트롤러의 메서드·경로와 문서의1:1 일치를 검사하고, path parameter 선언, operationId 중복, 컴포넌트 참조를 검증합니다. 소스 변경 시 계약 정의도 함께 갱신해야 합니다.
