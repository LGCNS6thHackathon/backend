# 관리자 출결·소명 화면 연동

## 브랜치 관계

백엔드 `feat/attendance-admin-integration`은 미병합 `feat/attendance`의 ADMIN 권한 변경을 포함한다. 후속 PR의 최초 base는 `feat/attendance`로 지정하고 앞 PR 병합 후 `develop`으로 변경한다. 프론트엔드는 별도 저장소의 최신 `main`에서 동일한 작업 브랜치를 생성했다.

## 기존 API 재사용

| 화면 동작 | 요청 |
|---|---|
| 출결 조회 | GET /api/cohorts/{cohortId}/attendance |
| 출결 등록·갱신 | PUT /api/cohorts/{cohortId}/attendance |
| 출결 삭제 | DELETE /api/attendance/{attendanceId} |
| 소명 목록 | GET /api/cohorts/{cohortId}/attendance/corrections |
| 승인·반려 | PATCH /api/attendance/corrections/{id} |

관리자는 ADMIN JWT를 사용한다. 학생 요청은 POST /api/attendance/{attendanceId}/corrections를 사용하며 실제 파일은 전송하지 않는다.

프론트 `/admin`의 연결 설정에서 API 모드·서버 주소·관리자 토큰을 지정한다. 토큰은 메모리에만 보관한다. API 오류에서 Mock 데이터로 대체하지 않는다.

## 프론트 보완

- 소명 목록에 출결 날짜·일자/회차 구분·회차 번호를 표시한다.
- 소명 응답의 attendanceDate를 화면 date 필드로 변환한다.
- 저장·삭제·승인 후 재조회 결과를 화면 목록과 내부 상태에 함께 반영한다.
- 409 발생 시 최신 목록을 읽고 서버 메시지를 표시한다. 이미 처리된 요청을 원본 변경에 의한 무효화로 잘못 안내하지 않는다.
- 승인·반려 처리 중에는 버튼을 비활성화한다.

## 재현 검증

프론트 저장소의 scripts/verify-admin-attendance.mjs는 실제 admin-api.ts를 로드하고 일회용 로컬 백엔드에 HTTP 요청한다. 환경 변수 ATTENDANCE_ADMIN_EMAIL, ATTENDANCE_ADMIN_PASSWORD와 서버 주소를 전달한다. 기수·학생·출결·소명 합성 데이터가 생성되므로 영속 업무 DB에서 실행하지 않는다. 브라우저 UI 검증과 별개의 API 어댑터 검증이다.

## 검증 결과 (2026-11-09)

프론트 실제 어댑터와 ADMIN 전용 변경을 포함한 백엔드를 연결해 H2 메모리 DB에서 검증했다. 합성 기수·학생을 생성하고 출결 저장·날짜/회차 조회, 학생 관리 요청 403, 소명 접수·중복 409, 관리자 승인·반려·이미 처리된 요청 409, 원본 수정 무효화, 삭제 후 목록 제외, 학생 재조회를 확인했다.
프론트 타입 검사·린트·프로덕션 빌드 및 공백 오류 검사가 통과했다. 브라우저 조작과 MariaDB 프로필 검증은 수행하지 않았다. 이번 백엔드 변경은 연동·검증 문서이며 애플리케이션 변경은 앞 브랜치의 ADMIN 권한 수정이다.
