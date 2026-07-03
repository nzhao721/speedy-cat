// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT: unit tests for the graduated hint-ladder pure helpers that drive the
// desktop practice UI — the NO-SKIP gating (a revealed subquestion must be
// answered before revealing the next tier or submitting the main question) and
// the highest-tier-reached / assisted tracking that feeds hint_level_used +
// assisted (penalized in the Performance pillar).

import { expect, test } from "vitest";

import {
    canRevealNextHint,
    canSubmitMain,
    emptyHintProgress,
    hintAnswerCorrect,
    hintLevelReached,
    type HintProgress,
    type HintSubquestion,
    isAssisted,
    pendingHintIndex,
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
