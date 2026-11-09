# LMS Backend

LGCNS 6기 해커톤 LMS의 Spring Boot 초기 골격입니다. Java 17, Spring Web MVC, Validation, Actuator, Gradle Wrapper를 사용합니다.

## 실행

JDK 17을 설치하고 `JAVA_HOME` 또는 PATH를 설정하세요. 별도 Gradle 설치는 필요하지 않습니다.

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
```

macOS / Linux:

```bash
./gradlew bootRun
```

기본 주소는 `http://localhost:8080`입니다. 처음 실행할 때 Gradle과 의존성을 내려받습니다.

## 빌드 및 테스트

```powershell
.\gradlew.bat clean build
```

macOS / Linux에서는 `./gradlew clean build`를 사용합니다. 실행 파일은 `build/libs/backend-0.0.1-SNAPSHOT.jar`에 생성됩니다.

```bash
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

## 연결 확인

`GET /api/health`

```json
{"status":"UP","service":"lms-backend"}
```

Actuator 상태 확인 주소는 `/actuator/health`입니다. 프론트 개발 서버의 `/api/*` 프록시는 이 서버의 `/api/*`로 연결됩니다.

## 환경 설정

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 서버 포트 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | 허용할 프론트 출처, 쉼표로 구분 |

`.env.example`은 환경 변수 예시입니다. Spring Boot가 `.env` 파일을 자동으로 읽지는 않으므로 터미널이나 IDE 실행 설정에 등록하세요. 로컬 전용 `application-local.yml`과 실제 비밀정보는 커밋하지 않습니다.

## 패키지 구조

```text
kr.wsts.lms
├── BackendApplication.java
├── global
│   ├── config     # 공통 웹 설정
│   └── health     # 서버 연결 확인 API
└── domain         # 이후 기능별 패키지 추가
```

기능은 `domain/{기능명}/controller`, `service`, `repository`, `dto`, `entity`로 나누어 추가하면 됩니다.

현재는 초기 실행 설정과 연결 확인 API만 있습니다. 회원·로그인·LMS 업무 API·DB 스키마/연결은 구현하지 않았습니다. DB 설정이 없어도 시작할 수 있습니다.
