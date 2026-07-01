<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: practice-question sub-panel for the stats dashboard. Shows the
learner's overall practice accuracy and a per-MCAT-section accuracy breakdown.
Every figure is deterministic (no AI) and comes from recorded practice-session
attempts (see dashboard.ts / PracticeService.GetTopicStats).
-->
<script lang="ts">
    import { formatDurationLong, sectionShort } from "../practice/lib";
    import type { PracticeOverview } from "./dashboard";
    import { pct } from "./dashboard";

    export let overview: PracticeOverview | null;
    export let error: boolean;
</script>

<section class="card">
    <h2 class="card-title">Practice questions</h2>

    {#if error}
        <p class="muted">Couldn't load practice stats.</p>
    {:else if overview === null}
        <p class="muted">Loading…</p>
    {:else if overview.attempted === 0}
        <div class="empty">
            <div class="empty-headline">No practice questions answered yet</div>
            <div class="empty-sub">
                Start a practice session to build up your section-by-section
                accuracy.
            </div>
        </div>
    {:else}
        <div class="summary">
            <div class="value-row">
                <span class="value">{pct(overview.accuracy)}</span>
                <span class="value-caption">overall accuracy</span>
            </div>
            <div class="summary-sub">
                {overview.correct}/{overview.attempted} correct · ~{formatDurationLong(
                    Math.round(overview.avgTimeSeconds),
                )} per question
            </div>
        </div>

        <div class="sections">
            {#each overview.stats.sections as s (s.section)}
                {#if s.attempts > 0}
                    <div class="section-row">
                        <span class="section-name">{sectionShort(s.section)}</span>
                        <span class="section-stat">
                            <span class="section-pct">{pct(s.accuracy)}</span>
                            <span class="section-count"
                                >{s.correct}/{s.attempts}</span
                            >
                        </span>
                    </div>
                {/if}
            {/each}
        </div>
    {/if}
</section>

<style lang="scss">
    .card {
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 12px;
        padding: 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
    }
    .card-title {
        margin: 0;
        font-size: 1.1rem;
        color: var(--fg);
    }
    .muted {
        margin: 0;
        color: var(--fg-subtle);
        font-size: 0.9rem;
    }
    .empty {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
    }
    .empty-headline {
        color: var(--fg-subtle);
        font-size: 0.95rem;
        font-weight: 600;
    }
    .empty-sub {
        color: var(--fg-subtle);
        font-size: 0.82rem;
    }
    .summary {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
    }
    .value-row {
        display: flex;
        align-items: baseline;
        gap: 0.5rem;
    }
    .value {
        font-size: 2.6rem;
        font-weight: 700;
        line-height: 1;
        color: var(--fg);
        font-variant-numeric: tabular-nums;
    }
    .value-caption {
        font-size: 0.82rem;
        color: var(--fg-subtle);
    }
    .summary-sub {
        font-size: 0.85rem;
        color: var(--fg-subtle);
        font-variant-numeric: tabular-nums;
    }
    .sections {
        display: flex;
        flex-direction: column;
        background: var(--canvas-inset);
        border: 1px solid var(--border-subtle);
        border-radius: 8px;
        padding: 0.25rem 0.75rem;
        margin-top: 0.2rem;
    }
    .section-row {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        gap: 0.75rem;
        padding: 0.45rem 0;
        font-size: 0.9rem;
    }
    .section-row + .section-row {
        border-top: 1px solid var(--border-subtle);
    }
    .section-name {
        color: var(--fg);
    }
    .section-stat {
        display: flex;
        align-items: baseline;
        gap: 0.5rem;
        font-variant-numeric: tabular-nums;
    }
    .section-pct {
        color: var(--fg);
        font-weight: 600;
    }
    .section-count {
        color: var(--fg-subtle);
        font-size: 0.82rem;
    }
</style>
