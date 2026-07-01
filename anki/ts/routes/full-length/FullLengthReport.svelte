<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: full-length post-test report. Per-section correct/total, accuracy and
time, plus overall raw correct. Scaled scores (118–132 per section, 472–528
overall) are only shown when licensed AAMC scoring data provides them — the
AI-generated proof-of-concept forms deliberately refuse to invent a scaled
score rather than show a misleading number.
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    import {
        formatDurationLong,
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
            <div class="label">Scaled score (472–528)</div>
        </div>
    </div>

    <table>
        <thead>
            <tr>
                <th>Section</th>
                <th>Correct</th>
                <th>Accuracy</th>
                <th>Time</th>
                <th>Scaled (118–132)</th>
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
        Scaled scores require licensed AAMC scoring data and are shown as “N/A” for the
        AI-generated proof-of-concept forms. Raw accuracy above is based only on the
        questions you answered.
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
