-- MySQL 8.0.16+; first-version course demo schema. No DROP statements.
CREATE DATABASE IF NOT EXISTS rehab_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rehab_platform;

CREATE TABLE IF NOT EXISTS app_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(64) NOT NULL,
 role VARCHAR(16) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK (role IN ('TRAINEE','GUIDE','ADMIN'))
);
CREATE TABLE IF NOT EXISTS auth_session (
 token_hash VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL,
 expires_at TIMESTAMP NOT NULL, FOREIGN KEY(user_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS guide_relation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, guide_id BIGINT NOT NULL, trainee_id BIGINT NOT NULL UNIQUE,
 FOREIGN KEY(guide_id) REFERENCES app_user(id), FOREIGN KEY(trainee_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS training_baseline (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, trainee_id BIGINT NOT NULL, note VARCHAR(500) NOT NULL,
 recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(trainee_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS exercise_template (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(40) NOT NULL UNIQUE,
 name VARCHAR(80) NOT NULL, description VARCHAR(1000) NOT NULL,
 camera_view VARCHAR(64) NOT NULL, joint_names VARCHAR(100) NOT NULL
);
CREATE TABLE IF NOT EXISTS rule_version (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, exercise_id BIGINT NOT NULL,
 version VARCHAR(30) NOT NULL, target_min DOUBLE NOT NULL, target_max DOUBLE NOT NULL,
 min_duration DOUBLE NOT NULL, max_duration DOUBLE NOT NULL, trunk_limit DOUBLE NOT NULL,
 UNIQUE(exercise_id,version), FOREIGN KEY(exercise_id) REFERENCES exercise_template(id),
 CHECK (target_min >= 0 AND target_max > target_min)
);
CREATE TABLE IF NOT EXISTS training_plan (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, trainee_id BIGINT NOT NULL, guide_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL, scheduled_date DATE NOT NULL, version INT NOT NULL DEFAULT 1,
 status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED', note VARCHAR(500),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(trainee_id) REFERENCES app_user(id), FOREIGN KEY(guide_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS plan_item (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, plan_id BIGINT NOT NULL, exercise_id BIGINT NOT NULL,
 rule_id BIGINT NOT NULL, sets INT NOT NULL, repetitions INT NOT NULL,
 target_min DOUBLE NOT NULL, target_max DOUBLE NOT NULL, rest_seconds INT NOT NULL,
 hold_seconds DOUBLE NOT NULL DEFAULT 0, cycle_min_seconds DOUBLE NULL, cycle_max_seconds DOUBLE NULL,
 FOREIGN KEY(plan_id) REFERENCES training_plan(id), FOREIGN KEY(exercise_id) REFERENCES exercise_template(id),
 FOREIGN KEY(rule_id) REFERENCES rule_version(id), UNIQUE(plan_id,exercise_id),
 CHECK(sets BETWEEN 1 AND 5 AND repetitions BETWEEN 1 AND 20),
 CHECK(target_min >= 0 AND target_max > target_min AND target_max <= 180)
);
CREATE TABLE IF NOT EXISTS training_session (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, plan_id BIGINT NOT NULL, trainee_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'RUNNING', started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ended_at TIMESTAMP NULL, rule_snapshot TEXT NOT NULL, algorithm_version VARCHAR(64) NOT NULL,
 data_source VARCHAR(24) NOT NULL DEFAULT 'SIMULATED', total_reps INT NOT NULL DEFAULT 0,
 valid_reps INT NOT NULL DEFAULT 0, issue_count INT NOT NULL DEFAULT 0, duration_seconds DOUBLE NOT NULL DEFAULT 0,
 KEY idx_session_trainee_time (trainee_id,started_at),
 FOREIGN KEY(plan_id) REFERENCES training_plan(id), FOREIGN KEY(trainee_id) REFERENCES app_user(id),
 CHECK(status IN ('RUNNING','COMPLETED','REVIEWED'))
);
CREATE TABLE IF NOT EXISTS session_set (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, plan_item_id BIGINT NOT NULL,
 set_index INT NOT NULL, UNIQUE(session_id,plan_item_id,set_index),
 FOREIGN KEY(session_id) REFERENCES training_session(id), FOREIGN KEY(plan_item_id) REFERENCES plan_item(id)
);
CREATE TABLE IF NOT EXISTS repetition_result (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, set_id BIGINT NOT NULL, rep_index INT NOT NULL,
 start_time DOUBLE NOT NULL, end_time DOUBLE NOT NULL, peak_angle DOUBLE NOT NULL,
 duration_seconds DOUBLE NOT NULL, amplitude_ok BOOLEAN NULL, tempo_ok BOOLEAN NULL,
 trunk_ok BOOLEAN NULL, hold_ok BOOLEAN NULL, complete BOOLEAN NOT NULL,
 UNIQUE(set_id,rep_index), FOREIGN KEY(set_id) REFERENCES session_set(id), CHECK(end_time > start_time)
);
CREATE TABLE IF NOT EXISTS issue_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, repetition_id BIGINT NOT NULL,
 event_time DOUBLE NOT NULL, code VARCHAR(40) NOT NULL, message VARCHAR(300) NOT NULL,
 observed DOUBLE NOT NULL, threshold_value VARCHAR(80) NOT NULL,
 KEY idx_event_session_time (session_id,event_time),
 FOREIGN KEY(session_id) REFERENCES training_session(id), FOREIGN KEY(repetition_id) REFERENCES repetition_result(id)
);
CREATE TABLE IF NOT EXISTS feature_sample (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, set_id BIGINT NOT NULL,
 sample_time DOUBLE NOT NULL, angle DOUBLE NULL, trunk_offset DOUBLE NOT NULL,
 visibility DOUBLE NOT NULL, phase VARCHAR(24) NOT NULL,
 FOREIGN KEY(session_id) REFERENCES training_session(id), FOREIGN KEY(set_id) REFERENCES session_set(id),
 UNIQUE(session_id,sample_time)
);
CREATE TABLE IF NOT EXISTS review_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, guide_id BIGINT NOT NULL,
 event_id BIGINT NULL, decision VARCHAR(20) NOT NULL, note VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(session_id) REFERENCES training_session(id), FOREIGN KEY(guide_id) REFERENCES app_user(id),
 FOREIGN KEY(event_id) REFERENCES issue_event(id), CHECK(decision IN ('CONFIRMED','REJECTED','COMMENT'))
);
CREATE TABLE IF NOT EXISTS plan_adjustment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, old_plan_id BIGINT NOT NULL, new_plan_id BIGINT NOT NULL,
 guide_id BIGINT NOT NULL, reason VARCHAR(500) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(old_plan_id) REFERENCES training_plan(id), FOREIGN KEY(new_plan_id) REFERENCES training_plan(id),
 FOREIGN KEY(guide_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS game_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, trainee_id BIGINT NOT NULL, session_id BIGINT NULL,
 cycles INT NOT NULL, duration_seconds INT NOT NULL, input_mode VARCHAR(30) NOT NULL DEFAULT 'POINTER',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(trainee_id) REFERENCES app_user(id), FOREIGN KEY(session_id) REFERENCES training_session(id),
 CHECK(cycles BETWEEN 1 AND 100 AND duration_seconds BETWEEN 1 AND 3600)
);

CREATE TABLE IF NOT EXISTS observation_record (
 session_id BIGINT PRIMARY KEY, assessment_json TEXT NOT NULL, captures_json MEDIUMTEXT NOT NULL,
 FOREIGN KEY(session_id) REFERENCES training_session(id)
);
