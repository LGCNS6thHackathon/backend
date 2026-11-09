import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Only run against a local verification database. This creates synthetic records.
const base = new URL(process.argv[2] || 'http://127.0.0.1:18080');
assert.ok(['127.0.0.1','localhost','[::1]'].includes(base.hostname), 'Local verification only');
const config = Object.fromEntries((await readFile('.env','utf8')).split(/\r?\n/).filter(line=>line && !line.startsWith('#')).map(line=>{const split=line.indexOf('=');return [line.slice(0,split),line.slice(split+1)];}));
let checks=0;
const run=`api-${Date.now()}`;
async function api(method,path,token,body,expected=200){
  const headers={};if(token)headers.Authorization=`Bearer ${token}`;
  if(body!==undefined)headers['Content-Type']='application/json';
  const response=await fetch(new URL(path,base),{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)})});
  assert.equal(response.status,expected,`${method} ${path}: expected ${expected}, got ${response.status}`);
  return expected===204?null:await response.json();
}
function check(condition,name){assert.ok(condition,name);checks++;console.log(`PASS ${name}`);}
async function login(email,password){return api('POST','/api/auth/login',null,{email,password});}
async function register(suffix){const email=`${run}-${suffix}@example.test`,password='ApiVerifyPassword!42';const user=await api('POST','/api/auth/register',null,{email,password,name:`검증 ${suffix}`},201);return {...user,...await login(email,password)};}
const health=await api('GET','/api/health');check(health.status==='UP','Spring Boot health');
const admin=await login(config.BOOTSTRAP_ADMIN_EMAIL,config.BOOTSTRAP_ADMIN_PASSWORD);
const a=await register('student-a'),b=await register('student-b'),outsider=await register('outsider');
check(a.user.role==='STUDENT','Public registration cannot create admin');
await api('GET','/api/users',a.accessToken,undefined,403);checks++;
const course=await api('POST','/api/courses',admin.accessToken,{title:`${run} 과정`,description:'MariaDB 실제 API 검증',totalHours:100},201);
const now=new Date(),date=(days)=>new Date(now.getTime()+days*86400000).toISOString().slice(0,10),at=(hours)=>new Date(now.getTime()+hours*3600000).toISOString();
const cohort=await api('POST',`/api/courses/${course.id}/cohorts`,admin.accessToken,{name:`${run} 기수`,startDate:date(-10),endDate:date(30)},201);
for(const u of [a,b])await api('POST',`/api/cohorts/${cohort.id}/enrollments`,admin.accessToken,{userId:u.id,role:'STUDENT'},201);
await api('GET',`/api/cohorts/${cohort.id}/posts`,outsider.accessToken,undefined,403);check(true,'Cross-cohort access rejected');
const post=await api('POST',`/api/cohorts/${cohort.id}/posts`,a.accessToken,{category:'QUESTION',title:'검증용 학습 질문',body:'실제 MariaDB에 저장합니다.'},201);
await api('POST',`/api/posts/${post.id}/comments`,b.accessToken,{body:'동료가 남긴 답변'},201);
const comments=await api('GET',`/api/posts/${post.id}/comments`,a.accessToken);check(comments.total===1,'Posts and comments persisted');
await api('PATCH',`/api/posts/${post.id}`,b.accessToken,{title:'무단 수정'},403);checks++;
const form=new FormData();form.append('file',new Blob(['verification-file'],{type:'text/plain'}),'guide.txt');
const uploadedResponse=await fetch(new URL(`/api/posts/${post.id}/files`,base),{method:'POST',headers:{Authorization:`Bearer ${a.accessToken}`},body:form});assert.equal(uploadedResponse.status,201,'File upload');const file=await uploadedResponse.json();
const download=await fetch(new URL(`/api/files/${file.id}/download`,base),{headers:{Authorization:`Bearer ${b.accessToken}`}});check(download.status===200 && await download.text()==='verification-file','Authorized file round trip');
const event=await api('POST',`/api/cohorts/${cohort.id}/events`,admin.accessToken,{title:'서비스 설계 실습',type:'LECTURE',startAt:at(1),endAt:at(3),location:'온라인',description:'일정 검증'},201);check(event.id>0,'Schedule saved');
await api('PATCH',`/api/cohorts/${cohort.id}`,admin.accessToken,{endDate:date(-1)},409);check(true,'Cohort period cannot exclude existing learning records');
const attendance=await api('PUT',`/api/cohorts/${cohort.id}/attendance`,admin.accessToken,{userId:a.id,date:date(-1),status:'ABSENT'});
const correction=await api('POST',`/api/attendance/${attendance.id}/corrections`,a.accessToken,{requestedStatus:'PRESENT',reason:'실제 출석 기록 정정을 요청합니다.'},201);
await api('PATCH',`/api/attendance/corrections/${correction.id}`,admin.accessToken,{status:'APPROVED',reviewNote:'확인 후 승인'});check(true,'Attendance correction approved');
const assignment=await api('POST',`/api/cohorts/${cohort.id}/assignments`,admin.accessToken,{title:'설계 과제',description:'기능과 테이블 관계를 설명해주세요.',dueAt:at(24),maxScore:100},201);
const submission=await api('PUT',`/api/assignments/${assignment.id}/submission`,a.accessToken,{body:'과제 설명을 제출합니다.',repositoryUrl:'https://example.com/repository'});
await api('PATCH',`/api/submissions/${submission.id}/review`,admin.accessToken,{score:88,feedback:'구조가 명확합니다.'});
await api('PUT',`/api/assignments/${assignment.id}/submission`,a.accessToken,{body:'검토 후 수정 불가',repositoryUrl:''},409);check(true,'Submission review locks further editing');
const group=await api('POST',`/api/cohorts/${cohort.id}/study-groups`,a.accessToken,{name:'함께 복습하는 스터디',description:'정원을 검증합니다.',capacity:2},201);
await api('POST',`/api/study-groups/${group.id}/members/me`,b.accessToken,undefined,201);
await api('POST',`/api/study-groups/${group.id}/members/me`,admin.accessToken,undefined,409);check(true,'Study group capacity enforced');
const assessment=await api('POST',`/api/cohorts/${cohort.id}/assessments`,admin.accessToken,{title:'DB 기초 평가',description:'객관식 검증',domain:'DATABASE',startAt:at(-1),endAt:at(1),durationMinutes:20},201);
await api('POST',`/api/assessments/${assessment.id}/questions`,admin.accessToken,{prompt:'데이터 무결성을 보장하는 제약은?',position:1,points:1,explanation:'외래키는 참조 무결성을 보장합니다.',options:[{label:'FOREIGN KEY',isCorrect:true},{label:'ORDER BY',isCorrect:false}]},201);
await api('PATCH',`/api/assessments/${assessment.id}`,admin.accessToken,{status:'PUBLISHED'});
const adminExam=await api('GET',`/api/assessments/${assessment.id}`,admin.accessToken);
const publicExam=await api('GET',`/api/assessments/${assessment.id}`,a.accessToken);
check(!JSON.stringify(publicExam).includes('isCorrect') && !JSON.stringify(publicExam).includes('explanation'),'Student cannot see answer key before grading');
const question=adminExam.questions[0],correct=question.options.find(x=>x.isCorrect);
const attempt=await api('POST',`/api/assessments/${assessment.id}/attempts`,a.accessToken,undefined,201);
await api('PUT',`/api/assessment-attempts/${attempt.id}/answers`,a.accessToken,{answers:[{questionId:question.id,optionId:correct.id}]});
const graded=await api('POST',`/api/assessment-attempts/${attempt.id}/submit`,a.accessToken);
check(graded.score===100,'Server-side assessment grading');
const notifications=await api('GET','/api/notifications',a.accessToken);check(notifications.total>0,'Business actions create notifications');
await api('POST','/api/notifications/read-all',a.accessToken);
const refreshed=await api('POST','/api/auth/refresh',null,{refreshToken:a.refreshToken});
await api('POST','/api/auth/refresh',null,{refreshToken:a.refreshToken},401);
await api('POST','/api/auth/refresh',null,{refreshToken:refreshed.refreshToken},401);check(true,'Refresh replay revokes token family');
const docs=await api('GET','/api-docs');check(docs.openapi.startsWith('3.'),'OpenAPI served by backend');
console.log(`\n${checks} local MariaDB checks passed.`);
