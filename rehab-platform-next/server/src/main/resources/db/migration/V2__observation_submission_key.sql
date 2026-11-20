ALTER TABLE training_observation ADD COLUMN submission_key VARCHAR(80);
ALTER TABLE training_observation ADD COLUMN capture_digest CHAR(64);
ALTER TABLE training_observation ADD CONSTRAINT uq_observation_submission UNIQUE (submission_key);
ALTER TABLE training_observation ADD CONSTRAINT ck_observation_submission_digest
    CHECK ((submission_key IS NULL) = (capture_digest IS NULL));
