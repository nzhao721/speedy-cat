# PRD: SpeedyCAT MCAT MVP

## Product Summary

Build a unified MCAT study app by extending Anki (desktop) and AnkiDroid (mobile) with two new study modes alongside existing flashcards: a UWorld-like practice question bank and AAMC full-length practice tests. SpeedyCAT delivers the MCAT "holy trinity"—Anki flashcards, application practice, and official-style full-length exams—in one app so students stop juggling expensive, fragmented tools.

The MVP integrates these three modalities with immediate feedback after question submission, session persistence, and sync between phone and desktop. It does not include AI tutoring, unified scoring dashboards, or other advanced features; those ship in the final product.

Everything needed to score well is already on the internet; SpeedyCAT's job is integration into one frictionless platform, not reinvention of content.

## Target User

### Persona

**Age:** Typically 19–25 (undergraduate or post-bacc pre-med students).

**Education:** College student or recent graduate on a pre-med track, usually 1–2 years from applying to medical school.

**Study Pattern:** ~20 hours/week for ~3 months before test day (per AAMC averages).

**Flashcard Background:** Uses or is willing to use Anki for content review; comfortable with decks, tags, and daily review queues.

**Question-Bank Background:** Has used or wants UWorld-style blocks but dislikes switching apps and paying for multiple subscriptions.

**Test Background:** Relies on official AAMC full-length exams to estimate readiness; knows they are the most reliable predictors of real MCAT performance.

**Goal:** Score competitively on the MCAT (472–528 scale; four sections at 118–132 each) using one app for flashcards, daily question blocks, and AAMC practice exams—without wasting time or cognitive load switching between platforms.

### Pain Points

- Flashcards, question banks, and AAMC practice tests live on separate sites that cost thousands of dollars combined.
- Switching between apps spreads critical information across sources and increases cognitive load.
- UWorld-style practice requires a dedicated subscription and a separate workflow from Anki review.
- AAMC full-length exams are the gold standard for readiness but live outside the flashcard and question-bank tools students use daily.

## MVP Goals

The MVP should help a student:

- Review MCAT content with existing Anki flashcard decks and spaced repetition.
- Complete UWorld-like practice question blocks filtered by MCAT section and topic.
- Take official AAMC full-length practice tests in a timed, exam-like flow within the app.
- Receive immediate correctness feedback and explanations after submitting answers in the question bank.
- Sync question-bank progress and test attempt history between desktop and mobile.

The MVP will not:

- Include an AI tutor or any LLM-powered hinting.
- Include Memory, Performance, or Readiness scoring dashboards.
- Replace or redistribute AAMC exam content without proper licensing and attribution.
- Ship a video lecture library, mini diagnostics, or third-party deflated full-length exams.

## Final Product (Post-MVP)

Features planned after the MVP, not required for initial launch:

- **AI tutor** for question-bank sessions—system-timed, escalating hints with named-source grounding (Koedinger & Aleven assistance dilemma).
- **Unified scoring dashboard**—Memory, Performance, and Readiness as separate ranges with give-up rules.
- **Section-length and mini diagnostic tests** beyond AAMC full-lengths.
- **Cross-mode recommendations** linking weak topics to question-bank filters and flashcard decks.
- **Advanced analytics**—streaks, study time, assisted vs unassisted attempt tracking.

## Technical Constraints

### Required Stack

- **Core engine:** Anki Rust library (`anki/rslib`) with new logic implemented in Rust, not Python-only screens.
- **API layer:** Protobuf messages in `proto/anki/`, exposed through Python (`pylib/`) and TypeScript web UI (`ts/`).
- **Desktop:** Anki fork (`anki/`) — Rust + Python + Svelte/TS (PyQt shell).
- **Mobile:** AnkiDroid fork (`Anki-Android/`) — Kotlin, shared backend AAR.
- **Sync:** Existing Anki collection sync where possible.
- **License:** AGPL-3.0-or-later; preserve Anki attribution (`UPSTREAM.md`, in-app about screen).

### Architecture Requirements

- Every new backend capability needs a protobuf message and a Rust implementation in `rslib`.
- No AI or LLM dependencies in the MVP codebase or runtime.
- AAMC practice test content requires proper licensing and attribution.
- UWorld-like question bank content requires license-compatible attribution and named source metadata—no import without it.

### Recommended Architecture

- **Rust (`rslib`):** Practice question storage, session state, full-length test timing/scoring, attempt history.
- **Python (`pylib` / `aqt`):** Collection integration and desktop shell wiring.
- **TypeScript (`ts/`):** Web UI for question bank and AAMC test runner embedded in Qt web views.
- **AnkiDroid:** Consumes shared backend; use `local_backend=true` when shipping custom Rust builds to mobile.
- **Content bundles:** Structured import for question banks; AAMC full-length definitions linked to licensed exam packages.

### Platform Guardrails

- Completed practice sessions and test attempts are immutable; in-progress session conflicts resolve to latest attempt.
- Question-bank explanations are shown after submit—not before—to preserve retrieval practice.
- MVP has no network calls for tutoring, scoring services, or third-party AI APIs.

## Core User Stories

- As an MCAT student, I need to review flashcards with spaced repetition so that core content stays in memory throughout my prep.
- As an MCAT student, I need UWorld-like practice blocks—filtered by section and topic, with elimination, flagging, and scratch work—and my session progress available on any device I study from.
- As an MCAT student, I need to take official AAMC full-length exams inside SpeedyCAT with timed section structure, a post-test score report, and synced attempt history so that I can simulate test day and track readiness without leaving the app.

## MVP Feature Set

The MVP ships exactly three integrated study modes:

1. **Flashcards (existing Anki)** — Deck/note/card model, FSRS scheduler, sync; MCAT deck support.
2. **Practice Question Bank (new)** — UWorld-like MCAT items; open-ended practice sessions with optional time limit; post-submit explanations; source attribution; session logging and sync.
3. **AAMC Full-Length Practice Tests (new)** — Timed official AAMC full-length exams (~6h 15m testing time); exam-like section flow; post-test report; attempt history and sync.

MCAT sections covered: CPBS, CARS, BBLS, PSBB.

## Functional Requirements

### Flashcards

- Retain existing Anki deck/note/card model, FSRS scheduler, and sync with no regression when SpeedyCAT question-bank and test features are unused.
- Support MCAT-specific deck templates and importable MCAT decks (content strategy TBD).
- Flashcard review remains the default Anki experience—no SpeedyCAT-specific gating.

### Practice Question Bank

Each practice question must include:

- Stem (passage + prompt where applicable).
- Multiple-choice options (typically 4–5).
- Correct answer and explanation (shown after submit).
- Metadata: section, topic tags, difficulty, source name, license/attribution.

Session modes (MVP):

- **Practice mode** — Untimed; feedback and explanation after submit; student ends the session when done.
- **Timed session** — Optional time limit only; questions continue until time runs out or the student ends the session.

Session behavior:

- No preset number of questions per session; the student works through the filtered pool until they choose to stop (or time expires in timed sessions).
- One question at a time; elimination, flag-for-review, scratch area (local only, not sent to any service).
- Log selected answer, time on question (seconds), and correctness.
- Interleaved and focused filters (section, topic, missed-only, flagged).
- Post-session summary with correct/incorrect breakdown.

No tutor mode, hint system, or AI integration in MVP.

### AAMC Full-Length Practice Tests

Test types for MVP:

- **AAMC full-length** — Official AAMC practice exam; ~6h 15m MCAT testing time; four sections (CPBS, CARS, BBLS, PSBB).

Test session rules:

- Timed, exam-like UI aligned with AAMC section structure.
- No back-navigation to closed sections once a section is submitted.
- Flag-and-review within current section; auto-submit on section timer expiry.
- Break handling per AAMC exam structure (or documented simplification for v1).

Post-test report:

- Raw correct/total per section.
- Section scaled scores per AAMC scoring where licensed content provides it.
- Topic or skill breakdown where metadata exists.
- Comparison to prior attempts on the same exam form.

Content requirements:

- Only officially licensed AAMC exam packages; clear attribution in UI.
- Exam attempts stored locally and synced; no redistribution of exam content.

### Content and Import

- Import question-bank items from a structured bundle format (schema in technical design doc).
- Display named source attribution in UI for every question-bank item.
- Reject question-bank imports missing source or license metadata.
- AAMC exams loaded from licensed packages, not scraped or user-uploaded without authorization.

## Data Model

### `PracticeQuestion`

- `id`
- `stem`
- `choices[]`
- `correctAnswer`
- `explanation`
- `section` (CPBS | CARS | BBLS | PSBB)
- `topicTags[]`
- `difficulty`
- `sourceName`
- `sourceLicense`

### `PracticeSession`

- `sessionId`
- `userId` / collection scope
- `filter` (section, topics, missed-only, etc.)
- `startedAt`
- `completedAt`

### `PracticeSessionAttempt`

- `attemptId`
- `sessionId`
- `questionId`
- `selectedAnswer`
- `correct`
- `timeOnQuestionSeconds`

### `AamcFullLengthTest`

- `testId`
- `aamcExamId` (official exam identifier)
- `title`
- `sections[]` (section id, questionIds[], durationSeconds)
- `totalDurationSeconds`

### `AamcTestAttempt`

- `attemptId`
- `testId`
- `aamcExamId`
- `startedAt`
- `completedAt`
- `sectionResults[]` (section, correct, total, scaledScore)
- `overallScaledScore` (if provided by AAMC scoring rules)

### Proposed Rust / proto domains (MVP)

- `PracticeQuestion` — storage, tagging, filtering
- `PracticeSession` — session state, attempt logs, timing
- `AamcPracticeTest` — exam definitions, section timers, attempt scoring

Exact RPC names and messages to be defined in the technical design doc.

## Success Metrics

- Weekly active study sessions across flashcards, question bank, or AAMC tests.
- Percentage of weekly active users who complete at least one question-bank block within 30 days of launch.
- Question-block session completion rate (started → finished).
- Number of AAMC full-length attempts started and completed per user.
- Return rate after first AAMC full-length attempt.
- Sync success rate for completed question-bank sessions and test attempts ≥99%.
- P95 question load time <500ms local.

## MVP Acceptance Criteria

- A student can review flashcards using existing Anki workflows with no regression.
- A student can run an open-ended practice session on desktop and mobile, answer multiple questions with explanations after submit, end the session when done, and see results after sync.
- A student can take an AAMC full-length practice test end-to-end with section timers, answer persistence, and a post-test report.
- Question-bank items display named source attribution; imports without source metadata are rejected.
- AAMC exams are served only from licensed packages with proper attribution.
- No AI tutor UI, hint system, or LLM network calls exist anywhere in the MVP build.
- No Memory, Performance, or Readiness scoring dashboard in the MVP build.
- All MVP functionality works on desktop and mobile.
- SpeedyCAT changes remain AGPL-3.0-or-later with Anki attribution preserved.
