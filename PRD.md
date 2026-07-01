# PRD: SpeedyCAT MCAT MVP

## Product Summary

Build a unified MCAT study app by extending Anki (desktop) and AnkiDroid (mobile) with two new study modes alongside existing flashcards: a UWorld-like practice question bank and AAMC full-length practice tests. SpeedyCAT delivers the MCAT "holy trinity"—Anki flashcards, application practice, and official-style full-length exams—in one app so students stop juggling expensive, fragmented tools.

The MVP integrates these three modalities with immediate feedback after question submission, session persistence, and sync between phone and desktop. It does not include AI tutoring, unified scoring dashboards, or other advanced features; those ship in the final product.

Everything needed to score well is already on the internet; SpeedyCAT's job is integration into one frictionless platform, not reinvention of content.

## Proof-of-Concept Content Disclaimer

Two of SpeedyCAT's content types stand in for material that is proprietary and licensed, so they are **AI-generated and provided only as a proof-of-concept**:

- **CARS questions** — AI-generated proof-of-concept items. Authentic, openly-licensed full-length CARS passages with answer keys effectively do not exist, and the real equivalents (UWorld, AAMC) are licensed.
- **Full-length practice tests** — AI-generated proof-of-concept stand-ins for the official, licensed AAMC full-length exams.

This AI-generated content **may not be completely true to the difficulty, style, or formatting of the real MCAT**; it exists only to demonstrate SpeedyCAT's end-to-end functionality. It is **generated offline during development, not by any runtime AI** — the shipped app still has **no AI/LLM dependency at runtime** (see Architecture Requirements). A production release would replace these placeholders with properly **licensed UWorld/AAMC content and attribution**.

(The bundled flashcards and the CPBS/BBLS/PSBB discrete practice questions are **not** AI-generated — they are scraped from named, openly-licensed sources such as OpenStax and LibreTexts with per-item attribution.)

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

1. **Flashcards (existing Anki)** — Deck/note/card model, FSRS scheduler, sync; ships a built-in MCAT flashcard library (~5,150 cards across CPBS, BBLS, PSBB).
2. **Practice Question Bank (new)** — UWorld-like MCAT items; open-ended practice sessions with optional time limit; post-submit explanations; source attribution; session logging and sync.
3. **AAMC Full-Length Practice Tests (new)** — Timed official AAMC full-length exams (~6h 15m testing time); exam-like section flow; post-test report; attempt history and sync.

MCAT sections covered: CPBS, CARS, BBLS, PSBB.

## Functional Requirements

### Flashcards

- Retain existing Anki deck/note/card model, FSRS scheduler, and sync with no regression when SpeedyCAT question-bank and test features are unused.
- Ship a **built-in flashcard library** so students start reviewing immediately—no hunting for or manually importing decks. It bundles two free, redistributable community Anki decks:
  - **MileDown MCAT** (~2,900 cards) — science sections CPBS, BBLS, and PSBB, including required equations, units, and constants.
  - **Mr. Pankow P/S** (~2,254 cards) — Psychology/Sociology (PSBB) only, for deeper P/S coverage.
  - ~5,150 cards combined, covering CPBS, BBLS, and PSBB.
- CARS is intentionally **not** covered by flashcards (flashcards are ineffective for CARS); CARS prep is handled through the practice question bank and AAMC full-length tests instead.
- Default organization: merge the bundled decks under a single parent deck named **"SpeedyCAT MCAT"**, with each original deck preserved as a subdeck.
- Preserve named-source attribution for every bundled deck (deck name, author, source URL/license), consistent with SpeedyCAT's existing source-attribution requirements and AGPL-3.0-or-later + Anki credit.
- The AnKing MCAT deck is deliberately **not** bundled—it is gated behind AnkiHub (paid login) and not freely redistributable; it remains a possible future optional add-on.
- Continue to support MCAT-specific deck templates and user-imported decks alongside the built-in library.
- Flashcard review remains the default Anki experience—no SpeedyCAT-specific gating.

### Practice Question Bank

> **Note:** **CARS** items are **AI-generated proof-of-concept** content (see *Proof-of-Concept Content Disclaimer*) and may not match real MCAT CARS difficulty/formatting. CPBS/BBLS/PSBB items are scraped from named, openly-licensed sources with per-item attribution.

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
- **Proof-of-concept:** absent an AAMC license, the MVP's full-length forms are **AI-generated placeholders** (not official AAMC content) and may not match real MCAT difficulty/formatting; a production build loads only officially licensed AAMC packages as above. See *Proof-of-Concept Content Disclaimer*.

### Content and Import

- Import question-bank items from a structured bundle format (schema in technical design doc).
- Display named source attribution in UI for every question-bank item.
- Reject question-bank imports missing source or license metadata.
- AAMC exams loaded from licensed packages, not scraped or user-uploaded without authorization.

## Data Model

SpeedyCAT's data model has two layers:

- **Content models** (bundled, read-only study material): `Flashcard`, `PracticeQuestion`, `CarsPassageSet`, and `FullLengthTest`.
- **Session / attempt models** (user-generated, synced): `PracticeSession`, `PracticeSessionAttempt`, and `AamcTestAttempt`.

Each field below is listed as `field` — type, nullability, followed by description and allowed values. `Flashcard` mirrors `content/flashcards/cards.json`; `PracticeQuestion` and `CarsPassageSet` mirror `content/practice-questions/questions.json`. CARS passages/questions and full-length tests are **AI-generated proof-of-concept** content (see *Proof-of-Concept Content Disclaimer*); flashcards and CPBS/BBLS/PSBB questions are scraped from named, openly-licensed sources.

### `Flashcard`

The built-in deck-library item: one rendered Anki card from the bundled MCAT decks (MileDown MCAT, Mr. Pankow P/S). Backed by Anki's native deck/note/card model and FSRS scheduler, and reviewed through the existing Anki pipeline — **not** through `PracticeSession`. Mirrors `content/flashcards/cards.json`; the bundle JSON uses snake_case keys, and the model field names are the camelCase equivalents shown in parentheses.

- `cardId` — integer, required. Primary key; Anki card id (`card_id`).
- `noteId` — integer, required. Foreign key to the backing Anki note (`note_id`).
- `ord` — integer, required. 0-based template ordinal within the note; distinguishes the multiple cards a single note can generate.
- `source` — string, required. The **single** source-attribution field (e.g., `"MileDown MCAT"`, `"Mr. Pankow P/S"`). Source is stored only here — it is not duplicated into `topic`, `tags`, or `notetype`.
- `topic` — string, required. Source-stripped topic label (e.g., `"General Chemistry"`).
- `notetype` — string, required. Source-neutralized Anki notetype name (e.g., `"Cloze-b279e"`).
- `fields` — map<string, string>, required. Raw Anki note fields (field name → value) backing the card.
- `tags` — string[], required (may be empty). Topic-only Anki tags.
- `questionHtml` — string, required. Engine-rendered front/question HTML (`question_html`).
- `answerHtml` — string, required. Engine-rendered back/answer HTML (`answer_html`).
- `questionText` — string, required. Plain-text question, HTML stripped (`question_text`).
- `answerText` — string, required. Plain-text answer, HTML stripped (`answer_text`).

**Relationships:** one Anki note (`noteId`) → one or more `Flashcard`s, each a distinct `cardId` with its own `ord`. Independent of the practice-question and full-length-test models.

### `PracticeQuestion`

A single multiple-choice item — either **discrete** (standalone) or **passage-linked** (belongs to a `CarsPassageSet` or a `FullLengthTest` section passage). Mirrors `content/practice-questions/questions.json`.

- `id` — string, required. Primary key (e.g., `"cpbs-001"`, `"cars-001"`).
- `section` — enum `CPBS | CARS | BBLS | PSBB`, required.
- `passageId` — string, **nullable**. Foreign key → `CarsPassageSet.passageId` (CARS bank) or a `FullLengthTest` section passage's `passageId`. `null` for discrete questions.
- `stem` — string, required. The question prompt. (Passage text is not stored here when `passageId` is set — it is canonical on the passage; see note below.)
- `choices` — array of `{ label: A | B | C | D, text: string }`, required. Exactly 4 options in the current bundle; each `label` is unique.
- `correctAnswer` — enum `A | B | C | D`, required. Must equal one `choices[].label`.
- `explanation` — string, required. Shown only after submit (retrieval-practice guardrail).
- `questionType` — string, nullable. Optional item classifier (e.g., discrete vs. passage-based, or skill type). Reserved; not yet populated in the sample bundle.
- `topicTags` — string[], required (may be empty). Topic/skill tags for filtering.
- `difficulty` — enum `easy | medium | hard`, required.
- `sourceName` — string, required. Named, human-readable source.
- `sourceLicense` — string, required. License/attribution string; proprietary or unclear rights are flagged inline with `LICENSE-UNCERTAIN` and must not ship without clearance.
- `sourceUrl` — string, nullable. Direct URL of the scraped source page (traceability).
- `answerProvenance` — enum `source-answer-key | source-solution-guide | verified-from-source-text`, required for scraped items. How `correctAnswer`/`explanation` were obtained.
- `notes` — string, nullable. Per-item provenance / licensing notes.

> **Note:** `section = "CARS"` items are **AI-generated proof-of-concept** (see *Proof-of-Concept Content Disclaimer*); CPBS/BBLS/PSBB items are scraped from named, openly-licensed sources. In the sample bundle, passage text is denormalized (inlined as a `passage` field on each passage-linked item) for convenience; in the normalized model the passage text lives once on `CarsPassageSet.passage` / a `FullLengthTest` section passage and is referenced by `passageId`.

**Relationships:** referenced by `PracticeSessionAttempt.questionId`. Grouped into a `CarsPassageSet` (CARS) or contained by a `FullLengthTest` section via `passageId` / containment.

### `CarsPassageSet`

A CARS reading passage grouped with the questions that hang off it. **AI-generated proof-of-concept** (see *Proof-of-Concept Content Disclaimer*).

- `passageId` — string, required. Primary key; the shared id its questions reference (e.g., `"cars-passage-bemo-03"`).
- `section` — constant `"CARS"`, required.
- `title` — string, required. Passage title/label.
- `passage` — string, required. Full passage text (multi-paragraph).
- `discipline` — enum `humanities | social sciences`, required. CARS content discipline.
- `wordCount` — integer, required. Passage length in words.
- `topicTags` — string[], required (may be empty).
- `difficulty` — enum `easy | medium | hard`, required.
- `sourceName` — string, required.
- `sourceLicense` — string, required. Some POC CARS sources are flagged `LICENSE-UNCERTAIN` and are not cleared for redistribution.
- `questions` — array of `PracticeQuestion`, required. The items sharing this `passageId` (each has `section = "CARS"` and `passageId =` this set's id).

**Relationships:** one `CarsPassageSet` (`passageId`) → many `PracticeQuestion`s (via `PracticeQuestion.passageId`).

### `FullLengthTest`

A full-length practice exam that **mirrors the AAMC full-length structure** (four sections, 230 questions, 6h 15m / 22,500 s testing time). **AI-generated proof-of-concept — not official AAMC content** (see *Proof-of-Concept Content Disclaimer*). Refines the former `AamcFullLengthTest`.

- `testId` — string, required. Primary key.
- `title` — string, required.
- `source` — string, required. Provenance/attribution label (POC: AI-generated).
- `format` — string, required. Structure descriptor (e.g., `"AAMC full-length"`).
- `disclaimer` — string, required. Proof-of-concept disclaimer (AI-generated; not official AAMC).
- `totalQuestions` — integer, required. `230`.
- `totalTestingSeconds` — integer, required. `22500` (6h 15m; equals the sum of section `durationSeconds`).
- `totalBreakSeconds` — integer, required. Sum of `breaks[].durationSeconds` (e.g. `1800` for three ~10-min breaks). Computed from `breaks` on load.
- `breaks` — array of break objects, required (may be empty). The real-MCAT scheduled breaks the UI enforces between sections. When the source bundle omits them, the backend **synthesizes** the standard breaks on import. Each break object:
  - `afterSection` — integer, required. 1-based `order` of the section this break follows. Standard breaks follow sections `1`, `2`, and `3`.
  - `durationSeconds` — integer, required. Break length; `600` (~10 min) for each standard MCAT break (optional break after section 1, the mid-exam break after section 2, and the optional break after section 3).
  - `optional` — boolean, required. Whether the student may skip/shorten the break (all standard breaks are optional).
  - `label` — string, required. Display label, e.g. `"Break"`, or `"Mid-exam break"` for the break after section 2.
- `sections` — array of section objects, required. Exactly four, one per MCAT section. Each section object:
  - `sectionId` — enum `CPBS | CARS | BBLS | PSBB`, required.
  - `order` — integer, required. 1-based section order.
  - `durationSeconds` — integer, required. `5700` for CPBS / BBLS / PSBB; `5400` for CARS.
  - `questionCount` — integer, required. `59` (CPBS), `53` (CARS), `59` (BBLS), `59` (PSBB) — sums to `230`.
  - `passages` — array of passage objects, required (may be empty for an all-discrete section). Each passage object carries the same descriptive fields as a `CarsPassageSet` passage (`passageId`, `title`, `passage`, `topicTags`, `difficulty`, plus `discipline` for CARS) but **without** its own `questions[]` — the section's `questions[]` link in via `passageId`.
  - `questions` — array of `PracticeQuestion`, required. Discrete items have `passageId = null`; passage-linked items set `passageId` to a passage in this section's `passages[]`.

**Relationships:** referenced by `AamcTestAttempt.testId`. Contains `PracticeQuestion`s and passage objects, scoped per section.

### `PracticeSession`

User-generated and synced. Groups one open-ended practice run.

- `sessionId` — string, required. Primary key.
- `userId` — string / collection scope, required. Owning user / Anki collection.
- `filter` — object, required. Active filter (section, topics, missed-only, flagged, etc.).
- `startedAt` — timestamp, required.
- `completedAt` — timestamp, nullable. `null` while in progress; set on completion (completed sessions are immutable).

**Relationships:** one `PracticeSession` → many `PracticeSessionAttempt`s.

### `PracticeSessionAttempt`

One answered question within a `PracticeSession`.

- `attemptId` — string, required. Primary key.
- `sessionId` — string, required. Foreign key → `PracticeSession.sessionId`.
- `questionId` — string, required. Foreign key → `PracticeQuestion.id`.
- `selectedAnswer` — enum `A | B | C | D`, nullable. `null` (empty) if skipped/unanswered.
- `correct` — boolean, required.
- `timeOnQuestionSeconds` — integer, required. Seconds spent on the question.
- `section` — enum `CPBS | CARS | BBLS | PSBB`, required. Section this attempt is attributed to; drives per-section tracking.
- `topic` — string, required (may be empty). Topic this attempt is attributed to; the single attribution key for per-topic tracking (`get_topic_stats` groups attempts by `section` + `topic`). The caller supplies it (typically the question's primary/filter topic), since a question can carry multiple `topicTags`.

### `AamcTestAttempt`

User-generated and synced. One attempt at a `FullLengthTest`. (Name retained for continuity; it references `FullLengthTest`.)

- `attemptId` — string, required. Primary key.
- `testId` — string, required. Foreign key → `FullLengthTest.testId`.
- `aamcExamId` — string, nullable. Official AAMC exam id when a licensed form is used; `null` for AI-generated proof-of-concept forms.
- `startedAt` — timestamp, required.
- `completedAt` — timestamp, nullable. `null` while in progress.
- `sectionResults` — array of `{ section: CPBS|CARS|BBLS|PSBB, correct: int, total: int, scaledScore: int|null }`, required.
- `overallScaledScore` — integer (472–528), nullable. Present only when scoring rules / licensed content provide it.

### Relationships at a glance

- Anki note `noteId` — 1 → N — `Flashcard` (`cardId`).
- `CarsPassageSet.passageId` — 1 → N — `PracticeQuestion` (`passageId`); discrete questions have `passageId = null`.
- `FullLengthTest` — 1 → 4 — sections; each section holds N passages + N `PracticeQuestion`s (passage-linked questions reference a section passage's `passageId`).
- `PracticeSession.sessionId` — 1 → N — `PracticeSessionAttempt`; each attempt → 1 `PracticeQuestion` (`questionId`).
- `FullLengthTest.testId` — 1 → N — `AamcTestAttempt` (`testId`).

### Proposed Rust / proto domains (MVP)

- `Flashcard` — reuses Anki's existing **deck/note/card + FSRS** domain (no new domain; rendered via existing collection APIs).
- `PracticeQuestion` — storage, tagging, filtering.
- `CarsPassageSet` — CARS passage grouping (passage + linked questions).
- `FullLengthTest` — full-length definitions, section timers, per-section passages/questions, attempt scoring (replaces `AamcPracticeTest`).
- `PracticeSession` — session state, attempt logs, timing.

**Implemented backend (MVP foundation).** The domains above are implemented as a real Rust change plus protobuf: `proto/anki/practice.proto` (`PracticeService`), `rslib/src/practice/` (module + `impl PracticeService for Collection`), and `rslib/src/storage/practice/` (collection-DB tables added via schema-19 migration: `practice_questions`, `practice_passages`, `practice_sessions`, `practice_attempts`, `full_length_tests`, `full_length_attempts`). Python wrappers live on `Collection` in `pylib/anki/collection.py`. The RPC surface:

- Content import: `LoadPracticeQuestionBundle`, `LoadFullLengthTestBundle` (parse `content/practice-questions/*.json` incl. the CARS `passageSets` format, and `content/full-length-tests/*.json`; reject items missing source/license metadata; synthesize breaks).
- Query: `GetPracticeQuestions` (section/topic/difficulty/passage/missed-only filter), `GetCarsPassageSet` (passage + its questions), `ListPassages`.
- Practice sessions: `StartPracticeSession`, `RecordPracticeAttempt`, `EndPracticeSession` (post-session summary).
- Full-length: `ListFullLengthTests`, `GetFullLengthTest`, `StartFullLengthAttempt` (returns timers + breaks), `RecordFullLengthAnswer`, `SubmitFullLengthAttempt` (per-section results).
- Tracking: `GetTopicStats` — time-spent + accuracy aggregated by `topic` and by `section`, with an optional section filter and attempt-source filter (all / practice-session / full-length).

Both apps run with AI off; nothing here calls an LLM at runtime.

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
- In production, AAMC exams are served only from licensed packages with proper attribution; the proof-of-concept uses **AI-generated placeholder** full-length forms (see *Proof-of-Concept Content Disclaimer*).
- No AI tutor UI, hint system, or LLM network calls exist anywhere in the MVP build.
- No Memory, Performance, or Readiness scoring dashboard in the MVP build.
- All MVP functionality works on desktop and mobile.
- SpeedyCAT changes remain AGPL-3.0-or-later with Anki attribution preserved.
