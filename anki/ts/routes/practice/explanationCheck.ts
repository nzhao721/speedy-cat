// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

import { bridgeCommand, bridgeCommandsAvailable } from "@tslib/bridgecommand";

import {
    DEFAULT_EXPLANATION_PROXY_URL,
    type ExplanationAiStatus,
    type ExplanationCheckResult,
} from "./explanationGate";

/** Read AI on/off + availability from the Qt bridge when embedded in desktop. */
export function fetchExplanationAiStatus(): Promise<ExplanationAiStatus> {
    if (!bridgeCommandsAvailable()) {
        return Promise.resolve({ available: true, aiOn: true });
    }
    return new Promise((resolve) => {
        bridgeCommand<ExplanationAiStatus>("speedycat:explanationAiStatus", (status) => {
            resolve(status ?? { available: false, aiOn: false });
        });
    });
}

const DEFAULT_SOURCE = "openai/gpt-5.4-mini via speedycat-proxy";

/** Coerce an arbitrary payload (bridge JSON or fetch JSON) into a verdict, or
 * null when it isn't a usable `{ pass, feedback }` object. */
function parseCheckResult(data: unknown): ExplanationCheckResult | null {
    if (!data || typeof data !== "object") {
        return null;
    }
    const record = data as Record<string, unknown>;
    if (typeof record.pass !== "boolean") {
        return null;
    }
    const feedback = typeof record.feedback === "string" ? record.feedback.trim() : "";
    const source = typeof record.source === "string" && record.source
        ? record.source
        : DEFAULT_SOURCE;
    return { pass: record.pass, feedback, source };
}

// --- Desktop Qt bridge path -------------------------------------------------
// The embedded Qt webview cannot reach the Firebase proxy directly (external
// fetches are blocked / fail), which made every check return the transient
// fallback. Instead we hand the check to Python (same proxy + local-key infra
// the working answer checker uses), run it off the GUI thread, and receive the
// verdict back via `web.eval` calling `__speedycatResolveExplanationCheck`.

type CheckResolver = (result: ExplanationCheckResult | null) => void;
const pendingExplanationChecks = new Map<number, CheckResolver>();
let nextExplanationCheckId = 1;

// Safety net: if Python never calls back (crash/late teardown), resolve as a
// transient error so the learner is never stuck in the "Checking…" state.
const BRIDGE_CHECK_TIMEOUT_MS = 30_000;

/** Called by the Qt bridge (via `web.eval`) when the Python check completes.
 * `payload` is the verdict object, or null when the check could not run. */
function resolveExplanationCheck(id: number, payload: unknown): void {
    const resolver = pendingExplanationChecks.get(id);
    if (!resolver) {
        return;
    }
    pendingExplanationChecks.delete(id);
    resolver(parseCheckResult(payload));
}

if (typeof window !== "undefined") {
    (window as unknown as Record<string, unknown>)
        .__speedycatResolveExplanationCheck = resolveExplanationCheck;
}

function checkExplanationViaBridge(
    stem: string,
    userExplanation: string,
    correctAnswer: string,
): Promise<ExplanationCheckResult | null> {
    return new Promise((resolve) => {
        const id = nextExplanationCheckId++;
        let settled = false;
        let timer: ReturnType<typeof setTimeout>;
        const finish = (result: ExplanationCheckResult | null): void => {
            if (settled) {
                return;
            }
            settled = true;
            clearTimeout(timer);
            pendingExplanationChecks.delete(id);
            resolve(result);
        };
        pendingExplanationChecks.set(id, finish);
        timer = setTimeout(() => finish(null), BRIDGE_CHECK_TIMEOUT_MS);
        // encodeURIComponent escapes ':' so the payload never breaks the
        // colon-delimited bridge command (and is UTF-8 safe for MCAT content).
        const payload = encodeURIComponent(
            JSON.stringify({ stem, userExplanation, correctAnswer }),
        );
        bridgeCommand(`speedycat:explanationCheck:${id}:${payload}`);
    });
}

async function checkExplanationViaFetch(
    stem: string,
    userExplanation: string,
    correctAnswer: string,
): Promise<ExplanationCheckResult | null> {
    try {
        const response = await fetch(DEFAULT_EXPLANATION_PROXY_URL, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ stem, userExplanation, correctAnswer }),
        });
        if (!response.ok) {
            return null;
        }
        return parseCheckResult(await response.json());
    } catch {
        return null;
    }
}

/** Verify an explanation with the AI. Returns a verdict, or null on a genuine
 * transient failure (network/proxy/AI unavailable) — callers treat null as
 * "couldn't verify" and let the learner advance rather than trapping them. */
export async function checkExplanation(
    stem: string,
    userExplanation: string,
    correctAnswer: string,
): Promise<ExplanationCheckResult | null> {
    // Embedded desktop: go through Python (reliable proxy + local-key path).
    // Dev server / browser / e2e (no bridge): fall back to a direct fetch.
    if (bridgeCommandsAvailable()) {
        return checkExplanationViaBridge(stem, userExplanation, correctAnswer);
    }
    return checkExplanationViaFetch(stem, userExplanation, correctAnswer);
}
