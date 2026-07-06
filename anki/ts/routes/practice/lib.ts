// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT: shared helpers for the two MCAT study-mode pages (Practice
// Questions + Full-Length Tests). Section/difficulty labels, time formatting,
// and thin wrappers over the generated PracticeService backend so the Svelte
// components don't have to touch `@generated` directly.

import { AttemptSource, Difficulty, McatSection } from "@generated/anki/practice_pb";
import type {
    CarsPassageSet,
    FullLengthAttemptSummary,
    FullLengthBreak,
    FullLengthReport,
    FullLengthReviewItem,
    FullLengthSection,
    FullLengthStats,
    FullLengthTest,
    FullLengthTestSummary,
    GetFullLengthReviewResponse,
    GetTopicStatsResponse,
    HintChoice,
    HintSubquestion,
    PassageSummary,
    PracticeQuestion,
    PracticeSessionSummary,
    StartFullLengthAttemptResponse,
    StartPracticeSessionResponse,
    TopicScore,
} from "@generated/anki/practice_pb";
import {
    abandonFullLengthAttempt,
    endPracticeSession,
    getCarsPassageSet,
    getFullLengthReview,
    getFullLengthStats,
    getPracticeQuestions,
    getRecommendedPracticeTopics,
    getTopicStats,
    listFullLengthAttempts,
    listFullLengthTests,
    listPassages,
    recordFullLengthAnswer,
    recordPracticeAttempt,
    startFullLengthAttempt,
    startPracticeSession,
    submitFullLengthAttempt,
} from "@generated/backend";
import { bridgeCommand, bridgeCommandsAvailable } from "@tslib/bridgecommand";

export { AttemptSource, Difficulty, McatSection };
export type {
    CarsPassageSet,
    FullLengthAttemptSummary,
    FullLengthBreak,
    FullLengthReport,
    FullLengthReviewItem,
    FullLengthSection,
    FullLengthStats,
    FullLengthTest,
    FullLengthTestSummary,
    GetFullLengthReviewResponse,
    GetTopicStatsResponse,
    HintChoice,
    HintSubquestion,
    PassageSummary,
    PracticeQuestion,
    PracticeSessionSummary,
    TopicScore,
};

// ---- Sections & difficulty -------------------------------------------------

/** MCAT sections in test order. */
export const SECTIONS: McatSection[] = [
    McatSection.CPBS,
    McatSection.CARS,
    McatSection.BBLS,
    McatSection.PSBB,
];

const SECTION_SHORT: Record<number, string> = {
    [McatSection.UNSPECIFIED]: "—",
    [McatSection.CPBS]: "Chem/Phys",
    [McatSection.CARS]: "CARS",
    [McatSection.BBLS]: "Bio/Biochem",
    [McatSection.PSBB]: "Psych/Soc",
};

const SECTION_LONG: Record<number, string> = {
    [McatSection.UNSPECIFIED]: "Unspecified",
    [McatSection.CPBS]: "Chemical & Physical Foundations of Biological Systems",
    [McatSection.CARS]: "Critical Analysis & Reasoning Skills",
    [McatSection.BBLS]: "Biological & Biochemical Foundations of Living Systems",
    [McatSection.PSBB]: "Psychological, Social & Biological Foundations of Behavior",
};

export function sectionShort(section: McatSection): string {
    return SECTION_SHORT[section] ?? "—";
}

export function sectionLong(section: McatSection): string {
    return SECTION_LONG[section] ?? "Unspecified";
}

// ---- Scaled-score estimate (raw correct -> MCAT 118-132 / 472-528) ---------

// SpeedyCAT: the full-length scaled scores are a deterministic ESTIMATE, not an
// official score and NOT an AI output. They come from a representative averaged
// number-correct→scaled conversion computed in the Rust backend
// (rslib/src/practice/scoring.rs), anchored to AAMC's own published scoring
// examples. This label names that source in the UI so every estimate is
// traceable (per the PRD), and is shared by the report + dashboard so the two
// stay consistent.
export const SCALED_SCORE_SOURCE =
    "AAMC published scoring examples (students-residents.aamc.org)";

/** One-line caption describing the scaled estimate + its named source. */
export const SCALED_SCORE_CAPTION =
    `Estimated MCAT-scale score from an averaged raw→scaled conversion (${SCALED_SCORE_SOURCE}). `
    + "These are AI-generated proof-of-concept forms, so treat it as an estimate, not an official score.";

// ---- Readiness pillar tooltips (rslib/src/practice/service.rs) ------------

/** One-sentence overview of the Memory pillar. */
export const MEMORY_PILLAR_TOOLTIP =
    "The chance you can correctly recall a flashcard you have studied.";

/** One-sentence overview of the Performance pillar. */
export const PERFORMANCE_PILLAR_TOOLTIP =
    "The chance that a student effectively applies their knowledge to solve problems in varied contexts.";

/** One-sentence overview of the Readiness pillar. */
export const READINESS_PILLAR_TOOLTIP =
    "The chance that a student correctly solves a previously unseen question in a real testing environment.";

/** Hover/tap copy when a pillar breakdown row has insufficient data. */
export const PILLAR_BREAKDOWN_INSUFFICIENT_TOOLTIP =
    "Insufficient data in this category to calculate an estimate";

/** Dashboard hover copy keyed by pillar title. */
export const PILLAR_TOOLTIPS: Record<string, string> = {
    Memory: MEMORY_PILLAR_TOOLTIP,
    Performance: PERFORMANCE_PILLAR_TOOLTIP,
    Readiness: READINESS_PILLAR_TOOLTIP,
};

const DIFFICULTY_LABEL: Record<number, string> = {
    [Difficulty.UNSPECIFIED]: "",
    [Difficulty.EASY]: "Easy",
    [Difficulty.MEDIUM]: "Medium",
    [Difficulty.HARD]: "Hard",
};

export function difficultyLabel(difficulty: Difficulty): string {
    return DIFFICULTY_LABEL[difficulty] ?? "";
}

// ---- Time ------------------------------------------------------------------

/** "h:mm:ss" (with hours only when needed) for a countdown/elapsed display. */
export function formatClock(totalSeconds: number): string {
    const s = Math.max(0, Math.floor(totalSeconds));
    const hours = Math.floor(s / 3600);
    const minutes = Math.floor((s % 3600) / 60);
    const seconds = s % 60;
    const mm = minutes.toString().padStart(hours > 0 ? 2 : 1, "0");
    const ss = seconds.toString().padStart(2, "0");
    return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Longer human-readable duration, e.g. "1h 5m" / "45s". */
export function formatDurationLong(totalSeconds: number): string {
    const s = Math.max(0, Math.floor(totalSeconds));
    const hours = Math.floor(s / 3600);
    const minutes = Math.floor((s % 3600) / 60);
    const seconds = s % 60;
    const parts: string[] = [];
    if (hours > 0) {
        parts.push(`${hours}h`);
    }
    if (minutes > 0) {
        parts.push(`${minutes}m`);
    }
    if (hours === 0 && (seconds > 0 || parts.length === 0)) {
        parts.push(`${seconds}s`);
    }
    return parts.join(" ");
}

// ---- Filters ---------------------------------------------------------------

export interface FilterOptions {
    /** Sections to include (matches ANY). Empty / omitted = all sections. */
    sections?: McatSection[];
    /** Topics to include (matches ANY, case-insensitive). Empty = all topics. */
    topics?: string[];
    includeFullLength?: boolean;
    limit?: number;
}

/** Build a fully-populated QuestionFilter plain object from partial options. */
export function buildFilter(opts: FilterOptions) {
    // Drop the UNSPECIFIED sentinel so an "all sections" pseudo-choice never
    // narrows the query (empty list = all sections).
    const sections = (opts.sections ?? []).filter(
        (s) => s !== McatSection.UNSPECIFIED,
    );
    return {
        sections,
        topics: opts.topics ?? [],
        includeFullLength: opts.includeFullLength ?? false,
        limit: opts.limit ?? 0,
    };
}

// ---- Setup input parsing ---------------------------------------------------

export interface QuestionCountInput {
    /** Limit to pass to the QuestionFilter (0 = all available). */
    limit: number;
    /** Whether the raw text is acceptable to start a session. */
    valid: boolean;
    /** True when the input means "all available" (blank / "max" / "all"). */
    all: boolean;
}

/**
 * Parse the free-text "number of questions" field. Blank, "max" or "all" mean
 * all available (limit 0 — the backend then serves everything, rounding CARS
 * down to whole passage sets). Otherwise the text must be a positive integer,
 * which is clamped to `available` when that is known; anything else (0,
 * negative, decimal, non-numeric) is invalid.
 */
export function parseQuestionCount(
    raw: string,
    available?: number,
): QuestionCountInput {
    const trimmed = raw.trim().toLowerCase();
    if (trimmed === "" || trimmed === "max" || trimmed === "all") {
        return { limit: 0, valid: true, all: true };
    }
    if (!/^\d+$/.test(trimmed)) {
        return { limit: 0, valid: false, all: false };
    }
    const n = Number.parseInt(trimmed, 10);
    if (n <= 0) {
        return { limit: 0, valid: false, all: false };
    }
    const limit =
        available !== undefined && available > 0 ? Math.min(n, available) : n;
    return { limit, valid: true, all: false };
}

export interface TimerInput {
    /** Session time limit in seconds (0 when invalid / untimed). */
    seconds: number;
    /** Whether the raw text is a usable positive whole-minute count. */
    valid: boolean;
}

/**
 * Parse the free-text timer field (whole minutes). Must be a positive integer;
 * blank / non-numeric / non-positive is not valid. The caller only consults
 * this when the session is timed (the "Untimed" toggle bypasses it entirely).
 */
export function parseTimerMinutes(raw: string): TimerInput {
    const trimmed = raw.trim();
    if (!/^\d+$/.test(trimmed)) {
        return { seconds: 0, valid: false };
    }
    const mins = Number.parseInt(trimmed, 10);
    if (mins <= 0) {
        return { seconds: 0, valid: false };
    }
    return { seconds: mins * 60, valid: true };
}

// ---- Backend wrappers ------------------------------------------------------

export async function fetchQuestions(
    opts: FilterOptions,
): Promise<PracticeQuestion[]> {
    const res = await getPracticeQuestions({ filter: buildFilter(opts) });
    return res.questions;
}

export async function fetchPassageSet(passageId: string): Promise<CarsPassageSet> {
    return await getCarsPassageSet({ passageId });
}

export async function fetchPassages(
    section?: McatSection,
    testId?: string,
): Promise<PassageSummary[]> {
    const input: { section?: McatSection; testId?: string } = {};
    if (section) {
        input.section = section;
    }
    if (testId) {
        input.testId = testId;
    }
    const res = await listPassages(input);
    return res.passages;
}

export async function fetchRecommendedTopics(): Promise<string[]> {
    const res = await getRecommendedPracticeTopics({});
    return res.topics;
}

export async function beginPracticeSession(
    opts: FilterOptions,
    timeLimitSeconds: number,
): Promise<StartPracticeSessionResponse> {
    return await startPracticeSession({
        filter: buildFilter(opts),
        timeLimitSeconds,
    });
}

export interface AttemptRecord {
    sessionId: string;
    questionId: string;
    selectedAnswer: string;
    correct: boolean;
    timeOnQuestionSeconds: number;
    section: McatSection;
    topic: string;
    /** SpeedyCAT hint ladder: highest tier reached before locking (0-3). */
    hintLevelUsed?: number;
    /** SpeedyCAT hint ladder: reached level 3 (penalized in Performance). */
    assisted?: boolean;
    /** True when a wrong main-question submit escalated into the hint ladder. */
    mainWrongFirst?: boolean;
}

export async function logPracticeAttempt(a: AttemptRecord): Promise<void> {
    await recordPracticeAttempt({
        sessionId: a.sessionId,
        questionId: a.questionId,
        selectedAnswer: a.selectedAnswer,
        correct: a.correct,
        timeOnQuestionSeconds: a.timeOnQuestionSeconds,
        section: a.section,
        topic: a.topic,
        hintLevelUsed: a.hintLevelUsed ?? 0,
        assisted: a.assisted ?? false,
        mainWrongFirst: a.mainWrongFirst ?? false,
    });
}

export async function finishPracticeSession(
    sessionId: string,
): Promise<PracticeSessionSummary> {
    return await endPracticeSession({ sessionId });
}

export async function fetchFullLengthTests(): Promise<FullLengthTestSummary[]> {
    const res = await listFullLengthTests({});
    return res.tests;
}

export async function beginFullLengthAttempt(
    testId: string,
): Promise<StartFullLengthAttemptResponse> {
    return await startFullLengthAttempt({ testId });
}

export interface FullLengthAnswerRecord {
    attemptId: string;
    section: McatSection;
    questionId: string;
    selectedAnswer: string;
    correct: boolean;
    timeOnQuestionSeconds: number;
    topic: string;
}

export async function logFullLengthAnswer(a: FullLengthAnswerRecord): Promise<void> {
    await recordFullLengthAnswer({
        attemptId: a.attemptId,
        section: a.section,
        questionId: a.questionId,
        selectedAnswer: a.selectedAnswer,
        correct: a.correct,
        timeOnQuestionSeconds: a.timeOnQuestionSeconds,
        topic: a.topic,
    });
}

export async function finishFullLengthAttempt(
    attemptId: string,
): Promise<FullLengthReport> {
    return await submitFullLengthAttempt({ attemptId });
}

export async function abandonAttempt(attemptId: string): Promise<void> {
    await abandonFullLengthAttempt({ attemptId });
}

export async function fetchCompletedAttempts(): Promise<FullLengthAttemptSummary[]> {
    const res = await listFullLengthAttempts({});
    return res.attempts;
}

/** Regex to pull a full-length exam number from a test id or verbose title. */
const FULL_LENGTH_LABEL_NUMBER =
    /(?:full[- ]?length|(?:^|[-_])fl)[-_ ]*(\d+)/i;

/**
 * Short dashboard label for a completed full-length exam, e.g.
 * "speedycat-fl-1" → "Full-length 1". Falls back to title parsing, then
 * "Full-length test".
 */
export function formatFullLengthExamLabel(testId: string, title = ""): string {
    const fromId = FULL_LENGTH_LABEL_NUMBER.exec(testId)?.[1];
    if (fromId) return `Full-length ${fromId}`;
    const fromTitle = FULL_LENGTH_LABEL_NUMBER.exec(title)?.[1];
    if (fromTitle) return `Full-length ${fromTitle}`;
    return "Full-length test";
}

/** Full-length tests not yet completed — exclude from the picker once submitted. */
export function availableFullLengthTests(
    tests: FullLengthTestSummary[],
    completed: FullLengthAttemptSummary[],
): FullLengthTestSummary[] {
    const completedTestIds = new Set(completed.map((a) => a.testId));
    return tests.filter((t) => !completedTestIds.has(t.testId));
}

export async function fetchFullLengthStats(attemptId: string): Promise<FullLengthStats> {
    return await getFullLengthStats({ attemptId });
}

export async function fetchFullLengthReview(
    attemptId: string,
): Promise<GetFullLengthReviewResponse> {
    return await getFullLengthReview({ attemptId });
}

/** Desktop exam lockdown: hide the top nav and block mode switches. */
export function setFullLengthLockdown(active: boolean, attemptId?: string): void {
    if (!bridgeCommandsAvailable()) {
        return;
    }
    if (active && attemptId) {
        bridgeCommand(`speedycat:fullLengthLock:on:${attemptId}`);
    } else {
        bridgeCommand("speedycat:fullLengthLock:off");
    }
}

export const FULL_LENGTH_START_WARNING =
    "The section timer starts immediately when you begin. There is no pause "
    + "except scheduled breaks. Leaving the test before you finish will abandon "
    + "your attempt and the score will not count toward Readiness.\n\n"
    + "Start the full-length test now?";

export function confirmFullLengthStart(): boolean {
    return globalThis.confirm(FULL_LENGTH_START_WARNING);
}

export function earlySectionEndWarning(unansweredCount: number): string {
    let msg =
        "End this section now? You will not be able to return to it "
        + "once it ends.";
    if (unansweredCount > 0) {
        const noun = unansweredCount === 1 ? "question" : "questions";
        msg += `\n\nYou have ${unansweredCount} unanswered ${noun} in this section.`;
    }
    return msg;
}

export function confirmEarlySectionEnd(unansweredCount: number): boolean {
    return globalThis.confirm(earlySectionEndWarning(unansweredCount));
}

export async function fetchTopicStats(
    source: AttemptSource,
    section?: McatSection,
): Promise<GetTopicStatsResponse> {
    // Session summaries count every recorded attempt, so leave the
    // first-attempt-no-hint filter off (only the dashboard accuracy card sets it).
    const input: {
        source: AttemptSource;
        section?: McatSection;
        firstAttemptNoHintOnly: boolean;
    } = { source, firstAttemptNoHintOnly: false };
    if (section) {
        input.section = section;
    }
    return await getTopicStats(input);
}

// ---- Graduated hint ladder -------------------------------------------------

// SpeedyCAT: a question's `hints` are an ordered ladder of scaffolding
// SUBQUESTIONS (each a 4-choice MCQ). The learner works through them ONE AT A
// TIME — they must answer the currently-revealed subquestion before revealing
// the next one or submitting the main question (the NO-SKIP rule) — and the main
// answer stays locked until the ladder has been worked through. The highest tier
// reached sets `hint_level_used`; reaching level 3 sets `assisted` (penalized in
// the Performance pillar). The pure helpers below drive the UI and are unit
// tested; the content of each subquestion is generated separately.

/** Per-question progress through the graduated hint ladder. */
export interface HintProgress {
    /** Number of subquestions revealed so far (0..hints.length). */
    revealed: number;
    /** Chosen label per revealed subquestion index (0-based). */
    picks: Record<number, string>;
}

export function emptyHintProgress(): HintProgress {
    return { revealed: 0, picks: {} };
}

/** The hint subquestions carried on a practice item (empty when none). */
export function questionHints(question: PracticeQuestion): HintSubquestion[] {
    const hints = question.hints;
    return Array.isArray(hints) ? hints : [];
}

/** Whether a question offers a (well-formed) hint ladder at all. */
export function hasHintLadder(question: PracticeQuestion): boolean {
    return questionHints(question).length > 0;
}

/**
 * The highest hint tier reached so far (0 = none), clamped to 0-3. Drives
 * `hint_level_used`. Uses each subquestion's `level`, falling back to its 1-based
 * position when the level is missing/out of range.
 */
export function hintLevelReached(
    hints: HintSubquestion[],
    revealed: number,
): number {
    if (revealed <= 0 || hints.length === 0) {
        return 0;
    }
    const last = Math.min(revealed, hints.length);
    let level = 0;
    for (let i = 0; i < last; i++) {
        level = Math.max(level, hints[i]?.level || i + 1);
    }
    return Math.min(3, level);
}

/** A learner is "assisted" once they reach level 3 of the ladder. */
export function isAssisted(level: number): boolean {
    return level >= 3;
}

/**
 * Index of the currently-revealed subquestion still awaiting an answer, or -1
 * when the latest revealed subquestion is answered (or none is revealed). This
 * is what enforces the NO-SKIP rule.
 */
export function pendingHintIndex(progress: HintProgress): number {
    if (progress.revealed <= 0) {
        return -1;
    }
    const idx = progress.revealed - 1;
    return progress.picks[idx] === undefined ? idx : -1;
}

/**
 * Indices of the revealed hint subquestions in DISPLAY order: the most-recently
 * revealed tier first. This ONLY affects how the revealed tiers are rendered
 * (newest on top, directly under the main question); the ladder still PROGRESSES
 * strictly L1 -> L2 -> L3 with no skipping (see `pendingHintIndex` /
 * `canRevealNextHint`, which are unchanged). e.g. after all three are revealed,
 * this returns [2, 1, 0] so the view reads Hint 3, Hint 2, Hint 1.
 */
export function hintDisplayOrder(revealed: number): number[] {
    const order: number[] = [];
    for (let i = Math.max(0, revealed) - 1; i >= 0; i--) {
        order.push(i);
    }
    return order;
}

/**
 * Can the learner reveal the NEXT hint? Only when one remains AND the current
 * revealed subquestion has been answered (no skipping ahead).
 */
export function canRevealNextHint(
    hints: HintSubquestion[],
    progress: HintProgress,
): boolean {
    return progress.revealed < hints.length && pendingHintIndex(progress) === -1;
}

/**
 * Can the learner submit the MAIN question? A choice must be selected AND there
 * must be no revealed-but-unanswered subquestion (they cannot skip the ladder).
 */
export function canSubmitMain(
    mainSelected: string,
    progress: HintProgress,
): boolean {
    return mainSelected !== "" && pendingHintIndex(progress) === -1;
}

/** Whether a subquestion answer is correct. */
export function hintAnswerCorrect(hint: HintSubquestion, label: string): boolean {
    return label !== "" && label === hint.correctAnswer;
}

/** Label + text for the correct choice on a hint subquestion (compact display). */
export function hintCorrectAnswerLine(hint: HintSubquestion): string {
    const choice = hint.choices.find((c) => c.label === hint.correctAnswer);
    if (!choice) {
        return hint.correctAnswer;
    }
    return `${choice.label}. ${choice.text}`;
}

// ---- Hint ladder UX timers -------------------------------------------------
//
// SpeedyCAT practice mode gates hint affordances so learners attempt retrieval
// before scaffolding. Timers are pure functions of elapsed seconds + progress
// so both desktop and mobile can share identical rules (see PracticeLogic.kt).

/** Minimum wait before the first "I'm stuck" button (seconds). */
export const HINT_FIRST_MIN_SECONDS = 15;
/** Maximum wait before the first "I'm stuck" button (seconds). */
export const HINT_FIRST_MAX_SECONDS = 30;
/** Wait on the main question before "I'm still stuck" reveals the next tier.
 *  Counting starts only after the hint popup is dismissed (see
 *  {@link shouldTickSubsequentHintTimer}). */
export const HINT_SUBSEQUENT_SECONDS = 6;
/** Cooldown after a wrong hint subquestion submit before retry (seconds). */
export const HINT_WRONG_RETRY_SECONDS = 5;
/** Assumed reading speed when gating hints behind passage read time (words/min). */
export const PASSAGE_READ_WPM = 300;

/** Readable character count for a question stem + all answer choices. */
export function questionReadingChars(question: PracticeQuestion): number {
    const choiceChars = question.choices.reduce((n, c) => n + c.text.length, 0);
    return question.stem.length + choiceChars;
}

/** Word count from passage metadata or plain text (HTML stripped). */
export function passageWordCount(
    wordCount: number | undefined | null,
    passageText: string | undefined | null,
): number {
    if (wordCount != null && wordCount > 0) {
        return wordCount;
    }
    const text = (passageText ?? "").replace(/<[^>]+>/g, " ").trim();
    if (!text) {
        return 0;
    }
    return text.split(/\s+/).filter(Boolean).length;
}

/**
 * Seconds to allow reading a passage at {@link PASSAGE_READ_WPM} before hints
 * may unlock (e.g. 600 words → 120 s).
 */
export function passageReadGateSeconds(wordCount: number): number {
    if (wordCount <= 0) {
        return 0;
    }
    return Math.ceil((wordCount / PASSAGE_READ_WPM) * 60);
}

/**
 * Passage read gate for one question within a run item. In a multi-question
 * CARS sidebar only the first question carries the shared passage allowance;
 * later questions use stem-only timing once the learner scrolls to them.
 */
export function passageHintWordCountForQuestion(
    questionCountInItem: number,
    questionIndexInItem: number,
    passageWordCount: number,
): number | undefined {
    if (passageWordCount <= 0) {
        return undefined;
    }
    if (questionCountInItem > 1 && questionIndexInItem > 0) {
        return undefined;
    }
    return passageWordCount;
}

/**
 * Delay before showing the first "I'm stuck" affordance. Starts at 15 s, adds
 * ~1 s per 100 characters of stem/choices (time to read + attempt retrieval),
 * capped at 30 s. When a passage is linked, uses whichever is longer: that
 * base delay or the time to read the passage at 300 WPM.
 */
export function firstHintDelaySeconds(
    question: PracticeQuestion,
    passageWordCount?: number | null,
): number {
    const extra = Math.floor(questionReadingChars(question) / 100);
    const base = Math.min(
        HINT_FIRST_MAX_SECONDS,
        Math.max(HINT_FIRST_MIN_SECONDS, HINT_FIRST_MIN_SECONDS + extra),
    );
    if (!passageWordCount || passageWordCount <= 0) {
        return base;
    }
    return Math.max(base, passageReadGateSeconds(passageWordCount));
}

/**
 * Whether a per-question hint timer should advance this tick. Timers start on
 * first viewport visibility and pause when scrolled away without resetting.
 */
export function shouldTickQuestionHintTimer(
    everVisible: boolean,
    currentlyVisible: boolean,
): boolean {
    return everVisible && currentlyVisible;
}

/** May the learner tap "I'm stuck" to reveal hint tier 1? */
export function canShowFirstHintButton(
    elapsedSeconds: number,
    question: PracticeQuestion,
    progress: HintProgress,
    locked: boolean,
    passageWordCount?: number | null,
): boolean {
    return (
        !locked
        && progress.revealed === 0
        && elapsedSeconds >= firstHintDelaySeconds(question, passageWordCount)
    );
}

/**
 * May the learner tap "I'm still stuck" on the main question to reveal the
 * next tier? Requires the current tier to be answered and a subsequent delay.
 */
export function canShowNextHintButton(
    elapsedSinceHintComplete: number,
    hints: HintSubquestion[],
    progress: HintProgress,
    locked: boolean,
): boolean {
    return (
        !locked
        && canRevealNextHint(hints, progress)
        && elapsedSinceHintComplete >= HINT_SUBSEQUENT_SECONDS
    );
}

/**
 * Whether the post-hint "I'm still stuck" delay should advance this second.
 * The timer starts only after the learner dismisses the hint popup (or returns
 * to the main question), not while answering a tier inside the overlay.
 */
export function shouldTickSubsequentHintTimer(
    hints: HintSubquestion[],
    progress: HintProgress,
    hintLadderDismissed: boolean,
): boolean {
    return (
        hintLadderDismissed
        && pendingHintIndex(progress) === -1
        && progress.revealed > 0
        && canRevealNextHint(hints, progress)
    );
}

/** May the learner submit a hint subquestion answer right now? */
export function canSubmitHintAnswer(
    choice: string,
    cooldownRemaining: number,
): boolean {
    return choice !== "" && cooldownRemaining <= 0;
}

/** May the learner pick a choice on the active hint subquestion? */
export function hintChoicesEnabled(
    answered: boolean,
    locked: boolean,
    isCurrent: boolean,
    cooldownRemaining: number,
): boolean {
    return !answered && !locked && isCurrent && cooldownRemaining <= 0;
}

/** Start the post-wrong hint retry cooldown for one question. */
export function startHintWrongCooldown(
    cooldowns: Readonly<Record<string, number>>,
    questionId: string,
): Record<string, number> {
    return { ...cooldowns, [questionId]: HINT_WRONG_RETRY_SECONDS };
}

/** Advance all per-question hint wrong-answer cooldowns by one second. */
export function tickHintCooldowns(
    cooldowns: Readonly<Record<string, number>>,
): Record<string, number> {
    let changed = false;
    const next: Record<string, number> = { ...cooldowns };
    for (const qid of Object.keys(next)) {
        if (next[qid] > 0) {
            next[qid] -= 1;
            changed = true;
        }
    }
    return changed ? next : cooldowns;
}

/**
 * Show a duplicate main Submit next to the hint affordances (near the top of
 * the question) so learners need not scroll past the ladder after using hints.
 */
export function shouldShowConvenientMainSubmit(
    progress: HintProgress,
    locked: boolean,
): boolean {
    return !locked && progress.revealed > 0;
}

/**
 * Apply a hint subquestion answer. Wrong picks are rejected (progress unchanged)
 * so the learner must retry until correct — picks only store correct answers.
 */
export function applyHintAnswer(
    hint: HintSubquestion,
    index: number,
    label: string,
    progress: HintProgress,
): { progress: HintProgress; correct: boolean } {
    if (!hintAnswerCorrect(hint, label)) {
        return { progress, correct: false };
    }
    return {
        progress: {
            revealed: progress.revealed,
            picks: { ...progress.picks, [index]: label },
        },
        correct: true,
    };
}

// ---- Misc ------------------------------------------------------------------

/** Known science / exam acronyms (matched case-insensitively). */
const TOPIC_ACRONYMS = new Set([
    "MCAT",
    "DNA",
    "RNA",
    "mRNA",
    "tRNA",
    "rRNA",
    "ATP",
    "ADP",
    "NAD",
    "FAD",
    "GDP",
    "GTP",
    "PCR",
    "AAMC",
    "pH",
    "pKa",
    "pKb",
    "UV",
    "IR",
    "NMR",
    "HIV",
    "AIDS",
    "CNS",
    "PNS",
]);

function formatTopicWord(word: string): string {
    if (!word) {
        return "";
    }
    const upper = word.toUpperCase();
    for (const acr of TOPIC_ACRONYMS) {
        if (upper === acr.toUpperCase()) {
            return acr;
        }
    }
    return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
}

/**
 * Title-case a topic label for UI display. Splits on whitespace, `-`, and `_`.
 * Canonical `topic_tags` in JSON/DB are unchanged; call at render time only.
 */
export function formatTopicLabel(raw: string): string {
    const trimmed = raw.trim();
    if (!trimmed) {
        return "";
    }
    return trimmed
        .split(/[\s_-]+/)
        .filter(Boolean)
        .map(formatTopicWord)
        .join(" ");
}

/** Primary topic attributed to an attempt (first tag, else section name). */
export function primaryTopic(question: PracticeQuestion): string {
    if (question.topicTags && question.topicTags.length > 0) {
        return question.topicTags[0];
    }
    return sectionShort(question.section);
}

/** Group discrete + passage-linked questions in a stable serving order while
 * keeping every question that shares a passage adjacent. */
export interface RunItem {
    /** passage id if this run item is a CARS/passage set, else undefined */
    passageId?: string;
    questions: PracticeQuestion[];
}

export function groupIntoRunItems(questions: PracticeQuestion[]): RunItem[] {
    const items: RunItem[] = [];
    const passageIndex = new Map<string, number>();
    for (const q of questions) {
        const pid = q.passageId;
        if (pid) {
            let idx = passageIndex.get(pid);
            if (idx === undefined) {
                idx = items.length;
                passageIndex.set(pid, idx);
                items.push({ passageId: pid, questions: [] });
            }
            items[idx].questions.push(q);
        } else {
            items.push({ questions: [q] });
        }
    }
    return items;
}
