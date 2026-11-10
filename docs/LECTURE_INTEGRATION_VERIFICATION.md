# 강의영상 관리자·다시보기 통합 검증

검증일: 2026-11-10 (Asia/Seoul)

후속 통합: main을 develop에 합치면서 아래에서 누락으로 기록한 다시보기·복습 GET 7개 계약을 문서 생성기에 추가했습니다. 생성기는 통합 코드의 97개 HTTP 작업과 명세의 일치를 확인합니다. 아래 결과는 최초 영상 통합 검증 시점의 기록입니다.

로컬 전용 `test/lecture-replay-integration` 브랜치에서 검증했습니다. 원격 develop과 PR 브랜치는 변경하지 않았습니다.

## 기준 커밋

- origin/develop: `a5fed515f1dd0ac4fe8849806555139f55d1d5d1`
- PR #13: `38ffd3532d65c1b82b0fadc8b1ce0fa8df9d5920`
- PR #4: `54f95f123110c8ef8eb05b44820cf559fc31c11f`
- 두 PR을 로컬 임시 브랜치에 합치는 과정에서 병합 충돌 없음.

## 결과

- `gradlew.bat clean build`: 성공. 테스트 58개, 실패 0, 오류 0.
- 새 연동 테스트: `LectureVideoIntegrationTests.adminChangesFlowThroughReplayCalendarArchiveAndPlayback`.
- 관리자 비공개 등록 시 다시보기에서 숨김 → 공개 후 날짜·아카이브·날짜별 영상·재생 응답 노출 검증.
- 관리자 제목·YouTube URL 수정 후 다시보기 제목과 재생 영상 ID 반영 검증.
- 다른 기수 회원의 재생 접근 403 검증.
- 비공개 전환 및 논리 삭제 후 캘린더·아카이브·날짜별 목록에서 숨김, 재생 404 및 빈 날짜 안내 검증.
- 기존 평가 복습 링크·권한·게시판·과제·출결·인증 테스트 포함.

## 문서 검증 문제

문서 생성기의 `api_docs()` 경로 검증은 실패했습니다. PR #4의 다음 7개 GET 경로 계약이 생성기에 없습니다. 실행 코드 충돌과는 별개이며, 합친 뒤 문서를 재생성하려면 계약 추가가 필요합니다.

- `/api/cohorts/{cohortId}/lecture-replays/dates`
- `/api/cohorts/{cohortId}/lecture-replays`
- `/api/cohorts/{cohortId}/lecture-replays/recent`
- `/api/cohorts/{cohortId}/lecture-chapters/{chapterId}/playback`
- `/api/cohorts/{cohortId}/lecture-videos/{videoId}/playback`
- `/api/assessments/{assessmentId}/review-guide`
- `/api/assessments/{assessmentId}/review-recommendations`

## 검증 범위

H2 MariaDB 호환 모드와 MockMvc를 사용하는 실제 Spring 애플리케이션 API 통합 테스트입니다. 실제 MariaDB, 브라우저 iframe 재생, 외부 YouTube 영상 존재·임베드 허용 여부는 검증하지 않았습니다. 두 URL 파서의 허용 범위 차이와 영상 교체 시 기존 소단원 연결 유지 정책도 별도 정리 대상입니다.
