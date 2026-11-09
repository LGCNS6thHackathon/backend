# NewLearn LMS Backend

Java 17 · Spring Boot 4.1.2 · Spring Security · JDBC · MariaDB 11.8 · Flyway 기반 LMS 백엔드입니다. 프론트 코드는 포함하지 않습니다.

## 기능명세서 추가 개발 기반

최신 Notion 기능명세서의 26개 항목과 채팅에서 확정한 계정·출결 규칙을 기준으로 **35개 테이블·61개 외래키, Java 도메인 모델과 저장소 인터페이스**를 제공합니다. 기존 JDBC API에는 관리자 계정 발급·일자/회차 출결·소명 처리·과제 담당 강사·논리 삭제·최종 파일 업로드 시각을 반영했습니다. 과제 API는 현재 화면의 본인 제출 카드·저장소/배포 주소·메모·파일·점수·강사 평가를 지원합니다. AI·강의 영상·평가 복습 연결·Discord는 도메인 기반이며 실제 API 구현은 담당자 범위입니다. 구현된 HTTP 작업은 87개입니다.

- [도메인 설계와 이번 작업 범위](docs/foundation/DOMAIN_PLAN.md)
- [기능별 팀 인수인계와 구현 TODO](docs/foundation/TEAM_HANDOFF.md)
- 도메인 코드: `src/main/java/kr/wsts/lms/domain/<기능>/model`, `repository`
- DB 적용: 기존 V1~V3를 보존하고 Flyway V4로 과제의 선택 과목명과 제출 배포 URL을 추가합니다. 전체 스키마 SQL은 새 DB/ERD 가져오기용이며 기존 DB에 중복 실행하지 않습니다.

AI 복습은 공통 세션과 백지 복습·객관식 복습의 공유 PK/FK로 IS-A를 표현합니다. 나머지는 기능에 맞는 FK 관계를 사용합니다. 도메인 기반의 저장소 인터페이스는 아직 Spring Bean이 아니므로 구현체를 만든 후 서비스에 연결하세요. 기존 API 수정 사항과 신규 기능의 남은 작업은 인수인계 문서에서 구분합니다.

## 구현 기능

- 관리자 전용 수강생 계정 발급·발급자 이력, BCrypt 로그인, JWT 접근 토큰, 갱신 토큰 회전·폐기, 내 정보·비밀번호 변경, 계정 권한 관리
- 과정·기수·수강 등록, 기수별 교육생·강사·매니저 권한, 대시보드 집계
- 교육 일정, 단계·모듈 로드맵, 일자/회차 출결·휴가·조퇴, 출결 소명 요청·승인·반려·변경 시 무효화·논리 삭제
- 게시판 분류·검색·페이지, 게시글·댓글 작성/수정/삭제
- 과제 담당 강사·과목명·기한·논리 삭제, 본인 제출 카드·저장소/배포 주소·선택 메모, JSON/multipart 제출·재제출, 제출 파일 재조회·최종 업로드 시각, 강사 점수·피드백, 제출 마감과 검토 후 수정 잠금
- 객관식 평가 문항·선택지, 제한 시간 응시, 답안 저장, 서버 채점, 결과 리포트
- 스터디 생성·수정·가입·탈퇴·정원 제어, 알림 읽음 처리
- 게시글·과제·제출물 첨부 업로드·권한 있는 다운로드·논리 삭제

실제 기존 LMS의 계정이나 데이터를 가져오지 않습니다. 새 MariaDB 데이터베이스에 회원·과정·학습 기록을 생성하는 독립된 백엔드입니다. 강의실·자료실 URL은 기수 설정에 보관하며 외부 LMS·AWS 계정 발급·화상 수업·Discord 서비스와 자동 연동하지 않습니다.

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

문서와 ERD는 실제 코드의 87개 HTTP 작업, V1+V2+V3+V4의 35개 테이블·61개 외래키를 기준으로 생성합니다. ERD Cloud의 공식 가져오기 코드로 JSON을 로컬 검증하며, 이번 파일은 사용자 다이어그램에 직접 업로드하지 않습니다. **기존 다이어그램에 재업로드하면 변경 내용이 반영되지 않으므로 새 빈 다이어그램에서 가져오세요.**

```powershell
python scripts/generate-docs.py
```

## 인증·권한·시간 규칙

공개 API를 제외한 모든 요청에 `Authorization: Bearer <accessToken>`이 필요합니다. 접근 토큰은 15분, 갱신 토큰은 7일입니다. 갱신 토큰은 DB에 해시만 저장하고 한 번 사용하면 교체합니다. 재사용하면 같은 토큰 패밀리를 폐기합니다. 비밀번호·역할·계정 상태를 바꾸면 기존 토큰도 무효화됩니다. 로그아웃 후 기존 접근 토큰은 남은 만료 시간까지 유효하며 갱신은 불가능합니다.

전역 `ADMIN`과 기수별 `INSTRUCTOR`·`MANAGER`·`STUDENT`를 구분합니다. 다른 기수의 리소스와 다른 교육생의 제출물·출결·평가에 접근하지 못하도록 서버에서 확인합니다. 제한된 스터디 정원, 제출물 검토, 평가 응시는 트랜잭션과 행 잠금을 사용합니다.

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

## 검증

총 41개 테스트를 통과했습니다. Spring 테스트36개는 관리자 계정 발급·공개 가입 차단, 토큰 폐기, 기수별 접근, 출결 회차·여섯 정정 전환·동시 요청·무효화·삭제, 과제 담당 강사·화면 카드·주소/메모 저장·multipart 제출·첨부 재조회·최종 업로드 시각·실패 시 DB/파일 롤백, 평가 정답 보호·채점, 파일 접근, 스터디 정원을 검사합니다. 스키마 테스트5개는 V1→V2→V3→V4 데이터 보존, 발급자 자기참조 FK, 출결·AI 퀴즈 제약, 마이그레이션과 통합 SQL의 일치를 검사합니다.

실제 MariaDB 11.8을 사용하는 별도 로컬 서버에서 V4 마이그레이션과31개 API 흐름 검증도 통과했습니다. 과제 화면 폼 저장, 실제 multipart 요청, 제출 파일 재조회·다운로드, 본인 카드 분리, 메모 수정 시각 보존, 실패 롤백과 실제 채점 결과를 포함합니다. 아래 스크립트는 `.env`의 관리자 계정으로 합성 데이터를 생성하며 루프백 주소만 허용합니다.

```powershell
node scripts/verify-backend.mjs http://127.0.0.1:8080
```

기존 적용된 마이그레이션은 수정하지 않고 후속 SQL을 추가하세요. 프론트·배포 환경에 맞춘 도메인·CORS·키·데이터 보존 정책은 운영 설정에서 관리합니다.
