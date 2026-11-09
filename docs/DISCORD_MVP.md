# Discord 공지 MVP

기존 `POST /api/cohorts/{cohortId}/posts`로 `COURSE` 또는 `SYSTEM` 게시글을 먼저 저장합니다. 저장만으로 Discord에 전송하지 않습니다. 관리자 화면의 **Discord 전송** 버튼이 아래 API를 호출할 때 제목과 본문 전문을 한 번 전송합니다.

| 메서드 | 경로 | 결과 |
| --- | --- | --- |
| `POST` | `/api/posts/{postId}/discord-delivery` | 단건 전송 또는 기존 전송 결과 반환 |
| `GET` | `/api/posts/{postId}/discord-delivery` | 단건 전송 결과 조회 |
| `GET` | `/api/cohorts/{cohortId}/discord-deliveries` | 게시글 ID별 전송 상태 조회 |

세 API 모두 관리자 JWT가 필요합니다. 단건 응답의 `status`는 `PENDING`, `SENT`, `FAILED`이며 `errorCode`가 `UNCERTAIN`이면 Discord 채널을 직접 확인해야 합니다. 목록 상태는 이 경우 `UNKNOWN`을 반환합니다. 실패한 전송은 자동 재시도하지 않습니다.

서버 환경 변수 `DISCORD_WEBHOOK_URL`에 기존 Discord 웹후크 URL을 설정합니다. 값은 브라우저, Git, DB, 로그에 넣지 않습니다. 멘션은 차단하며 메시지가 2000자를 넘으면 전송 전에 거부합니다. Discord URL이 반환한 메시지 ID와 전송 상태만 DB에 보관합니다.

이 브랜치는 `develop`의 기존 게시판·보안·설정 파일을 수정하지 않고, `domain/announcement`의 신규 구현 파일과 이 문서만 추가합니다. 프론트엔드는 기존 게시글 저장 API와 위 전송 API를 연결해야 실제 버튼 전송이 됩니다.
