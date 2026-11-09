"""Regenerate API/ERD artifacts from the consolidated schema and API contracts.

Run from the backend root: python scripts/generate-docs.py
No database, credentials, or external service is contacted.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
DDL_PATH = DOCS / "erd/lms-mariadb.sql"
MIGRATION_PATHS = sorted((ROOT / "src/main/resources/db/migration").glob("V*__*.sql"))
DDL = DDL_PATH.read_text(encoding="utf-8")


def split_sql(text):
    chunks, start, depth, quote = [], 0, 0, False
    for i, char in enumerate(text):
        if char == "'":
            quote = not quote
        elif not quote:
            if char == "(": depth += 1
            elif char == ")": depth -= 1
            elif char == "," and depth == 0:
                chunks.append(text[start:i].strip()); start = i + 1
    chunks.append(text[start:].strip())
    return chunks


def camel(text):
    head, *tail = text.split("_")
    return head + "".join(item.title() for item in tail)


def read_tables():
    tables = []
    for match in re.finditer(r"CREATE TABLE (\w+) \((.*?)\n\);", DDL, re.S):
        table = dict(name=match[1], columns=[], primaryKey=[], uniqueKeys=[], foreignKeys=[], checks=[], indexes=[])
        for item in split_sql(match[2]):
            if item.startswith("CONSTRAINT"):
                name, rule = re.match(r"CONSTRAINT (\w+)\s+(.*)", item, re.S).groups()
                fk = re.fullmatch(r"FOREIGN KEY\s*\((.*?)\)\s+REFERENCES (\w+)\((.*?)\)", rule)
                uq = re.fullmatch(r"UNIQUE\s*\((.*?)\)", rule)
                if fk:
                    table["foreignKeys"].append(dict(name=name, columns=split_sql(fk[1]), referencedTable=fk[2], referencedColumns=split_sql(fk[3]), onDelete="RESTRICT", onUpdate="RESTRICT"))
                elif uq: table["uniqueKeys"].append(dict(name=name, columns=split_sql(uq[1])))
                elif rule.startswith("CHECK"): table["checks"].append(dict(name=name, expression=rule[len("CHECK"):].strip().removeprefix("(").removesuffix(")")))
                else: raise ValueError(item)
            else:
                column, sql_type, rest = re.fullmatch(r"(\w+)\s+([A-Z]+(?:\([^)]*\))?)(.*)", item, re.S).groups()
                col = dict(name=column, apiName=camel(column), type=sql_type, nullable="NOT NULL" not in rest and "PRIMARY KEY" not in rest, autoIncrement="AUTO_INCREMENT" in rest)
                default = re.search(r"DEFAULT\s+('[^']*'|\w+)", rest)
                if default: col["defaultSql"] = default[1]
                table["columns"].append(col)
                if "PRIMARY KEY" in rest: table["primaryKey"].append(column)
                if "UNIQUE" in rest: table["uniqueKeys"].append(dict(name=None, columns=[column]))
        for index in re.finditer(r"CREATE INDEX (\w+) ON " + table["name"] + r"\(([^)]*)\);", DDL):
            table["indexes"].append(dict(name=index[1], columns=split_sql(index[2])))
        for check in table["checks"]:
            enum = re.fullmatch(r"(\w+) IN \((.*?)\)", check["expression"])
            if enum:
                next(col for col in table["columns"] if col["name"] == enum[1])["enum"] = re.findall(r"'([^']*)'", enum[2])
        tables.append(table)
    assert len(tables) == 31, f"Unexpected foundation table count: {len(tables)}"
    by_name = {table["name"]: table for table in tables}
    for table in tables:
        for fk in table["foreignKeys"]:
            assert fk["referencedTable"] in by_name
            assert len(fk["columns"]) == len(fk["referencedColumns"])
            assert all(any(c["name"] == column for c in table["columns"]) for column in fk["columns"])
            assert all(any(c["name"] == column for c in by_name[fk["referencedTable"]]["columns"]) for column in fk["referencedColumns"])
    return tables


TABLES = read_tables()
SCHEMAS = {}
PATHS = {}


def string(minimum=None, maximum=None, **kwargs):
    result = dict(type="string", **kwargs)
    if minimum is not None: result["minLength"] = minimum
    if maximum is not None: result["maxLength"] = maximum
    return result


def integer(minimum=None, maximum=None, **kwargs):
    result = dict(type="integer", **kwargs)
    if minimum is not None: result["minimum"] = minimum
    if maximum is not None: result["maximum"] = maximum
    return result


ID = integer(1, format="int64")
DATE = string(format="date")
TIME = string(format="date-time", description="시간대 오프셋 필수. 예: 2026-11-09T09:00:00+09:00. 응답은 UTC.")
BOOL = dict(type="boolean")


def enum(*values, default=None):
    result = string(enum=list(values))
    if default is not None: result["default"] = default
    return result


def nullable(schema):
    return dict(anyOf=[schema, dict(type="null")])


def ref(name): return {"$ref": "#/components/schemas/" + name}
def array(schema, **kwargs): return dict(type="array", items=ref(schema) if isinstance(schema, str) else schema, **kwargs)


def obj(properties, required=(), **kwargs):
    result = dict(type="object", properties=properties, **kwargs)
    if required: result["required"] = list(required)
    return result


def model(name, properties, required=(), **kwargs):
    SCHEMAS[name] = obj(properties, required, **kwargs)
    return name


NAMES = {"users":"User", "courses":"Course", "cohorts":"Cohort", "enrollments":"Enrollment", "schedule_events":"Event", "attendance_records":"Attendance", "attendance_corrections":"AttendanceCorrection", "posts":"Post", "comments":"Comment", "assignments":"Assignment", "submissions":"Submission", "submission_reviews":"SubmissionReview", "assessments":"Assessment", "assessment_questions":"Question", "assessment_options":"Option", "assessment_attempts":"Attempt", "assessment_answers":"Answer", "notifications":"Notification", "files":"File"}


def sql_schema(col):
    kind = col["type"]
    if kind.startswith(("BIGINT", "INT")): result = integer(format="int64" if kind.startswith("BIGINT") else "int32")
    elif kind == "BOOLEAN": result = dict(BOOL)
    elif kind.startswith("DATETIME"): result = dict(TIME)
    elif kind == "DATE": result = dict(DATE)
    else:
        result = string()
        limit = re.search(r"\((\d+)\)", kind)
        if limit: result["maxLength"] = int(limit[1])
    if "enum" in col: result["enum"] = col["enum"]
    return nullable(result) if col["nullable"] else result


def setup_models():
    for table in TABLES:
        if table["name"] not in NAMES: continue
        props = {c["apiName"]:sql_schema(c) for c in table["columns"] if c["name"] not in ("password_hash", "token_version", "stored_name", "provisioned_by", "provisioned_at")}
        model(NAMES[table["name"]], props, props.keys())
        SCHEMAS[NAMES[table["name"]]]["required"] = [k for k in props if k != "deletedAt"]
    for name, props in {
        "File":dict(downloadUrl=string(description="Bearer 인증이 필요한 다운로드 API의 상대 경로")),
        "Cohort":dict(courseTitle=string(), totalHours=integer()),
        "Enrollment":dict(name=string(), email=string(format="email"), phone=nullable(string())),
        "Post":dict(authorName=string(), commentCount=integer(0)),
        "Comment":dict(authorName=string()),
        "Attendance":dict(userId=ID, userName=string()),
        "AttendanceCorrection":dict(userId=ID, userName=string(), attendanceDate=DATE, unitType=enum("DATE","SESSION"), sessionNo=integer(0)),
        "Submission":dict(userName=string(), score=nullable(integer()), feedback=nullable(string()), reviewerId=nullable(ID), reviewerName=nullable(string()), repoUrl=nullable(string(maximum=2000)), deployUrl=nullable(string(maximum=2000)), submitMemo=string(maximum=20000), files=array("SubmissionFile"), fileName=nullable(string()), status=enum("SUBMITTED","REVIEWED")),
        "SubmissionReview":dict(reviewerName=string()),
    }.items(): SCHEMAS[name]["properties"].update(props)
    SCHEMAS["User"]["properties"].update(provisionedBy=nullable(ID),provisionedAt=nullable(TIME))
    # Projection endpoints may omit base-row fields. Distinct detailed schemas document nesting.
    model("FileSummary", {k:SCHEMAS["File"]["properties"][k] for k in ("id","originalName","contentType","sizeBytes","createdAt")}, ["id","originalName","contentType","sizeBytes","createdAt"])
    model("SubmissionFile", dict(**SCHEMAS["FileSummary"]["properties"], downloadUrl=string(description="Bearer 인증이 필요한 다운로드 API의 상대 경로")), SCHEMAS["FileSummary"]["required"] + ["downloadUrl"])
    model("PostListItem",{k:v for k,v in SCHEMAS["Post"]["properties"].items() if k not in ("body","deletedAt","commentCount")},["id","cohortId","authorId","authorName","category","title","pinned","views","createdAt","updatedAt"])
    model("PostDetail",dict(**SCHEMAS["Post"]["properties"],files=array("FileSummary")),SCHEMAS["Post"]["required"] + ["files"])
    for name, removals in {"Question":["explanation"], "Option":["isCorrect"], "Answer":["isCorrect","earnedPoints"]}.items():
        SCHEMAS[name]["required"] = [k for k in SCHEMAS[name]["required"] if k not in removals]
    SCHEMAS["Question"]["properties"]["options"] = array("Option")
    SCHEMAS["Question"]["required"].append("options")
    SCHEMAS["Question"]["description"] = "수강생은 응시 시작 후 문항을 조회합니다. 해설과 선택지 isCorrect는 본인 응시 종료와 평가 전체 종료(또는 CLOSED)가 모두 충족된 뒤 공개합니다. 스태프는 항상 조회 가능합니다."
    model("AssignmentFeedback", dict(mentor=string(), score=string(description="점수 / 만점. 예: 90 / 100"), comment=string()), ["mentor","score","comment"])
    card = dict(**SCHEMAS["Assignment"]["properties"])
    card.update(subject=string(description="과목 표시명. 미지정 과제는 과제로 표시"), author=string(description="화면 담당 강사 표시명"), dueDate=string(description="한국 시각 yyyy-MM-dd HH:mm"), dDay=string(description="오늘 마감, D-N, 마감 또는 제출완료"), status=enum("미제출","제출완료"), submissionDate=nullable(string(description="본인 최종 제출 시각, 한국 시각 yyyy-MM-dd HH:mm")), feedback=nullable(ref("AssignmentFeedback")), reviewStatus=enum("NOT_SUBMITTED","SUBMITTED","REVIEWED"), canSubmit=BOOL, canResubmit=BOOL, submissionLocked=BOOL, lockReason=nullable(enum("DEADLINE_PASSED","REVIEWED")), mySubmission=nullable(ref("Submission")), myReview=nullable(ref("SubmissionReview")))
    card_required = SCHEMAS["Assignment"]["required"] + ["author","dueDate","dDay","status","submissionDate","feedback","reviewStatus","canSubmit","canResubmit","submissionLocked","lockReason","mySubmission","myReview"]
    model("AssignmentCard",card,card_required)
    model("AssignmentDetail", dict(**card, files=array("FileSummary")), card_required + ["files"])
    model("AssessmentDetail", dict(**SCHEMAS["Assessment"]["properties"], questionCount=integer(0), myAttempt=nullable(ref("Attempt")), questions=array("Question")), SCHEMAS["Assessment"]["required"] + ["questionCount", "myAttempt"])
    SCHEMAS["Attempt"]["properties"].update(expiresAt=TIME, remainingSeconds=integer(0,format="int64"), expired=BOOL, questions=array("Question"), answers=array("Answer"))
    model("Error", dict(code=string(), message=string(), timestamp=TIME), ["code","message","timestamp"])
    model("Health", dict(status=enum("UP"), service=enum("lms-backend")), ["status","service"])
    model("Tokens", dict(accessToken=string(), tokenType=enum("Bearer"), expiresIn=integer(default=900), refreshToken=string(), refreshExpiresIn=integer(default=604800), user=ref("User")), ["accessToken","tokenType","expiresIn","refreshToken","refreshExpiresIn","user"])
    model("AttendanceSummary", dict(trainingDays=integer(0), attendedDays=integer(0), absentDays=integer(0), lateDays=integer(0), earlyDays=integer(0), attendanceRate=dict(type="number",minimum=0,maximum=100)), ["trainingDays","attendedDays","absentDays","lateDays","earlyDays","attendanceRate"])
    model("Dashboard", dict(cohort=ref("Cohort"), enrollment=nullable(ref("Enrollment")), progressPercent=integer(0,100), attendance=ref("AttendanceSummary"), todayEvents=array("Event"), upcomingAssignments=array("Assignment"), notices=array(obj(dict(id=ID,title=string(),category=enum("COURSE","SYSTEM"),pinned=BOOL,createdAt=TIME,authorName=string()),["id","title","category","pinned","createdAt","authorName"])), activeMemberCount=integer(0), unreadNotificationCount=integer(0)), ["cohort","enrollment","progressPercent","attendance","todayEvents","upcomingAssignments","notices","activeMemberCount","unreadNotificationCount"])
    model("AssessmentReport", dict(userId=ID, cohortId=ID, results=array(obj(dict(id=ID,title=string(),domain=string(),startAt=TIME,attemptId=ID,status=enum("SUBMITTED","EXPIRED"),score=integer(0,100),submittedAt=TIME,cohortAverage=dict(type="number")))), domains=array(obj(dict(domain=string(),averageScore=dict(type="number"),completedCount=integer(0))))), ["userId","cohortId","results","domains"])


def query(name, schema, description="", required=False):
    return dict(name=name, **{"in":"query"}, required=required, schema=schema, description=description)


def page_params(size=20):
    return [query("page",integer(0,default=0),"0부터 시작"),query("size",integer(1,100,default=size),"페이지 크기")]


def paged(name):
    key = name + "Page"
    if key not in SCHEMAS: model(key,dict(items=array(name),page=integer(0),size=integer(1,100),total=integer(0,format="int64")),["items","page","size","total"])
    return key


def endpoint(method, path, summary, response=None, body=None, required=(), status=200, tag="", access="인증 필요", description="", params=(), public=False, multipart=False):
    parameters = [dict(name=name, **{"in":"path"},required=True,schema=string() if name=="slug" else ID) for name in re.findall(r"\{(\w+)\}",path)] + list(params)
    operation = dict(summary=summary, description="권한: " + access + (". " + description if description else "."), operationId=method + "_" + re.sub(r"[^a-zA-Z0-9]+","_",path).strip("_"), tags=[tag], responses={str(status):dict(description="성공" if status != 204 else "성공, 응답 본문 없음")})
    if response is not None:
        operation["responses"][str(status)]["content"]={"application/json":dict(schema=ref(response) if isinstance(response,str) else response)}
    if parameters: operation["parameters"] = parameters
    if public: operation["security"] = []
    for code, label in [(400,"입력 검증 실패"),(401,"인증 실패"),(403,"권한 없음"),(404,"리소스 없음"),(409,"중복 또는 상태 충돌")]:
        if public and code==403: continue
        operation["responses"][str(code)] = dict(description=label,content={"application/json":dict(schema=ref("Error"))})
    if body is not None:
        key = operation["operationId"] + "Request"
        if isinstance(body,str): schema=ref(body)
        else:
            model(key,body,required); schema=ref(key)
        operation["requestBody"] = dict(required=True,content={"multipart/form-data" if multipart else "application/json":dict(schema=schema)})
    PATHS.setdefault(path,{})[method]=operation


ADMIN = "전역 ADMIN"
MEMBER = "전역 ADMIN 또는 해당 기수 ACTIVE 회원"
STAFF = "전역 ADMIN 또는 해당 기수 ACTIVE INSTRUCTOR/MANAGER"
MANAGER = "전역 ADMIN 또는 해당 기수 ACTIVE MANAGER"
MONTH = query("month",string(pattern=r"^\d{4}-\d{2}$"),"한국 시간 기준 YYYY-MM")
USER_FILTER = query("userId",ID,"스태프는 특정 회원 선택 가능. 수강생은 본인만 조회")


def auth_catalog_endpoints():
    endpoint("get","/api/health","서비스 상태","Health",public=True,tag="상태",access="공개")
    endpoint("post","/api/users","수강생 계정 발급","User",dict(email=string(maximum=254,format="email"),password=string(8,72,description="운영자가 정한 초기 비밀번호. UTF-8로도 최대72바이트"),name=string(2,80),phone=nullable(string(maximum=30,pattern=r"^(?:|[0-9+() -]{7,30})$",description="빈 값 또는 숫자·공백·+()-로 구성된7~30자"))),["email","password","name"],status=201,tag="회원",access=ADMIN,description="부트캠프 등록자에게 운영자가 계정을 발급합니다. STUDENT로 생성하며 발급자·시각을 DB에 기록합니다. 비밀번호 원문은 응답하지 않습니다. 기수 배정은 기존 수강 등록 API를 사용합니다.")
    endpoint("post","/api/auth/login","로그인","Tokens",dict(email=string(maximum=254,format="email"),password=string(1,72)),["email","password"],public=True,tag="인증",access="공개; ACTIVE 계정")
    token = dict(refreshToken=string(20,512))
    endpoint("post","/api/auth/refresh","토큰 갱신·회전","Tokens",token,["refreshToken"],public=True,tag="인증",access="공개; 유효한 갱신 토큰",description="한 번 사용한 갱신 토큰은 폐기됩니다. 재사용은 같은 토큰 패밀리를 폐기합니다.")
    endpoint("post","/api/auth/logout","로그아웃",body=token,required=["refreshToken"],status=204,tag="인증",access="인증된 토큰 소유자",description="갱신 토큰 패밀리를 폐기합니다. 이미 발급한 접근 토큰은 남은 유효시간 동안 유효합니다.")
    endpoint("get","/api/me","내 정보","User",tag="회원")
    endpoint("patch","/api/me","내 정보 수정","User",dict(name=string(2,80),phone=nullable(string(maximum=30,pattern=r"^(?:|[0-9+() -]{7,30})$",description="빈 값 또는 숫자·공백·+()-로 구성된7~30자"))),tag="회원")
    endpoint("patch","/api/me/password","비밀번호 변경",body=dict(currentPassword=string(1,72),newPassword=string(8,72,description="UTF-8 최대72바이트")),required=["currentPassword","newPassword"],status=204,tag="회원",description="전체 갱신 토큰과 기존 접근 토큰을 무효화합니다. 다시 로그인해야 합니다.")
    endpoint("get","/api/users","회원 검색",paged("User"),tag="회원",access=ADMIN,params=page_params()+[query("q",string(maximum=100,default=""),"이메일 또는 이름")])
    endpoint("patch","/api/users/{userId}","회원 권한·상태 변경","User",dict(role=enum("ADMIN","INSTRUCTOR","STUDENT"),status=enum("ACTIVE","DISABLED")),tag="회원",access=ADMIN,description="마지막 활성 ADMIN의 권한은 제거할 수 없습니다. 변경 시 사용자의 모든 토큰을 무효화합니다.")
    course=dict(title=string(2,160),description=string(maximum=10000,default=""),totalHours=integer(0,10000,default=0))
    endpoint("get","/api/courses","과정 목록",paged("Course"),tag="과정·기수",params=page_params(),access="ADMIN 전체; 그 외 ACTIVE 등록 과정")
    endpoint("post","/api/courses","과정 생성","Course",course,["title"],status=201,tag="과정·기수",access=ADMIN)
    endpoint("get","/api/courses/{courseId}","과정 상세","Course",tag="과정·기수",access="ADMIN 또는 해당 과정 ACTIVE 등록 회원")
    endpoint("patch","/api/courses/{courseId}","과정 수정","Course",course,tag="과정·기수",access=ADMIN)
    cohort=dict(name=string(2,120),startDate=DATE,endDate=DATE,classroomUrl=string(maximum=2000,description="빈 문자열 또는 인증정보 없는 HTTPS URL"),materialsUrl=string(maximum=2000,description="빈 문자열 또는 인증정보 없는 HTTPS URL"))
    for path in ["/api/cohorts","/api/courses/{courseId}/cohorts"]:
        endpoint("get",path,"기수 목록",paged("Cohort"),tag="과정·기수",params=page_params(),access="ADMIN 전체; 그 외 본인 ACTIVE 등록 기수")
    endpoint("post","/api/courses/{courseId}/cohorts","기수 생성","Cohort",cohort,["name","startDate","endDate"],status=201,tag="과정·기수",access=ADMIN,description="endDate >= startDate")
    endpoint("get","/api/cohorts/{cohortId}","기수 상세","Cohort",tag="과정·기수",access=MEMBER)
    endpoint("patch","/api/cohorts/{cohortId}","기수 수정","Cohort",cohort,tag="과정·기수",access=MANAGER,description="기존 일정·과제 마감·평가·출결 기록을 포함하는 교육 기간이어야 합니다.")
    endpoint("get","/api/cohorts/{cohortId}/enrollments","수강 등록 목록",paged("Enrollment"),tag="과정·기수",access=STAFF,params=page_params())
    endpoint("post","/api/cohorts/{cohortId}/enrollments","수강 등록","Enrollment",dict(userId=ID,role=enum("STUDENT","INSTRUCTOR","MANAGER",default="STUDENT")),["userId"],status=201,tag="과정·기수",access=MANAGER,description="INSTRUCTOR/MANAGER 배정은 전역 ADMIN만 가능합니다. 중복 등록은409입니다.")
    endpoint("patch","/api/cohorts/{cohortId}/enrollments/{enrollmentId}","수강 등록 수정","Enrollment",dict(role=enum("STUDENT","INSTRUCTOR","MANAGER"),status=enum("ACTIVE","WITHDRAWN")),tag="과정·기수",access=MANAGER,description="기수 MANAGER는 STUDENT 등록만 수정할 수 있습니다.")
    endpoint("get","/api/cohorts/{cohortId}/dashboard","대시보드","Dashboard",tag="과정·기수",access=MEMBER,description="진행률은 달력 날짜 기준. 출석률은 등록된 출결 기록 기준이며 EXCUSED를 출석에 포함합니다.")


def learning_endpoints():
    event=dict(title=string(2,160),type=enum("LECTURE","PROJECT","SPECIAL","HOLIDAY"),startAt=TIME,endAt=TIME,location=string(maximum=200,default=""),description=string(maximum=10000,default=""))
    endpoint("get","/api/cohorts/{cohortId}/events","일정 목록",paged("Event"),tag="일정",access=MEMBER,params=page_params(50)+[MONTH])
    endpoint("post","/api/cohorts/{cohortId}/events","일정 생성","Event",event,["title","type","startAt","endAt"],status=201,tag="일정",access=STAFF,description="endAt > startAt. 한국 날짜 기준 기수 교육 기간 안에 있어야 합니다.")
    endpoint("patch","/api/events/{id}","일정 수정","Event",event,tag="일정",access=STAFF)
    endpoint("delete","/api/events/{id}","일정 삭제",status=204,tag="일정",access=STAFF)
    attend_states=enum("PRESENT","LATE","EARLY","ABSENT","EXCUSED","LEAVE")
    attendance_page=paged("Attendance")
    SCHEMAS[attendance_page]["properties"]["summary"] = array(obj(dict(status=attend_states,recordCount=integer(0)),["status","recordCount"]))
    endpoint("get","/api/cohorts/{cohortId}/attendance","출결 목록·요약",attendance_page,tag="출결",access=MEMBER,params=page_params(50)+[MONTH,USER_FILTER],description="수강생은 본인 기록만 조회. summary는 현재 필터 전체의 상태별 건수입니다.")
    endpoint("put","/api/cohorts/{cohortId}/attendance","출결 생성·갱신","Attendance",dict(userId=ID,date=DATE,status=attend_states,unitType=enum("DATE","SESSION",default="DATE"),sessionNo=integer(0,default=0),checkIn=nullable(TIME),checkOut=nullable(TIME),note=string(maximum=1000,default="")),["userId","date","status"],tag="출결",access=STAFF,description="DATE는 sessionNo=0, SESSION은 양수입니다. 미래 출결 불가. ABSENT는 입퇴실 시각 불가. 퇴실은 입실 이후24시간 이내. 같은 회원·날짜·회차는 갱신합니다. 소명 허용 대상 전환을 직접 수정으로 우회할 수 없습니다. 변경 시 version을 증가시키고 대기 요청을 무효화합니다.")
    endpoint("delete","/api/attendance/{attendanceId}","출결 논리 삭제",status=204,tag="출결",access=STAFF,description="deletedAt과 변경 version을 기록하고 대기 중인 정정 요청을 INVALIDATED로 처리합니다. 삭제 기록은 출결 조회·대시보드에서 제외합니다.")
    endpoint("post","/api/attendance/{attendanceId}/corrections","출결 정정 요청","AttendanceCorrection",dict(requestedStatus=enum("PRESENT","LATE",default="PRESENT"),reason=string(5,2000)),status=201,tag="출결",access="해당 출결 본인",description="ABSENT→LATE/PRESENT, LATE/EXCUSED/LEAVE/EARLY→PRESENT만 허용합니다. 목표 상태를 생략하면 PRESENT, 사유를 생략하면 기본 소명 문구를 사용합니다. 원본 상태·version을 저장하며 접수 시 출결을 바꾸지 않습니다. PENDING 중복 불가. 파일은 받지 않습니다.")
    PATHS["/api/attendance/{attendanceId}/corrections"]["post"]["requestBody"]["required"] = False
    endpoint("get","/api/cohorts/{cohortId}/attendance/corrections","출결 정정 목록",paged("AttendanceCorrection"),tag="출결",access=MEMBER,params=page_params(),description="수강생은 본인 요청만 조회합니다.")
    endpoint("patch","/api/attendance/corrections/{id}","출결 정정 승인·반려","AttendanceCorrection",dict(status=enum("APPROVED","REJECTED"),reviewNote=string(maximum=2000,default="")),["status"],tag="출결",access=STAFF,description="PENDING만 처리 가능. 원본 상태·version·삭제 여부를 재검증합니다. 달라졌다면 INVALIDATED를 저장하고409를 반환합니다. 승인과 실제 출결 변경은 한 트랜잭션입니다.")
    assignment=dict(title=string(2,160),description=string(2,20000),dueAt=TIME,maxScore=integer(1,1000,default=100),instructorId=nullable(ID),instructorName=nullable(string(2,80)),subject=nullable(string(maximum=100,description="과목 뱃지에 쓸 단순 표시명. 별도 학습 분류 관리 불필요")))
    endpoint("get","/api/cohorts/{cohortId}/assignments","과제 목록",paged("AssignmentCard"),tag="과제",access=MEMBER,params=page_params()+[query("q",string(maximum=200,default=""),"제목 검색")],description="카드 표시 필드와 로그인 본인의 제출·첨부·평가를 함께 반환합니다. 학생에게 다른 회원의 제출 내역을 노출하지 않습니다.")
    endpoint("post","/api/cohorts/{cohortId}/assignments","과제 생성","Assignment",assignment,["title","description","dueAt"],status=201,tag="과제",access=STAFF,description="담당 강사 ID는 해당 기수의 ACTIVE INSTRUCTOR 등록이어야 합니다. ID 없이 강사 이름만 저장할 수 있습니다. 등록자와 담당 강사는 별개입니다.")
    endpoint("get","/api/assignments/{id}","과제 상세·내 제출·피드백","AssignmentDetail",tag="과제",access=MEMBER,description="files는 과제 안내 첨부, mySubmission.files는 본인 제출 첨부입니다. feedback은 실제 채점 전 null입니다.")
    endpoint("patch","/api/assignments/{id}","과제 수정","Assignment",assignment,tag="과제",access=STAFF,description="이미 부여한 점수보다 낮은 maxScore로 변경할 수 없습니다.")
    endpoint("delete","/api/assignments/{id}","과제 논리 삭제",status=204,tag="과제",access=STAFF,description="제출물·첨부 기록을 보존하고 과제를 논리 삭제합니다. 삭제 후 목록·상세·제출·평가·첨부 접근에서 제외합니다.")
    submission=dict(body=nullable(string(maximum=20000,description="제출 메모. 저장소 URL이 있으면 비워도 됨")),repositoryUrl=nullable(string(maximum=2000,description="빈 문자열 또는 인증정보 없는 HTTPS URL")),deploymentUrl=nullable(string(maximum=2000,description="선택 배포 URL. 빈 문자열 또는 인증정보 없는 HTTPS URL")),repoUrl=nullable(string(maximum=2000,description="화면 필드명. repositoryUrl의 별칭")),deployUrl=nullable(string(maximum=2000,description="화면 필드명. deploymentUrl의 별칭")),submitMemo=nullable(string(maximum=20000,description="화면 필드명. body의 별칭")))
    endpoint("get","/api/assignments/{assignmentId}/submissions","제출물 목록",paged("Submission"),tag="과제",access=MEMBER,params=page_params(),description="수강생은 본인 제출만, 스태프는 전체 제출을 조회합니다. 제출 파일·다운로드 경로·리뷰어 이름을 함께 반환합니다.")
    endpoint("post","/api/assignments/{assignmentId}/submissions","과제 최초 제출","Submission",submission,status=201,tag="과제",access="해당 기수 ACTIVE 등록 회원",description="저장소 URL 또는 제출 메모 중 하나는 필요합니다. 중복 제출은409. 기한 전만 가능합니다.")
    endpoint("put","/api/assignments/{assignmentId}/submission","내 과제 제출·재제출","Submission",submission,tag="과제",access="해당 기수 ACTIVE 등록 회원",description="없으면 생성, 있으면 수정. JSON 또는 multipart/form-data를 받습니다. multipart의 file은 선택이며 내용·첨부를 하나의 트랜잭션으로 저장합니다. 생략한 필드는 기존값을 보존하고 명시적 null은 빈 값으로 지웁니다. canonical/별칭을 함께 보냈을 때 공백 제거 후 다르면400입니다. 저장소 URL 또는 제출 메모 중 하나는 필요합니다. 기존 제출 파일이 있으면 JSON 수정만으로 최종 업로드 시각을 바꾸지 않습니다. 기한 만료 또는 채점 완료 시409.")
    multipart_submission = model("MultipartSubmissionRequest",dict(**submission,file=dict(type="string",format="binary",description="선택 첨부. 1바이트~50MiB")))
    PATHS["/api/assignments/{assignmentId}/submission"]["put"]["requestBody"]["content"]["multipart/form-data"] = dict(schema=ref(multipart_submission))
    PATHS["/api/assignments/{assignmentId}/submission"]["put"]["responses"]["413"] = dict(description="업로드 크기 초과",content={"application/json":dict(schema=ref("Error"))})
    endpoint("patch","/api/submissions/{submissionId}/review","과제 채점·피드백","SubmissionReview",dict(score=integer(0,1000,description="실제 상한은 과제 maxScore"),feedback=string(2,10000)),["score","feedback"],tag="과제",access=STAFF,description="피드백을 생성하거나 갱신하고 제출자에게 알림을 생성합니다.")
    assessment=dict(title=string(2,160),description=string(maximum=20000,default=""),domain=string(1,100),startAt=TIME,endAt=TIME,durationMinutes=integer(1,480,default=40))
    endpoint("get","/api/cohorts/{cohortId}/assessments","평가 목록",paged("Assessment"),tag="평가",access=MEMBER,params=page_params(),description="수강생에게 DRAFT는 노출하지 않습니다.")
    endpoint("post","/api/cohorts/{cohortId}/assessments","평가 초안 생성","Assessment",assessment,["title","domain","startAt","endAt"],status=201,tag="평가",access=STAFF)
    endpoint("get","/api/assessments/{id}","평가 상세","AssessmentDetail",tag="평가",access=MEMBER,description="학생에게 DRAFT는404. questions는 스태프 또는 본인 응시 시작 후 제공됩니다.")
    endpoint("patch","/api/assessments/{id}","평가 수정·게시·종료","Assessment",dict(**assessment,status=enum("DRAFT","PUBLISHED","CLOSED")),tag="평가",access=STAFF,description="게시에는 유효한 문항이 필요합니다. 응시 후 일정·시간·영역 변경과 DRAFT 전환 불가. CLOSED 재개 불가.")
    endpoint("delete","/api/assessments/{id}","평가 초안 삭제",status=204,tag="평가",access=STAFF,description="응시 기록이 없는 DRAFT만 가능합니다.")
    question=dict(topicId=nullable(ID),chapterId=nullable(ID),prompt=string(2,10000),position=integer(1,1000,default=1),points=integer(1,100,default=1),explanation=string(maximum=10000,default=""),options=array(obj(dict(label=string(1,2000),isCorrect=dict(type="boolean",default=False)),["label"]),minItems=2,maxItems=10))
    endpoint("post","/api/assessments/{assessmentId}/questions","객관식 문항 생성","Question",question,["prompt","options"],status=201,tag="평가",access=STAFF,description="응시 없는 DRAFT만. 선택지2~10개, 정확히 하나의 정답.")
    endpoint("patch","/api/assessment-questions/{id}","객관식 문항 수정","Question",question,tag="평가",access=STAFF,description="응시 없는 DRAFT만. options 제공 시 선택지를 모두 교체합니다.")
    endpoint("delete","/api/assessment-questions/{id}","객관식 문항 삭제",status=204,tag="평가",access=STAFF,description="응시 없는 DRAFT만.")
    endpoint("post","/api/assessments/{assessmentId}/attempts","평가 응시 시작","Attempt",status=201,tag="평가",access="해당 기수 ACTIVE 등록 회원",description="회원당 한 번. PUBLISHED이면서 시작 시각 이상, 종료 시각 미만. 마감은 startedAt+durationMinutes와 endAt 중 빠른 시각.")
    endpoint("get","/api/assessment-attempts/{id}","응시 상세·남은 시간","Attempt",tag="평가",access="응시자 본인 또는 해당 기수 스태프",description="시간 경과 자체는 상태를 변경하지 않습니다. 제출 시 만료 여부에 따라 EXPIRED로 확정합니다.")
    endpoint("put","/api/assessment-attempts/{id}/answers","응시 답안 저장","Attempt",dict(answers=array(obj(dict(questionId=ID,optionId=ID),["questionId","optionId"]),minItems=1,maxItems=200)),["answers"],tag="평가",access="응시자 본인",description="주어진 문항만 생성·갱신하고 나머지 저장 답안은 유지합니다. 중복 문항·다른 평가 선택지는400. 마감 후409.")
    endpoint("post","/api/assessment-attempts/{id}/submit","평가 제출·자동 채점","Attempt",tag="평가",access="응시자 본인",description="저장한 답안을0~100점으로 정규화. 마감 후에는 EXPIRED로 저장 답안을 채점. 재호출은 기존 결과 반환. 정답·해설 공개 시점은 Question 스키마 참조.")
    endpoint("get","/api/cohorts/{cohortId}/assessment-report","평가 결과·영역별 통계","AssessmentReport",tag="평가",access=MEMBER,params=[USER_FILTER],description="완료·만료 응시만 집계. 본인 또는 스태프가 지정한 회원. cohortAverage는 동일 평가의 기수 평균입니다.")


def community_endpoints():
    post=dict(category=enum("COURSE","SYSTEM","QUESTION","FAQ"),title=string(2,160),body=string(2,20000),pinned=dict(type="boolean",default=False),visibility=enum("COHORT","PUBLIC"),publicSlug=nullable(string(maximum=160)))
    endpoint("get","/api/cohorts/{cohortId}/posts","게시글 목록",paged("PostListItem"),tag="게시판",access=MEMBER,params=page_params()+[query("category",enum("","COURSE","SYSTEM","QUESTION","FAQ",default=""),"빈 값은 전체"),query("q",string(maximum=100,default=""),"제목·본문 검색")])
    endpoint("post","/api/cohorts/{cohortId}/posts","게시글 작성","Post",post,["category","title","body"],status=201,tag="게시판",access=MEMBER,description="학생은 QUESTION만 작성 가능. 나머지 분류와 pinned=true는 스태프만 가능. 공지 작성 시 다른 기수 회원에게 알림 생성.")
    endpoint("get","/api/posts/{postId}","게시글 상세","PostDetail",tag="게시판",access=MEMBER,description="조회 시 views가 증가합니다.")
    endpoint("patch","/api/posts/{postId}","게시글 수정","Post",post,tag="게시판",access="작성자 또는 기수 스태프",description="QUESTION 이외 분류로 변경하거나 공지를 수정하거나 고정하려면 스태프 권한이 필요합니다.")
    endpoint("delete","/api/posts/{postId}","게시글 삭제",status=204,tag="게시판",access="작성자 또는 기수 스태프",description="논리 삭제합니다.")
    endpoint("get","/api/posts/{postId}/comments","댓글 목록",paged("Comment"),tag="게시판",access=MEMBER,params=page_params())
    endpoint("post","/api/posts/{postId}/comments","댓글 작성","Comment",dict(body=string(1,5000)),["body"],status=201,tag="게시판",access=MEMBER,description="게시글 작성자에게 알림. 본인 댓글은 알림 생략.")
    endpoint("patch","/api/comments/{commentId}","댓글 수정","Comment",dict(body=string(1,5000)),["body"],tag="게시판",access="댓글 작성자 또는 기수 스태프")
    endpoint("delete","/api/comments/{commentId}","댓글 삭제",status=204,tag="게시판",access="댓글 작성자 또는 기수 스태프",description="논리 삭제합니다.")
    endpoint("get","/api/notifications","내 알림 목록",paged("Notification"),tag="알림",params=page_params()+[query("unread",dict(type="boolean",default=False),"읽지 않은 알림만")],access="본인")
    endpoint("patch","/api/notifications/{id}/read","알림 읽음 처리","Notification",tag="알림",access="본인",description="다른 사용자의 알림은404입니다. 이미 읽은 시각은 유지합니다.")
    endpoint("post","/api/notifications/read-all","전체 알림 읽음 처리",obj(dict(updated=integer(0)),["updated"]),tag="알림",access="본인")
    for kind, id_name, access in [("posts","postId","게시글 작성자 또는 기수 스태프"),("assignments","assignmentId",STAFF),("submissions","submissionId","제출자 본인(ADMIN 예외); 기한 전·검토 전")]:
        limit = 50 if kind == "submissions" else 10
        endpoint("post",f"/api/{kind}/{{{id_name}}}/files","첨부파일 업로드","File",dict(file=dict(type="string",format="binary",description=f"1바이트~{limit}MiB, 단일 파일")),["file"],status=201,tag="첨부파일",access=access,multipart=True,description=f"multipart/form-data의 file 파트로 전송합니다. 파일 이름은 최대255자, 크기는 {limit}MiB 이하입니다. 파일 크기 제한은 요청 파싱 시413, 서비스 검증 시400입니다.")
        PATHS[f"/api/{kind}/{{{id_name}}}/files"]["post"]["responses"]["413"] = dict(description="업로드 크기 초과",content={"application/json":dict(schema=ref("Error"))})
    endpoint("get","/api/submissions/{submissionId}/files","제출 파일 목록",array("SubmissionFile"),tag="첨부파일",access="제출자 본인 또는 해당 기수 스태프",description="삭제되지 않은 제출 파일의 이름·크기·다운로드 경로를 반환합니다. 삭제된 과제는404입니다.")
    endpoint("get","/api/files/{fileId}","첨부파일 메타데이터","File",tag="첨부파일",access="기수 회원; 제출물 첨부는 제출자 또는 기수 스태프")
    endpoint("get","/api/files/{fileId}/download","첨부파일 다운로드",tag="첨부파일",access="기수 회원; 제출물 첨부는 제출자 또는 기수 스태프")
    PATHS["/api/files/{fileId}/download"]["get"]["responses"]["200"] = dict(description="원본 파일; Content-Disposition: attachment; X-Content-Type-Options: nosniff",headers={"Content-Disposition":dict(description="UTF-8 파일 이름을 포함한 attachment",schema=string()),"Content-Length":dict(schema=integer(1))},content={"application/octet-stream":dict(schema=dict(type="string",format="binary"))})
    endpoint("delete","/api/files/{fileId}","첨부파일 삭제",status=204,tag="첨부파일",access="부모 리소스 수정 권한 및 업로더/스태프 권한",description="논리 삭제. 제출물 첨부는 기한 전·검토 전만 변경할 수 있습니다. 물리 파일 정리는 별도 운영 정책이 필요합니다.")


def integration_endpoints():
    for kind, table in [("subjects","learning_subjects"),("units","learning_units"),("topics","learning_topics"),("videos","lecture_videos"),("chapters","lecture_chapters")]:
        name = "Asset" + kind.title()
        props={c["apiName"]:sql_schema(c) for c in next(t for t in TABLES if t["name"]==table)["columns"]}
        model(name,dict(**props,**({} if "cohortId" in props else dict(cohortId=ID))))
        body=dict(title=string(1,160)); required=["title"]
        if kind=="units": body["subjectId"]=ID; required += ["subjectId"]
        if kind=="topics": body.update(unitId=ID,description=string(maximum=10000)); required += ["unitId"]
        if kind=="videos": body.update(youtubeUrl=string(format="uri"),lectureDate=DATE,periodNo=integer(1,100),visibility=enum("PUBLIC","PRIVATE")); required += ["youtubeUrl","lectureDate","visibility"]
        if kind=="chapters": body.update(videoId=ID,topicId=nullable(ID),startSeconds=integer(0)); required += ["videoId"]
        endpoint("get",f"/api/cohorts/{{cohortId}}/{kind}",f"{kind} 목록",paged(name),tag="학습 자료",access=MEMBER,params=page_params()+([query("videoId",ID,"영상별 소단원 필터")] if kind=="chapters" else []),description="학생에게는 공개 영상과 해당 소단원만 제공하며 다른 기수 접근은 차단합니다.")
        endpoint("post",f"/api/cohorts/{{cohortId}}/{kind}",f"{kind} 등록",name,body,required,status=201,tag="학습 자료",access=STAFF)
        endpoint("patch",f"/api/{kind}/{{id}}",f"{kind} 수정",name,body,tag="학습 자료",access=STAFF)
        endpoint("delete",f"/api/{kind}/{{id}}",f"{kind} 삭제",status=204,tag="학습 자료",access=STAFF,description="하위 커리큘럼이 남아 있으면 409입니다. 소단원 삭제 시 문항 연결도 제거합니다.")
    endpoint("get","/api/cohorts/{cohortId}/assessment-summary","기수 평가 성적 요약",array(obj(dict(id=ID,title=string(),completedCount=integer(0),average=nullable(dict(type="number")),highestScore=nullable(integer())))),tag="평가",access=STAFF)
    generic=obj({},additionalProperties=True)
    endpoint("get","/api/public/notices/{slug}","공개 공지 조회",obj(dict(title=string(),body=string(),createdAt=TIME)),tag="Discord",public=True,access="공개",description="PUBLIC 범위의 삭제되지 않은 과정·시스템 공지만 조회합니다.")
    endpoint("post","/api/posts/{id}/discord","Discord 전송 및 실패 재시도",obj(dict(id=ID,postId=ID,status=enum("PENDING","SENT","FAILED"),attemptCount=integer(0),errorCode=nullable(string()),sentAt=nullable(TIME))),tag="Discord",access=STAFF,description="공개 링크가 있는 공지만 전송합니다. 게시글 잠금과 전송 상태로 중복 요청을 차단합니다. Webhook 미설정은 FAILED/DISCORD_NOT_CONFIGURED로 저장합니다.")
    endpoint("post","/api/cohorts/{cohortId}/review-sessions","복습 세션 생성",generic,dict(topicId=ID,mode=enum("BLANK","QUIZ")),["topicId","mode"],status=201,tag="AI 복습",access=MEMBER)
    endpoint("get","/api/review-sessions/{id}","본인 복습 조회",generic,tag="AI 복습",access="본인 및 소속 기수 회원",description="퀴즈 정답·해설은 해당 문제 답안 제출 이후에만 제공합니다.")
    endpoint("patch","/api/review-sessions/{id}","본인 복습 종료",generic,tag="AI 복습",access="본인")
    endpoint("put","/api/review-sessions/{id}/blank","백지 복습 AI 피드백 및 재시도",generic,dict(content=string(1,20000)),["content"],tag="AI 복습",access="본인",description="실패 시 FAILED 상태를 보존하고 503을 반환합니다. 서버에 OPENAI_API_KEY 및 OPENAI_MODEL 설정이 필요합니다.")
    endpoint("post","/api/review-sessions/{id}/questions","다음 객관식 문제 생성",generic,tag="AI 복습",access="본인",description="미응답 문항이 있으면 새 생성 없이 기존 문항을 반환합니다. 정답은 응답 전에 숨깁니다.")
    endpoint("post","/api/review-questions/{id}/answer","객관식 복습 답안 제출",generic,dict(selectedChoice=integer(1,4)),["selectedChoice"],tag="AI 복습",access="본인",description="단일 선택 답안을 저장하고 정답·해설을 반환합니다. 재답변은409입니다.")
    for path in ["/api/review-sessions/{id}/blank","/api/review-sessions/{id}/questions"]:
        for op in PATHS[path].values():op["responses"]["503"]=dict(description="AI 설정 없음 또는 외부 생성 실패",content={"application/json":dict(schema=ref("Error"))})
    SCHEMAS["PostListItem"]["properties"].update(deliveryStatus=nullable(enum("PENDING","SENT","FAILED")))
    SCHEMAS["Question"]["properties"].update(chapterId=nullable(ID),reviewLinks=array(obj(dict(id=ID,chapterId=ID,title=string(),startSeconds=integer(0),lectureDate=DATE,periodNo=integer(1),youtubeUrl=string(format="uri")))))


def source_routes():
    routes = set()
    for source in (ROOT / "src/main/java").rglob("*Controller.java"):
        text = source.read_text(encoding="utf-8")
        header = text.split("public class",1)[0]
        prefix_match = re.search(r'@RequestMapping\("([^"]*)"\)',header)
        prefix = prefix_match[1] if prefix_match else ""
        for match in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^\n]*?)\))?',text):
            path_match = re.search(r'"([^"]*)"',match[2] or "")
            path = prefix + (path_match[1] if path_match else "")
            if path.startswith("/api/"):
                pattern = re.search(r"\{kind:([^}]+)\}", path)
                paths = [path.replace(pattern[0], kind) for kind in pattern[1].split("|")] if pattern else [path]
                for expanded in paths: routes.add((match[1].lower(),expanded))
    return routes


def verify_openapi(spec):
    seen = set()
    actual = source_routes()
    documented = {(method,path) for path,operations in PATHS.items() for method in operations}
    assert documented == actual, f"Route mismatch. Undocumented={actual-documented}; not implemented={documented-actual}"
    for path, operations in PATHS.items():
        for operation in operations.values():
            assert operation["operationId"] not in seen
            seen.add(operation["operationId"])
            declared = {p["name"] for p in operation.get("parameters",[]) if p["in"]=="path" and p["required"]}
            assert declared == set(re.findall(r"\{(\w+)\}",path))
    def visit(value):
        if isinstance(value,dict):
            if "$ref" in value:
                cursor = spec
                for segment in value["$ref"].removeprefix("#/").split("/"): cursor=cursor[segment]
            for child in value.values(): visit(child)
        elif isinstance(value,list):
            for child in value: visit(child)
    visit(spec)
    return len(seen)


def api_docs():
    setup_models(); auth_catalog_endpoints(); learning_endpoints(); community_endpoints(); integration_endpoints()
    tags = ["인증","회원","과정·기수","일정","출결","게시판","과제","평가","알림","첨부파일","상태","학습 자료","Discord","AI 복습"]
    spec = dict(openapi="3.1.0",info=dict(title="WSTS LMS Backend API",version="1.0.0",description="Spring Boot LMS의 실제 컨트롤러·입력 검증·DB 스키마를 기준으로 작성했습니다. 날짜는 한국 달력, 시각은 ISO8601 오프셋 필수이며 UTC로 저장/반환합니다. 응답은 data 래퍼 없이 직접 반환합니다. 전역 역할과 기수별 역할을 구분합니다."),servers=[dict(url="http://localhost:8080",description="로컬 실행")],tags=[dict(name=t) for t in tags],security=[dict(bearerAuth=[])],paths=PATHS,components=dict(securitySchemes=dict(bearerAuth=dict(type="http",scheme="bearer",bearerFormat="JWT")),schemas=SCHEMAS))
    count = verify_openapi(spec)
    write_json(DOCS / "openapi.json",spec)
    lines=["# LMS API 명세", "", f"{count}개 실제 HTTP 작업의 상세 요청·응답 스키마는 [openapi.json](openapi.json)에 있습니다. 실행 중에는 `GET /api-docs`로 동일 명세를 받을 수 있습니다. Swagger Editor 또는 OpenAPI를 지원하는 Postman에 가져올 수 있습니다.", "", "## 공통 규칙", "", "- 기본 주소: `http://localhost:8080`. 운영 주소는 배포 환경에 맞게 교체합니다.", "- 인증: 공개 API를 제외하고 `Authorization: Bearer <accessToken>`. 토큰은 응답 JSON으로 반환하며 쿠키를 사용하지 않습니다.", "- JSON 요청: `Content-Type: application/json`. 파일은 `multipart/form-data`, 단일 파트 이름은 `file`입니다.", "- 응답은 객체·배열을 직접 반환합니다. 목록은 보통 `{items,page,size,total}`이며 `page=0`, `size=20`, 최대100입니다. 일정·출결 기본 크기는50입니다.", "- 날짜는 `YYYY-MM-DD`, 월은 `YYYY-MM`. 시각은 `2026-11-09T09:00:00+09:00`처럼 오프셋을 반드시 포함합니다. 응답 시각은 UTC입니다. 월 필터·교육 날짜·오늘 기준은 한국 시간입니다.", "- PATCH는 제공한 필드만 변경합니다. PUT 출결은 날짜별 생성/갱신, PUT 제출은 본인 제출의 생성/재제출, PUT 평가 답안은 제공 문항만 갱신합니다.", "- 생성은201, 일반 조회·수정은200, 삭제·로그아웃·비밀번호 변경은204(본문 없음)입니다. 제출물 PUT은 생성일 때도200입니다.", "", "## 인증과 권한", "", "접근 토큰은15분, 갱신 토큰은7일입니다. 갱신할 때 새 토큰 쌍으로 교체하고 이전 갱신 토큰을 폐기합니다. 사용한 갱신 토큰을 재사용하면 같은 토큰 패밀리를 폐기합니다. 로그아웃은 갱신 토큰 패밀리를 폐기하며 기존 접근 토큰은 남은 유효시간까지 유효합니다. 비밀번호·전역 역할·계정 상태 변경은 모든 기존 토큰을 무효화합니다.", "", "공개 회원가입은 없습니다. 운영자가 전역 ADMIN 권한으로 `POST /api/users`에 등록자의 이메일·초기 비밀번호·이름을 입력해 STUDENT 계정을 발급합니다. 초기 비밀번호 전달은 운영 절차이며 이 API는 메일·문자를 발송하지 않습니다. 발급 후 기수별 수강 등록 API로 배정합니다. 관리자 계정은 `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`를 최초 실행 환경에 지정해 준비합니다. 고정 기본 관리자 계정은 없습니다. 전역 ADMIN은 전체 관리 권한을 가지며, 기수의 INSTRUCTOR/MANAGER 권한은 해당 ACTIVE 수강 등록에서 확인합니다. 전역 INSTRUCTOR 역할만으로 다른 기수를 관리할 수 없습니다.", "", "## 오류", "", "```json", '{"code":"INVALID_REQUEST","message":"입력 값을 확인해주세요.","timestamp":"2026-11-09T00:00:00Z"}', "```", "", "| HTTP | code 예 | 의미 |", "|---|---|---|", "| 400 | INVALID_REQUEST | 형식·필수값·기간·선택지 검증 실패 |", "| 401 | UNAUTHORIZED | 로그인 필요·만료·인증 실패 |", "| 403 | FORBIDDEN | 기수·작성자·스태프 권한 없음 |", "| 404 | NOT_FOUND | 존재하지 않거나 숨겨진 리소스 |", "| 409 | CONFLICT, DATA_CONFLICT | 중복 등록·종료 상태·참조 무결성 충돌 |", "| 413 | FILE_TOO_LARGE | 첨부 요청 크기 제한 초과 |", "| 500 | INTERNAL_ERROR | 내부 처리 실패 |", "", "## 엔드포인트", "", "상세한 필수값·길이·범위·enum·응답 필드는 OpenAPI의 각 `requestBody`, `parameters`, `responses`를 기준으로 연결하세요.", ""]
    for tag in tags:
        lines += [f"### {tag}","","| 메서드 | 경로 | 기능 | 성공 | 권한·동작 |","|---|---|---|---|---|"]
        for path,operations in PATHS.items():
            for method,operation in operations.items():
                if tag not in operation["tags"]:continue
                success=next(c for c in operation["responses"] if c.startswith("2"))
                description=operation["description"].replace("|","\\|")
                lines.append(f"| {method.upper()} | `{path}` | {operation['summary']} | {success} | {description} |")
        lines.append("")
    lines += ["## 연결 예시", "", "### 로그인 → 기수 → 대시보드", "", "```http", "POST /api/auth/login", "Content-Type: application/json", "", '{"email":"student@example.com","password":"your-password"}', "```", "", "응답의 `accessToken`을 다음 요청의 Bearer 헤더로 전달합니다.", "", "```http", "GET /api/cohorts?page=0&size=20", "Authorization: Bearer <accessToken>", "```", "", "선택한 기수 ID로 `GET /api/cohorts/{cohortId}/dashboard`를 호출합니다. 아직 수강 등록이 없으면 기수 목록이 비어 있으므로 ADMIN/MANAGER가 수강 등록을 먼저 생성해야 합니다.", "", "### 과제 제출 → 첨부 → 피드백", "", "```http", "PUT /api/assignments/1/submission", "Authorization: Bearer <accessToken>", "Content-Type: application/json", "", '{"body":"과제 구현 내용입니다.","repositoryUrl":"https://github.com/example/project"}', "```", "", "반환된 제출 ID를 사용해 `POST /api/submissions/{submissionId}/files`에 파일을 첨부합니다. 스태프는 `PATCH /api/submissions/{submissionId}/review`에 `{\"score\":90,\"feedback\":\"구현과 검증이 완료되었습니다.\"}`를 전달합니다. 기한이 지나거나 채점되면 재제출·첨부 변경이 제한됩니다.", "", "### 평가 생성 → 게시 → 응시 → 채점", "", "1. 스태프가 평가를 생성합니다(DRAFT).", "2. 문항과2~10개 선택지를 등록합니다. 선택지마다 `label`, `isCorrect`를 지정하고 정답은 정확히 하나입니다.", "3. `PATCH /api/assessments/{id}`에 `{\"status\":\"PUBLISHED\"}`를 보냅니다.", "4. 학생이 `POST /api/assessments/{assessmentId}/attempts`로 응시를 시작합니다. 학생당 한 번입니다.", "5. `PUT /api/assessment-attempts/{id}/answers`에 `{\"answers\":[{\"questionId\":1,\"optionId\":2}]}`를 보냅니다.", "6. `POST /api/assessment-attempts/{id}/submit`로 저장한 답안을 채점합니다. 반복 제출은 같은 결과를 반환합니다.", "7. 본인 응시 완료 후 점수·본인 답안의 채점 결과가 보입니다. 정답 선택지·해설은 평가 전체 종료 또는 CLOSED 이후에 공개합니다.", "", "응시 마감은 전체 종료 시각과 개인 제한 시간 중 빠른 시각입니다. 만료 뒤 답안 저장은 거부되고, 제출을 호출하면 기존 저장 답안을 EXPIRED 상태로 채점합니다. 자동 스케줄러로 만료 상태를 확정하지 않으므로 프론트는 `expiresAt`, `remainingSeconds`, `expired`를 함께 사용해야 합니다.", "", "## 구현 범위", "", "실제 DB CRUD, JWT 인증·권한, 출결 정정 승인, 과제 제출·채점, 객관식 평가 자동 채점, 파일 저장·권한별 다운로드, 서비스 이벤트 알림을 포함합니다. 메일·문자 발송, 비밀번호 찾기, 실시간 강의/채팅, 바이러스 검사, 외부 LMS 데이터 이전은 포함하지 않습니다.", "", "## 최신 프론트 기준 정리", "", "2026-11-09 frontend main `c835066`의 학생·관리자 화면을 기준으로 스터디 8개와 기존 로드맵 5개 API를 제거했습니다. 기존 경로는 더 이상 제공하지 않습니다. 캘린더 일정, 알림, 과제, 평가 결과, 인증·기수 및 신규 학습 분류·AI·영상·Discord 기반은 유지합니다.", "", "## 재생성 및 검증", "", "```powershell", "python scripts/generate-docs.py", "```", "", "생성기는 실제 컨트롤러의 메서드·경로와 문서의1:1 일치를 검사하고, path parameter 선언, operationId 중복, 컴포넌트 참조를 검증합니다. 소스 변경 시 계약 정의도 함께 갱신해야 합니다.", ""]
    lines += [
        "## 현재 과제 화면 연결", "",
        "- 학생 카드 목록은 `GET /api/cohorts/{cohortId}/assignments`로 조회합니다. `subject`, `author`, `dueDate`, `dDay`, `status`, `submissionDate`, `feedback`은 현재 화면 표시용 필드이며 `mySubmission`·`myReview`는 본인 원본 데이터입니다. 날짜 표시 문자열은 한국 시각이고 `dueAt`·`submittedAt` 원본은 UTC입니다.",
        "- `feedback`은 실제 채점 전 null입니다. `reviewStatus`는 NOT_SUBMITTED/SUBMITTED/REVIEWED이며 제출된 과제의 화면 `status`는 채점 여부와 무관하게 제출완료입니다. `canSubmit`·`canResubmit`·`submissionLocked`·`lockReason`으로 버튼 상태를 연결합니다.",
        "- 제출 팝업의 `repoUrl`, `deployUrl`, `submitMemo`를 그대로 JSON 또는 multipart 필드로 보낼 수 있습니다. 각각 기존 `repositoryUrl`, 신규 `deploymentUrl`, 기존 `body`와 같습니다. 저장소 URL이 있으면 메모는 비워도 됩니다. canonical/별칭을 같이 보내면 공백 제거 후 같은 값이어야 합니다. 생략값은 보존하고 null은 해당 값을 지웁니다.",
        "- 파일을 함께 제출하려면 같은 PUT 경로에 multipart/form-data로 필드와 `file`을 보냅니다. 파일이 없으면 JSON을 사용할 수 있습니다. multipart 내용·파일은 함께 저장되며 파일 검증·저장 실패 시 제출 변경도 롤백합니다. 기존 제출 생성 → 개별 파일 업로드 방식도 유지합니다.",
        "- 제출 첨부는 50MiB, 공지·과제 안내 첨부는 10MiB입니다. `mySubmission.files` 또는 `GET /api/submissions/{submissionId}/files`로 재조회할 수 있고 `downloadUrl`에는 Bearer 인증이 필요합니다. 제출물 목록에도 첨부·점수·평가·평가자 이름을 제공합니다.",
        "- 제출 파일 업로드 성공 시 최종 제출 시각을 갱신합니다. 파일이 이미 있는 제출물의 메모·URL만 수정하면 최종 업로드 시각을 유지합니다. 링크/메모만 제출하는 과제는 저장 성공 시 최종 제출 시각을 갱신합니다.",
        "- 관리자 과제 폼의 기존 API는 그대로 사용합니다. 과목 뱃지는 선택 `subject` 문자열로 저장하며 별도 과목 관리 기능은 필요하지 않습니다. 현재 채점 권한은 관리자 또는 해당 기수 강사·매니저입니다.",
        "- 이번 변경은 백엔드 계약만 준비합니다. 현재 프런트엔드의 로컬 샘플 상태·업로드 시뮬레이션은 변경하지 않았으므로 프런트엔드 담당자가 실제 호출을 연결해야 합니다.", "",
    ]
    (DOCS / "API_SPEC.md").write_text("\n".join(lines),encoding="utf-8")
    print(f"Generated OpenAPI: {count} operations, {len(PATHS)} paths, {len(SCHEMAS)} schemas")


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


ERD_BUNDLE_URL = "https://www.erdcloud.com/362fd7b87ef4582c2b50d92b6825046e74d62296.js?meteor_js_resource=true"
ERD_BUNDLE_SHA256 = "3b8ca5420469256b4f27380871de657302bcb2d63c506d6f4171b2378c1370e2"
# ERD Cloud resolves entity colors through a fixed palette keyed by these exact
# strings. Arbitrary CSS colors parse as JSON but crash its canvas renderer.
ERD_ENTITY_COLOR = "rgba(178, 69, 0, 0.5)"


def erdcloud_snapshot():
    """Use ERD Cloud's observed lp_erd_export/import.ts snapshot data contract.

    Foreign-key-only columns belong in fields; keys.fks means PK+FK, matching
    the official DDLParser. Snapshot upload remaps entity IDs and diagram ID.
    """
    def stable_id(value): return hashlib.sha256(value.encode()).hexdigest()[:17]
    entities = []
    field_maps = {}
    positions = {
        "users":(80,80), "refresh_tokens":(880,80), "courses":(1680,80), "cohorts":(2480,80), "enrollments":(3280,80),
        "schedule_events":(80,980), "attendance_records":(2480,980), "attendance_corrections":(3280,980),
        "posts":(80,1880), "comments":(880,1880), "assignments":(1680,1880), "submissions":(2480,1880), "submission_reviews":(3280,1880),
        "assessments":(80,2780), "assessment_questions":(880,2780), "assessment_options":(1680,2780), "assessment_attempts":(2480,2780), "assessment_answers":(3280,2780),
        "notifications":(1680,3680), "files":(2480,3680),
        "notice_deliveries":(3280,3680), "assessment_question_review_links":(4080,2780),
        "learning_subjects":(80,4580), "learning_units":(880,4580), "learning_topics":(1680,4580), "lecture_videos":(2480,4580), "lecture_chapters":(3280,4580),
        "review_sessions":(80,5480), "blank_reviews":(880,5480), "review_quizzes":(1680,5480), "review_quiz_questions":(2480,5480),
    }
    for table in TABLES:
        x,y = positions[table["name"]]
        entity = dict(_id=stable_id("table:"+table["name"]), _diagramId=None, position=dict(x=x,y=y), name=table["name"], pName=table["name"], fields=[], keys=dict(pks=[], fks=[]), color=ERD_ENTITY_COLOR)
        field_maps[table["name"]] = {}
        for col in table["columns"]:
            notes = []
            if col["autoIncrement"]: notes.append("AUTO_INCREMENT")
            if any(u["columns"]==[col["name"]] for u in table["uniqueKeys"]): notes.append("UNIQUE")
            if "enum" in col: notes.append("허용값: " + ", ".join(col["enum"]))
            field = dict(_id=stable_id("column:"+table["name"]+"."+col["name"]),name=col["name"],pName=col["name"],domain="",type=col["type"],defaultValue=col.get("defaultSql",""),isAllowNull=col["nullable"],comment="; ".join(notes),relEntity=None,relFieldId=None,relType=None,relGroupId=None)
            field_maps[table["name"]][col["name"]] = field
            if col["name"] in table["primaryKey"]:
                entity["keys"]["fks" if any(col["name"] in fk["columns"] for fk in table["foreignKeys"]) else "pks"].append(field)
            else: entity["fields"].append(field)
        entities.append(entity)
    by_name = {e["pName"]:e for e in entities}
    relationships = 0
    for table in TABLES:
        for fk in table["foreignKeys"]:
            unique = fk["columns"]==table["primaryKey"] or any(u["columns"]==fk["columns"] for u in table["uniqueKeys"])
            for column, target in zip(fk["columns"],fk["referencedColumns"]):
                row = field_maps[table["name"]][column]
                row.update(relEntity=by_name[fk["referencedTable"]]["_id"],relFieldId=field_maps[fk["referencedTable"]][target]["_id"],relType="ZERO_OR_ONE" if unique else "ZERO_OR_MANY",relGroupId=stable_id("fk:"+fk["name"]))
                relationships += 1
    snapshot = dict(entityData=entities,memoData=[],domainData=[],createdAt=datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00","Z"),creatorName="",creatorThumb="")
    assert len(snapshot["entityData"]) == len(TABLES)
    assert relationships == sum(len(fk["columns"]) for t in TABLES for fk in t["foreignKeys"])
    entity_ids = {e["_id"] for e in entities}
    assert len(entity_ids) == len(TABLES)
    for entity in entities:
        assert isinstance(entity["name"],str) and isinstance(entity["pName"],str)
        assert entity["color"] == ERD_ENTITY_COLOR
        rows = entity["keys"]["pks"] + entity["keys"]["fks"] + entity["fields"]
        assert len({r["_id"] for r in rows})==len(rows)
        for row in rows:
            if row["relEntity"]:
                assert row["relEntity"] in entity_ids
                target = next(e for e in entities if e["_id"]==row["relEntity"])
                assert row["relFieldId"] in {r["_id"] for r in target["keys"]["pks"]+target["keys"]["fks"]+target["fields"]}
    write_json(DOCS / "erd/lms.erdcloud.json",snapshot)
    write_json(DOCS / "erd/erdcloud-format-source.json",dict(service="ERD Cloud",verifiedOn="2026-11-09",officialSource=ERD_BUNDLE_URL,downloadedBundleSha256=ERD_BUNDLE_SHA256,modules=["lp_erd_export.ts","lp_erd_import.ts","DDLParser.ts","ERDCardinality.ts","EntityCollection.ts","entityColorPalette.ts"],observations=["Export uses entityData, memoData, domainData, createdAt, creatorName, creatorThumb.","Upload Snapshot normalizes entity position/name/pName/keys/fields/color, remaps entity IDs and rebinds relationships.","Only identifying FK columns belong in keys.fks; ordinary FKs remain in fields.","Relationship types ZERO_OR_ONE and ZERO_OR_MANY are official enum values.","Entity color must be an exact palette key; arbitrary CSS colors fail after entities have already been persisted.","Import skips entities with existing pName. Updated definitions must be imported into a new blank diagram."],validation=dict(tables=len(TABLES),columns=sum(len(t["columns"]) for t in TABLES),foreignKeys=sum(len(t["foreignKeys"]) for t in TABLES),allRelationshipReferencesResolved=True,officialImportNormalizer="verified locally using the official handler and color palette; no live upload of the foundation schema",liveUploadPerformed=False)))


def database_docs():
    count = sum(len(t["foreignKeys"]) for t in TABLES)
    schema = dict(format="wsts-lms-relational-schema", formatVersion="1.1.0", database=dict(engine="MariaDB", charset="utf8mb4", timeStorage="UTC", dateCalendar="Asia/Seoul"), source=dict(path=str(DDL_PATH.relative_to(ROOT)).replace("\\", "/"), sha256=hashlib.sha256(DDL.encode()).hexdigest(),migrations=[dict(path=str(p.relative_to(ROOT)).replace("\\", "/"),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in MIGRATION_PATHS]), erdCloud=dict(nativeSnapshot=False, nativeSnapshotFile="lms.erdcloud.json", sqlImportFile="lms-mariadb.sql", instructions="이 파일은 자체 스키마 교환 형식입니다. ERD Cloud Upload Snapshot에는 lms.erdcloud.json을 사용하세요."), tables=TABLES)
    write_json(DOCS / "erd/lms-schema.json", schema)
    purposes = {
        "users":"회원·전역 역할·계정 상태", "refresh_tokens":"해시된 갱신 토큰·회전 이력", "courses":"교육 과정", "cohorts":"과정별 기수·교육 기간·교육 링크", "enrollments":"사용자별 기수 역할·수강 상태", "schedule_events":"강의·프로젝트·특강·휴일 일정", "attendance_records":"수강 등록별 일자 출결", "attendance_corrections":"출결 정정 요청·검토", "posts":"기수 게시글·공지", "comments":"게시글 댓글", "assignments":"과제·기한·배점", "submissions":"회원별 과제 제출", "submission_reviews":"제출물 점수·피드백", "assessments":"객관식 평가·응시 기간", "assessment_questions":"평가 문항·순서·배점", "assessment_options":"문항 선택지·정답", "assessment_attempts":"평가 응시·점수", "assessment_answers":"응시별 선택 답안·채점 결과", "notifications":"개인 알림·읽음 상태", "files":"첨부파일 메타데이터·소속 리소스"}
    purposes.update({"learning_subjects":"기수별 과목", "learning_units":"과목별 단원", "learning_topics":"단원별 핵심 개념·복습 주제", "lecture_videos":"날짜·교시별 YouTube 강의", "lecture_chapters":"강의 소단원·시작 시각", "assessment_question_review_links":"평가 문항과 복습 구간 연결", "review_sessions":"개인 AI 복습의 공통 부모", "blank_reviews":"백지 복습 내용·세 부분 피드백", "review_quizzes":"객관식 복습 세션·순차 문제 번호", "review_quiz_questions":"4개 보기·단일 선택·채점 결과", "notice_deliveries":"Discord 공지 전송 스냅샷·처리 이력"})
    lines = [
        "# MariaDB 데이터베이스와 ERD", "",
        f"실행 기준은 `src/main/resources/db/migration`의 V1 + V2 + V3 + V4 + V5입니다. `{schema['source']['path']}`는 다섯 마이그레이션 적용 후의 전체 스키마입니다. {len(TABLES)}개 테이블과 {count}개 외래 키를 ERD Cloud 스냅샷 JSON으로 제공합니다.", "",
        "V2는 추가 기능의 DB 기반이고, V3는 관리자 계정 발급 이력입니다. V4는 현재 과제 화면의 과목 표시명과 제출 배포 URL을 저장하며 기존 행은 NULL을 유지합니다. V5는 최신 프론트에서 사용하지 않는 스터디·기존 로드맵 네 테이블과 그 안의 데이터를 삭제하고 STUDY_GROUP 알림을 정리합니다. 관리자 계정 발급·출결 소명·과제 제출/첨부/평가 API를 구현했습니다. AI·영상·Discord의 신규 Java 모델·저장소 인터페이스는 기반이며 서비스·컨트롤러·외부 호출 구현은 각 담당자 작업 범위입니다. [설계 범위](foundation/DOMAIN_PLAN.md)와 [팀 인수인계](foundation/TEAM_HANDOFF.md)를 먼저 확인하세요.", "",
        "## 제공 파일", "", "| 파일 | 용도 |", "|---|---|",
        f"| [erd/lms.erdcloud.json](erd/lms.erdcloud.json) | **ERD Cloud Upload Snapshot용**. {len(TABLES)}개 엔티티·{count}개 관계·화면 배치 포함 |",
        "| [erd/lms-mariadb.sql](erd/lms-mariadb.sql) | V1+V2+V3+V4+V5 통합 스키마. 새 DB 생성·ERD Cloud SQL Import용. 기존 DB에는 Flyway 후속 마이그레이션 적용 |",
        "| [erd/lms-schema.json](erd/lms-schema.json) | 컬럼·타입·널 허용·PK·FK·유니크·CHECK·인덱스를 담은 자체 JSON |",
        "| [erd/erdcloud-format-source.json](erd/erdcloud-format-source.json) | 공식 형식의 출처·검증 범위 |", "",
        "## ERD Cloud JSON 가져오기", "",
        "1. ERD Cloud에서 새 빈 다이어그램을 엽니다.",
        "2. 하단 **Import → Upload Snapshot**을 누릅니다.",
        "3. `lms.erdcloud.json` 파일을 선택합니다.",
        "4. 가져온 엔티티와 연결선을 확인하고 **Zoom fit** 또는 **Auto Layout**으로 배치를 조정합니다.", "",
        "**이전 24개 테이블 다이어그램에 재업로드하지 말고 반드시 새 빈 다이어그램에서 가져오세요.** ERD Cloud는 물리 이름이 같은 기존 테이블을 건너뛰므로 변경된 컬럼·색상·제약이 갱신되지 않습니다. 잘못된 색상으로 가져오기에 실패했어도 테이블이 이미 저장될 수 있습니다. 이번 31개 테이블 기반 파일은 사이트에 직접 업로드하지 않았습니다.", "",
        "JSON은 [ERD Cloud 공식 공개 프런트엔드 코드](" + ERD_BUNDLE_URL + ")의 실제 스냅샷 내보내기·가져오기 데이터 구조를 확인해 생성했습니다. `entityData`, `memoData`, `domainData`, 엔티티·컬럼·관계 ID와 허용된 관계 타입을 사용하며 공식 가져오기 처리·색상 팔레트·참조 무결성을 로컬에서 검증했습니다. 실제 서비스 업로드·렌더링 검증은 수행하지 않았습니다. ERD 그림은 타입·NULL·PK·FK·기본값을 표현하고, 유니크·CHECK·인덱스·AUTO_INCREMENT의 실행 기준은 SQL 및 `lms-schema.json`입니다.", "",
        "## SQL로 가져오기", "",
        "하단 **Import**의 SQL 창에 `lms-mariadb.sql` 전체 내용을 붙여 넣고 **Import**를 누르는 방법도 사용할 수 있습니다. SQL Import에서 CHECK·인덱스 등 일부 세부 제약의 그림 표현은 도구의 파서 지원 범위에 따릅니다.", "",
        "## 테이블", "", "| 테이블 | 역할 |", "|---|---|"
    ]
    lines += [f"| `{t['name']}` | {purposes[t['name']]} |" for t in TABLES]
    lines += ["", "## 관계", "", "```mermaid", "erDiagram"]
    for table in TABLES:
        for fk in table["foreignKeys"]:
            optional = any(next(c for c in table["columns"] if c["name"] == col)["nullable"] for col in fk["columns"])
            unique = fk["columns"] == table["primaryKey"] or any(u["columns"] == fk["columns"] for u in table["uniqueKeys"])
            parent = "|o" if optional else "||"
            child = "o|" if unique else "o{"
            lines.append(f'    {fk["referencedTable"]} {parent}--{child} {table["name"]} : "{",".join(fk["columns"])}"')
    lines += ["```", "", "## 주요 제약과 저장 규칙", "", "- 사용자와 기수는 `enrollments`로 연결합니다. 전역 `users.role`과 기수별 `enrollments.role`을 구분합니다.", "- 회원·기수, 수강 등록·출결 날짜·회차 번호, 과제·회원, 평가·회원, 응시·문항은 각각 유니크입니다.", "- DATE 출결은 session_no=0, SESSION 출결은 양수입니다. 기존 출결 API도 일자·회차를 구분하고 삭제 기록은 조회·대시보드에서 제외합니다.", "- 출결 정정은 요청 당시 상태·version 스냅샷과 INVALIDATED 상태를 담습니다. 승인 시 원본 상태·version을 검사하고 변경·삭제된 요청은 INVALIDATED로 무효화합니다. ABSENT→LATE/PRESENT, LATE/EXCUSED/LEAVE/EARLY→PRESENT를 허용합니다. 소명자료 파일 저장용 테이블은 추가하지 않습니다.", "- 제출물당 피드백은 하나입니다. 제출물 파일 업로드 성공 시 submitted_at을 갱신합니다. 학생은 본인 제출·평가·첨부에만 접근합니다.", "- AI 복습은 review_sessions와 blank_reviews/review_quizzes의 공유 PK·FK로 IS-A를 표현합니다. 부모 mode와 자식 종류 일치·배타성은 서비스 트랜잭션에서 보장해야 합니다.", "- AI 퀴즈 한 문제는 필수 보기4개와 단일 선택 번호(1~4)를 가집니다. 동일 세션·문제 순번은 유니크입니다. 정답 공개 시점·다음 문제 동의·본인 접근은 담당자 구현 범위입니다.", "- 과목→단원→핵심 개념이 복습 주제입니다. 영상은 날짜·교시, 소단원은 시작 초를 저장하며 평가 문항에서 연결합니다. 같은 기수인지와 해설 공개 여부는 서비스에서 확인해야 합니다.", "- 파일은 게시글·과제·제출물 중 정확히 하나에 속하도록 CHECK로 제한합니다. 디스크 파일과 files 메타데이터를 함께 사용합니다.", "- 출결·과제의 deleted_at을 기존 API에 연결했으며 원본·제출물·첨부 이력은 보존합니다. FK는 기본 RESTRICT이며 일괄 연쇄 삭제를 하지 않습니다.", "- Discord 전송 이력은 notice_deliveries에 저장하며 Webhook URL과 AI 키는 서버 비밀 설정에서만 관리합니다.", "- 비밀번호는 BCrypt, 갱신 토큰은 SHA-256 해시로 저장합니다. 토큰 원문은 JSON 스키마/ERD/문서에 포함하지 않습니다.", "- DATETIME(6)은 UTC 기준으로 저장하고 API는 오프셋이 포함된 ISO 8601 문자열을 반환합니다. 교육 날짜와 월별 필터는 한국 시간 기준입니다.", "", "## 재생성", "", "```powershell", "python scripts/generate-docs.py", "```", "", "테이블 구조는 V1+V2+V3+V4+V5 통합 SQL을 읽어 재생성하고, API 계약은 실제 컨트롤러 경로와 대조합니다. 스키마 변경 시 마이그레이션·통합 SQL·도메인 모델을 함께 맞추고 마이그레이션 테스트로 일치 여부를 확인하세요.", ""]
    (DOCS / "DATABASE.md").write_text("\n".join(lines), encoding="utf-8")
    erdcloud_snapshot()


if __name__ == "__main__":
    database_docs()
    print(f"Generated DB artifacts: {len(TABLES)} tables, {sum(len(t['foreignKeys']) for t in TABLES)} foreign keys")
    api_docs()
