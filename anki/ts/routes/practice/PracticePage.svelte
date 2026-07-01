<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: Practice Question Bank page. Orchestrates the three phases — setup
(section / topic / missed-only / count / optional timer filters), the runner,
and the post-session summary.
-->
<script lang="ts">
    import PracticeRunner from "./PracticeRunner.svelte";
    import SessionSummary from "./SessionSummary.svelte";
    import {
        beginPracticeSession,
        fetchQuestions,
        McatSection,
        SECTIONS,
        sectionLong,
        type FilterOptions,
        type PracticeQuestion,
        type PracticeSessionSummary,
    } from "./lib";

    type Phase = "loading" | "setup" | "running" | "summary";

    let phase: Phase = "loading";
    let bank: PracticeQuestion[] = [];
    let loadError = false;
    let starting = false;
    let noMatch = false;

    // filter state
    let sectionFilter: McatSection = McatSection.UNSPECIFIED;
    let topic = "";
    let missedOnly = false;
    let limit = 20;
    let timeLimit = 0;

    // running state
    let sessionQuestions: PracticeQuestion[] = [];
    let sessionId = "";
    let summary: PracticeSessionSummary | undefined;

    const TIME_LIMITS: { label: string; value: number }[] = [
        { label: "Untimed", value: 0 },
        { label: "10 min", value: 600 },
        { label: "20 min", value: 1200 },
        { label: "30 min", value: 1800 },
        { label: "60 min", value: 3600 },
    ];
    const COUNTS: { label: string; value: number }[] = [
        { label: "5", value: 5 },
        { label: "10", value: 10 },
        { label: "20", value: 20 },
        { label: "40", value: 40 },
        { label: "All", value: 0 },
    ];

    $: topicOptions = Array.from(
        new Set(
            bank
                .filter(
                    (q) =>
                        sectionFilter === McatSection.UNSPECIFIED ||
                        q.section === sectionFilter,
                )
                .flatMap((q) => q.topicTags),
        ),
    ).sort((a, b) => a.localeCompare(b));

    $: sectionCounts = SECTIONS.map((s) => ({
        section: s,
        count: bank.filter((q) => q.section === s).length,
    }));

    // reset topic if it no longer applies to the chosen section
    $: if (topic && !topicOptions.includes(topic)) {
        topic = "";
    }

    async function loadBank(): Promise<void> {
        phase = "loading";
        loadError = false;
        try {
            bank = await fetchQuestions({ includeFullLength: false });
            phase = "setup";
        } catch {
            loadError = true;
            phase = "setup";
        }
    }

    loadBank();

    function currentOptions(): FilterOptions {
        return {
            section:
                sectionFilter === McatSection.UNSPECIFIED ? undefined : sectionFilter,
            topics: topic ? [topic] : [],
            missedOnly,
            includeFullLength: false,
            limit,
        };
    }

    async function start(): Promise<void> {
        starting = true;
        noMatch = false;
        try {
            const opts = currentOptions();
            const qs = await fetchQuestions(opts);
            if (qs.length === 0) {
                noMatch = true;
                return;
            }
            const session = await beginPracticeSession(opts, timeLimit);
            sessionQuestions = qs;
            sessionId = session.sessionId;
            phase = "running";
        } catch {
            noMatch = true;
        } finally {
            starting = false;
        }
    }

    function onFinished(e: CustomEvent<PracticeSessionSummary>): void {
        summary = e.detail;
        phase = "summary";
    }

    function restart(): void {
        summary = undefined;
        sessionQuestions = [];
        sessionId = "";
        void loadBank();
    }
</script>

{#if phase === "running"}
    <PracticeRunner
        questions={sessionQuestions}
        {sessionId}
        timeLimitSeconds={timeLimit}
        on:finished={onFinished}
    />
{:else if phase === "summary" && summary}
    <SessionSummary {summary} on:restart={restart} />
{:else}
    <div class="setup">
        <header>
            <h1>Practice Questions</h1>
            <p class="tagline">
                MCAT-style multiple-choice practice — discrete questions and CARS
                passage sets. The explanation is shown after you submit.
            </p>
        </header>

        {#if phase === "loading"}
            <div class="loading">Loading question bank…</div>
        {:else if loadError || bank.length === 0}
            <div class="notice">
                No practice questions are loaded yet. The bundled content is imported in
                the background on first run — give it a moment, then retry.
                <button class="secondary" on:click={loadBank}>Retry</button>
            </div>
        {:else}
            <div class="counts">
                {#each sectionCounts as sc (sc.section)}
                    <div class="count-pill">
                        <span class="n">{sc.count}</span>
                        {sectionLong(sc.section)}
                    </div>
                {/each}
            </div>

            <div class="form">
                <label>
                    <span>Section</span>
                    <select bind:value={sectionFilter}>
                        <option value={McatSection.UNSPECIFIED}>All sections</option>
                        {#each SECTIONS as s (s)}
                            <option value={s}>{sectionLong(s)}</option>
                        {/each}
                    </select>
                </label>

                <label>
                    <span>Topic</span>
                    <select bind:value={topic}>
                        <option value="">All topics</option>
                        {#each topicOptions as t (t)}
                            <option value={t}>{t}</option>
                        {/each}
                    </select>
                </label>

                <label>
                    <span>Number of questions</span>
                    <select bind:value={limit}>
                        {#each COUNTS as c (c.value)}
                            <option value={c.value}>{c.label}</option>
                        {/each}
                    </select>
                </label>

                <label>
                    <span>Timer</span>
                    <select bind:value={timeLimit}>
                        {#each TIME_LIMITS as t (t.value)}
                            <option value={t.value}>{t.label}</option>
                        {/each}
                    </select>
                </label>

                <label class="check">
                    <input type="checkbox" bind:checked={missedOnly} />
                    <span>Only questions I previously missed</span>
                </label>
            </div>

            {#if noMatch}
                <div class="notice warn">
                    No questions match those filters. Try widening them.
                </div>
            {/if}

            <button class="primary start" on:click={start} disabled={starting}>
                {starting ? "Starting…" : "Start practice"}
            </button>
        {/if}
    </div>
{/if}

<style lang="scss">
    .setup {
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
    }
    .counts {
        display: flex;
        gap: 0.6rem;
        flex-wrap: wrap;
    }
    .count-pill {
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 8px;
        padding: 0.5rem 0.8rem;
        font-size: 0.85rem;
        color: var(--fg-subtle);
    }
    .count-pill .n {
        font-weight: 700;
        color: var(--fg);
        margin-right: 0.3rem;
    }
    .form {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 1rem;
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 12px;
        padding: 1.25rem;
    }
    label {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        font-size: 0.9rem;
    }
    label > span {
        color: var(--fg-subtle);
    }
    label.check {
        grid-column: 1 / -1;
        flex-direction: row;
        align-items: center;
        gap: 0.5rem;
    }
    select {
        padding: 0.5rem;
        border-radius: 6px;
        border: 1px solid var(--border);
        background: var(--canvas-inset);
        color: var(--fg);
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
    .loading {
        color: var(--fg-subtle);
    }
    button {
        cursor: pointer;
        border-radius: 6px;
    }
    button.primary {
        border: 1px solid var(--button-primary-bg);
        background: var(--button-primary-bg);
        color: #fff;
        padding: 0.7rem 1.6rem;
        font-size: 1rem;
    }
    button.secondary {
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
        padding: 0.4rem 0.9rem;
    }
    button.start {
        align-self: flex-start;
    }
    button:disabled {
        opacity: 0.6;
        cursor: default;
    }
</style>
