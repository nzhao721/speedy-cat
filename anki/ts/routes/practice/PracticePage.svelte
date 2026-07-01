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
    import Dropdown from "./Dropdown.svelte";
    import PracticeRunner from "./PracticeRunner.svelte";
    import SessionSummary from "./SessionSummary.svelte";
    import {
        beginPracticeSession,
        fetchQuestions,
        parseQuestionCount,
        parseTimerMinutes,
        SECTIONS,
        sectionLong,
        type FilterOptions,
        type McatSection,
        type PracticeQuestion,
        type PracticeSessionSummary,
    } from "./lib";

    type Phase = "loading" | "setup" | "running" | "summary";

    let phase: Phase = "loading";
    let bank: PracticeQuestion[] = [];
    let loadError = false;
    let starting = false;
    let noMatch = false;

    // filter state — sections/topics are multi-select; empty = all.
    let selectedSections: McatSection[] = [];
    let selectedTopics: string[] = [];
    let missedOnly = false;
    // Free-text inputs: how many questions (blank / "max" = all available) and
    // an optional timer in minutes gated by the Untimed toggle.
    let countInput = "20";
    let untimed = true;
    let timerInput = "20";

    // running state
    let sessionQuestions: PracticeQuestion[] = [];
    let sessionId = "";
    let summary: PracticeSessionSummary | undefined;

    $: sectionOptions = SECTIONS.map((s) => ({
        value: s as number,
        label: sectionLong(s),
    }));

    $: topicOptions = Array.from(
        new Set(
            bank
                .filter(
                    (q) =>
                        selectedSections.length === 0 ||
                        selectedSections.includes(q.section),
                )
                .flatMap((q) => q.topicTags),
        ),
    ).sort((a, b) => a.localeCompare(b));

    $: topicDropdownOptions = topicOptions.map((t) => ({ value: t, label: t }));

    $: sectionCounts = SECTIONS.map((s) => ({
        section: s,
        count: bank.filter((q) => q.section === s).length,
    }));

    // drop any chosen topics that no longer apply to the selected sections
    $: {
        const stillValid = selectedTopics.filter((t) => topicOptions.includes(t));
        if (stillValid.length !== selectedTopics.length) {
            selectedTopics = stillValid;
        }
    }

    // Questions available for the current section + topic selection, used to
    // clamp the typed count and show the "all available" hint. missed-only is
    // resolved server-side, so it isn't reflected in this client-side count.
    $: availableForFilter = bank.filter(
        (q) =>
            (selectedSections.length === 0 ||
                selectedSections.includes(q.section)) &&
            (selectedTopics.length === 0 ||
                q.topicTags.some((t) => selectedTopics.includes(t))),
    ).length;

    $: countParse = parseQuestionCount(countInput, availableForFilter);
    $: timerParse = parseTimerMinutes(timerInput);
    $: limit = countParse.limit;
    $: timeLimit = untimed ? 0 : timerParse.seconds;
    $: canStart =
        !starting &&
        bank.length > 0 &&
        countParse.valid &&
        (untimed || timerParse.valid);

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
            sections: selectedSections,
            topics: selectedTopics,
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
            // The session owns the exact questions to serve — a per-session
            // shuffled selection with shuffled answer choices — so we use those
            // directly (they stay stable for this session, differ across ones).
            const session = await beginPracticeSession(opts, timeLimit);
            if (session.questions.length === 0) {
                noMatch = true;
                return;
            }
            sessionQuestions = session.questions;
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
                <div class="field">
                    <span class="field-label">Section</span>
                    <Dropdown
                        multiple
                        options={sectionOptions}
                        selected={selectedSections}
                        placeholder="All sections"
                        ariaLabel="Section filter"
                        on:change={(e) =>
                            (selectedSections = e.detail.selected as McatSection[])}
                    />
                </div>

                <div class="field">
                    <span class="field-label">Topic</span>
                    <Dropdown
                        multiple
                        options={topicDropdownOptions}
                        selected={selectedTopics}
                        placeholder="All topics"
                        ariaLabel="Topic filter"
                        on:change={(e) =>
                            (selectedTopics = e.detail.selected as string[])}
                    />
                </div>

                <div class="field">
                    <span class="field-label">Number of questions</span>
                    <input
                        class="text-input"
                        class:invalid={!countParse.valid}
                        type="text"
                        inputmode="numeric"
                        bind:value={countInput}
                        placeholder="e.g. 20"
                        aria-label="Number of questions"
                        aria-invalid={!countParse.valid}
                    />
                    {#if !countParse.valid}
                        <span class="hint error">
                            Enter a positive number, or leave blank / “max” for all.
                        </span>
                    {:else}
                        <span class="hint">
                            Blank or “max” = all available ({availableForFilter}).
                        </span>
                    {/if}
                </div>

                <div class="field">
                    <span class="field-label">Timer</span>
                    <div class="timer-controls">
                        <label class="inline-check">
                            <input type="checkbox" bind:checked={untimed} />
                            <span>Untimed</span>
                        </label>
                        <div class="minutes">
                            <input
                                class="text-input"
                                class:invalid={!untimed && !timerParse.valid}
                                type="text"
                                inputmode="numeric"
                                bind:value={timerInput}
                                placeholder="20"
                                aria-label="Timer minutes"
                                disabled={untimed}
                                aria-invalid={!untimed && !timerParse.valid}
                            />
                            <span class="unit">min</span>
                        </div>
                    </div>
                    {#if !untimed && !timerParse.valid}
                        <span class="hint error">
                            Enter a positive number of minutes.
                        </span>
                    {/if}
                </div>

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

            <button class="primary start" on:click={start} disabled={!canStart}>
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
    .field,
    label {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        font-size: 0.9rem;
    }
    .field-label,
    label > span {
        color: var(--fg-subtle);
    }
    label.check {
        grid-column: 1 / -1;
        flex-direction: row;
        align-items: center;
        gap: 0.5rem;
    }
    .text-input {
        width: 100%;
        padding: 0.5rem;
        border-radius: 6px;
        border: 1px solid var(--border);
        background: var(--canvas-inset);
        color: var(--fg);
        font-size: 0.9rem;
    }
    .text-input:disabled {
        opacity: 0.55;
        cursor: default;
    }
    .text-input.invalid {
        border-color: #d1434b;
    }
    .hint {
        font-size: 0.78rem;
        color: var(--fg-subtle);
    }
    .hint.error {
        color: #d1434b;
    }
    .timer-controls {
        display: flex;
        align-items: center;
        gap: 1rem;
    }
    .inline-check {
        flex-direction: row;
        align-items: center;
        gap: 0.4rem;
        font-size: 0.85rem;
        white-space: nowrap;
    }
    .minutes {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        flex: 1;
    }
    .unit {
        color: var(--fg-subtle);
        font-size: 0.85rem;
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
