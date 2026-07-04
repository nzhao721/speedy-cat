<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: full-length post-test report. Per-section correct/total, accuracy and
time, plus overall raw correct. Alongside the raw score it shows an ESTIMATED
MCAT-scale score (118–132 per section, 472–528 overall) from a deterministic
representative raw→scaled conversion computed in the Rust backend
(rslib/src/practice/scoring.rs), anchored to AAMC's published scoring examples.
It is an estimate on AI-generated proof-of-concept forms — not an official score
and not an AI output — and is labelled as such.
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    import {
        formatDurationLong,
        SCALED_SCORE_CAPTION,
        sectionLong,
        type FullLengthReport,
    } from "../practice/lib";

    export let report: FullLengthReport;
    export let testTitle = "";

    const dispatch = createEventDispatcher<{ done: void }>();

    $: overallAccuracy =
        report.totalQuestions > 0
            ? Math.round((report.totalCorrect / report.totalQuestions) * 100)
            : 0;
</script>

<div class="report">
    <header>
        <h1>Test complete</h1>
        {#if testTitle}
            <p class="title">{testTitle}</p>
        {/if}
    </header>

    <div class="overall">
        <div class="big">
            <div class="value">{report.totalCorrect} / {report.totalQuestions}</div>
            <div class="label">Questions correct ({overallAccuracy}%)</div>
        </div>
        <div class="scaled">
            <div class="value">
                {report.overallScaledScore ?? "N/A"}
            </div>
            <div class="label">Est. scaled score (472–528)</div>
        </div>
    </div>

    {#if !report.countsForReadiness}
        <p class="exclude-note">
            This score is not counted toward your Readiness pillar because another
            full-length attempt was left unfinished when you submitted.
        </p>
    {/if}

    <table>
        <thead>
            <tr>
                <th>Section</th>
                <th>Correct</th>
                <th>Accuracy</th>
                <th>Time</th>
                <th>Est. scaled (118–132)</th>
            </tr>
        </thead>
        <tbody>
            {#each report.sectionResults as r (r.section)}
                <tr>
                    <td>{sectionLong(r.section)}</td>
                    <td>{r.correct} / {r.total}</td>
                    <td>
                        {r.total > 0 ? Math.round((r.correct / r.total) * 100) : 0}%
                    </td>
                    <td>{formatDurationLong(r.timeSeconds)}</td>
                    <td>{r.scaledScore ?? "N/A"}</td>
                </tr>
            {/each}
        </tbody>
    </table>

    <p class="disclaimer">
        {SCALED_SCORE_CAPTION}
        The raw score above counts unanswered questions as incorrect, matching how the MCAT
        is scored.
    </p>

    <div class="actions">
        <button class="primary" on:click={() => dispatch("done")}>Back to tests</button>
    </div>
</div>

<style lang="scss">
    .report {
        max-width: 820px;
        margin: 0 auto;
        padding: 2rem 1.5rem;
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
    }
    header h1 {
        margin: 0;
    }
    .title {
        color: var(--fg-subtle);
        margin: 0.25rem 0 0;
    }
    .exclude-note {
        background: var(--canvas-inset);
        border-radius: 8px;
        padding: 0.85rem 1rem;
        color: var(--fg-subtle);
        font-size: 0.88rem;
        line-height: 1.5;
        margin: 0;
    }
    .overall {
        display: flex;
        gap: 1rem;
        flex-wrap: wrap;
    }
    .big,
    .scaled {
        flex: 1;
        min-width: 200px;
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 12px;
        padding: 1.25rem;
        text-align: center;
    }
    .value {
        font-size: 2rem;
        font-weight: 700;
    }
    .label {
        color: var(--fg-subtle);
        font-size: 0.85rem;
        margin-top: 0.3rem;
    }
    table {
        width: 100%;
        border-collapse: collapse;
        font-size: 0.92rem;
    }
    th,
    td {
        text-align: left;
        padding: 0.5rem 0.6rem;
        border-bottom: 1px solid var(--border-subtle);
    }
    th {
        color: var(--fg-subtle);
        font-weight: 600;
    }
    .disclaimer {
        color: var(--fg-subtle);
        font-size: 0.82rem;
        line-height: 1.5;
        background: var(--canvas-inset);
        border-radius: 8px;
        padding: 0.85rem 1rem;
    }
    .actions {
        display: flex;
        justify-content: center;
    }
    button.primary {
        border-radius: 6px;
        padding: 0.6rem 1.6rem;
        cursor: pointer;
        border: 1px solid var(--button-primary-bg);
        background: var(--button-primary-bg);
        color: #fff;
        font-size: 0.95rem;
    }
</style>
