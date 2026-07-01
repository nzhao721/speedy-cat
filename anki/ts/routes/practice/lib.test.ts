// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

import { expect, test } from "vitest";

import {
    buildFilter,
    Difficulty,
    difficultyLabel,
    formatClock,
    formatDurationLong,
    groupIntoRunItems,
    McatSection,
    parseQuestionCount,
    parseTimerMinutes,
    primaryTopic,
    sectionLong,
    sectionShort,
} from "./lib";
import type { PracticeQuestion } from "./lib";

function question(
    id: string,
    section: McatSection,
    opts: { passageId?: string; topicTags?: string[] } = {},
): PracticeQuestion {
    return {
        id,
        section,
        passageId: opts.passageId,
        topicTags: opts.topicTags ?? [],
    } as unknown as PracticeQuestion;
}

test("section and difficulty labels fall back for unknown values", () => {
    expect(sectionShort(McatSection.CPBS)).toBe("Chem/Phys");
    expect(sectionShort(McatSection.CARS)).toBe("CARS");
    expect(sectionLong(McatSection.PSBB)).toBe(
        "Psychological, Social & Biological Foundations of Behavior",
    );
    expect(sectionShort(McatSection.UNSPECIFIED)).toBe("—");
    expect(difficultyLabel(Difficulty.HARD)).toBe("Hard");
    expect(difficultyLabel(Difficulty.UNSPECIFIED)).toBe("");
});

test("formatClock shows hours only when needed and clamps negatives", () => {
    expect(formatClock(0)).toBe("0:00");
    expect(formatClock(65)).toBe("1:05");
    expect(formatClock(3661)).toBe("1:01:01");
    expect(formatClock(-30)).toBe("0:00");
});

test("formatDurationLong drops trailing units sensibly", () => {
    expect(formatDurationLong(0)).toBe("0s");
    expect(formatDurationLong(45)).toBe("45s");
    expect(formatDurationLong(65)).toBe("1m 5s");
    expect(formatDurationLong(60)).toBe("1m");
    expect(formatDurationLong(3665)).toBe("1h 1m");
    expect(formatDurationLong(3600)).toBe("1h");
});

test("buildFilter fills defaults and drops the unspecified section sentinel", () => {
    const empty = buildFilter({});
    expect(empty).toEqual({
        sections: [],
        topics: [],
        missedOnly: false,
        includeFullLength: false,
        limit: 0,
    });

    const populated = buildFilter({
        sections: [McatSection.CARS, McatSection.CPBS],
        topics: ["ethics", "kinetics"],
        missedOnly: true,
        includeFullLength: true,
        limit: 5,
    });
    expect(populated.sections).toEqual([McatSection.CARS, McatSection.CPBS]);
    expect(populated.topics).toEqual(["ethics", "kinetics"]);
    expect(populated.missedOnly).toBe(true);
    expect(populated.limit).toBe(5);

    // UNSPECIFIED (0) is a pseudo "all sections" sentinel and is filtered out,
    // so an "all" pick never narrows the query.
    expect(buildFilter({ sections: [McatSection.UNSPECIFIED] }).sections).toEqual(
        [],
    );
    expect(
        buildFilter({ sections: [McatSection.UNSPECIFIED, McatSection.BBLS] })
            .sections,
    ).toEqual([McatSection.BBLS]);
});

test("parseQuestionCount treats blank/max/all as everything and validates positives", () => {
    // blank / keywords => all available (limit 0, still valid to start)
    for (const raw of ["", "   ", "max", "MAX", "all", "All"]) {
        expect(parseQuestionCount(raw)).toEqual({
            limit: 0,
            valid: true,
            all: true,
        });
    }

    // a positive integer passes through, clamped to what's available
    expect(parseQuestionCount("15")).toEqual({
        limit: 15,
        valid: true,
        all: false,
    });
    expect(parseQuestionCount("15", 40).limit).toBe(15);
    expect(parseQuestionCount("100", 40).limit).toBe(40); // clamped to available
    expect(parseQuestionCount("15", 0).limit).toBe(15); // 0 available => no clamp

    // invalid: zero, negative, decimal, junk, embedded space
    for (const bad of ["0", "-3", "3.5", "abc", "1a", "1 2"]) {
        const p = parseQuestionCount(bad);
        expect(p.valid).toBe(false);
        expect(p.limit).toBe(0);
        expect(p.all).toBe(false);
    }
});

test("parseTimerMinutes accepts positive whole minutes only", () => {
    expect(parseTimerMinutes("20")).toEqual({ seconds: 1200, valid: true });
    expect(parseTimerMinutes(" 5 ")).toEqual({ seconds: 300, valid: true });
    for (const bad of ["", "0", "-1", "1.5", "abc", "10m"]) {
        expect(parseTimerMinutes(bad)).toEqual({ seconds: 0, valid: false });
    }
});

test("primaryTopic prefers the first tag, else the section label", () => {
    expect(
        primaryTopic(
            question("q1", McatSection.CPBS, { topicTags: ["kinetics", "acids"] }),
        ),
    ).toBe("kinetics");
    expect(primaryTopic(question("q2", McatSection.CARS))).toBe("CARS");
});

test("groupIntoRunItems keeps questions sharing a passage adjacent", () => {
    const items = groupIntoRunItems([
        question("d1", McatSection.CPBS),
        question("p1a", McatSection.CARS, { passageId: "p1" }),
        question("d2", McatSection.BBLS),
        question("p1b", McatSection.CARS, { passageId: "p1" }),
        question("p2a", McatSection.CARS, { passageId: "p2" }),
    ]);
    expect(items.length).toBe(4);
    expect(items[0].passageId).toBeUndefined();
    expect(items[0].questions.map((q) => q.id)).toEqual(["d1"]);
    expect(items[1].passageId).toBe("p1");
    expect(items[1].questions.map((q) => q.id)).toEqual(["p1a", "p1b"]);
    expect(items[2].questions.map((q) => q.id)).toEqual(["d2"]);
    expect(items[3].passageId).toBe("p2");
    expect(items[3].questions.map((q) => q.id)).toEqual(["p2a"]);
});
