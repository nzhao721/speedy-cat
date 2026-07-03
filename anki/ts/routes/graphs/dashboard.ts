// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT: data assembly for the stats dashboard (the app homepage).
//
// Every figure is deterministic and traceable to an existing backend RPC:
// * flashcard numbers come from the stock StatsService `graphs` call (fetched
//   by WithGraphData and summarised here);
// * practice-question numbers come from PracticeService.GetTopicStats over
//   recorded practice-session attempts;
// * full-length numbers come from PracticeService.GetReadiness (completed
//   tests only — raw scores with an explicit range, never a fabricated scaled
//   score) plus ListFullLengthTests for context.

import type { GetReadinessResponse } from "@generated/anki/practice_pb";
import type { GraphsResponse } from "@generated/anki/stats_pb";
import { getReadiness, getTopicStats, listFullLengthTests } from "@generated/backend";

import type { GetTopicStatsResponse } from "../practice/lib";
import { AttemptSource, formatDurationLong } from "../practice/lib";

// SpeedyCAT: the dashboard's practice/full-length figures come from our custom
// PracticeService RPCs, which only exist in our own Rust backend. The mobile app
// ships the STOCK prebuilt backend (no PracticeService), so these calls 500
// there. We pass `alertOnError: false` so a missing RPC never pops a blocking
// alert() dialog; the caller catches the rejection and hides the affected
// section instead. On desktop the RPCs succeed, so behaviour is unchanged.
const NO_ALERT = { alertOnError: false } as const;

// ---- Practice questions -----------------------------------------------------

export const PRACTICE_SOURCE =
    "SpeedyCAT practice-session attempt tracking (PracticeService.GetTopicStats)";

export interface PracticeOverview {
    /** Recorded attempts; a question skipped in a session counts as one. */
    attempted: number;
    correct: number;
    /** correct ÷ attempted, 0..1 (0 when nothing attempted). */
    accuracy: number;
    avgTimeSeconds: number;
    stats: GetTopicStatsResponse;
}

export async function loadPracticeOverview(): Promise<PracticeOverview> {
    const stats = await getTopicStats(
        { source: AttemptSource.PRACTICE_SESSION },
        NO_ALERT,
    );
    let attempted = 0;
    let correct = 0;
    let time = 0;
    for (const s of stats.sections) {
        attempted += s.attempts;
        correct += s.correct;
        time += s.totalTimeSeconds;
    }
    return {
        attempted,
        correct,
        accuracy: attempted > 0 ? correct / attempted : 0,
        avgTimeSeconds: attempted > 0 ? time / attempted : 0,
        stats,
    };
}

// ---- Full-length tests ------------------------------------------------------

export interface FullLengthOverview {
    /** Readiness response; only the full-length pillar + section scores are
     * used here (completed tests only, raw scores). */
    readiness: GetReadinessResponse;
    /** Number of full-length tests shipped in the bank (for empty states). */
    testsAvailable: number;
}

export async function loadFullLengthOverview(): Promise<FullLengthOverview> {
    // The full-length figures ignore the deck search entirely; "" (whole
    // collection) matches how the Readiness page calls the same RPC.
    const [readiness, tests] = await Promise.all([
        getReadiness({ deckSearch: "" }, NO_ALERT),
        listFullLengthTests({}, NO_ALERT),
    ]);
    return { readiness, testsAvailable: tests.tests.length };
}

/** Raw correct/total summed across the per-section scores of completed
 * full-length tests. */
export function fullLengthRawTotals(readiness: GetReadinessResponse): {
    correct: number;
    total: number;
} {
    let correct = 0;
    let total = 0;
    for (const s of readiness.sectionScores) {
        correct += s.correct;
        total += s.total;
    }
    return { correct, total };
}

/** The averaged MCAT-scale total-score ESTIMATE (472–528) + its range, or null
 * when the backend didn't produce one (no completed full-length). Deterministic
 * lookup computed in the Rust backend — never an AI/official score. */
export interface ScaledEstimate {
    score: number;
    low: number;
    high: number;
}

export function fullLengthScaledEstimate(
    readiness: GetReadinessResponse,
): ScaledEstimate | null {
    const score = readiness.readinessScaledScore;
    if (score === undefined) {
        return null;
    }
    return {
        score,
        low: readiness.readinessScaledLow ?? score,
        high: readiness.readinessScaledHigh ?? score,
    };
}

// ---- Formatting ---------------------------------------------------------------

export function pct(fraction: number): string {
    return `${Math.round(fraction * 100)}%`;
}

// ---- Summary cards ------------------------------------------------------------

export interface SummaryCardData {
    label: string;
    value: string;
    sub: string;
    /** Dimmed: there is not enough data behind the number yet. */
    muted: boolean;
}

const LOADING_CARD_VALUE = "—";

// SpeedyCAT: the "Memory now" recall percentage is only meaningful with a
// large enough sample, so it's hidden until strictly more than this many cards
// have been reviewed (count > MEMORY_CARD_MIN). Below the threshold the card
// shows progress instead of a number. Matches rslib MIN_MEMORY_CARDS (30).
const MEMORY_CARD_MIN = 30;

export function studiedTodayCard(data: GraphsResponse | null): SummaryCardData {
    const label = "Studied today";
    if (!data?.today) {
        return { label, value: LOADING_CARD_VALUE, sub: "Loading…", muted: true };
    }
    const today = data.today;
    if (!today.answerCount) {
        return {
            label,
            value: "0 cards",
            sub: "No flashcards reviewed yet today",
            muted: true,
        };
    }
    const minutes = Math.round(today.answerMillis / 1000 / 60);
    const correct = pct(today.correctCount / today.answerCount);
    return {
        label,
        value: `${today.answerCount} cards`,
        sub: `${minutes} min · ${correct} correct`,
        muted: false,
    };
}

export function memoryCard(data: GraphsResponse | null): SummaryCardData {
    const label = "Memory now";
    if (!data) {
        return { label, value: LOADING_CARD_VALUE, sub: "Loading…", muted: true };
    }
    if (!data.fsrs) {
        return {
            label,
            value: LOADING_CARD_VALUE,
            sub: "Turn on FSRS to see your average recall",
            muted: true,
        };
    }
    const retrievability = data.retrievability;
    const cardCount = retrievability
        ? Object.keys(retrievability.retrievability).length
        : 0;
    // Require more than MEMORY_CARD_MIN reviewed cards before showing a recall
    // percentage; a handful of cards isn't a trustworthy sample. Below the
    // threshold, show progress toward it instead of a number.
    if (!retrievability || cardCount <= MEMORY_CARD_MIN) {
        return {
            label,
            value: LOADING_CARD_VALUE,
            sub: `Study more to unlock (${cardCount}/${MEMORY_CARD_MIN} reviewed cards)`,
            muted: true,
        };
    }
    return {
        label,
        // `average` is already a percentage (0-100).
        value: `${Math.round(retrievability.average)}%`,
        sub: `Average recall chance across ${cardCount} reviewed cards (FSRS)`,
        muted: false,
    };
}

export function collectionCard(data: GraphsResponse | null): SummaryCardData {
    const label = "Collection";
    const counts = data?.cardCounts?.includingInactive;
    if (!counts) {
        return { label, value: LOADING_CARD_VALUE, sub: "Loading…", muted: true };
    }
    const total = counts.newCards
        + counts.learn
        + counts.relearn
        + counts.young
        + counts.mature
        + counts.suspended
        + counts.buried;
    if (total === 0) {
        return { label, value: "0 cards", sub: "No flashcards yet", muted: true };
    }
    return {
        label,
        value: `${total} cards`,
        sub: `${counts.mature} mature · ${counts.newCards} unseen`,
        muted: false,
    };
}

export function practiceCard(
    overview: PracticeOverview | null,
    error: boolean,
): SummaryCardData {
    const label = "Practice accuracy";
    if (error) {
        return { label, value: LOADING_CARD_VALUE, sub: "Unavailable", muted: true };
    }
    if (!overview) {
        return { label, value: LOADING_CARD_VALUE, sub: "Loading…", muted: true };
    }
    if (overview.attempted === 0) {
        return {
            label,
            value: LOADING_CARD_VALUE,
            sub: "No practice questions answered yet",
            muted: true,
        };
    }
    const avgTime = formatDurationLong(Math.round(overview.avgTimeSeconds));
    return {
        label,
        value: pct(overview.accuracy),
        sub: `${overview.attempted} attempts · ~${avgTime} per question`,
        muted: false,
    };
}

export function fullLengthCard(
    overview: FullLengthOverview | null,
    error: boolean,
): SummaryCardData {
    const label = "Full-length raw score";
    if (error) {
        return { label, value: LOADING_CARD_VALUE, sub: "Unavailable", muted: true };
    }
    if (!overview) {
        return { label, value: LOADING_CARD_VALUE, sub: "Loading…", muted: true };
    }
    const pillar = overview.readiness.readiness;
    if (!pillar?.available) {
        return {
            label,
            value: LOADING_CARD_VALUE,
            sub: "No full-length test completed yet",
            muted: true,
        };
    }
    const { correct, total } = fullLengthRawTotals(overview.readiness);
    const range = `${pct(pillar.rangeLow)}–${pct(pillar.rangeHigh)}`;
    return {
        label,
        value: `${correct}/${total}`,
        sub: `${pct(pillar.value)} · 95% range ${range}`,
        muted: false,
    };
}
