// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT: shared helpers for the two MCAT study-mode pages (Practice
// Questions + Full-Length Tests). Section/difficulty labels, time formatting,
// and thin wrappers over the generated PracticeService backend so the Svelte
// components don't have to touch `@generated` directly.

import { AttemptSource, Difficulty, McatSection } from "@generated/anki/practice_pb";
import type {
    CarsPassageSet,
    FullLengthBreak,
    FullLengthReport,
    FullLengthSection,
    FullLengthTest,
    FullLengthTestSummary,
    GetTopicStatsResponse,
    HintChoice,
    HintSubquestion,
    PassageSummary,
    PracticeQuestion,
    PracticeSessionSummary,
    StartFullLengthAttemptResponse,
    StartPracticeSessionResponse,
} from "@generated/anki/practice_pb";
import {
    endPracticeSession,
    getCarsPassageSet,
    getPracticeQuestions,
    getTopicStats,
    listFullLengthTests,
    listPassages,
    recordFullLengthAnswer,
    recordPracticeAttempt,
    startFullLengthAttempt,
    startPracticeSession,
    submitFullLengthAttempt,
} from "@generated/backend";

export { AttemptSource, Difficulty, McatSection };
export type {
    CarsPassageSet,
    FullLengthBreak,
    FullLengthReport,
    FullLengthSection,
    FullLengthTest,
    FullLengthTestSummary,
    GetTopicStatsResponse,
    HintChoice,
    HintSubquestion,
    PassageSummary,
    PracticeQuestion,
    PracticeSessionSummary,
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
    missedOnly?: boolean;
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
        missedOnly: opts.missedOnly ?? false,
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

export async function fetchTopicStats(
    source: AttemptSource,
    section?: McatSection,
): Promise<GetTopicStatsResponse> {
    const input: { source: AttemptSource; section?: McatSection } = { source };
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

/** Whether a question offers a (well-formed) hint ladder at all. */
export function hasHintLadder(question: PracticeQuestion): boolean {
    return (question.hints?.length ?? 0) > 0;
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

// ---- Misc ------------------------------------------------------------------

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
