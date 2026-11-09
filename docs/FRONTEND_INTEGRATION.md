# 프론트엔드 통합 API

2026-11-10 `codex/api-integration` 브랜치는 수강생·관리자 전체 화면 연결에 필요한 서비스를 구현합니다. 기존 74개 작업에 학습 자료·Discord·AI·평가 통계 API를 추가했습니다. 정확한 요청/응답은 [API_SPEC.md](API_SPEC.md), [openapi.json](openapi.json)을 확인하세요. DB 마이그레이션은 추가/수정하지 않습니다.

## 추가한 기능

- 과목·단원·핵심 개념, 강의 영상·챕터의 실제 JDBC CRUD와 기수/운영 권한 검증
- 공개 영상만 학생 조회, HTTPS YouTube ID 형식 검사, 소단원 시작 시각 저장
- 평가 문항의 nullable topicId / chapterId 저장 및 같은 기수 검증. 정답·해설 공개 시점 전에는 복습 링크도 숨김
- 과정/시스템 공지 PUBLIC 공개 조회, 슬러그 검증, Discord 전송 이력·실패 재시도·중복 방지
- 기수 평가 성적 요약과 관리자 회원 목록의 발급자/발급 시각
- 본인 백지/객관식 복습 세션, AI 피드백 저장·실패 보존, 한 번에 한 문제 생성·단일 답안·정답/해설 공개·종료

## 선택 외부 설정

백엔드 환경변수만 사용하며 API 응답과 프론트 번들에는 포함하지 않습니다.

| 변수 | 용도 |
|---|---|
| OPENAI_API_KEY | Responses API 인증 |
| OPENAI_MODEL | 사용할 계정에서 지원하는 모델 ID. 임의 기본값 없음 |
| DISCORD_WEBHOOK_URL | Discord 채널 Webhook |
| PUBLIC_BASE_URL | 공개 공지를 서비스할 프론트 주소 |

AI 키/모델 누락은 503/AI_NOT_CONFIGURED, 생성 실패는 503/AI_REQUEST_FAILED입니다. 백지 복습은 FAILED를 저장하고 같은 세션에서 재시도할 수 있습니다. 외부 모델에 사용자 ID/이름/이메일을 보내지 않습니다.

Discord Webhook 미설정은 FAILED/DISCORD_NOT_CONFIGURED, 명확한 HTTP 거절은 FAILED/DELIVERY_REJECTED로 저장합니다. 네트워크 오류 등 결과가 불명확하면 PENDING/DELIVERY_UNKNOWN으로 남겨 자동 재전송을 차단합니다. 실제 외부 전송 후 DB 장애까지 포함한 분산 exactly-once 보장은 하지 않습니다.

학생 개인 프로필 API는 기존 이름·전화번호·비밀번호를 지원합니다. AWS 계정 발급, 수당 계좌, 자리이탈감지 서비스는 별도 서비스 범위입니다.

## 검증

- `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test`
- 새 PortalIntegrationTests에서 기수 격리·비공개 영상·매핑·해설 공개 시점·외부 설정 누락의 실패 저장·AI 정상 피드백/퀴즈/중복 답안 차단 확인
- 프론트 `scripts/verify-integration.mjs`는 격리된 H2 메모리 서버와 Next.js 프록시를 거쳐 쿠키·인증·CRUD·파일·소명·채점·로그아웃을 검증합니다. 실제 사용자 DB/외부 AI/Discord에는 호출하지 않았습니다.
- 공식 AI 형식: https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses
