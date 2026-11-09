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
      "latestCorrection": null,
      "availableRequestedStatuses": ["LATE", "PRESENT"]
    }
  ]
}
```

- events는 선택 월과 겹치는 기수 교육일정이다. 시작·종료 시각은 UTC ISO 8601이며 한국 시간으로 월 경계를 계산한다. 다른 기수 일정은 포함하지 않는다.
- attendance는 본인의 미삭제 기록만 날짜·회차 순서로 반환한다. 페이지 제한으로 월 중간 데이터가 잘리지 않는다. 미등록 날짜에 결석을 만들어 넣지 않는다.
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

## 범위와 검증

영상 날짜·재생 링크는 별도 강의 다시보기 API로 연결한다. 프론트 코드, 강의 API, DB 구조·마이그레이션은 변경하지 않는다. API 계약은 프론트 담당자에게 전달할 제안이며 화면 연동 합의를 완료했다고 간주하지 않는다.

검증 결과: `./gradlew build` 및 전체 테스트 37개 통과(신규 캘린더 테스트 4개). H2 통합 테스트에서 본인 격리·접근 제어·소명 상태 반영·한국 시간 경계·109개 회차 전체 조회를 확인했다. 실제 MariaDB·브라우저 검증은 미실행이다.
