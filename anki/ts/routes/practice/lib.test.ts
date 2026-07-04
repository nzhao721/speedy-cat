// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT: unit tests for the graduated hint-ladder pure helpers that drive the
// desktop practice UI — the NO-SKIP gating (a revealed subquestion must be
// answered before revealing the next tier or submitting the main question) and
// the highest-tier-reached / assisted tracking that feeds hint_level_used +
// assisted (penalized in the Performance pillar).

import { expect, test } from "vitest";

import {
    applyHintAnswer,
    availableFullLengthTests,
    canRevealNextHint,
    canReturnToMain,
    canShowFirstHintButton,
    canShowNextHintButton,
    canSubmitHintAnswer,
    canSubmitMain,
    shouldShowConvenientMainSubmit,
    emptyHintProgress,
    firstHintDelaySeconds,
    hasHintLadder,
    hintAnswerCorrect,
    hintChoicesEnabled,
    hintDisplayOrder,
    hintLevelReached,
    type HintProgress,
    type HintSubquestion,
    HINT_SUBSEQUENT_SECONDS,
    HINT_WRONG_RETRY_SECONDS,
    isAssisted,
    passageHintWordCountForQuestion,
    passageReadGateSeconds,
    passageWordCount,
    pendingHintIndex,
    shouldTickQuestionHintTimer,
    startHintWrongCooldown,
    tickHintCooldowns,
} from "./lib";

function hint(level: number, correct = "A"): HintSubquestion {
    return {
        level,
        prompt: `prompt L${level}`,
        choices: [
            { label: "A", text: "a" },
            { label: "B", text: "b" },
            { label: "C", text: "c" },
            { label: "D", text: "d" },
        ],
        correctAnswer: correct,
        rationale: "because",
    } as unknown as HintSubquestion;
}

const ladder = [hint(1), hint(2), hint(3)];

test("availableFullLengthTests hides completed tests from the picker", () => {
    const tests = [
        { testId: "fl-1", title: "FL 1" },
        { testId: "fl-2", title: "FL 2" },
        { testId: "fl-3", title: "FL 3" },
    ] as never[];
    const completed = [
        { attemptId: "a1", testId: "fl-1", testTitle: "FL 1" },
        { attemptId: "a2", testId: "fl-3", testTitle: "FL 3" },
    ] as never[];

    expect(availableFullLengthTests(tests, completed).map((t) => t.testId)).toEqual([
        "fl-2",
    ]);
    expect(availableFullLengthTests(tests, []).map((t) => t.testId)).toEqual([
        "fl-1",
        "fl-2",
        "fl-3",
    ]);
    expect(availableFullLengthTests([], completed)).toEqual([]);
});

test("hasHintLadder is true only when hints are present", () => {
    expect(hasHintLadder({ hints: ladder } as never)).toBe(true);
    expect(hasHintLadder({ hints: [] } as never)).toBe(false);
    expect(hasHintLadder({} as never)).toBe(false);
});

test("hintLevelReached tracks the highest tier and clamps to 0-3", () => {
    expect(hintLevelReached(ladder, 0)).toBe(0);
    expect(hintLevelReached(ladder, 1)).toBe(1);
    expect(hintLevelReached(ladder, 2)).toBe(2);
    expect(hintLevelReached(ladder, 3)).toBe(3);
    // Falls back to 1-based position when a level is missing/out of range.
    const noLevels = [hint(0), hint(0), hint(0)];
    expect(hintLevelReached(noLevels, 3)).toBe(3);
});

test("assisted is true only once level 3 is reached", () => {
    expect(isAssisted(hintLevelReached(ladder, 1))).toBe(false);
    expect(isAssisted(hintLevelReached(ladder, 2))).toBe(false);
    expect(isAssisted(hintLevelReached(ladder, 3))).toBe(true);
});

test("no-skip: cannot reveal the next hint until the current one is answered", () => {
    // Just revealed tier 1, not answered yet -> tier 1 is pending, cannot advance.
    const p1: HintProgress = { revealed: 1, picks: {} };
    expect(pendingHintIndex(p1)).toBe(0);
    expect(canRevealNextHint(ladder, p1)).toBe(false);

    // Answered tier 1 -> may reveal tier 2.
    const p1done: HintProgress = { revealed: 1, picks: { 0: "A" } };
    expect(pendingHintIndex(p1done)).toBe(-1);
    expect(canRevealNextHint(ladder, p1done)).toBe(true);

    // All three revealed + answered -> nothing left to reveal.
    const pAll: HintProgress = { revealed: 3, picks: { 0: "A", 1: "B", 2: "C" } };
    expect(canRevealNextHint(ladder, pAll)).toBe(false);
});

test("no-skip: cannot submit the main question while a hint is unanswered", () => {
    // A selection but a revealed-and-unanswered hint blocks submission.
    const pending: HintProgress = { revealed: 2, picks: { 0: "A" } };
    expect(canSubmitMain("C", pending)).toBe(false);
    // Answering the pending hint unblocks submission.
    const cleared: HintProgress = { revealed: 2, picks: { 0: "A", 1: "B" } };
    expect(canSubmitMain("C", cleared)).toBe(true);
    // No selection never submits, even with the ladder cleared.
    expect(canSubmitMain("", cleared)).toBe(false);
    // With no ladder engaged, a selection submits immediately.
    expect(canSubmitMain("C", emptyHintProgress())).toBe(true);
});

test("hintAnswerCorrect compares against the subquestion's answer", () => {
    expect(hintAnswerCorrect(hint(1, "B"), "B")).toBe(true);
    expect(hintAnswerCorrect(hint(1, "B"), "A")).toBe(false);
    expect(hintAnswerCorrect(hint(1, "B"), "")).toBe(false);
});

test("firstHintDelaySeconds scales with reading load and clamps 15-30s", () => {
    const short = { stem: "x", choices: [{ label: "A", text: "a" }] } as never;
    const medium = {
        stem: "x".repeat(500),
        choices: [{ label: "A", text: "y".repeat(500) }],
    } as never;
    const veryLong = {
        stem: "x".repeat(1500),
        choices: [{ label: "A", text: "y".repeat(1500) }],
    } as never;
    expect(firstHintDelaySeconds(short)).toBe(15);
    expect(firstHintDelaySeconds(medium)).toBe(25);
    expect(firstHintDelaySeconds(veryLong)).toBe(30);
});

test("passageReadGateSeconds uses 300 WPM", () => {
    expect(passageReadGateSeconds(600)).toBe(120);
    expect(passageReadGateSeconds(300)).toBe(60);
    expect(passageReadGateSeconds(0)).toBe(0);
});

test("firstHintDelaySeconds uses max of base and passage read time", () => {
    const q = { stem: "x", choices: [{ label: "A", text: "a" }] } as never;
    expect(firstHintDelaySeconds(q, 600)).toBe(120);
    expect(firstHintDelaySeconds(q, 50)).toBe(15);
});

test("passageHintWordCountForQuestion applies only to first in multi-question sets", () => {
    expect(passageHintWordCountForQuestion(3, 0, 500)).toBe(500);
    expect(passageHintWordCountForQuestion(3, 1, 500)).toBeUndefined();
    expect(passageHintWordCountForQuestion(1, 0, 500)).toBe(500);
});

test("passageWordCount prefers metadata then counts plain text", () => {
    expect(passageWordCount(42, "ignored")).toBe(42);
    expect(passageWordCount(null, "one two three")).toBe(3);
});

test("shouldTickQuestionHintTimer starts on first visibility and pauses off-screen", () => {
    expect(shouldTickQuestionHintTimer(false, false)).toBe(false);
    expect(shouldTickQuestionHintTimer(false, true)).toBe(false);
    expect(shouldTickQuestionHintTimer(true, true)).toBe(true);
    expect(shouldTickQuestionHintTimer(true, false)).toBe(false);
});

test("per-question hint timers are independent via visibility gating", () => {
    // Q1 visible 60s does not advance Q2 until Q2 has been seen.
    let q1Elapsed = 0;
    let q2Elapsed = 0;
    let q1Ever = true;
    let q2Ever = false;
    for (let t = 0; t < 60; t += 1) {
        if (shouldTickQuestionHintTimer(q1Ever, true)) q1Elapsed += 1;
        if (shouldTickQuestionHintTimer(q2Ever, false)) q2Elapsed += 1;
    }
    expect(q1Elapsed).toBe(60);
    expect(q2Elapsed).toBe(0);
    q2Ever = true;
    for (let t = 0; t < 10; t += 1) {
        if (shouldTickQuestionHintTimer(q2Ever, true)) q2Elapsed += 1;
    }
    expect(q2Elapsed).toBe(10);
});

test("hint affordance buttons respect timers and progress", () => {
    const q = { stem: "stem", choices: [{ label: "A", text: "a" }] } as never;
    expect(canShowFirstHintButton(14, q, emptyHintProgress(), false)).toBe(false);
    expect(canShowFirstHintButton(15, q, emptyHintProgress(), false)).toBe(true);
    expect(canShowFirstHintButton(20, q, { revealed: 1, picks: {} }, false)).toBe(
        false,
    );

    const doneL1: HintProgress = { revealed: 1, picks: { 0: "A" } };
    expect(canShowNextHintButton(5, ladder, doneL1, false)).toBe(false);
    expect(canShowNextHintButton(HINT_SUBSEQUENT_SECONDS, ladder, doneL1, false)).toBe(
        true,
    );
});

test("wrong hint answers are rejected; picks store correct answers only", () => {
    const prog: HintProgress = { revealed: 1, picks: {} };
    const wrong = applyHintAnswer(ladder[0], 0, "B", prog);
    expect(wrong.correct).toBe(false);
    expect(wrong.progress.picks[0]).toBeUndefined();

    const right = applyHintAnswer(ladder[0], 0, "A", prog);
    expect(right.correct).toBe(true);
    expect(right.progress.picks[0]).toBe("A");
});

test("hint submit cooldown blocks rapid retries", () => {
    expect(canSubmitHintAnswer("A", 0)).toBe(true);
    expect(canSubmitHintAnswer("A", 3)).toBe(false);
    expect(canSubmitHintAnswer("", 0)).toBe(false);
    expect(HINT_WRONG_RETRY_SECONDS).toBe(5);
});

test("hint cooldown expires and re-enables choices for retry", () => {
    const qid = "q1";
    let cooldowns = startHintWrongCooldown({}, qid);
    expect(cooldowns[qid]).toBe(HINT_WRONG_RETRY_SECONDS);
    expect(hintChoicesEnabled(false, false, true, cooldowns[qid] ?? 0)).toBe(false);
    expect(canSubmitHintAnswer("A", cooldowns[qid] ?? 0)).toBe(false);

    for (let i = 0; i < HINT_WRONG_RETRY_SECONDS; i++) {
        cooldowns = tickHintCooldowns(cooldowns);
    }
    expect(cooldowns[qid] ?? 0).toBe(0);
    expect(hintChoicesEnabled(false, false, true, cooldowns[qid] ?? 0)).toBe(true);
    expect(canSubmitHintAnswer("A", cooldowns[qid] ?? 0)).toBe(true);

    const prog: HintProgress = { revealed: 1, picks: {} };
    const wrong = applyHintAnswer(ladder[0], 0, "B", prog);
    expect(wrong.correct).toBe(false);
    expect(wrong.progress.picks[0]).toBeUndefined();
    cooldowns = startHintWrongCooldown(cooldowns, qid);
    for (let i = 0; i < HINT_WRONG_RETRY_SECONDS; i++) {
        cooldowns = tickHintCooldowns(cooldowns);
    }
    const right = applyHintAnswer(ladder[0], 0, "A", prog);
    expect(right.correct).toBe(true);
    expect(canSubmitHintAnswer("A", cooldowns[qid] ?? 0)).toBe(true);
});

test("convenient main submit appears once any hint tier is revealed", () => {
    expect(shouldShowConvenientMainSubmit(emptyHintProgress(), false)).toBe(false);
    expect(shouldShowConvenientMainSubmit({ revealed: 1, picks: {} }, false)).toBe(
        true,
    );
    expect(shouldShowConvenientMainSubmit({ revealed: 2, picks: { 0: "A" } }, false)).toBe(
        true,
    );
    expect(shouldShowConvenientMainSubmit({ revealed: 1, picks: {} }, true)).toBe(false);
});

test("hintDisplayOrder renders the most-recent tier first (reverse of progression)", () => {
    // Display-only reversal: the newest revealed hint sits on top (under the
    // main question), while the ladder still progresses 0 -> 1 -> 2.
    expect(hintDisplayOrder(0)).toEqual([]);
    expect(hintDisplayOrder(1)).toEqual([0]);
    expect(hintDisplayOrder(3)).toEqual([2, 1, 0]);
    // Never yields negative indices for a degenerate revealed count.
    expect(hintDisplayOrder(-2)).toEqual([]);
});

test("canReturnToMain: only the latest revealed hint, once answered, while unlocked", () => {
    // Nothing revealed yet — no shortcut.
    expect(canReturnToMain({ revealed: 0, picks: {} }, 0, false)).toBe(false);
    // Latest hint revealed but not yet answered — no shortcut (no-skip intact).
    expect(canReturnToMain({ revealed: 2, picks: { 0: "A" } }, 1, false)).toBe(false);
    // Latest hint answered — shortcut appears on that (latest) tier only.
    const answered: HintProgress = { revealed: 2, picks: { 0: "A", 1: "A" } };
    expect(canReturnToMain(answered, 1, false)).toBe(true);
    expect(canReturnToMain(answered, 0, false)).toBe(false);
    // Locked (main question already submitted) — never shown.
    expect(canReturnToMain(answered, 1, true)).toBe(false);
});
