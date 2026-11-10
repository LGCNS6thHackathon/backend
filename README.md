# NewLearn LMS Backend

Java 17 · Spring Boot 4.1.2 · Spring Security · JDBC · MariaDB 11.8 · Flyway 기반 LMS 백엔드입니다. 프론트 코드는 포함하지 않습니다.

## 기능명세서 추가 개발 기반

기능명세서와 확정된 계정·출결 규칙, 2026-11-09 확인한 [프론트 main `c835066`](https://github.com/LGCNS6thHackathon/frontend/tree/c835066)의 학생·관리자 화면을 기준으로 개발합니다. `main`의 과제 화면 지원·미사용 기능 제거와 `develop`의 관리자 게시판·출결 캘린더·영상·평가 복습·Discord API를 통합하여 **31개 테이블·55개 외래키, 97개 HTTP 작업**을 제공합니다. 과제 API는 본인 제출 카드·저장소/배포 주소·메모·파일·점수·강사 평가를 지원합니다. AI 백지 복습·객관식 복습은 도메인 기반 단계이며 실제 AI 요청·API 구현은 별도 작업입니다.

- [도메인 설계와 이번 작업 범위](docs/foundation/DOMAIN_PLAN.md)
- [학생 캘린더 API 연동 계약](docs/attendance/STUDENT_CALENDAR.md)
- [기능별 팀 인수인계와 구현 TODO](docs/foundation/TEAM_HANDOFF.md)
- 도메인 코드: `src/main/java/kr/wsts/lms/domain/<기능>/model`, `repository`
- DB 적용: 기존 V1~V4는 보존합니다. V4는 과제 과목명·제출 배포 URL을 추가했고, V5는 사용하지 않는 스터디·로드맵 테이블 4개와 스터디 알림을 제거합니다. **V5 적용 시 해당 기능의 기존 데이터도 삭제되므로 필요한 기록은 적용 전에 백업하세요.** 전체 스키마 SQL은 새 DB/ERD 가져오기용이며 기존 DB에 중복 실행하지 않습니다.

AI 복습은 공통 세션과 백지 복습·객관식 복습의 공유 PK/FK로 IS-A를 표현합니다. 나머지는 기능에 맞는 FK 관계를 사용합니다. 구현체가 없는 저장소 인터페이스는 아직 Spring Bean이 아니므로 구현체를 만든 후 서비스에 연결하세요. 실제 API 계약은 [API 명세](docs/API_SPEC.md)를 확인하세요.

## 실제 LMS 기반 시연 데이터

[수집 범위와 실행 방법](docs/FULL_DEMO_SEED.md)에 따라 `.env`에 `app.full-demo.password`를 설정한 뒤 `bash scripts/seed-demo.sh mariadb`로 실행합니다. 실제 LMS 화면의 일정·공지·과제·평가 목록과 기존 강의 TSV를 사용합니다. 개인 출결·성적은 복사하지 않으며 출결은 출석 65일·지각 1일·결석 1일의 가상 시연 기록입니다. 추가 가상 과제·평가는 선택 옵션으로 분리했습니다.

## 구현 기능

- 관리자 전용 수강생 계정 발급·발급자 이력, BCrypt 로그인, JWT 접근 토큰, 갱신 토큰 회전·폐기, 내 정보·비밀번호 변경, 계정 권한 관리
- 과정·기수·수강 등록, 기수별 교육생·강사·매니저 권한, 대시보드 집계
- 교육 일정, 일자/회차 출결·휴가·조퇴, 출결 소명 요청·승인·반려·변경 시 무효화·논리 삭제
- 게시판 분류·검색·페이지, 게시글·댓글 작성/수정/삭제
- ADMIN 전용 게시판 관리, 출결 관리·소명 승인, 본인 출결 캘린더와 입퇴실 시각 보존
- 관리자 강의영상 CRUD, 날짜·교시별 다시보기, 소단원 시작 시점 재생, 평가 문항별 복습 영상·오답 추천
- 관리자 버튼 요청에 따른 Discord 공지 단건 발송·결과 조회·중복 발송 방지
- 과제 담당 강사·과목명·기한·논리 삭제, 본인 제출 카드·저장소/배포 주소·선택 메모, JSON/multipart 제출·재제출, 제출 파일 재조회·최종 업로드 시각, 강사 점수·피드백, 제출 마감과 검토 후 수정 잠금
- 객관식 평가 문항·선택지, 제한 시간 응시, 답안 저장, 서버 채점, 결과 리포트
- 개인 알림 조회·읽음 처리
- 게시글·과제·제출물 첨부 업로드·권한 있는 다운로드·논리 삭제

실제 기존 LMS의 계정이나 데이터를 가져오지 않습니다. 새 MariaDB 데이터베이스에 회원·과정·학습 기록을 생성하는 독립된 백엔드입니다. 강의실·자료실 URL은 기수 설정에 보관합니다. Discord는 `DISCORD_WEBHOOK_URL` 설정 후 관리자가 발송 요청할 때 호출합니다. 자세한 범위는 [Discord MVP](docs/DISCORD_MVP.md)를 확인하세요.

### 프론트 기준 제거·유지 범위

- 제거: 학생·관리자 메뉴와 API 어댑터 모두에 없는 스터디 API 8개, 기존 로드맵 API 5개 및 전용 모델·저장소·테이블. 기존 경로는 인증된 요청에도 404를 반환합니다.
- 유지: 홈·캘린더의 일정, 사이드바의 알림, 관리자 평가·문항 관리, 학생 성적 보고서와 과제·출결·게시판·계정 기능.
- 평가 응시·답안·채점은 현재 성적 보고서의 점수를 생성하고 문제별 복습 기능이 응시 완료 여부를 판단하는 공통 기반이므로 유지합니다. 학생 응시 화면을 새로 추가한 것은 아닙니다.
- `learning_subjects/units/topics`는 AI·영상·평가의 학습 분류이며 제거한 로드맵과 다른 도메인입니다. 프론트에 대응하는 신규 AI·영상·Discord 기반도 유지합니다.
- 이번 변경은 미사용 기능 제거입니다. 프론트 코드는 변경하지 않으며, 홈 집계·성적 리포트·Q&A·마이페이지의 추가 화면 데이터 및 실제 API 연결은 별도 작업입니다. 관리자 역할 통합도 이번 범위에 포함하지 않습니다.

## MariaDB로 실행

JDK 17과 Docker가 필요합니다. 이미 MariaDB를 운영하고 있다면 Docker 대신 해당 DB 연결 정보를 입력하세요.

```powershell
Copy-Item .env.example .env
```

`.env`의 DB 비밀번호, 관리자 비밀번호, JWT 비밀키를 본인의 값으로 변경합니다. JWT 키는 최소 32바이트의 충분히 무작위인 값이어야 합니다. `.env`는 Git에서 제외되며 이 프로젝트는 `spring.config.import`로 명시적으로 읽습니다. 값에 따옴표·공백을 넣지 않는 형식을 사용하세요.

```powershell
docker compose up -d db
.\gradlew.bat bootRun --args='--spring.profiles.active=mariadb'
```

macOS / Linux에서는 `./gradlew`를 사용합니다. DB가 준비되면 Flyway가 `db/migration`의 테이블을 생성합니다. 기본 서버는 `http://localhost:8080`, MariaDB는 `127.0.0.1:3306/lms`입니다. 다른 포트에서는 `DB_PORT`와 `DB_URL`을 함께 바꾸세요.

초기 관리자는 `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`를 지정해 준비합니다. **공개 회원가입은 없습니다.** 운영자가 관리자 토큰으로 `POST /api/users`에 부트캠프 등록자의 이메일·초기 비밀번호·이름·선택 전화번호를 입력해 STUDENT 계정을 발급합니다. 비밀번호 원문은 응답하지 않으며 발급자와 UTC 발급 시각을 DB에 기록합니다. 초기 계정 전달은 운영 절차로 진행하고, 발급 후 `POST /api/cohorts/{cohortId}/enrollments`로 기수를 배정합니다. 강사·운영자 권한은 기존 관리자 권한 변경 API로 지정합니다. 기존 계정·최초 관리자의 발급 이력은 추정하지 않고 NULL로 둡니다. 초기 관리자 준비 후 해당 환경 변수를 비우면 이후 시작 시 부트스트랩을 하지 않습니다.

## 로컬 빠른 실행과 테스트

Docker 없이 개발 동작만 확인할 때는 H2의 MariaDB 호환 모드를 사용할 수 있습니다. 운영은 `mariadb` 프로필을 사용하세요.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
.\gradlew.bat clean build
```

로컬 DB는 `.data`, 첨부파일은 `.storage`에 보존됩니다. 운영 배포에서는 `STORAGE_DIRECTORY`를 영속 볼륨에 연결하세요. DB만 백업하면 첨부 원본은 포함되지 않으므로 두 저장소를 함께 백업해야 합니다. H2 테스트는 `test` 프로필과 별도의 메모리 DB를 사용합니다.

실행 파일:

```powershell
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=mariadb
```

## API 명세와 ERD

- [한국어 API 명세](docs/API_SPEC.md)
- [OpenAPI 3.1 JSON](docs/openapi.json): 실행 중 `GET /api-docs`, Swagger Editor·Postman에서 가져오기 가능
- [ERD Cloud 업로드용 JSON](docs/erd/lms.erdcloud.json): **Import → Upload Snapshot**
- [MariaDB SQL](docs/erd/lms-mariadb.sql): 테이블 생성 및 ERD Cloud SQL Import
- [테이블 구조와 가져오기 안내](docs/DATABASE.md)
- [일반 스키마 JSON](docs/erd/lms-schema.json): 자동화 도구용. ERD Cloud에는 위 스냅샷 JSON을 사용하세요.

문서와 ERD는 실제 코드의 97개 HTTP 작업, V1~V5 적용 후 31개 테이블·55개 외래키를 기준으로 생성합니다. ERD Cloud의 공식 가져오기 코드로 JSON을 로컬 검증하며, 이번 파일은 사용자 다이어그램에 직접 업로드하지 않습니다. **기존 다이어그램에 재업로드하면 변경 내용이 반영되지 않으므로 새 빈 다이어그램에서 가져오세요.**

```powershell
python scripts/generate-docs.py
```

## 인증·권한·시간 규칙

공개 API를 제외한 모든 요청에 `Authorization: Bearer <accessToken>`이 필요합니다. 접근 토큰은 15분, 갱신 토큰은 7일입니다. 갱신 토큰은 DB에 해시만 저장하고 한 번 사용하면 교체합니다. 재사용하면 같은 토큰 패밀리를 폐기합니다. 비밀번호·역할·계정 상태를 바꾸면 기존 토큰도 무효화됩니다. 로그아웃 후 기존 접근 토큰은 남은 만료 시간까지 유효하며 갱신은 불가능합니다.

전역 `ADMIN`과 기수별 `INSTRUCTOR`·`MANAGER`·`STUDENT`를 구분합니다. 다른 기수의 리소스와 다른 교육생의 제출물·출결·평가에 접근하지 못하도록 서버에서 확인합니다. 제출물 검토, 평가 응시는 트랜잭션과 행 잠금을 사용합니다.

날짜는 `YYYY-MM-DD`, 시각은 `2026-11-09T09:00:00+09:00`처럼 오프셋이 있는 ISO 8601 형식입니다. DB 시각은 UTC로 저장하고 교육 날짜와 월 필터는 한국 시간 기준입니다. 과제 카드의 `dueDate`·`submissionDate`는 한국 시간 표시용 문자열입니다. 제출물 첨부는 파일당50MiB, 게시글·과제 안내 첨부는10MiB까지이며 원본 이름과 저장 경로를 분리하고 다운로드를 첨부 형식으로 제공합니다.

## 환경 변수

| 변수 | 설명 |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MariaDB JDBC 연결 |
| `DB_PORT`, `DB_ROOT_PASSWORD` | 로컬 Docker MariaDB 설정 |
| `JWT_SECRET` | MariaDB 실행 필수, 최소 32바이트 키 |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | 최초 관리자 준비, 둘 다 지정할 때 적용 |
| `SERVER_PORT` | 기본 8080 |
| `CORS_ALLOWED_ORIGINS` | 기본 localhost:3000 / 127.0.0.1:3000 |
| `STORAGE_DIRECTORY` | 기본 `.storage`, 운영 영속 볼륨 권장 |
| `DISCORD_WEBHOOK_URL` | 관리자 공지 발송용 Discord Webhook URL |

## 검증

출결·소명 전용 실제 서버 검증: [실행 방법 및 결과](docs/attendance/VERIFICATION.md), `scripts/verify-attendance.mjs`. 별도의 일회용 로컬 DB를 사용하며 관리자 자격 증명은 환경 변수로 전달합니다.

통합 후 총 69개 테스트가 계정·권한·게시판·출결 캘린더·소명·과제 제출과 첨부·평가·영상·Discord를 검사합니다. 과제 화면 지원과 출결 ADMIN 제한을 함께 검증하며, 제거된 스터디·로드맵 경로는 404여야 합니다. 스키마 테스트는 V1~V5 업그레이드, 제거 대상 정리와 나머지 데이터 보존, 마이그레이션과 통합 SQL의 일치를 확인합니다. 문서 생성기는 실제 API 97개와 OpenAPI 경로·스키마 참조의 일치를 검증합니다.

개발 DB 대신 격리된 H2 테스트 프로필로 빌드하려면 다음 명령을 사용합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'test'
.\gradlew.bat --no-daemon clean build
Remove-Item Env:SPRING_PROFILES_ACTIVE
```

main의 과제 기능·미사용 기능 제거 작업에서는 별도 MariaDB 11.8 서버의 V4→V5 마이그레이션과 32개 API 흐름 검증을 통과했습니다. 이는 통합 전 검증 기록이며, 제거된 경로·과제 폼·multipart·본인 카드·채점 흐름을 포함합니다. 아래 스크립트는 `.env`의 관리자 계정으로 합성 데이터를 생성하며 루프백 주소만 허용합니다.

```powershell
node scripts/verify-backend.mjs http://127.0.0.1:8080
```

기존 적용된 마이그레이션은 수정하지 않고 후속 SQL을 추가하세요. 프론트·배포 환경에 맞춘 도메인·CORS·키·데이터 보존 정책은 운영 설정에서 관리합니다.
