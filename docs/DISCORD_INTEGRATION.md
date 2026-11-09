# Discord 공지 발송 연결 지점

이 기능은 **이미 게시된 공지** 한 건을 Discord에 한 번 발송합니다. 공지 CRUD, 공개 전환, 공개 페이지는 게시판 담당자가 구현합니다. 현재 연결 계약은 임시이며 담당자 코드가 들어오면 아래 두 지점만 맞춥니다.

1. `PublishedNoticeSource.find`: `posts.id`, `category=COURSE/SYSTEM`, `visibility=PUBLIC`, `public_slug`, `deleted_at`을 읽습니다. 게시판 담당자의 게시 상태·필드명이 확정되면 이 조회만 바꿉니다.
2. `PublishedNoticeSource`의 공개 URL: `DISCORD_PUBLIC_BASE_URL/public/notices/{public_slug}`를 사용합니다. 실제 공개 상세 경로가 확정되면 이 경로만 바꿉니다. 발송 전에 외부에서 링크가 열리는지 확인해야 합니다.

임시 관리자 API는 `POST /api/posts/{postId}/discord-delivery`와 결과 조회용 `GET /api/posts/{postId}/discord-delivery`입니다. 둘 다 JWT 관리자 권한이 필요합니다. 응답은 `id`, `postId`, `status`(`PENDING`, `SENT`, `FAILED`), `externalMessageId`, `errorCode`입니다. 화면 담당자는 실제 관리자 UI 경로가 정해지면 이 API만 연결하면 됩니다.

서버 설정 `DISCORD_WEBHOOK_URL`에는 **기존 URL**을 그대로 사용합니다. 값은 Git, DB, API 응답, 로그에 넣지 않습니다. `DISCORD_PUBLIC_BASE_URL`은 실제 공개 사이트의 HTTPS origin으로 설정합니다. 두 설정 중 하나라도 없으면 전송하지 않습니다.

메시지는 제목, **본문 전문**, 공개 링크를 하나의 Discord `content`로 보냅니다. 축약하지 않으며 전체가 Discord 길이 제한을 넘으면 400을 반환합니다. `notice_deliveries`의 공지별 고유 키로 중복 클릭·동시 요청을 막습니다. 전송 결과가 불명확하거나 실패한 기록은 자동 재발송하지 않습니다. 재시도 기능과 시도별 이력은 오늘 범위에 포함하지 않습니다.
