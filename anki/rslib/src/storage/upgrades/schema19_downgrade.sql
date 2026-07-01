-- Reverses the SpeedyCAT schema 19 migration. Practice tables are additive and
-- local-only, so a full downgrade simply drops them.
DROP TABLE IF EXISTS full_length_attempts;
DROP TABLE IF EXISTS full_length_tests;
DROP TABLE IF EXISTS practice_attempts;
DROP TABLE IF EXISTS practice_sessions;
DROP TABLE IF EXISTS practice_passages;
DROP TABLE IF EXISTS practice_questions;
UPDATE col
SET ver = 18;
