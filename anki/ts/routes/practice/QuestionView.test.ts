// @vitest-environment jsdom

// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT regression: learners must see which main-question choice they picked
// BEFORE submitting (practice + full-length). The highlight is a solid theme-accent
// border + selection-tint fill on the selected button (the same styling the hint
// subquestions reuse); switching choices moves it. Post-submit uses green/red.

import { afterEach, beforeEach, expect, test } from "vitest";
import { mount, tick, unmount } from "svelte";

import { McatSection } from "./lib";
import type { PracticeQuestion } from "./lib";
import QuestionView from "./QuestionView.svelte";

function sampleQuestion(): PracticeQuestion {
    return {
        id: "q1",
        section: McatSection.BBLS,
        stem: "Which base pairs with adenine?",
        choices: [
            { label: "A", text: "Thymine" },
            { label: "B", text: "Guanine" },
            { label: "C", text: "Cytosine" },
            { label: "D", text: "Uracil" },
        ],
        correctAnswer: "A",
        explanation: "A pairs with T in DNA.",
        topicTags: [],
    } as unknown as PracticeQuestion;
}

function choiceButtons(host: HTMLElement): HTMLButtonElement[] {
    return [...host.querySelectorAll<HTMLButtonElement>("button.choice")];
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

test("applies selected class to the picked choice before submit", async () => {
    component = mount(QuestionView, {
        target: host,
        props: {
            question: sampleQuestion(),
            number: 1,
            selected: "B",
            revealed: false,
            disabled: false,
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

test("clicking a choice emits select so the parent can move the highlight", async () => {
    let picked = "";
    component = mount(QuestionView, {
        target: host,
        props: {
            question: sampleQuestion(),
            number: 1,
            selected: picked,
            revealed: false,
            disabled: false,
        },
        events: {
            select: (event: CustomEvent<string>) => {
                picked = event.detail;
            },
        },
    });
    await tick();

    choiceButtons(host)[2].click();
    await tick();
    expect(picked).toBe("C");
});

test("revealed state shows correct/incorrect instead of selected", async () => {
    component = mount(QuestionView, {
        target: host,
        props: {
            question: sampleQuestion(),
            number: 1,
            selected: "B",
            revealed: true,
            disabled: true,
        },
    });
    await tick();

    const buttons = choiceButtons(host);
    expect(buttons[0].classList.contains("correct")).toBe(true);
    expect(buttons[0].classList.contains("selected")).toBe(false);
    expect(buttons[1].classList.contains("incorrect")).toBe(true);
    expect(buttons[1].classList.contains("selected")).toBe(false);
});
