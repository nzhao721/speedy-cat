<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: detailed post-test stats — overall + per-section scaled/raw scores
and per-topic raw breakdown. Entry point before optional question review.
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    import {
        SCALED_SCORE_CAPTION,
        sectionLong,
        type FullLengthStats,
    } from "../practice/lib";

    export let stats: FullLengthStats;
    export let testTitle = "";

    const dispatch = createEventDispatcher<{
        review: void;
        done: void;
    }>();

    $: overallAccuracy =
        stats.totalQuestions > 0
            ? Math.round((stats.totalCorrect / stats.totalQuestions) * 100)
            : 0;
</script>

<div class="stats">
    <header>
        <h1>Test results</h1>
        {#if testTitle}
            <p class="title">{testTitle}</p>
        {/if}
    </header>

    <div class="overall">
        <div class="big">
            <div class="value">{stats.totalCorrect} / {stats.totalQuestions}</div>
            <div class="label">Questions correct ({overallAccuracy}%)</div>
        </div>
        <div class="scaled">
            <div class="value">{stats.overallScaledScore ?? "N/A"}</div>
            <div class="label">Est. scaled score (472–528)</div>
        </div>
    </div>

    {#if !stats.countsForReadiness}
        <p class="exclude-note">
            This score is not counted toward your Readiness pillar because another
            full-length attempt was left unfinished when you submitted.
        </p>
    {/if}

    <h2>By section</h2>
    <table>
        <thead>
            <tr>
                <th>Section</th>
                <th>Correct</th>
                <th>Accuracy</th>
                <th>Est. scaled (118–132)</th>
            </tr>
        </thead>
        <tbody>
            {#each stats.sectionResults as r (r.section)}
                <tr>
                    <td>{sectionLong(r.section)}</td>
                    <td>{r.correct} / {r.total}</td>
                    <td>
                        {r.total > 0 ? Math.round((r.correct / r.total) * 100) : 0}%
                    </td>
                    <td>{r.scaledScore ?? "N/A"}</td>
                </tr>
            {/each}
        </tbody>
    </table>

    <h2>By topic</h2>
    <table>
        <thead>
            <tr>
                <th>Section</th>
                <th>Topic</th>
                <th>Correct</th>
                <th>Accuracy</th>
            </tr>
        </thead>
        <tbody>
            {#each stats.topicScores as t (`${t.section}-${t.topic}`)}
                <tr>
                    <td>{sectionLong(t.section)}</td>
                    <td>{t.topic}</td>
                    <td>{t.correct} / {t.total}</td>
                    <td>
                        {t.total > 0 ? Math.round((t.correct / t.total) * 100) : 0}%
                    </td>
                </tr>
            {/each}
        </tbody>
    </table>

    <p class="disclaimer">{SCALED_SCORE_CAPTION}</p>

    <div class="actions">
        <button class="secondary" on:click={() => dispatch("done")}>
            Back to tests
        </button>
        <button class="primary" on:click={() => dispatch("review")}>
            Review questions
        </button>
    </div>
</div>

<style lang="scss">
    .stats {
        max-width: 900px;
        margin: 0 auto;
        padding: 2rem 1.5rem;
        display: flex;
        flex-direction: column;
        gap: 1.25rem;
    }
    header h1,
    h2 {
        margin: 0;
    }
    h2 {
        font-size: 1.05rem;
        margin-top: 0.5rem;
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
    .exclude-note {
        background: var(--canvas-inset);
        border-radius: 8px;
        padding: 0.85rem 1rem;
        color: var(--fg-subtle);
        font-size: 0.88rem;
        line-height: 1.5;
        margin: 0;
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
        gap: 0.75rem;
        justify-content: center;
        flex-wrap: wrap;
    }
    button {
        border-radius: 6px;
        padding: 0.6rem 1.6rem;
        cursor: pointer;
        font-size: 0.95rem;
    }
    button.primary {
        border: 1px solid var(--button-primary-bg);
        background: var(--button-primary-bg);
        color: #fff;
    }
    button.secondary {
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
    }
</style>
