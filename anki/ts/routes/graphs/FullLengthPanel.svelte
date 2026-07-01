<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: "Full-length tests" panel for the stats dashboard. Shows the
learner's raw score on completed full-length practice tests, always paired with
its explicit 95% range — the score is never fabricated. When no full-length test
has been completed the panel "gives up" with an explanation instead of inventing
a number. Every figure is deterministic and computed in the Rust backend (AI
off).
-->
<script lang="ts">
    import type { FullLengthOverview } from "./dashboard";
    import { fullLengthRawTotals, pct } from "./dashboard";
    import { sectionShort } from "../practice/lib";

    export let overview: FullLengthOverview | null;
    export let error: boolean;
</script>

<section class="panel">
    <h2>Full-length tests</h2>

    {#if error}
        <p class="muted">Couldn’t load full-length stats.</p>
    {:else if overview === null}
        <p class="muted">Loading…</p>
    {:else if !overview.readiness.readiness?.available}
        <div class="giveup">
            <div class="giveup-head">No full-length test completed yet</div>
            <p class="muted">
                {overview.readiness.readiness?.message
                    || "Finish a full-length test to see your raw score."}
            </p>
            {#if overview.testsAvailable > 0}
                <p class="muted">
                    {overview.testsAvailable} full-length
                    {overview.testsAvailable === 1 ? "test" : "tests"} available
                </p>
            {/if}
        </div>
    {:else}
        {@const pillar = overview.readiness.readiness}
        {@const totals = fullLengthRawTotals(overview.readiness)}
        <div class="summary">
            <div class="value">{totals.correct}/{totals.total}</div>
            <div class="caption">raw score</div>
            <div class="range">
                {pct(pillar.value)} · 95% range {pct(pillar.rangeLow)}–{pct(
                    pillar.rangeHigh,
                )}
            </div>
        </div>

        <div class="sections">
            {#each overview.readiness.sectionScores as s (s.section)}
                <div class="section-row">
                    <span>{sectionShort(s.section)}</span>
                    <span class="mono">{s.correct}/{s.total}</span>
                </div>
            {/each}
        </div>
    {/if}
</section>

<style lang="scss">
    .panel {
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 12px;
        padding: 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
    }
    h2 {
        margin: 0;
        font-size: 1.1rem;
        color: var(--fg);
    }
    .muted {
        margin: 0;
        color: var(--fg-subtle);
        font-size: 0.85rem;
    }
    .giveup {
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
    }
    .giveup-head {
        font-weight: 600;
        color: var(--fg);
    }
    .summary {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
    }
    .value {
        font-size: 2.4rem;
        font-weight: 700;
        line-height: 1;
        color: var(--fg);
        font-variant-numeric: tabular-nums;
    }
    .caption {
        font-size: 0.72rem;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        color: var(--fg-subtle);
    }
    .range {
        font-size: 0.85rem;
        color: var(--fg-subtle);
    }
    .sections {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        margin-top: 0.3rem;
    }
    .section-row {
        display: flex;
        justify-content: space-between;
        font-size: 0.85rem;
        color: var(--fg-subtle);
        background: var(--canvas-inset);
        border-radius: 6px;
        padding: 0.35rem 0.55rem;
    }
    .mono {
        font-variant-numeric: tabular-nums;
        color: var(--fg);
    }
</style>
