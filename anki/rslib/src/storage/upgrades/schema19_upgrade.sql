-- SpeedyCAT schema 19: Practice Question Bank + Full-Length Practice Tests +
-- per-topic tracking. All tables are local to the collection (no usn/sync
-- columns); see rslib/src/storage/practice/.
CREATE TABLE practice_questions (
  id text NOT NULL PRIMARY KEY,
  section text NOT NULL,
  passage_id text,
  test_id text,
  stem text NOT NULL,
  choices text NOT NULL,
  correct_answer text NOT NULL,
  explanation text NOT NULL,
  question_type text,
  topic_tags text NOT NULL,
  difficulty text NOT NULL,
  source_name text NOT NULL,
  source_license text NOT NULL,
  source_url text,
  answer_provenance text,
  notes text,
  -- SpeedyCAT graduated hint ladder: JSON array of scaffolding subquestions
  -- (levels 1..3). NULL when a question has no hints. Content generated
  -- separately into the question bundles.
  hints text
) WITHOUT ROWID;
CREATE INDEX idx_practice_questions_section ON practice_questions (section);
CREATE INDEX idx_practice_questions_passage ON practice_questions (passage_id);
CREATE INDEX idx_practice_questions_test ON practice_questions (test_id);
CREATE TABLE practice_passages (
  passage_id text NOT NULL PRIMARY KEY,
  section text NOT NULL,
  test_id text,
  title text NOT NULL,
  passage text NOT NULL,
  discipline text,
  word_count integer,
  topic_tags text NOT NULL,
  difficulty text NOT NULL,
  source_name text,
  source_license text
) WITHOUT ROWID;
CREATE INDEX idx_practice_passages_section ON practice_passages (section);
CREATE INDEX idx_practice_passages_test ON practice_passages (test_id);
CREATE TABLE practice_sessions (
  id text NOT NULL PRIMARY KEY,
  filter text NOT NULL,
  time_limit_seconds integer NOT NULL DEFAULT 0,
  started_at integer NOT NULL,
  completed_at integer
) WITHOUT ROWID;
CREATE TABLE practice_attempts (
  id text NOT NULL PRIMARY KEY,
  session_id text,
  full_length_attempt_id text,
  question_id text NOT NULL,
  selected_answer text NOT NULL DEFAULT '',
  correct integer NOT NULL,
  time_on_question_seconds integer NOT NULL,
  section text NOT NULL,
  topic text NOT NULL,
  answered_at integer NOT NULL,
  -- SpeedyCAT graduated hint ladder: highest hint tier reached before the
  -- answer was locked (0..3), and whether the learner reached level 3
  -- (assisted). Assisted-correct answers are penalized in the Performance
  -- pillar. Always 0 for full-length answers (no hint ladder there).
  hint_level_used integer NOT NULL DEFAULT 0,
  assisted integer NOT NULL DEFAULT 0
) WITHOUT ROWID;
CREATE INDEX idx_practice_attempts_session ON practice_attempts (session_id);
CREATE INDEX idx_practice_attempts_fl ON practice_attempts (full_length_attempt_id);
CREATE INDEX idx_practice_attempts_topic ON practice_attempts (section, topic);
CREATE TABLE full_length_tests (
  id text NOT NULL PRIMARY KEY,
  title text NOT NULL,
  source text NOT NULL,
  format text NOT NULL,
  disclaimer text NOT NULL,
  total_questions integer NOT NULL,
  total_testing_seconds integer NOT NULL,
  sections text NOT NULL,
  breaks text NOT NULL
) WITHOUT ROWID;
CREATE TABLE full_length_attempts (
  id text NOT NULL PRIMARY KEY,
  test_id text NOT NULL,
  aamc_exam_id text,
  started_at integer NOT NULL,
  completed_at integer,
  section_results text,
  overall_scaled_score integer
) WITHOUT ROWID;
CREATE INDEX idx_full_length_attempts_test ON full_length_attempts (test_id);
UPDATE col
SET ver = 19;
