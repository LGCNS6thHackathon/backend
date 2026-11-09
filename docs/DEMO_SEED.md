# 시연 데이터 입력 (demo-seed)

강의 다시보기·역량평가 복습 연결 시연에 필요한 데이터를 로컬 DB에 한 번에 넣습니다.

## 들어가는 데이터 (`demo-data/`)

| 파일 | 내용 |
|---|---|
| `lecture_videos.tsv` | 2026-07-28 ~ 10-29 강의 다시보기 YouTube 영상 230개 (날짜·교시·영상 ID) |
| `lms_exam_158_pre.json` | 2차 사전평가 60문항 (MSA 기술 I·II) — **저장소에 없음, 따로 받아서 넣기** |
| `lms_exam_159_post.json` | 2차 사후평가 80문항 (MSA 기술 I·II) — **저장소에 없음, 따로 받아서 넣기** |
| `review_links.json` | 소단원 62개(시작 시각)와 문항 124개 직접 연결 (연결 규칙: docs/REVIEW_LINK_RULES.md) |

- 평가 문항 파일 2개는 운영 LMS 역량평가 문제를 옮긴 팀 내부 시연용 사본이라 **Git에 올리지 않습니다**(`.gitignore`). 김우석에게 받아 `demo-data/`에 넣으세요. 응시자 답안·점수·계정 정보는 들어 있지 않습니다.
- 두 파일이 없으면 실행 시 `No lms_exam_*.json` 오류로 멈춥니다.
- 소단원 시작 시각은 강의 자막에서 찾은 값입니다. `"verified": true`인 5개는 영상·자막으로 확인했고 나머지는 후보값입니다.

## 실행

`.env`(Git 제외)에 아래 값을 넣습니다. 값은 각자 정합니다.

```properties
JWT_SECRET=32바이트 이상 임의 문자열
BOOTSTRAP_ADMIN_EMAIL=admin@demo.test
BOOTSTRAP_ADMIN_PASSWORD=8자 이상
app.demo-seed.student-password=8자 이상
```

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local,demo-seed'
```

로그에 `[demo-seed] cohort=… videos=230 questions=140 linkedQuestions=124`가 나오면 완료입니다. 이후에는 `demo-seed` 없이 실행해도 데이터가 유지됩니다(로컬 H2: `.data/`). 같은 시연 기수가 이미 있으면 다시 넣지 않습니다. 처음부터 다시 넣으려면 서버를 끄고 `.data/`를 지운 뒤 실행합니다.

MariaDB에 넣을 때는 프로필을 `mariadb,demo-seed`로 바꿉니다.

## 결과

- 시연 기수 `AM Inspire Camp 6기 (시연)`, 시연 학생 `student@demo.test`(비밀번호는 `.env` 값)
- 평가 2개는 종료(CLOSED) 상태, 시연 학생의 응시 기록은 **가상**이며 복습 링크가 있는 문항 중 번호가 3의 배수인 것만 오답으로 저장
- 확인 예: 사후평가 51번(Circuit Breaker) → 2026-10-16 1교시 3:32부터 재생
