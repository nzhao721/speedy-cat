// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

import type { GraphsResponse } from "@generated/anki/stats_pb";
import { describe, expect, it } from "vitest";

import {
    collectionTotalCards,
    memoryCard,
    memoryStudiedLine,
    pctRange,
    retrievabilityCardCount,
} from "./dashboard";

function graphsWithCounts(
    retrievabilityBins: Record<number, number>,
    counts: Partial<GraphsResponse["cardCounts"]["includingInactive"]> = {},
): GraphsResponse {
    return {
        fsrs: true,
        retrievability: {
            retrievability: retrievabilityBins,
            average: 80,
        },
        cardCounts: {
            includingInactive: {
                newCards: 100,
                learn: 0,
                relearn: 0,
                young: 10,
                mature: 20,
                suspended: 5,
                buried: 0,
                ...counts,
            },
            excludingInactive: {
                newCards: 0,
                learn: 0,
                relearn: 0,
                young: 0,
                mature: 0,
                suspended: 0,
                buried: 0,
            },
        },
    } as GraphsResponse;
}

describe("retrievabilityCardCount", () => {
    it("sums histogram bins instead of counting bins", () => {
        expect(
            retrievabilityCardCount({
                retrievability: { 70: 12, 80: 18 },
                average: 76,
            }),
        ).toBe(30);
    });
});

describe("collectionTotalCards", () => {
    it("matches the card-counts graph total", () => {
        expect(collectionTotalCards(graphsWithCounts({ 80: 3 }))).toBe(135);
    });
});

describe("memoryStudiedLine", () => {
    it("formats studied out of total", () => {
        expect(memoryStudiedLine(42, 10287)).toMatch(/42 out of .*10,287 flashcards studied/);
    });

    it("appends so far in give-up state", () => {
        expect(memoryStudiedLine(42, 10287, true)).toMatch(/so far$/);
    });
});

describe("pctRange", () => {
    it("formats a 95% interval for pillar headlines", () => {
        expect(pctRange(0.72, 0.84)).toBe("72%–84%");
    });
});

describe("memoryCard", () => {
    it("uses histogram sum for reviewed card count", () => {
        const data = graphsWithCounts({ 70: 15, 80: 15 });
        const card = memoryCard(data);
        expect(card.sub).toContain("30 reviewed cards");
    });
});
