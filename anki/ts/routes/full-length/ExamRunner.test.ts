// @vitest-environment jsdom

// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

// SpeedyCAT regression: the Full-Length exam runner must show the passage for
// the FIRST question of a section the moment that section starts (every section
// is a freshly-mounted ExamRunner). The onMount passage-prefetch previously read
// a not-yet-recomputed reactive value, so it never fetched the first item's
// passage and the pane rendered "No passage." for question 1 of every section.

import { afterEach, beforeEach, expect, test, vi } from "vitest";

import type * as PracticeLib from "../practice/lib";

const PASSAGE_BODY = "SPEEDYCAT_FIRST_PASSAGE_BODY_MARKER";

const { fetchQuestions, fetchPassageSet, logFullLengthAnswer } = vi.hoisted(() => ({
    fetchQuestions: vi.fn(),
    fetchPassageSet: vi.fn(),
    logFullLengthAnswer: vi.fn(),
}));

vi.mock("../practice/lib", async (importOriginal) => {
    const actual = await importOriginal<typeof PracticeLib>();
    return { ...actual, fetchQuestions, fetchPassageSet, logFullLengthAnswer };
});

import { mount, tick, unmount } from "svelte";

import { McatSection, earlySectionEndWarning } from "../practice/lib";
import type { CarsPassageSet, PracticeQuestion } from "../practice/lib";
import ExamRunner from "./ExamRunner.svelte";

function passageQuestion(id: string, passageId: string): PracticeQuestion {
    return {
        id,
        section: McatSection.CARS,
        passageId,
        testId: "T1",
        stem: `stem ${id}`,
        choices: [
            { label: "A", text: "alpha" },
            { label: "B", text: "bravo" },
            { label: "C", text: "charlie" },
            { label: "D", text: "delta" },
        ],
        correctAnswer: "A",
        explanation: "",
        topicTags: [],
    } as unknown as PracticeQuestion;
}

const firstPassageSet = {
    passage: {
        passageId: "P1",
        section: McatSection.CARS,
        title: "Passage One",
        passage: PASSAGE_BODY,
        discipline: "",
        wordCount: 0,
        sourceName: "",
    },
    questions: [],
} as unknown as CarsPassageSet;

let host: HTMLElement;
let component: Record<string, unknown> | undefined;
let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
    confirmSpy = vi.spyOn(globalThis, "confirm");
    confirmSpy.mockReturnValue(true);
    fetchQuestions.mockReset();
    fetchPassageSet.mockReset();
    logFullLengthAnswer.mockReset();
    // The section opens on a passage-linked question (the real symptom case).
    fetchQuestions.mockResolvedValue([
        passageQuestion("q1", "P1"),
        passageQuestion("q2", "P1"),
    ]);
    fetchPassageSet.mockResolvedValue(firstPassageSet);
    logFullLengthAnswer.mockResolvedValue(undefined);
    host = document.createElement("div");
    document.body.appendChild(host);
});

afterEach(() => {
    confirmSpy.mockRestore();
    if (component) {
        unmount(component);
        component = undefined;
    }
    host.remove();
});

// Drain the async onMount (fetchQuestions -> ensurePassage -> fetchPassageSet)
// and let Svelte flush the resulting DOM updates.
async function settle(): Promise<void> {
    for (let i = 0; i < 20; i++) {
        await Promise.resolve();
        await tick();
    }
}

test("shows the first question's passage when a section starts", async () => {
    component = mount(ExamRunner, {
        target: host,
        props: {
            attemptId: "A1",
            testId: "T1",
            section: McatSection.CARS,
            durationSeconds: 600,
            sectionOrder: 1,
            sectionTotal: 4,
        },
    });

    await settle();

    expect(fetchQuestions).toHaveBeenCalledTimes(1);
    // The passage for the first served item must be requested on section start.
    expect(fetchPassageSet).toHaveBeenCalledWith("P1");

    const text = host.textContent ?? "";
    expect(text).toContain(PASSAGE_BODY);
    expect(text).not.toContain("No passage.");
});

test("earlySectionEndWarning includes unanswered count when needed", () => {
    expect(earlySectionEndWarning(0)).not.toContain("unanswered");
    expect(earlySectionEndWarning(1)).toContain(
        "You have 1 unanswered question in this section.",
    );
    expect(earlySectionEndWarning(3)).toContain(
        "You have 3 unanswered questions in this section.",
    );
    expect(earlySectionEndWarning(0)).toContain("not be able to return");
});

test("asks for confirmation before ending a section early", async () => {
    confirmSpy.mockReturnValue(false);
    let sectionDone = false;

    component = mount(ExamRunner, {
        target: host,
        props: {
            attemptId: "A1",
            testId: "T1",
            section: McatSection.CARS,
            durationSeconds: 600,
            sectionOrder: 1,
            sectionTotal: 4,
        },
        events: {
            sectionDone: () => {
                sectionDone = true;
            },
        },
    });

    await settle();

    const endButton = Array.from(host.querySelectorAll("button")).find(
        (b) => b.textContent?.trim() === "End section",
    );
    expect(endButton).toBeTruthy();
    endButton!.click();
    await settle();

    expect(confirmSpy).toHaveBeenCalledWith(
        earlySectionEndWarning(2),
    );
    expect(sectionDone).toBe(false);
    expect(logFullLengthAnswer).not.toHaveBeenCalled();
});

test("ends section when early end is confirmed", async () => {
    confirmSpy.mockReturnValue(true);
    let sectionDone = false;

    component = mount(ExamRunner, {
        target: host,
        props: {
            attemptId: "A1",
            testId: "T1",
            section: McatSection.CARS,
            durationSeconds: 600,
            sectionOrder: 1,
            sectionTotal: 4,
        },
        events: {
            sectionDone: () => {
                sectionDone = true;
            },
        },
    });

    await settle();

    const endButton = Array.from(host.querySelectorAll("button")).find(
        (b) => b.textContent?.trim() === "End section",
    );
    endButton!.click();
    await settle();

    expect(confirmSpy).toHaveBeenCalled();
    expect(sectionDone).toBe(true);
    expect(logFullLengthAnswer).toHaveBeenCalledTimes(2);
});

test("auto-ends section when the timer expires without confirmation", async () => {
    vi.useFakeTimers();
    confirmSpy.mockReturnValue(false);
    let sectionDone = false;

    try {
        component = mount(ExamRunner, {
            target: host,
            props: {
                attemptId: "A1",
                testId: "T1",
                section: McatSection.CARS,
                durationSeconds: 2,
                sectionOrder: 1,
                sectionTotal: 4,
            },
            events: {
                sectionDone: () => {
                    sectionDone = true;
                },
            },
        });

        await settle();
        expect(confirmSpy).not.toHaveBeenCalled();

        await vi.advanceTimersByTimeAsync(2000);
        await settle();

        expect(confirmSpy).not.toHaveBeenCalled();
        expect(sectionDone).toBe(true);
        expect(logFullLengthAnswer).toHaveBeenCalledTimes(2);
    } finally {
        vi.useRealTimers();
    }
});
