<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: post-session summary for the Practice Question Bank — overall
correct / incorrect / unanswered, a per-section breakdown, and per-topic
accuracy + time from get_topic_stats (practice-session attempts only).
-->
<script lang="ts">
    import { createEventDispatcher, onMount } from "svelte";

    import {
        AttemptSource,
        fetchTopicStats,
        formatClock,
        formatDurationLong,
        formatTopicLabel,
        sectionShort,
        type PracticeSessionSummary,
    } from "./lib";
    import type { GetTopicStatsResponse } from "./lib";

    export let summary: PracticeSessionSummary;

    const dispatch = createEventDispatcher<{ restart: void }>();

    let stats: GetTopicStatsResponse | undefined;
    let loading = true;

    onMount(async () => {
        try {
            stats = await fetchTopicStats(AttemptSource.PRACTICE_SESSION);
        } catch {
            stats = undefined;
        } finally {
            loading = false;
        }
    });

    $: accuracy =
        summary.total > 0 ? Math.round((summary.correct / summary.total) * 100) : 0;
    $: topics = (stats?.topics ?? []).slice().sort((a, b) => a.accuracy - b.accuracy);

    function pct(n: number): string {
        return `${Math.round(n * 100)}%`;
    }
</script>

<div class="summary">
    <h1>Session complete</h1>

    <div class="score-cards">
        <div class="card big">
            <div class="value">{accuracy}%</div>
            <div class="label">Accuracy</div>
        </div>
        <div class="card">
            <div class="value ok">{summary.correct}</div>
            <div class="label">Correct</div>
        </div>
        <div class="card">
            <div class="value bad">{summary.incorrect}</div>
            <div class="label">Incorrect</div>
        </div>
        <div class="card">
            <div class="value">{summary.unanswered}</div>
            <div class="label">Unanswered</div>
        </div>
        <div class="card">
            <div class="value">{formatDurationLong(summary.totalTimeSeconds)}</div>
            <div class="label">Total time</div>
        </div>
    </div>

    {#if summary.sectionBreakdown.length > 0}
        <section>
            <h2>By section</h2>
            <table>
                <thead>
                    <tr>
                        <th>Section</th>
                        <th>Correct</th>
                        <th>Accuracy</th>
                    </tr>
                </thead>
                <tbody>
                    {#each summary.sectionBreakdown as sc (sc.section)}
                        <tr>
                            <td>{sectionShort(sc.section)}</td>
                            <td>{sc.correct} / {sc.total}</td>
                            <td>
                                {sc.total > 0
                                    ? Math.round((sc.correct / sc.total) * 100)
                                    : 0}%
                            </td>
                        </tr>
                    {/each}
                </tbody>
            </table>
        </section>
    {/if}

    <section>
        <h2>By topic</h2>
        <p class="hint">
            Weakest topics first — based on your recorded practice-session attempts.
        </p>
        {#if loading}
            <p class="hint">Loading topic stats…</p>
        {:else if topics.length === 0}
            <p class="hint">No per-topic data yet.</p>
        {:else}
            <table>
                <thead>
                    <tr>
                        <th>Topic</th>
                        <th>Section</th>
                        <th>Accuracy</th>
                        <th>Attempts</th>
                        <th>Avg time</th>
                    </tr>
                </thead>
                <tbody>
                    {#each topics as t (t.section + t.topic)}
                        <tr>
                            <td>{formatTopicLabel(t.topic)}</td>
                            <td>{sectionShort(t.section)}</td>
                            <td>
                                <span
                                    class="acc"
                                    class:low={t.accuracy < 0.6}
                                    class:high={t.accuracy >= 0.8}
                                >
                                    {pct(t.accuracy)}
                                </span>
                            </td>
                            <td>{t.correct} / {t.attempts}</td>
                            <td>{formatClock(Math.round(t.avgTimeSeconds))}</td>
                        </tr>
                    {/each}
                </tbody>
            </table>
        {/if}
    </section>

    <div class="actions">
        <button class="primary" on:click={() => dispatch("restart")}>
            New session
        </button>
    </div>
</div>

<style lang="scss">
    .summary {
        max-width: 900px;
        margin: 0 auto;
        padding: 2rem 1.5rem;
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
    }
    h1 {
        margin: 0;
    }
    h2 {
        margin: 0 0 0.5rem;
        font-size: 1.1rem;
    }
    .score-cards {
        display: flex;
        gap: 1rem;
        flex-wrap: wrap;
    }
    .card {
        flex: 1;
        min-width: 110px;
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 10px;
        padding: 1rem;
        text-align: center;
    }
    .card.big {
        flex: 1.4;
    }
    .value {
        font-size: 1.8rem;
        font-weight: 700;
    }
    .value.ok {
        color: #2e9e4f;
    }
    .value.bad {
        color: #d1434b;
    }
    .label {
        color: var(--fg-subtle);
        font-size: 0.85rem;
        margin-top: 0.25rem;
    }
    table {
        width: 100%;
        border-collapse: collapse;
        font-size: 0.92rem;
    }
    th,
    td {
        text-align: left;
        padding: 0.45rem 0.6rem;
        border-bottom: 1px solid var(--border-subtle);
    }
    th {
        color: var(--fg-subtle);
        font-weight: 600;
    }
    .acc.low {
        color: #d1434b;
        font-weight: 700;
    }
    .acc.high {
        color: #2e9e4f;
        font-weight: 700;
    }
    .hint {
        color: var(--fg-subtle);
        font-size: 0.85rem;
        margin: 0 0 0.5rem;
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
