// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT: explanation gate for practice MCQs — ~1-in-5 correct answers
// require a written explanation verified by AI before advancing. Pure helpers
// mirror `anki/pylib/anki/speedycat_explanation.py` and mobile PracticeLogic.

export interface ExplanationChoice {
    label: string;
    text: string;
}

/** Minimal question shape for explanation-gate helpers (no protobuf import). */
export interface ExplanationQuestion {
    id: string;
    stem: string;
    choices: ExplanationChoice[];
    correctAnswer: string;
    hints?: { prompt: string }[];
}

export const EXPLANATION_GATE_MODULO = 5;
export const EXPLANATION_GATE_SUFFIX = ":explanation-gate";

export const DEFAULT_EXPLANATION_PROXY_URL =
    "https://us-central1-speedycat-mcat.cloudfunctions.net/checkPracticeExplanation";

export const GENERIC_EXPLANATION_FAIL_HINTS: readonly string[] = [
    "Walk through your reasoning step by step. What concept does this question test, "
    + "and how does it support your conclusion?",
    "Be more specific about the underlying principle. How does it apply to the "
    + "scenario in the stem?",
    "Connect the stem's details to the science. Explain why your answer follows "
    + "from that principle — without just restating the question.",
];

export interface ExplanationAiStatus {
    available: boolean;
    aiOn: boolean;
}

export interface ExplanationCheckResult {
    pass: boolean;
    feedback: string;
    source: string;
}

export interface ExplanationProgress {
    active: boolean;
    failCount: number;
    lastFeedback: string;
    passed: boolean;
    checking: boolean;
    // Set when the AI check could not run (network/proxy/AI unavailable). The
    // learner submitted an explanation but we couldn't verify it, so the gate
    // is cleared and they may advance — NOT a verified pass. Mirrors the spec
    // requirement that the app stays usable when AI is unavailable.
    bypassed: boolean;
}

export function emptyExplanationProgress(): ExplanationProgress {
    return {
        active: false,
        failCount: 0,
        lastFeedback: "",
        passed: false,
        checking: false,
        bypassed: false,
    };
}

/** Shown when the AI check errors and the gate is bypassed so the learner can
 * continue (transient/technical failure — never a verified pass). */
export const EXPLANATION_UNVERIFIED_NOTE =
    "We couldn't verify your explanation right now, so you can keep going.";

/** Terminal progress when the AI check could not run: clear the gate (bypassed)
 * so the learner advances after submitting. Distinct from a verified pass. */
export function explanationBypassedProgress(
    prog: ExplanationProgress,
): ExplanationProgress {
    return {
        ...prog,
        checking: false,
        passed: false,
        bypassed: true,
        lastFeedback: EXPLANATION_UNVERIFIED_NOTE,
    };
}

/** FNV-1a 64-bit hash (mirrors rslib `seed_from_str` / Kotlin). */
export function seedFromStr(s: string): bigint {
    let hash = 0xcbf29ce484222325n;
    const bytes = new TextEncoder().encode(s);
    for (const b of bytes) {
        hash ^= BigInt(b);
        hash = BigInt.asUintN(64, hash * 0x100000001b3n);
    }
    return hash;
}

/** Deterministic ~1-in-5 gate per session + question (stable on revisit). */
export function requiresExplanationGate(
    sessionId: string,
    questionId: string,
): boolean {
    const key = `${sessionId}:${questionId}${EXPLANATION_GATE_SUFFIX}`;
    return seedFromStr(key) % BigInt(EXPLANATION_GATE_MODULO) === 0n;
}

export function shouldUseExplanationGate(
    sessionId: string,
    questionId: string,
    status: ExplanationAiStatus,
): boolean {
    if (!status.aiOn || !status.available) {
        return false;
    }
    return requiresExplanationGate(sessionId, questionId);
}

/** Resolve correct choice text from label (internal evaluator use only). */
export function correctAnswerText(question: ExplanationQuestion): string {
    const match = question.choices.find((c) => c.label === question.correctAnswer);
    const text = match?.text?.trim();
    return text || question.correctAnswer;
}

/** Evaluator prompt — stem + learner text + internal answer (no choices). */
export function buildEvaluatorPrompt(
    stem: string,
    userExplanation: string,
    correctAnswer: string,
): string {
    return [
        `QUESTION STEM: ${stem.trim() || "(empty)"}`,
        `LEARNER'S WRITTEN EXPLANATION: ${userExplanation.trim() || "(blank)"}`,
        `CORRECT ANSWER (internal reference — judge only; do not reveal): ${
            correctAnswer.trim() || "(empty)"
        }`,
        "",
        "Return the JSON verdict now.",
    ].join("\n");
}

/** Instruction shown in the explanation gate before the learner writes. */
export const EXPLANATION_INSTRUCTION =
    "You answered correctly. Before moving on, explain your reasoning in a few "
    + "sentences.";

/** Chatbot opener shown to the learner (instruction only — no question quote). */
export function buildUserVisiblePrompt(_stem: string): string {
    return EXPLANATION_INSTRUCTION;
}

/** Progressive coaching after a failed explanation (never the full answer).
 * Takes hint prompt strings (see `hintPromptsFromQuestion`), mirroring the
 * Python twin's `explanation_failure_hint(fail_count, hint_prompts)`. */
export function explanationFailureHint(
    failCount: number,
    hintPrompts: string[] = [],
): string {
    const level = Math.max(1, failCount);
    if (level <= GENERIC_EXPLANATION_FAIL_HINTS.length) {
        return GENERIC_EXPLANATION_FAIL_HINTS[level - 1]!;
    }
    const hintIdx = level - GENERIC_EXPLANATION_FAIL_HINTS.length - 1;
    const prompt = hintPrompts[hintIdx];
    if (prompt) {
        return `Consider: ${prompt}`;
    }
    return GENERIC_EXPLANATION_FAIL_HINTS[GENERIC_EXPLANATION_FAIL_HINTS.length - 1]!;
}

export function hintPromptsFromQuestion(question: ExplanationQuestion): string[] {
    return (question.hints ?? []).map((h) => h.prompt);
}

const SENTENCE_SPLIT = /[.!?]+/;

/** Conservative AI-off fallback when a check cannot run (not used for gating). */
export function heuristicPassesExplanation(text: string): boolean {
    const collapsed = text.replace(/\s+/g, " ").trim();
    if (!collapsed) {
        return false;
    }
    const words = collapsed.split(" ").filter(Boolean);
    if (words.length < 12) {
        return false;
    }
    const sentences = collapsed.split(SENTENCE_SPLIT).map((s) => s.trim()).filter(Boolean);
    return sentences.length >= 2;
}

/** True when any question in the run item still awaits a passing explanation.
 * A `bypassed` question (AI check errored) no longer blocks — the learner may
 * advance once they've submitted, per the AI-unavailable fallback. */
export function itemBlocksProgress(
    questions: ExplanationQuestion[],
    progress: Readonly<Record<string, ExplanationProgress>>,
): boolean {
    return questions.some((q) => {
        const p = progress[q.id];
        return p?.active === true && p.passed !== true && p.bypassed !== true;
    });
}
