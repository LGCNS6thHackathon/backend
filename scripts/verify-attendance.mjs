import assert from 'node:assert/strict';

// Creates synthetic data. Use only a disposable, loopback-bound verification server.
const base = new URL(process.argv[2] || 'http://127.0.0.1:18081');
assert(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname), 'Local verification only');
assert(['http:', 'https:'].includes(base.protocol), 'HTTP(S) required');
const email = process.env.ATTENDANCE_ADMIN_EMAIL;
const password = process.env.ATTENDANCE_ADMIN_PASSWORD;
assert(email && password, 'Set ATTENDANCE_ADMIN_EMAIL and ATTENDANCE_ADMIN_PASSWORD');
let checks = 0;
async function api(method, path, token, body, expected = 200) {
  const response = await fetch(new URL(path, base), {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error',
  });
  assert.equal(response.status, expected, `${method} ${path}: expected ${expected}, received ${response.status}`);
  return expected === 204 ? null : response.json();
}
function check(condition, label) { assert(condition, label); console.log(`PASS ${++checks}: ${label}`); }
const login = async (email, password) => (await api('POST', '/api/auth/login', null, { email, password })).accessToken;
const admin = await login(email, password);
const run = Date.now();
const studentPassword = 'LocalAttendanceVerify!42';
const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' });
const month = today.slice(0, 7);
const course = await api('POST', '/api/courses', admin, { title: `출결 API 검증 ${run}`, totalHours: 8 }, 201);
const cohort = await api('POST', `/api/courses/${course.id}/cohorts`, admin, { name: `출결 검증 ${run}`, startDate: today, endDate: today }, 201);
async function member(label, cohortRole = 'STUDENT') {
  const email = `attendance-${run}-${label}@example.test`;
  const user = await api('POST', '/api/users', admin, { email, password: studentPassword, name: `검증 ${label}` }, 201);
  if (cohortRole === 'INSTRUCTOR') await api('PATCH', `/api/users/${user.id}`, admin, { role: 'INSTRUCTOR' });
  await api('POST', `/api/cohorts/${cohort.id}/enrollments`, admin, { userId: user.id, role: cohortRole }, 201);
  return { id: user.id, token: await login(email, studentPassword) };
}
const student = await member('student'), other = await member('other');
const instructor = await member('instructor', 'INSTRUCTOR'), manager = await member('manager', 'MANAGER');
const recordsPath = `/api/cohorts/${cohort.id}/attendance`;
const calendarPath = `${recordsPath}/calendar?month=${month}`;
const correctionsPath = `${recordsPath}/corrections`;
const body = (status, sessionNo) => ({ userId: student.id, date: today, unitType: 'SESSION', sessionNo, status });
const record = await api('PUT', recordsPath, admin, body('ABSENT', 1));
check(record.status === 'ABSENT' && record.sessionNo === 1, 'ADMIN creates session attendance');
let calendar = await api('GET', calendarPath, student.token);
check(calendar.attendance.length === 1 && calendar.attendance[0].availableRequestedStatuses.join() === 'LATE,PRESENT', 'Calendar provides own record and correction targets');
await api('GET', calendarPath, null, undefined, 401);
await api('GET', `${recordsPath}/calendar?month=invalid`, student.token, undefined, 400);
check((await api('GET', calendarPath, other.token)).attendance.length === 0, 'Other student cannot see attendance');
await api('POST', `/api/attendance/${record.id}/corrections`, other.token, {}, 403);
const correction = await api('POST', `/api/attendance/${record.id}/corrections`, student.token, {}, 201);
await api('POST', `/api/attendance/${record.id}/corrections`, student.token, {}, 409);
calendar = await api('GET', calendarPath, student.token);
check(calendar.attendance[0].latestCorrection.id === correction.id && calendar.attendance[0].availableRequestedStatuses.length === 0, 'Pending request disables another request');
for (const role of [student, instructor, manager]) {
  await api('PUT', recordsPath, role.token, body('PRESENT', 2), 403);
  await api('DELETE', `/api/attendance/${record.id}`, role.token, undefined, 403);
  await api('PATCH', `/api/attendance/corrections/${correction.id}`, role.token, { status: 'APPROVED' }, 403);
}
check(true, 'Student, instructor and manager cannot manage attendance or approve requests');
check((await api('GET', correctionsPath, other.token)).total === 0 && (await api('GET', correctionsPath, manager.token)).total === 0, 'Correction lists enforce ownership');
await api('PATCH', `/api/attendance/corrections/${correction.id}`, admin, { status: 'APPROVED' });
calendar = await api('GET', calendarPath, student.token);
check(calendar.attendance[0].status === 'PRESENT' && calendar.attendance[0].latestCorrection.status === 'APPROVED', 'Approval updates student calendar');
await api('PATCH', `/api/attendance/corrections/${correction.id}`, admin, { status: 'APPROVED' }, 409);
check(true, 'Already handled approval returns 409');
const leave = await api('PUT', recordsPath, admin, body('LEAVE', 2));
let request = await api('POST', `/api/attendance/${leave.id}/corrections`, student.token, {}, 201);
await api('PATCH', `/api/attendance/corrections/${request.id}`, admin, { status: 'REJECTED', reviewNote: '추가 확인 필요' });
calendar = await api('GET', calendarPath, student.token);
check(calendar.attendance.find(x => x.id === leave.id).availableRequestedStatuses.join() === 'PRESENT', 'Rejection permits a new request');
request = await api('POST', `/api/attendance/${leave.id}/corrections`, student.token, {}, 201);
await api('PUT', recordsPath, admin, body('EXCUSED', 2));
await api('PATCH', `/api/attendance/corrections/${request.id}`, admin, { status: 'APPROVED' }, 409);
calendar = await api('GET', calendarPath, student.token);
check(calendar.attendance.find(x => x.id === leave.id).latestCorrection.status === 'INVALIDATED', 'Original edit invalidates pending request');
request = await api('POST', `/api/attendance/${leave.id}/corrections`, student.token, {}, 201);
await api('DELETE', `/api/attendance/${leave.id}`, admin, undefined, 204);
await api('PATCH', `/api/attendance/corrections/${request.id}`, admin, { status: 'APPROVED' }, 409);
calendar = await api('GET', calendarPath, student.token);
check(!calendar.attendance.some(x => x.id === leave.id), 'Deleted attendance is absent from calendar');
check((await api('GET', correctionsPath, admin)).items.find(x => x.id === request.id).status === 'INVALIDATED', 'Administrator can see invalidated request after deletion');
for (const [i, original] of ['ABSENT', 'LATE', 'EXCUSED', 'LEAVE', 'EARLY'].entries()) {
  const attendance = await api('PUT', recordsPath, admin, body(original, i + 10));
  await api('PUT', recordsPath, admin, body('PRESENT', i + 10), 409);
  const pending = await api('POST', `/api/attendance/${attendance.id}/corrections`, student.token, {}, 201);
  await api('PATCH', `/api/attendance/corrections/${pending.id}`, admin, { status: 'APPROVED' });
}
const absent = await api('PUT', recordsPath, admin, body('ABSENT', 20));
const late = await api('POST', `/api/attendance/${absent.id}/corrections`, student.token, { requestedStatus: 'LATE' }, 201);
await api('PATCH', `/api/attendance/corrections/${late.id}`, admin, { status: 'APPROVED' });
check(true, 'All six supported correction transitions work through approval');
const day = await api('PUT', recordsPath, admin, { userId: student.id, date: today, status: 'PRESENT' });
check(day.unitType === 'DATE' && day.sessionNo === 0, 'Day attendance coexists with session records');
const start = `${today}T09:00:00+09:00`, end = `${today}T18:00:00+09:00`;
const event = await api('POST', `/api/cohorts/${cohort.id}/events`, admin, { title: '검증 교육일정', type: 'LECTURE', startAt: start, endAt: end }, 201);
check((await api('GET', calendarPath, student.token)).events.some(x => x.id === event.id), 'Calendar includes cohort education events');
const foreign = await api('POST', `/api/courses/${course.id}/cohorts`, admin, { name: '다른 기수', startDate: today, endDate: today }, 201);
await api('GET', `/api/cohorts/${foreign.id}/attendance/calendar?month=${month}`, student.token, undefined, 403);
check(true, 'Cross-cohort calendar access is blocked');
console.log(`Completed ${checks} attendance checks. Synthetic cohort ID: ${cohort.id}. No tokens or credentials were logged.`);
