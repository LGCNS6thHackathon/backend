-- Keep existing assignments/submissions readable while adding the current form fields.
ALTER TABLE assignments ADD COLUMN subject VARCHAR(100) NULL;
ALTER TABLE submissions ADD COLUMN deployment_url VARCHAR(2000) NULL;
