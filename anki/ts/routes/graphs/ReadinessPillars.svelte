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
        ReadinessPillar,
    } from "@generated/anki/practice_pb";
    import type { GraphsResponse } from "@generated/anki/stats_pb";
    import {
        getReadiness,
        getTopicStats,
        listFullLengthAttempts,
    } from "@generated/backend";

    import {
        collectionTotalCards,
        memoryStudiedLine,
        pct,
        pctRange,
    } from "./dashboard";

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
    }

    $: pillars = data
        ? ([
              { title: "Memory", pillar: data.memory },
              { title: "Performance", pillar: data.performance },
              { title: "Readiness", pillar: data.readiness },
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
            const title = exam.testTitle || "Full-length test";
            const accuracy =
                exam.totalQuestions > 0
                    ? pct(exam.totalCorrect / exam.totalQuestions)
                    : "—";
            return `${title}: ${accuracy}`;
        });
    }

    function pillarDetailLines(title: string, pillar: ReadinessPillar): string[] {
        if (title === "Memory") {
            return [memoryLine(pillar.sampleSize)];
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
            <div class="pillars">
                {#each pillars as pv (pv.title)}
                    <section class="pillar" class:muted={!pv.pillar?.available}>
                        <div class="pillar-head">
                            <h3>{pv.title}</h3>
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
                        {:else}
                            <div class="giveup">
                                <div class="giveup-badge">
                                    Not enough data yet
                                </div>
                                <p class="giveup-msg">
                                    {pv.pillar?.message ??
                                        "This score is unavailable right now."}
                                </p>
                                {#if (pv.pillar?.sampleSize ?? 0) > 0 && pv.title === "Memory"}
                                    <div class="detail">
                                        {memoryLine(
                                            pv.pillar?.sampleSize ?? 0,
                                            true,
                                        )}
                                    </div>
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
    .loading {
        color: var(--fg-subtle);
    }
    .pillars {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
        gap: 1rem;
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
</style>
