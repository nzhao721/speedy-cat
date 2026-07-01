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
    section?: McatSection;
    topics?: string[];
    missedOnly?: boolean;
    includeFullLength?: boolean;
    limit?: number;
}

/** Build a fully-populated QuestionFilter plain object from partial options. */
export function buildFilter(opts: FilterOptions) {
    const filter: {
        section?: McatSection;
        topics: string[];
        missedOnly: boolean;
        includeFullLength: boolean;
        limit: number;
    } = {
        topics: opts.topics ?? [],
        missedOnly: opts.missedOnly ?? false,
        includeFullLength: opts.includeFullLength ?? false,
        limit: opts.limit ?? 0,
    };
    // McatSection.UNSPECIFIED === 0 is falsy, so this also skips "all sections".
    if (opts.section) {
        filter.section = opts.section;
    }
    return filter;
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
