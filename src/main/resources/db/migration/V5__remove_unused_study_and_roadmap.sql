-- The current frontend has no standalone study-group or old roadmap screens.
-- These obsolete features and their existing rows are removed. Active calendar,
-- curriculum, assessment results, assignments and shared account data are retained.
DELETE FROM notifications WHERE resource_type = 'STUDY_GROUP';
DROP TABLE study_group_members;
DROP TABLE study_groups;
DROP TABLE roadmap_modules;
DROP TABLE roadmap_phases;
