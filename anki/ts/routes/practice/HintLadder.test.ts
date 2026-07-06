// @vitest-environment jsdom

// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT regression: hint subquestion choices must show the same pre-submit
// highlight as the main question (theme accent border + selection tint).

import { afterEach, beforeEach, expect, test } from "vitest";
import { mount, tick, unmount } from "svelte";

import type { HintProgress, HintSubquestion } from "./lib";
import HintLadder from "./HintLadder.svelte";

function sampleHint(): HintSubquestion {
    return {
        level: 1,
        prompt: "Which base pairs with adenine?",
        choices: [
            { label: "A", text: "Thymine" },
            { label: "B", text: "Guanine" },
            { label: "C", text: "Cytosine" },
            { label: "D", text: "Uracil" },
        ],
        correctAnswer: "A",
        rationale: "A pairs with T in DNA.",
    } as unknown as HintSubquestion;
}

function choiceButtons(host: HTMLElement): HTMLButtonElement[] {
    return [...host.querySelectorAll<HTMLButtonElement>("button.sub-choice")];
}

let host: HTMLElement;
let component: Record<string, unknown> | undefined;

beforeEach(() => {
    host = document.createElement("div");
    document.body.appendChild(host);
});

afterEach(() => {
    if (component) {
        unmount(component);
        component = undefined;
    }
    host.remove();
});

test("applies selected class to the picked hint choice before submit", async () => {
    const progress: HintProgress = { revealed: 1, picks: {} };
    component = mount(HintLadder, {
        target: host,
        props: {
            hints: [sampleHint()],
            progress,
            pendingChoice: "B",
        },
    });
    await tick();

    const buttons = choiceButtons(host);
    expect(buttons).toHaveLength(4);
    expect(buttons[0].classList.contains("selected")).toBe(false);
    expect(buttons[1].classList.contains("selected")).toBe(true);
    expect(buttons[2].classList.contains("selected")).toBe(false);
    expect(buttons[3].classList.contains("selected")).toBe(false);
});

test("clicking a hint choice emits pendingChoiceChange so the parent can move the highlight", async () => {
    const progress: HintProgress = { revealed: 1, picks: {} };
    let pending = "";
    component = mount(HintLadder, {
        target: host,
        props: {
            hints: [sampleHint()],
            progress,
            pendingChoice: pending,
        },
        events: {
            pendingChoiceChange: (event: CustomEvent<string>) => {
                pending = event.detail;
            },
        },
    });
    await tick();

    choiceButtons(host)[2].click();
    await tick();
    expect(pending).toBe("C");
});

test("only the pending hint tier can show a selected choice", async () => {
    const hints = [sampleHint(), { ...sampleHint(), level: 2, prompt: "Second hint?" }];
    const progress: HintProgress = { revealed: 2, picks: { 0: "A" } };
    component = mount(HintLadder, {
        target: host,
        props: {
            hints,
            progress,
            pendingChoice: "C",
        },
    });
    await tick();

    const buttons = choiceButtons(host);
    // Eight choices total (four per hint); only the active tier's "C" is selected.
    expect(buttons.filter((b) => b.classList.contains("selected"))).toHaveLength(1);
    expect(buttons.find((b) => b.classList.contains("selected"))?.textContent).toContain(
        "Cytosine",
    );
});

test("answered hint shows compact correct answer instead of choice buttons", async () => {
    const progress: HintProgress = { revealed: 1, picks: { 0: "A" } };
    component = mount(HintLadder, {
        target: host,
        props: {
            hints: [sampleHint()],
            progress,
            pendingChoice: "",
        },
    });
    await tick();

    expect(choiceButtons(host)).toHaveLength(0);
    const answer = host.querySelector(".sub-answer");
    expect(answer?.textContent).toContain("A. Thymine");
});

test("backdrop is a dimming layer, not a styled button", async () => {
    const progress: HintProgress = { revealed: 1, picks: {} };
    component = mount(HintLadder, {
        target: host,
        props: {
            hints: [sampleHint()],
            progress,
            pendingChoice: "",
        },
    });
    await tick();

    const backdrop = host.querySelector(".hint-backdrop");
    expect(backdrop).not.toBeNull();
    expect(backdrop?.tagName).toBe("DIV");
    expect(host.querySelector("button.hint-backdrop")).toBeNull();
});

test("close button dismisses the hint popup", async () => {
    const progress: HintProgress = { revealed: 1, picks: {} };
    let dismissed = false;
    component = mount(HintLadder, {
        target: host,
        props: {
            hints: [sampleHint()],
            progress,
            pendingChoice: "",
        },
        events: {
            dismiss: () => {
                dismissed = true;
            },
        },
    });
    await tick();

    const close = host.querySelector<HTMLButtonElement>("button.hint-close");
    expect(close).not.toBeNull();
    close!.click();
    await tick();
    expect(dismissed).toBe(true);
});
