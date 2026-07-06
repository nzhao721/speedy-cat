<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the three deterministic readiness pillars (Memory / Performance /
Readiness). Each available pillar shows value + 95% range + minimal sample
lines; insufficient data yields a give-up message — never a bare point estimate.
Shared by the dashboard; AI is off.
-->
<script lang="ts">
    import { AttemptSource } from "@generated/anki/practice_pb";
    import type {
        FullLengthAttemptSummary,
        GetReadinessResponse,
        PillarBreakdowns,
        PillarSectionBreakdown,
        PillarTopicBreakdown,
        ReadinessPillar,
    } from "@generated/anki/practice_pb";
    import type { GraphsResponse } from "@generated/anki/stats_pb";
    import {
        getReadiness,
        getTopicStats,
        listFullLengthAttempts,
    } from "@generated/backend";

    import HoverTooltip from "$lib/components/HoverTooltip.svelte";
    import Icon from "$lib/components/Icon.svelte";
    import IconConstrain from "$lib/components/IconConstrain.svelte";
    import { infoCircle } from "$lib/components/icons";

    import {
        collectionTotalCards,
        memoryLifetimeStudiedCount,
        memoryStudiedLine,
        pct,
        pctRange,
    } from "./dashboard";
    import {
        formatFullLengthExamLabel,
        PILLAR_BREAKDOWN_INSUFFICIENT_TOOLTIP,
        PILLAR_TOOLTIPS,
        sectionShort,
    } from "../practice/lib";

    /** Graph data from WithGraphData; used for collection card totals. */
    export let sourceData: GraphsResponse | null | undefined = undefined;

    type Phase = "loading" | "ready" | "error";

    let phase: Phase = "loading";
    let data: GetReadinessResponse | undefined;
    let practiceCorrect = 0;
    let practiceAttempted = 0;
    let completedExams: FullLengthAttemptSummary[] = [];

    interface PillarView {
        title: string;
        pillar: ReadinessPillar | undefined;
        breakdown: PillarBreakdowns | undefined;
    }

    function breakdownFor(
        title: string,
        response: GetReadinessResponse,
    ): PillarBreakdowns | undefined {
        if (title === "Memory") return response.memoryBreakdown;
        if (title === "Performance") return response.performanceBreakdown;
        if (title === "Readiness") return response.readinessBreakdown;
        return undefined;
    }

    function breakdownSectionLabel(row: PillarSectionBreakdown): string {
        return sectionShort(row.section);
    }

    function breakdownSectionValue(row: PillarSectionBreakdown): string {
        if (!row.available) return "N/A";
        return pctRange(row.rangeLow, row.rangeHigh);
    }

    function breakdownTopicValue(row: PillarTopicBreakdown): string {
        if (!row.available) return "N/A";
        return pctRange(row.rangeLow, row.rangeHigh);
    }

    $: pillars = data
        ? ([
              {
                  title: "Memory",
                  pillar: data.memory,
                  breakdown: breakdownFor("Memory", data),
              },
              {
                  title: "Performance",
                  pillar: data.performance,
                  breakdown: breakdownFor("Performance", data),
              },
              {
                  title: "Readiness",
                  pillar: data.readiness,
                  breakdown: breakdownFor("Readiness", data),
              },
          ] as PillarView[])
        : [];

    /** Left offset / width (in %) for the range bar segments. */
    function span(low: number, high: number): { left: string; width: string } {
        const lo = Math.max(0, Math.min(1, low));
        const hi = Math.max(0, Math.min(1, high));
        return {
            left: `${lo * 100}%`,
            width: `${Math.max(0, hi - lo) * 100}%`,
        };
    }

    const MCAT_TOTAL_MIN = 472;
    const MCAT_TOTAL_MAX = 528;
    const MCAT_SECTION_MIN = 118;
    const MCAT_SECTION_MAX = 132;

    /** Map a projected score onto a fixed MCAT scale for the range bar. */
    function mcatScaledSpan(
        scaleMin: number,
        scaleMax: number,
        low: number,
        high: number,
    ): { left: string; width: string } {
        const spanSize = scaleMax - scaleMin;
        const lo = Math.max(scaleMin, Math.min(scaleMax, low));
        const hi = Math.max(scaleMin, Math.min(scaleMax, high));
        return {
            left: `${((lo - scaleMin) / spanSize) * 100}%`,
            width: `${((hi - lo) / spanSize) * 100}%`,
        };
    }

    function mcatScaledMarker(
        scaleMin: number,
        scaleMax: number,
        value: number,
    ): string {
        const spanSize = scaleMax - scaleMin;
        const clamped = Math.max(scaleMin, Math.min(scaleMax, value));
        return `${((clamped - scaleMin) / spanSize) * 100}%`;
    }

    function memoryLine(studied: number, soFar = false): string {
        const total = collectionTotalCards(sourceData);
        if (total > 0) {
            return memoryStudiedLine(studied, total, soFar);
        }
        return `${studied} flashcards studied`;
    }

    function performanceLines(): string[] {
        const answered = data?.performance?.sampleSize ?? practiceAttempted;
        const correct = practiceCorrect;
        const lines = [
            `${correct} correct out of ${answered} practice questions answered`,
        ];
        const avgSeconds = data?.performanceAvgSeconds ?? 0;
        if (avgSeconds > 0) {
            lines.push(`Average time of ${Math.round(avgSeconds)} seconds`);
        }
        return lines;
    }

    function readinessExamLines(): string[] {
        return completedExams.map((exam) => {
            const title = formatFullLengthExamLabel(exam.testId, exam.testTitle);
            const accuracy =
                exam.totalQuestions > 0
                    ? pct(exam.totalCorrect / exam.totalQuestions)
                    : "—";
            return `${title}: ${accuracy}`;
        });
    }

    function pillarDetailLines(title: string, pillar: ReadinessPillar): string[] {
        if (title === "Memory") {
            const studied = memoryLifetimeStudiedCount(sourceData);
            return studied > 0 ? [memoryLine(studied)] : [];
        }
        if (title === "Performance") {
            return performanceLines();
        }
        if (title === "Readiness") {
            return readinessExamLines();
        }
        return [];
    }

    async function load(): Promise<void> {
        phase = "loading";
        try {
            // All bundled flashcards are MCAT cards, so the whole collection is
            // the MCAT deck; an empty search covers every reviewed card.
            // `alertOnError: false`: the mobile app ships the stock backend with
            // no getReadiness RPC, so this 500s there. We swallow the error and
            // hide the section (below) rather than pop a blocking alert() dialog.
            // Desktop's RPC succeeds, so this is a no-op there.
            data = await getReadiness({ deckSearch: "" }, { alertOnError: false });
            try {
                const topicStats = await getTopicStats(
                    {
                        source: AttemptSource.PRACTICE_SESSION,
                        firstAttemptNoHintOnly: false,
                    },
                    { alertOnError: false },
                );
                practiceCorrect = 0;
                practiceAttempted = 0;
                for (const section of topicStats.sections) {
                    practiceCorrect += section.correct;
                    practiceAttempted += section.attempts;
                }
            } catch {
                practiceCorrect = 0;
                practiceAttempted = data?.performance?.sampleSize ?? 0;
            }
            try {
                const attempts = await listFullLengthAttempts({}, { alertOnError: false });
                completedExams = attempts.attempts
                    .filter((exam) => exam.totalQuestions > 0)
                    .sort((a, b) => Number(b.completedAt) - Number(a.completedAt))
                    .slice(0, 3);
            } catch {
                completedExams = [];
            }
            phase = "ready";
        } catch {
            phase = "error";
        }
    }

    load();
</script>

<!--
SpeedyCAT: when the getReadiness RPC is unavailable (the stock mobile backend
has no PracticeService) `load()` sets phase = "error" and the whole section
renders nothing — a graceful degrade rather than a broken error box. On desktop
the RPC succeeds, so the section renders exactly as before.
-->
{#if phase !== "error"}
    <section class="readiness-section">
        {#if phase === "loading"}
            <div class="loading">Calculating your readiness…</div>
        {:else if data}
            {#if data.projected}
                <section
                    class="projected"
                    class:muted={!data.projected.available}
                >
                    <h2>Projected MCAT score</h2>
                    {#if data.projected.available}
                        <div class="projected-value">
                            {data.projected.total}
                            <span class="projected-range"
                                >({data.projected.totalLow}–{data.projected
                                    .totalHigh})</span
                            >
                        </div>

                        <div class="scaled-range-row">
                            <span class="scale-label">{MCAT_TOTAL_MIN}</span>
                            <div
                                class="range-bar"
                                role="img"
                                aria-label={`Projected MCAT total ${data.projected.total} (${data.projected.totalLow}–${data.projected.totalHigh}) on 472–528 scale`}
                            >
                                <div
                                    class="range-fill"
                                    style="left:{mcatScaledSpan(
                                        MCAT_TOTAL_MIN,
                                        MCAT_TOTAL_MAX,
                                        data.projected.totalLow,
                                        data.projected.totalHigh,
                                    ).left};width:{mcatScaledSpan(
                                        MCAT_TOTAL_MIN,
                                        MCAT_TOTAL_MAX,
                                        data.projected.totalLow,
                                        data.projected.totalHigh,
                                    ).width}"
                                ></div>
                                <div
                                    class="range-marker"
                                    style="left:{mcatScaledMarker(
                                        MCAT_TOTAL_MIN,
                                        MCAT_TOTAL_MAX,
                                        data.projected.total,
                                    )}"
                                ></div>
                            </div>
                            <span class="scale-label">{MCAT_TOTAL_MAX}</span>
                        </div>

                        <div class="projected-sections">
                            {#each data.projected.sections as s (s.section)}
                                <div class="projected-section">
                                    <span class="section-label"
                                        >{sectionShort(s.section)}</span
                                    >
                                    {#if s.scaledScore !== undefined}
                                        <span class="mono section-score">
                                            {s.scaledScore} ({s.scaledLow}–{s
                                                .scaledHigh})
                                        </span>
                                    {/if}
                                    {#if s.scaledScore !== undefined && s.scaledLow !== undefined && s.scaledHigh !== undefined}
                                        <div class="scaled-range-row section">
                                            <span class="scale-label"
                                                >{MCAT_SECTION_MIN}</span
                                            >
                                            <div
                                                class="range-bar range-bar--section"
                                                role="img"
                                                aria-label={`${sectionShort(s.section)} projected ${s.scaledScore} (${s.scaledLow}–${s.scaledHigh}) on 118–132 scale`}
                                            >
                                                <div
                                                    class="range-fill"
                                                    style="left:{mcatScaledSpan(
                                                        MCAT_SECTION_MIN,
                                                        MCAT_SECTION_MAX,
                                                        s.scaledLow,
                                                        s.scaledHigh,
                                                    ).left};width:{mcatScaledSpan(
                                                        MCAT_SECTION_MIN,
                                                        MCAT_SECTION_MAX,
                                                        s.scaledLow,
                                                        s.scaledHigh,
                                                    ).width}"
                                                ></div>
                                                <div
                                                    class="range-marker"
                                                    style="left:{mcatScaledMarker(
                                                        MCAT_SECTION_MIN,
                                                        MCAT_SECTION_MAX,
                                                        s.scaledScore,
                                                    )}"
                                                ></div>
                                            </div>
                                            <span class="scale-label"
                                                >{MCAT_SECTION_MAX}</span
                                            >
                                        </div>
                                    {/if}
                                </div>
                            {/each}
                        </div>
                    {:else}
                        <div class="giveup-badge">Not enough data yet</div>
                        <p class="giveup-msg">
                            {data.projected.message ||
                                "Projected score unavailable."}
                        </p>
                    {/if}
                </section>
            {/if}
            <div class="pillars">
                {#each pillars as pv (pv.title)}
                    <section class="pillar" class:muted={!pv.pillar?.available}>
                        <div class="pillar-head">
                            <h3>
                                {pv.title}
                                <HoverTooltip
                                    text={PILLAR_TOOLTIPS[pv.title]}
                                    class="pillar-help"
                                >
                                    <IconConstrain iconSize={100}>
                                        <Icon icon={infoCircle} />
                                    </IconConstrain>
                                </HoverTooltip>
                            </h3>
                        </div>

                        {#if pv.pillar?.available}
                            <div class="value">
                                {pctRange(
                                    pv.pillar.rangeLow,
                                    pv.pillar.rangeHigh,
                                )}
                            </div>

                            <div
                                class="range-bar"
                                role="img"
                                aria-label={`95% range ${pct(pv.pillar.rangeLow)} to ${pct(
                                    pv.pillar.rangeHigh,
                                )}`}
                            >
                                <div
                                    class="range-fill"
                                    style="left:{span(
                                        pv.pillar.rangeLow,
                                        pv.pillar.rangeHigh,
                                    ).left};width:{span(
                                        pv.pillar.rangeLow,
                                        pv.pillar.rangeHigh,
                                    ).width}"
                                ></div>
                                <div
                                    class="range-marker"
                                    style="left:{Math.max(
                                        0,
                                        Math.min(1, pv.pillar.value),
                                    ) * 100}%"
                                ></div>
                            </div>

                            <div class="detail">
                                {#each pillarDetailLines(pv.title, pv.pillar) as line}
                                    <div>{line}</div>
                                {/each}
                            </div>

                            {#if pv.breakdown && pv.breakdown.sections.length > 0}
                                <details class="breakdown">
                                    <summary>By section</summary>
                                    <div class="breakdown-block">
                                        {#each pv.breakdown.sections as row (row.section)}
                                            <div class="breakdown-row">
                                                <span class="breakdown-label"
                                                    >{breakdownSectionLabel(row)}</span
                                                >
                                                {#if !row.available}
                                                    <HoverTooltip
                                                        text={PILLAR_BREAKDOWN_INSUFFICIENT_TOOLTIP}
                                                        class="mono breakdown-value breakdown-value--insufficient"
                                                    >
                                                        {breakdownSectionValue(row)}
                                                    </HoverTooltip>
                                                {:else}
                                                    <span class="mono breakdown-value"
                                                        >{breakdownSectionValue(row)}</span
                                                    >
                                                {/if}
                                            </div>
                                        {/each}
                                    </div>
                                </details>
                            {/if}
                            {#if pv.title === "Performance" && pv.breakdown && pv.breakdown.topics.length > 0}
                                <details class="breakdown">
                                    <summary>By topic</summary>
                                    <div class="breakdown-block">
                                        {#each pv.breakdown.topics as row (row.section + row.topic)}
                                            <div class="breakdown-row">
                                                <span class="breakdown-label"
                                                    >{row.topic}</span
                                                >
                                                {#if !row.available}
                                                    <HoverTooltip
                                                        text={PILLAR_BREAKDOWN_INSUFFICIENT_TOOLTIP}
                                                        class="mono breakdown-value breakdown-value--insufficient"
                                                    >
                                                        {breakdownTopicValue(row)}
                                                    </HoverTooltip>
                                                {:else}
                                                    <span class="mono breakdown-value"
                                                        >{breakdownTopicValue(row)}</span
                                                    >
                                                {/if}
                                            </div>
                                        {/each}
                                    </div>
                                </details>
                            {/if}
                        {:else}
                            <div class="giveup">
                                <div class="giveup-badge">
                                    Not enough data yet
                                </div>
                                <p class="giveup-msg">
                                    {pv.pillar?.message ??
                                        "This score is unavailable right now."}
                                </p>
                                {#if pv.title === "Memory"}
                                    {@const studied = memoryLifetimeStudiedCount(sourceData)}
                                    {#if studied > 0}
                                        <div class="detail">
                                            {memoryLine(studied, true)}
                                        </div>
                                    {/if}
                                {/if}
                            </div>
                        {/if}
                    </section>
                {/each}
            </div>
        {/if}
    </section>
{/if}

<style lang="scss">
    .readiness-section {
        display: flex;
        flex-direction: column;
        gap: 1rem;
    }
    .projected {
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 12px;
        padding: 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
    }
    .projected.muted {
        opacity: 0.92;
    }
    .projected h2 {
        margin: 0;
        font-size: 1.1rem;
    }
    .projected-value {
        font-size: 2.6rem;
        font-weight: 700;
        line-height: 1;
        font-variant-numeric: tabular-nums;
    }
    .projected-range {
        font-size: 1.4rem;
        font-weight: 600;
        color: var(--fg-subtle);
    }
    .scaled-range-row {
        display: flex;
        align-items: center;
        gap: 0.45rem;
    }
    .scaled-range-row .range-bar {
        flex: 1;
        min-width: 0;
    }
    .scaled-range-row.section {
        flex: 1;
        min-width: 0;
    }
    .scale-label {
        flex-shrink: 0;
        font-size: 0.68rem;
        font-variant-numeric: tabular-nums;
        color: var(--fg-subtle);
        line-height: 1;
    }
    .scaled-range-row.section .scale-label {
        font-size: 0.62rem;
    }
    .projected-sections {
        display: flex;
        flex-direction: column;
        gap: 0;
        margin-top: 0.75rem;
    }
    .projected-section {
        display: flex;
        flex-direction: row;
        align-items: center;
        gap: 0.55rem;
        background: var(--canvas-inset);
        border-radius: 6px;
        padding: 0.2rem 0.55rem;
    }
    .section-label {
        flex-shrink: 0;
        font-size: 0.85rem;
        color: var(--fg-subtle);
    }
    .section-score {
        flex-shrink: 0;
        font-size: 0.85rem;
    }
    .mono {
        font-variant-numeric: tabular-nums;
        color: var(--fg);
    }
    .loading {
        color: var(--fg-subtle);
    }
    .pillars {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
        gap: 1rem;
        align-items: start;
    }
    .pillar {
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 12px;
        padding: 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
    }
    .pillar.muted {
        opacity: 0.92;
    }
    .pillar-head h3 {
        margin: 0;
        font-size: 1.1rem;
        display: flex;
        align-items: center;
        gap: 0.25rem;
    }
    :global(.pillar-help) {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        padding: 0;
        margin: 0;
        border: none;
        background: none;
        box-shadow: none;
        color: var(--fg-subtle);
        flex-shrink: 0;
        vertical-align: middle;
        --buttons-size: 1.25rem;
    }
    :global(.pillar-help:hover) {
        color: var(--fg);
        background: none;
        border: none;
        box-shadow: none;
    }
    .value {
        font-size: 2.6rem;
        font-weight: 700;
        line-height: 1;
        color: var(--fg);
    }
    .range-bar {
        position: relative;
        height: 8px;
        border-radius: 4px;
        background: var(--canvas-inset);
        border: 1px solid var(--border-subtle);
        overflow: hidden;
    }
    .range-bar--section {
        height: 6px;
        border-radius: 3px;
    }
    .range-fill {
        position: absolute;
        top: 0;
        bottom: 0;
        background: var(--button-primary-bg, #4c6ef5);
        opacity: 0.35;
    }
    .range-marker {
        position: absolute;
        top: -2px;
        bottom: -2px;
        width: 2px;
        background: var(--button-primary-bg, #4c6ef5);
        transform: translateX(-1px);
    }
    .detail {
        font-size: 0.9rem;
        color: var(--fg-subtle);
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
    }
    .giveup {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
    }
    .giveup-badge {
        align-self: flex-start;
        font-size: 0.72rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        color: var(--fg-subtle);
        background: var(--canvas-inset);
        border: 1px solid var(--border-subtle);
        border-radius: 999px;
        padding: 0.2rem 0.6rem;
    }
    .giveup-msg {
        margin: 0;
        color: var(--fg);
        font-size: 0.9rem;
    }
    .breakdown {
        margin-top: 0.25rem;
        font-size: 0.85rem;
    }
    .breakdown summary {
        cursor: pointer;
        color: var(--fg-subtle);
        font-weight: 600;
        user-select: none;
    }
    .breakdown summary:hover {
        color: var(--fg);
    }
    .breakdown-block {
        margin-top: 0.5rem;
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
    }
    .breakdown-row {
        display: flex;
        justify-content: space-between;
        gap: 0.75rem;
        padding: 0.3rem 0.5rem;
        background: var(--canvas-inset);
        border-radius: 6px;
        overflow: visible;
    }
    .breakdown-label {
        color: var(--fg);
    }
    .breakdown-value {
        color: var(--fg);
        font-variant-numeric: tabular-nums;
    }
    :global(.breakdown-value--insufficient) {
        color: var(--fg-subtle);
        font-size: 0.8rem;
    }
</style>
