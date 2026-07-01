<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the full-length test chooser. Lists the available AAMC-style
practice exams with their question count and testing/break time budgets.
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    import { formatDurationLong, type FullLengthTestSummary } from "../practice/lib";

    export let tests: FullLengthTestSummary[] = [];
    export let loading = false;
    export let starting = false;

    const dispatch = createEventDispatcher<{ select: string; retry: void }>();
</script>

<div class="picker">
    <header>
        <h1>Full-Length Tests</h1>
        <p class="tagline">
            AAMC-style four-section practice exams with enforced section timers and
            scheduled breaks. Once a section's timer ends you can't return to it — just
            like the real MCAT.
        </p>
    </header>

    {#if loading}
        <div class="notice">Loading tests…</div>
    {:else if tests.length === 0}
        <div class="notice">
            No full-length tests are loaded yet. The bundled content is imported in the
            background on first run — give it a moment, then retry.
            <button class="secondary" on:click={() => dispatch("retry")}>Retry</button>
        </div>
    {:else}
        <div class="list">
            {#each tests as test (test.testId)}
                <div class="test-card">
                    <div class="info">
                        <div class="name">{test.title}</div>
                        <div class="meta">
                            <span>{test.totalQuestions} questions</span>
                            <span>·</span>
                            <span>
                                {formatDurationLong(test.totalTestingSeconds)} testing
                            </span>
                            {#if test.totalBreakSeconds > 0}
                                <span>·</span>
                                <span>
                                    {formatDurationLong(test.totalBreakSeconds)} breaks
                                </span>
                            {/if}
                        </div>
                    </div>
                    <button
                        class="primary"
                        disabled={starting}
                        on:click={() => dispatch("select", test.testId)}
                    >
                        {starting ? "Starting…" : "Start test"}
                    </button>
                </div>
            {/each}
        </div>
        <p class="disclaimer">
            These proof-of-concept forms mirror the AAMC full-length structure but are
            not official AAMC content and may not match real MCAT difficulty or
            formatting.
        </p>
    {/if}
</div>

<style lang="scss">
    .picker {
        max-width: 760px;
        margin: 0 auto;
        padding: 2.5rem 1.5rem;
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
    }
    header h1 {
        margin: 0 0 0.25rem;
    }
    .tagline {
        color: var(--fg-subtle);
        margin: 0;
        line-height: 1.5;
    }
    .list {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
    }
    .test-card {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 12px;
        padding: 1.1rem 1.25rem;
    }
    .name {
        font-weight: 600;
        font-size: 1.05rem;
    }
    .meta {
        display: flex;
        gap: 0.4rem;
        flex-wrap: wrap;
        color: var(--fg-subtle);
        font-size: 0.85rem;
        margin-top: 0.3rem;
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
    .disclaimer {
        color: var(--fg-subtle);
        font-size: 0.82rem;
        line-height: 1.5;
    }
    button {
        cursor: pointer;
        border-radius: 6px;
    }
    button.primary {
        border: 1px solid var(--button-primary-bg);
        background: var(--button-primary-bg);
        color: #fff;
        padding: 0.55rem 1.3rem;
        flex: 0 0 auto;
    }
    button.secondary {
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
        padding: 0.4rem 0.9rem;
    }
    button:disabled {
        opacity: 0.6;
        cursor: default;
    }
</style>
