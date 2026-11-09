# 학생 교육일정·출결 캘린더 API

## 조회

`GET /api/cohorts/{cohortId}/attendance/calendar?month=2026-11`

Bearer 인증이 필요하며 해당 기수의 활성 회원만 조회할 수 있다(ADMIN은 기수 접근 허용). ADMIN도 이 엔드포인트에서는 본인의 출결만 받는다. 관리자 전체 목록은 기존 attendance API를 사용한다. month는 필수이며 0001~9998년 YYYY-MM 형식이다.

```json
{
  "month": "2026-11",
  "events": [],
  "attendance": [
    {
      "id": 1,
      "attendanceDate": "2026-11-09",
      "unitType": "SESSION",
      "sessionNo": 1,
      "status": "ABSENT",
      "note": "",
      "checkIn": null,
      "checkOut": null,
      "latestCorrection": null,
      "availableRequestedStatuses": ["LATE", "PRESENT"]
    }
  ]
}
```

- events는 선택 월과 겹치는 기수 교육일정이다. 시작·종료 시각은 UTC ISO 8601이며 한국 시간으로 월 경계를 계산한다. 다른 기수 일정은 포함하지 않는다.
- attendance는 본인의 미삭제 기록만 날짜·회차 순서로 반환한다. 페이지 제한으로 월 중간 데이터가 잘리지 않는다. 미등록 날짜에 결석을 만들어 넣지 않는다.
- checkIn/checkOut은 UTC ISO 8601 문자열 또는 null이다. 화면에서는 Asia/Seoul 시간으로 표시하고, 시간이 없는 경우 `-`로 표시한다.
- latestCorrection은 해당 기록의 최근 소명 1건 또는 null이다. id, status, requestedStatus, reason, reviewNote, createdAt, reviewedAt을 제공한다. 소명 생성일이 아니라 출결 날짜 기준으로 월을 선택한다.
- 대기 소명이 있으면 availableRequestedStatuses는 빈 배열이다. 그 외에는 결석→지각/출석, 지각·공결·휴가·조퇴→출석을 제공한다. 출석은 빈 배열이다.
- 빈 달은 events와 attendance가 각각 빈 배열이다. 본인 출결과 공유 기수 일정의 개수는 서로 다를 수 있다.
- 월별 상태 건수를 일수로 간주하면 안 된다. 일자·회차 기록이 함께 있을 수 있으므로 이 API는 출석 일수나 출석률을 새로 집계하지 않는다.

## 학생 소명 제출

`POST /api/attendance/{attendanceId}/corrections`

```json
{"requestedStatus":"PRESENT","reason":"출결 확인을 요청합니다."}
```

기존 API를 그대로 사용한다. reason은 생략할 수 있고 목표 생략 시 PRESENT이다. 실제 파일은 전송하지 않는다. 본인 기록인지·허용 전환인지·대기 중복인지는 서버가 다시 검증한다. availableRequestedStatuses는 화면 안내이며 제출 시점의 권한이나 상태를 보장하지 않는다.

제출 성공 후 캘린더를 다시 조회하면 PENDING 상태가 표시된다. 관리자 승인 이후 다시 조회하면 새 출결 상태가 반영된다. 409이면 서버 안내를 표시하고 재조회한다. 관리자 요청 확인·승인 API는 기존 계약을 유지한다.

## 현재 프론트 연결 시 확인할 사항

- 소명 버튼은 checkIn 유무가 아니라 availableRequestedStatuses가 비어 있는지로 제어한다. 결석은 입실 시각이 없어도 정정 요청이 가능하다.
- 결석→지각도 지원하려면 requestedStatus를 선택하게 한다. 생략은 PRESENT이므로 선택 없이 제출하면 출석으로 요청된다.
- 사유를 입력한다면 공백 제거 후 5~2000자 조건을 맞춘다. 사유 생략 시 기본 문구를 사용한다.
- 관리자 출결 PUT의 checkIn/checkOut은 생략하면 기존 값 유지, 명시적 null이면 해당 값 삭제이다. 생성 시 생략은 null이다. 입실을 지우면서 퇴실만 남기는 요청은 400이다. 기존 입퇴실이 있는 기록을 ABSENT로 바꿀 때는 두 값을 모두 null로 명시한다.
- 관리자 수정 폼의 학생·날짜·회차는 기록을 찾는 키다. 이를 변경한 PUT은 다른 기록을 생성·갱신할 수 있으므로 기존 기록 수정에서는 이 항목들을 고정한다. 이를 ID 기준 이동 기능으로 간주하지 않는다.
- 일정의 강사 이름·ZEP 링크 등은 이 응답에 새로 추가하지 않았다. 위치·설명·시각은 events로, 부가 정보는 기존 기수 설정 또는 다른 담당 계약으로 연결할지 확인한다.

## 범위와 검증

영상 날짜·재생 링크는 별도 강의 다시보기 API로 연결한다. 프론트 코드, 강의 API, DB 구조·마이그레이션은 변경하지 않는다. API 계약은 프론트 담당자에게 전달할 제안이며 화면 연동 합의를 완료했다고 간주하지 않는다.

검증 결과: `./gradlew build` 및 전체 테스트 37개 통과(신규 캘린더 테스트 4개). H2 통합 테스트에서 본인 격리·접근 제어·소명 상태 반영·한국 시간 경계·109개 회차 전체 조회를 확인했다. 실제 MariaDB·브라우저 검증은 미실행이다.

2026-11-10 입퇴실 보완: 수정 전 신규 테스트 3개의 실패로 문제를 재현한 뒤 수정했다. `./gradlew build --no-watch-fs` 및 전체 테스트 43개 통과(실패·오류·건너뜀 0). 이번 변경의 MariaDB·브라우저 검증은 미실행이다.

2026-11-10 후속 실제 DB 검증: MariaDB 13.0.2에서 입퇴실 UTC 조회, 생략 시 보존, 한 필드만 수정·삭제, 명시적 삭제 후 결석 변경, 잘못된 변경 거부 시 원본·버전 보존까지 확인했다. 기존 출결 흐름을 포함해 `scripts/verify-attendance.mjs` 22개 통과. MariaDB 11.8·브라우저 UI는 미검증이다.
