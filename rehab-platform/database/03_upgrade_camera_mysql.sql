-- Existing MySQL installations only. Back up first, select your project database.
-- New databases: 01_schema_mysql.sql already includes these structures.
USE rehab_platform;
ALTER TABLE repetition_result MODIFY COLUMN amplitude_ok BOOLEAN NULL;
ALTER TABLE repetition_result MODIFY COLUMN tempo_ok BOOLEAN NULL;
ALTER TABLE repetition_result MODIFY COLUMN trunk_ok BOOLEAN NULL;
ALTER TABLE repetition_result MODIFY COLUMN hold_ok BOOLEAN NULL;
ALTER TABLE feature_sample MODIFY COLUMN angle DOUBLE NULL;
-- The application checks metadata and adds these columns automatically if absent:
-- ALTER TABLE plan_item ADD COLUMN cycle_min_seconds DOUBLE NULL;
-- ALTER TABLE plan_item ADD COLUMN cycle_max_seconds DOUBLE NULL;
CREATE TABLE IF NOT EXISTS observation_record (
 session_id BIGINT PRIMARY KEY,
 assessment_json TEXT NOT NULL,
 captures_json MEDIUMTEXT NOT NULL,
 FOREIGN KEY(session_id) REFERENCES training_session(id)
);
