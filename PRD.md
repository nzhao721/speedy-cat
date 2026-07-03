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

> **Known constraint — mobile stats parity.** The current mobile build ships the **stock prebuilt backend AAR** (`io.github.david-allison:anki-android-backend`), whose bundled web assets render the **upstream Anki stats page** (with its own time-range + search choosers), *not* our SpeedyCAT dashboard (readiness pillars, practice/full-length panels). Those live in our fork's Rust RPCs + `anki/out/sveltekit`, which the stock AAR does not contain, so the desktop Dashboard cannot be mirrored on mobile by editing app code alone. Mobile therefore implements Practice + a 2-pillar Readiness (Memory + Performance) **natively in Kotlin**; the shared stats webview degrades gracefully (hides those sections instead of erroring) for the day our bundle ships. Full parity requires building a local `rsdroid` from the fork (`local_backend=true`), which swaps to `../Anki-Android-Backend/rsdroid/build/outputs/aar/rsdroid-release.aar` and bundles both our RPCs (`librsdroid.so` + generated proto) and our dashboard bundle. Feasibility: **GO-WITH-CAVEATS** (~2-4 days). Windows builds are supported and all missing tools are user-level installable (NDK `29.0.14206865`, Rust android targets — no admin needed); the dominant risk is **engine version skew** (our fork is Anki `26.05` while the backend + AnkiDroid Kotlin are pinned to `25.09.2`), best mitigated by rebasing the practice/readiness changes onto `25.09.2`.

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

#### Graduated hint ladder (subquestion-based)

A post-MVP addition to the Practice Question Bank (desktop **and** mobile). Instead of revealing the answer when a student is stuck, each question can carry a short **ladder of scaffolding SUBQUESTIONS** that reveal progressively more of the reasoning **without ever giving away the parent question's final answer** (Koedinger & Aleven assistance dilemma). Hint *content* is generated separately into the question bundles; the apps only parse + serve it (no LLM at runtime — both apps still run fully with AI off).

- **Schema.** Each question gains `hints`: an ordered array of up to 3 subquestion objects `{ level: 1|2|3, prompt, choices: [{label, text} ×4], correctAnswer: A–D, rationale }`. Each hint is itself a 4-choice MCQ with exactly one correct answer. Parsed **defensively**: a question may temporarily have missing/empty/malformed/<3 hints while generation is in progress — malformed tiers (not exactly 4 choices, a `correctAnswer` matching no choice, an empty prompt) are dropped and the well-formed ones kept; a question with no valid ladder simply disables the "Request hint" affordance.
- **Two entry paths.** The ladder starts when EITHER (a) the student presses **"Request hint"**, OR (b) the student submits a **WRONG** answer to the main question — a wrong choice does **not** reveal the correct answer; it enters/escalates the ladder instead.
- **No-skip rule.** Subquestions are presented **one at a time**. The student **must answer** the currently-revealed subquestion (select one of its 4 choices and submit it) before the next tier can be revealed or the main question can be (re)submitted — there is no skip / next-without-answering control, and the main answer stays locked until the ladder has been worked through step by step in order. After the last subquestion, the student returns to answer the main question; only once the ladder is **exhausted** does a subsequent wrong answer reveal the correct answer.
- **Tracking.** The highest subquestion **level reached** is recorded per attempt as `hintLevelUsed` (0–3); `assisted = (hintLevelUsed reached level 3)`. Both entry paths (Request-hint button and wrong-answer escalation) count. A correct answer given with no hints used is `hintLevelUsed = 0, assisted = false`.
- **Anti-gaming penalty (Performance pillar).** So students cannot game the tutor for a better readiness number, an **assisted-correct** attempt (reached level 3) does **not** count as a full correct: in the readiness **Performance** pillar's accuracy the credited count is `correct AND NOT assisted`, while the assisted-correct attempt still counts toward the denominator (answered). Unassisted correct = full credit; incorrect = incorrect regardless of assists; level-1/level-2 assists still earn full credit. Implemented in `rslib` `practice_accuracy_totals` (SQL `sum(correct = 1 and assisted = 0)`) and mirrored on mobile in `ReadinessLogic.computePerformance`. The pillar keeps the existing value + range + named-source + give-up discipline (never a bare number).
- **Sync.** `hintLevelUsed`/`assisted` ride the existing per-device media results file (`_speedycat_results_<deviceId>.json`) alongside every other attempt field, so the Performance pillar stays consistent across devices.

### AAMC Full-Length Practice Tests

Test types for MVP:

- **AAMC full-length** — Official AAMC practice exam; ~6h 15m MCAT testing time; four sections (CPBS, CARS, BBLS, PSBB).

Test session rules:

- Timed, exam-like UI aligned with AAMC section structure.
- No back-navigation to closed sections once a section is submitted.
- Flag-and-review within current section; auto-submit on section timer expiry.
- Break handling per AAMC exam structure (or documented simplification for v1).

Post-test report:

- Raw correct/total per section (unanswered questions count as incorrect, matching MCAT scoring).
- **Estimated** MCAT-scale scores: per section (118–132) and overall (472–528), alongside the raw score — see *Scaled-score estimate* below.
- Topic or skill breakdown where metadata exists.
- Comparison to prior attempts on the same exam form.

#### Scaled-score estimate (raw → 118–132 / 472–528)

The MCAT reports scaled section scores of **118–132** and a total of **472–528** (sum of the four sections). Real number-correct → scaled conversions are **form-specific and never published** — AAMC *equates* every form after test day, so there is no official conversion table. To let the AI-generated proof-of-concept full-length forms report a familiar scaled total, SpeedyCAT applies a deterministic **representative average** conversion — clearly labelled in the UI as an **ESTIMATE**, not an official score, and **not** an AI output (it is a plain lookup/interpolation; both apps behave identically with AI on or off).

- **Named source.** AAMC — official MCAT scoring guidance, `students-residents.aamc.org` ("How is the MCAT Exam Scored?" and "Your Questions Answered: The MCAT Scoring Process"). AAMC publishes the 118–132 / 472–528 scale and two illustrative number-correct → scaled anchors that apply "on one of the sections": **35–37 correct → 123** and **46–48 correct → 128**.
- **Method (interpolation).** We treat AAMC's illustrative anchors as a representative 59-question section (their 46–48 example only fits a ~59-question section) and express them plus the scale endpoints as **fraction-correct anchors**: `0.000 → 118` (floor), `36/59 ≈ 0.610 → 123`, `47/59 ≈ 0.797 → 128`, `1.000 → 132` (ceiling). A section's estimate is a **monotonic piecewise-linear interpolation** of its fraction correct (`correct / total`) between those anchors, rounded and clamped to 118–132. The **same** representative curve is applied to every section by that section's own question count — **CPBS 59, CARS 53, BBLS 59, PSBB 59** (230 total) — so each section gets a distinct raw→scaled mapping from one "fairly average" curve. The total is the sum of the four section estimates, clamped to 472–528.
- **Where it lives.** The conversion is a deterministic table/const in the Rust engine (`rslib/src/practice/scoring.rs`), computed at submit time (`SubmitFullLengthAttempt`) and aggregated for the Readiness pillar (`GetReadiness`). Mobile does not recompute it: the desktop-computed estimate rides the synced results summary and is shown read-only.
- **Caveat.** These are AI-generated proof-of-concept forms of unknown calibration, and the curve is a smoothed average of AAMC's illustrative examples — so the number is a rough estimate only. A production build with licensed AAMC packages would use each form's official equated conversion.

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
- `hints` — array of subquestion objects, optional (may be empty/absent). The graduated hint ladder (see *Graduated hint ladder*): each is `{ level: 1|2|3, prompt: string, choices: [{label: A–D, text}] ×4, correctAnswer: A–D, rationale: string }` — a 4-choice MCQ with exactly one correct answer, ordered by escalating specificity, never stating the parent question's answer. Generated separately into the bundle; parsed defensively (malformed tiers dropped).

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
- `totalBreakSeconds` — integer, required. Sum of `breaks[].durationSeconds` — `3000` (50 min) for the standard AAMC schedule: a 10-min break, the 30-min mid-exam break, and a 10-min break. Computed from `breaks` on load.
- `breaks` — array of break objects, required (may be empty). The real-MCAT scheduled breaks the UI enforces between sections. When the source bundle omits them, the backend **synthesizes** the standard breaks on import. Each break object:
  - `afterSection` — integer, required. 1-based `order` of the section this break follows. Standard breaks follow sections `1`, `2`, and `3`.
  - `durationSeconds` — integer, required. Break length, matching the real AAMC MCAT: `600` (10 min) for the break after section 1, `1800` (30 min) for the mid-exam break after section 2 (CARS), and `600` (10 min) for the break after section 3. Total scheduled break time is 50 min.
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
- `hintLevelUsed` — integer `0–3`, required (default `0`). Highest graduated-hint-ladder tier reached before the answer was locked (via the "Request hint" button or wrong-answer escalation). Always `0` for full-length answers (no ladder). See *Graduated hint ladder*.
- `assisted` — boolean, required (default `false`). `true` when the learner reached level 3 of the ladder (`hintLevelUsed >= 3`). Assisted-correct attempts are penalized in the readiness Performance pillar (they earn no credit while still counting toward the denominator).

### `AamcTestAttempt`

User-generated and synced. One attempt at a `FullLengthTest`. (Name retained for continuity; it references `FullLengthTest`.)

- `attemptId` — string, required. Primary key.
- `testId` — string, required. Foreign key → `FullLengthTest.testId`.
- `aamcExamId` — string, nullable. Official AAMC exam id when a licensed form is used; `null` for AI-generated proof-of-concept forms.
- `startedAt` — timestamp, required.
- `completedAt` — timestamp, nullable. `null` while in progress.
- `sectionResults` — array of `{ section: CPBS|CARS|BBLS|PSBB, correct: int, total: int, timeSeconds: int, scaledScore: int|null }`, required. `scaledScore` (118–132) is the **estimated** section scaled score from the representative averaged conversion (see *Scaled-score estimate*); `null` only when the section had no answered questions.
- `overallScaledScore` — integer (472–528), nullable. **Estimated** total scaled score = sum of the per-section `scaledScore`s (see *Scaled-score estimate*). An estimate on AI-generated proof-of-concept forms, not an official score; `null` only when no section could be scored.

### `SpeedyCatResults` (cross-device results-sync file)

User-generated **results** (`PracticeSessionAttempt`s and completed-`AamcTestAttempt` summaries) live in schema-19 collection tables that are **deliberately stripped from the AnkiWeb upload** (stock AnkiWeb only opens schema ≤18; the desktop uploads a schema-18 downgraded copy with the practice tables removed — `rslib/src/sync/collection/upload.rs` + `schema19_downgrade.sql`). So those tables never sync directly. To carry results across devices over **stock** AnkiWeb sync, each device writes one JSON file into the synced **`collection.media`** folder and reads the others'. Media is chosen because it survives the schema-19 strip (it is not in the `.anki2`), round-trips schema-agnostically, merges **per file** bidirectionally with no clobber (distinct per-device filenames), is readable/writable natively on mobile with the stock backend, and is preserved by Check Media (leading `_`). This is **not** stored in the schema-19 tables' sync path and **not** in `col.conf` (whose sync is whole-blob last-writer-wins).

- **Filename** — `_speedycat_results_<deviceId>.json`, one per device. `deviceId` is a random token stored **device-locally** (desktop: profile-manager meta; mobile: `SharedPreferences`) and **never** in the synced config, so each device keeps a distinct identity + filename.
- **`schema`** — integer, required. On-disk version (currently `1`).
- **`deviceId`** — string, required. The writing device's id.
- **`updatedAt`** — timestamp, required. When the file was last written.
- **`attempts`** — array of practice-session attempts (mirrors `PracticeSessionAttempt`): `{ id, sessionId, questionId, selectedAnswer, correct, timeSeconds, section, topic, answeredAt, hintLevelUsed, assisted }`. `id` is the stable, globally-unique dedup key (`"<sessionId>:<questionId>"`, and `sessionId` is a random `ps-…`). `hintLevelUsed`/`assisted` carry the graduated-hint-ladder tracking so the Performance-pillar anti-gaming penalty stays consistent across devices (default `0`/`false` on older files). **Bidirectional**: written by both apps.
- **`fullLength`** — array of completed full-length **summaries** (compact per-test roll-ups of `AamcTestAttempt`): `{ attemptId, testId, title, startedAt, completedAt, totalCorrect, totalQuestions, overallScaledScore, sections:[{ section, correct, total, timeSeconds, scaledScore }] }`. **One-way desktop → mobile**: only the desktop (which owns full-length tests) produces these; mobile renders them **read-only** in its 3rd Readiness pillar + a "taken on desktop" results card.

**Sync model.** Each device *publishes* its own file and *ingests* the **other** devices' files, upserting `attempts` into its local store (desktop `practice_attempts`; mobile `PracticeStore`) keyed by `id` (`insert or replace`). Because every Performance/Readiness/tracking query aggregates `count()`/`sum()` over distinct primary keys, the union is exact and never double-counts, and the existing computations work unchanged over the merged store. Desktop publishes on `sync_will_start` and ingests on `profile_did_open` + `sync_did_finish`; mobile publishes on session end + before media upload and ingests on-read (Readiness/Practice screens). Contract + logic: `pylib/anki/speedycat_sync.py` (desktop) and `practice/SpeedyCatResults.kt` + `PracticeResultsSync.kt` (mobile).

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
- Practice sessions: `StartPracticeSession`, `RecordPracticeAttempt`, `EndPracticeSession` (post-session summary). `PracticeQuestion` carries the `hints` subquestion ladder; `RecordPracticeAttempt` carries `hint_level_used`/`assisted` (see *Graduated hint ladder*).
- Full-length: `ListFullLengthTests`, `GetFullLengthTest`, `StartFullLengthAttempt` (returns timers + breaks), `RecordFullLengthAnswer`, `SubmitFullLengthAttempt` (per-section results **plus the estimated per-section (118–132) and overall (472–528) scaled scores**, computed by `rslib/src/practice/scoring.rs` — see *Scaled-score estimate*).
- Tracking: `GetTopicStats` — time-spent + accuracy aggregated by `topic` and by `section`, with an optional section filter and attempt-source filter (all / practice-session / full-length).
- Readiness: `GetReadiness` — the 3-pillar metric (Memory / Performance / Readiness), each a value + explicit range + named source that "gives up" without enough data. The **Performance** pillar applies the graduated-hint-ladder **anti-gaming penalty** (assisted-correct answers earn no credit; see *Graduated hint ladder*). The Readiness pillar carries the per-section raw scores and the aggregate **scaled-score estimate** (`readiness_scaled_score`/`_low`/`_high`, 472–528) so the dashboard presents the estimate within the value+range+source discipline (never a bare number).

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
