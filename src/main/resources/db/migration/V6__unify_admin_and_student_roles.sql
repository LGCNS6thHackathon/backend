-- Preserve account IDs, credentials, status and history while consolidating management roles.
-- Withdrawn cohort-only staff do not regain management access.
UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
WHERE revoked_at IS NULL AND user_id IN (
 SELECT u.id FROM users u WHERE u.role = 'INSTRUCTOR'
 OR (u.role = 'STUDENT' AND EXISTS (
  SELECT 1 FROM enrollments e WHERE e.user_id = u.id
  AND e.status = 'ACTIVE' AND e.role IN ('INSTRUCTOR','MANAGER')
 ))
);

UPDATE users SET role = 'ADMIN', token_version = token_version + 1, updated_at = CURRENT_TIMESTAMP
WHERE role = 'INSTRUCTOR' OR (role = 'STUDENT' AND EXISTS (
 SELECT 1 FROM enrollments e WHERE e.user_id = users.id
 AND e.status = 'ACTIVE' AND e.role IN ('INSTRUCTOR','MANAGER')
));

ALTER TABLE users DROP CONSTRAINT ck_users_role;
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('ADMIN','STUDENT'));

-- Enrollment roles are a compatibility projection of users.role, not an authority source.
ALTER TABLE enrollments DROP CONSTRAINT ck_enrollment_role;
UPDATE enrollments SET role = (SELECT u.role FROM users u WHERE u.id = enrollments.user_id),
 updated_at = CURRENT_TIMESTAMP;
ALTER TABLE enrollments ADD CONSTRAINT ck_enrollment_role CHECK (role IN ('ADMIN','STUDENT'));
