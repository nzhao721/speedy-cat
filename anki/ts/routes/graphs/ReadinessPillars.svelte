<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the three deterministic readiness pillars (Memory / Performance /
Readiness). Every available pillar shows value + 95% range + named source +
method + sample size; insufficient data yields a give-up message — never a
bare number. Shared by the dashboard; AI is off.
-->
<script lang="ts">
    import { getReadiness } from "@generated/backend";
    import type {
        GetReadinessResponse,
        ReadinessPillar,
    } from "@generated/anki/practice_pb";

    import { sectionShort } from "../practice/lib";

    type Phase = "loading" | "ready" | "error";

    let phase: Phase = "loading";
    let data: GetReadinessResponse | undefined;

    interface PillarView {
        title: string;
        subtitle: string;
        /** Noun for the sample-size line, e.g. "reviewed cards". */
        unit: string;
        pillar: ReadinessPillar | undefined;
    }

    $: pillars = data
        ? ([
              {
                  title: "Memory",
                  subtitle:
                      "Mean chance you'd recall a flashcard right now (FSRS retrievability).",
                  unit: "reviewed cards",
                  pillar: data.memory,
              },
              {
                  title: "Performance",
                  subtitle: "Your accuracy on practice-bank questions.",
                  unit: "answered questions",
                  pillar: data.performance,
              },
              {
                  title: "Readiness",
                  subtitle:
                      "Your raw score on completed full-length tests (score only).",
                  unit: "test questions",
                  pillar: data.readiness,
              },
          ] as PillarView[])
        : [];

    function pct(fraction: number): string {
        return `${Math.round(fraction * 100)}%`;
    }

    /**
     * The sample a pillar's number was computed from, e.g. "42 reviewed cards".
     * The unit noun is singularised when n is 1 ("1 reviewed card").
     */
    function countPhrase(n: number, unit: string): string {
        const noun = n === 1 ? unit.replace(/s$/, "") : unit;
        return `${n} ${noun}`;
    }

    /** Left offset / width (in %) for the range bar segments. */
    function span(low: number, high: number): { left: string; width: string } {
        const lo = Math.max(0, Math.min(1, low));
        const hi = Math.max(0, Math.min(1, high));
        return {
            left: `${lo * 100}%`,
            width: `${Math.max(0, hi - lo) * 100}%`,
        };
    }

    async function load(): Promise<void> {
        phase = "loading";
        try {
            // All bundled flashcards are MCAT cards, so the whole collection is
            // the MCAT deck; an empty search covers every reviewed card.
            data = await getReadiness({ deckSearch: "" });
            phase = "ready";
        } catch {
            phase = "error";
        }
    }

    load();
</script>

<section class="readiness-section">
    <header class="readiness-head">
        <h2>Exam readiness</h2>
        <p class="readiness-tagline">
            Three separate, deterministic measures — each with an explicit range
            and a named source. No single “readiness number”, and no AI.
        </p>
    </header>

    {#if phase === "loading"}
        <div class="loading">Calculating your readiness…</div>
    {:else if phase === "error" || !data}
        <div class="notice warn">
            Couldn’t load your readiness right now.
            <button class="secondary" on:click={load}>Retry</button>
        </div>
    {:else}
        <div class="pillars">
            {#each pillars as pv (pv.title)}
                <section class="pillar" class:muted={!pv.pillar?.available}>
                    <div class="pillar-head">
                        <h3>{pv.title}</h3>
                        <p class="pillar-sub">{pv.subtitle}</p>
                    </div>

                    {#if pv.pillar?.available}
                        <div class="value">{pct(pv.pillar.value)}</div>

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
                        <div class="range-label">
                            95% range {pct(pv.pillar.rangeLow)} – {pct(
                                pv.pillar.rangeHigh,
                            )}
                        </div>

                        {#if pv.title === "Performance" && data.performanceAvgSeconds > 0}
                            <div class="extra">
                                ~{Math.round(data.performanceAvgSeconds)}s per question
                            </div>
                        {/if}

                        {#if pv.title === "Readiness" && data.sectionScores.length > 0}
                            <div class="sections">
                                {#each data.sectionScores as s (s.section)}
                                    <div class="section-row">
                                        <span>{sectionShort(s.section)}</span>
                                        <span class="mono"
                                            >{s.correct}/{s.total}</span
                                        >
                                    </div>
                                {/each}
                            </div>
                        {/if}

                        <div class="meta">
                            <div class="sample">
                                Based on {countPhrase(
                                    pv.pillar.sampleSize,
                                    pv.unit,
                                )}
                            </div>
                            <div class="method">{pv.pillar.method}</div>
                            <div class="source">Source: {pv.pillar.source}</div>
                        </div>
                    {:else}
                        <div class="giveup">
                            <div class="giveup-badge">Not enough data yet</div>
                            <p class="giveup-msg">
                                {pv.pillar?.message ??
                                    "This score is unavailable right now."}
                            </p>
                            {#if (pv.pillar?.sampleSize ?? 0) > 0}
                                <div class="sample">
                                    Based on {countPhrase(
                                        pv.pillar?.sampleSize ?? 0,
                                        pv.unit,
                                    )} so far
                                </div>
                            {/if}
                            {#if pv.pillar?.source}
                                <div class="source">
                                    Source: {pv.pillar.source}
                                </div>
                            {/if}
                        </div>
                    {/if}
                </section>
            {/each}
        </div>

        <p class="footnote">
            Ranges are 95% intervals. Scores refuse to display until there is
            enough data to be meaningful, so an unlocked score is always shown
            with its range and source.
        </p>
    {/if}
</section>

<style lang="scss">
    .readiness-section {
        display: flex;
        flex-direction: column;
        gap: 1rem;
    }
    .readiness-head h2 {
        margin: 0 0 0.25rem;
        font-size: 1.2rem;
    }
    .readiness-tagline {
        color: var(--fg-subtle);
        margin: 0;
        max-width: 720px;
        font-size: 0.9rem;
    }
    .loading {
        color: var(--fg-subtle);
    }
    .notice {
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 8px;
        padding: 1rem;
        display: flex;
        align-items: center;
        gap: 1rem;
        color: var(--fg-subtle);
    }
    .notice.warn {
        border-color: #e0a34e;
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
    .pillar-sub {
        margin: 0.25rem 0 0;
        font-size: 0.82rem;
        color: var(--fg-subtle);
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
    .range-label {
        font-size: 0.82rem;
        color: var(--fg-subtle);
    }
    .extra {
        font-size: 0.85rem;
        color: var(--fg-subtle);
    }
    .sections {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
        margin-top: 0.2rem;
    }
    .section-row {
        display: flex;
        justify-content: space-between;
        font-size: 0.85rem;
        color: var(--fg-subtle);
    }
    .mono {
        font-variant-numeric: tabular-nums;
        color: var(--fg);
    }
    .meta {
        margin-top: 0.4rem;
        font-size: 0.72rem;
        color: var(--fg-subtle);
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
    }
    .meta .source {
        opacity: 0.85;
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
    .giveup .sample {
        font-size: 0.85rem;
        color: var(--fg);
        font-variant-numeric: tabular-nums;
    }
    .giveup .source {
        font-size: 0.72rem;
        color: var(--fg-subtle);
    }
    .footnote {
        color: var(--fg-subtle);
        font-size: 0.8rem;
        margin: 0;
    }
    button {
        cursor: pointer;
        border-radius: 6px;
    }
    button.secondary {
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
        padding: 0.4rem 0.9rem;
    }
</style>
