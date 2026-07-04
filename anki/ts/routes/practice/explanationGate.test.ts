// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

import { expect, test } from "vitest";

import {
    buildEvaluatorPrompt,
    buildUserVisiblePrompt,
    correctAnswerText,
    emptyExplanationProgress,
    EXPLANATION_UNVERIFIED_NOTE,
    explanationBypassedProgress,
    explanationFailureHint,
    hintPromptsFromQuestion,
    itemBlocksProgress,
    requiresExplanationGate,
    seedFromStr,
    shouldUseExplanationGate,
} from "./explanationGate";

const sampleQuestion = {
    id: "q-1",
    stem: "Which enzyme catalyzes the rate-limiting step of glycolysis?",
    choices: [
        { label: "A", text: "Hexokinase" },
        { label: "B", text: "Phosphofructokinase-1" },
        { label: "C", text: "Pyruvate kinase" },
        { label: "D", text: "Aldolase" },
    ],
    correctAnswer: "B",
    hints: [{ prompt: "What role does ATP play here?" }],
};

test("requiresExplanationGate is deterministic and ~20%", () => {
    const sessionId = "ps-test-session";
    const flags = Array.from({ length: 100 }, (_, i) =>
        requiresExplanationGate(sessionId, `q-${i}`),
    );
    const rate = flags.filter(Boolean).length / flags.length;
    expect(rate).toBeGreaterThan(0.1);
    expect(rate).toBeLessThan(0.35);
    expect(requiresExplanationGate(sessionId, "q-42")).toBe(
        requiresExplanationGate(sessionId, "q-42"),
    );
});

test("shouldUseExplanationGate skips when AI off or unavailable", () => {
    expect(
        shouldUseExplanationGate("ps-1", "q-1", { available: true, aiOn: false }),
    ).toBe(false);
    expect(
        shouldUseExplanationGate("ps-1", "q-1", { available: false, aiOn: true }),
    ).toBe(false);
});

test("buildEvaluatorPrompt includes stem and withholds choices", () => {
    const prompt = buildEvaluatorPrompt(
        sampleQuestion.stem,
        "PFK-1 is regulated by ATP.",
        correctAnswerText(sampleQuestion),
    );
    expect(prompt).toContain(sampleQuestion.stem);
    expect(prompt).toContain("PFK-1 is regulated by ATP.");
    expect(prompt).toContain("Phosphofructokinase-1");
    expect(prompt).not.toContain("Hexokinase");
    expect(prompt).not.toContain("A)");
    expect(prompt).not.toContain("Answer choices");
});

test("buildUserVisiblePrompt shows stem only", () => {
    const prompt = buildUserVisiblePrompt(sampleQuestion.stem);
    expect(prompt).toContain(sampleQuestion.stem);
    expect(prompt).not.toContain("Phosphofructokinase");
});

test("explanationFailureHint escalates without revealing the answer", () => {
    const h1 = explanationFailureHint(1);
    const h2 = explanationFailureHint(2);
    const h3 = explanationFailureHint(4, hintPromptsFromQuestion(sampleQuestion));
    expect(h1).not.toEqual(h2);
    expect(h3).toContain("What role does ATP play");
    expect(h1.toLowerCase()).not.toContain("phosphofructokinase");
});

test("itemBlocksProgress while explanation gate is active", () => {
    const questions = [sampleQuestion];
    expect(
        itemBlocksProgress(questions, {
            "q-1": {
                active: true,
                failCount: 0,
                lastFeedback: "",
                passed: false,
                checking: false,
                bypassed: false,
            },
        }),
    ).toBe(true);
    expect(
        itemBlocksProgress(questions, {
            "q-1": {
                active: true,
                failCount: 1,
                lastFeedback: "more detail",
                passed: true,
                checking: false,
                bypassed: false,
            },
        }),
    ).toBe(false);
});

test("explanation check error bypasses the gate so the learner can advance", () => {
    const questions = [sampleQuestion];
    const before = { ...emptyExplanationProgress(), active: true, checking: true };
    // A transient AI failure (null result) → bypass, not a verified pass.
    const after = explanationBypassedProgress(before);
    expect(after.bypassed).toBe(true);
    expect(after.passed).toBe(false);
    expect(after.checking).toBe(false);
    expect(after.lastFeedback).toBe(EXPLANATION_UNVERIFIED_NOTE);
    // Bypassed gate must not block navigation once submitted.
    expect(itemBlocksProgress(questions, { "q-1": after })).toBe(false);
    // A real "insufficient" verdict (pass=false, not bypassed) still blocks.
    expect(
        itemBlocksProgress(questions, {
            "q-1": {
                ...emptyExplanationProgress(),
                active: true,
                failCount: 1,
                lastFeedback: "add more reasoning",
            },
        }),
    ).toBe(true);
});

test("seedFromStr matches FNV-1a reference for empty string", () => {
    expect(seedFromStr("")).toBe(0xcbf29ce484222325n);
});
