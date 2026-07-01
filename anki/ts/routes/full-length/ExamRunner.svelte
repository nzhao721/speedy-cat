<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: one timed section of a full-length practice test. Enforces the
section countdown (auto-ends the section when it hits zero), allows free
navigation + flagging WITHIN the section only (no feedback until the whole test
is submitted), and records every answer via record_full_length_answer when the
section ends. There is deliberately no way back into a section once it closes.
-->
<script lang="ts">
    import { createEventDispatcher, onDestroy, onMount } from "svelte";

    import PassagePanel from "../practice/PassagePanel.svelte";
    import QuestionView from "../practice/QuestionView.svelte";
    import {
        fetchPassageSet,
        fetchQuestions,
        formatClock,
        groupIntoRunItems,
        logFullLengthAnswer,
        primaryTopic,
        sectionLong,
        type CarsPassageSet,
        type McatSection,
        type PracticeQuestion,
    } from "../practice/lib";

    export let attemptId: string;
    export let testId: string;
    export let section: McatSection;
    export let durationSeconds: number;
    export let sectionOrder = 1;
    export let sectionTotal = 4;

    const dispatch = createEventDispatcher<{ sectionDone: void }>();

    let ordered: PracticeQuestion[] = [];
    let loading = true;
    let index = 0;
    let selected: Record<string, string> = {};
    let flagged: Record<string, boolean> = {};
    let eliminated: Record<string, string[]> = {};
    const timeSpent: Record<string, number> = {};
    let passageCache: Record<string, CarsPassageSet> = {};
    let passageLoading = false;

    let remaining = durationSeconds;
    let secondsOnCurrent = 0;
    let ticker: ReturnType<typeof setInterval> | undefined;
    let ending = false;

    $: current = ordered[index];
    $: total = ordered.length;
    $: answeredCount = ordered.filter((q) => selected[q.id]).length;

    onMount(async () => {
        try {
            const all = await fetchQuestions({ section, includeFullLength: true });
            const sectionQuestions = all.filter((q) => q.testId === testId);
            ordered = groupIntoRunItems(sectionQuestions).flatMap(
                (item) => item.questions,
            );
        } finally {
            loading = false;
        }
        void ensurePassage(current);
        ticker = setInterval(tick, 1000);
    });
    onDestroy(() => {
        if (ticker) {
            clearInterval(ticker);
        }
    });

    function tick(): void {
        remaining -= 1;
        secondsOnCurrent += 1;
        if (remaining <= 0) {
            void endSection();
        }
    }

    function flushTime(): void {
        if (current) {
            timeSpent[current.id] = (timeSpent[current.id] ?? 0) + secondsOnCurrent;
        }
        secondsOnCurrent = 0;
    }

    async function ensurePassage(q: PracticeQuestion | undefined): Promise<void> {
        if (!q || !q.passageId || passageCache[q.passageId]) {
            return;
        }
        passageLoading = true;
        try {
            passageCache = {
                ...passageCache,
                [q.passageId]: await fetchPassageSet(q.passageId),
            };
        } catch {
            // fall back to empty panel
        } finally {
            passageLoading = false;
        }
    }

    function goto(newIndex: number): void {
        if (newIndex < 0 || newIndex >= total || newIndex === index) {
            return;
        }
        flushTime();
        index = newIndex;
        void ensurePassage(ordered[index]);
    }

    function onSelect(label: string): void {
        selected = { ...selected, [current.id]: label };
    }

    function onEliminate(label: string): void {
        const list = eliminated[current.id] ?? [];
        eliminated = {
            ...eliminated,
            [current.id]: list.includes(label)
                ? list.filter((l) => l !== label)
                : [...list, label],
        };
    }

    function onToggleFlag(): void {
        flagged = { ...flagged, [current.id]: !flagged[current.id] };
    }

    async function endSection(): Promise<void> {
        if (ending) {
            return;
        }
        ending = true;
        if (ticker) {
            clearInterval(ticker);
            ticker = undefined;
        }
        flushTime();
        for (const q of ordered) {
            const answer = selected[q.id] ?? "";
            const correct = answer !== "" && answer === q.correctAnswer;
            try {
                await logFullLengthAnswer({
                    attemptId,
                    section: q.section,
                    questionId: q.id,
                    selectedAnswer: answer,
                    correct,
                    timeOnQuestionSeconds: timeSpent[q.id] ?? 0,
                    topic: primaryTopic(q),
                });
            } catch {
                // ignore individual failures; continue recording the rest
            }
        }
        dispatch("sectionDone");
    }
</script>

<div class="runner">
    <div class="topbar">
        <div class="progress">
            <span class="sec">Section {sectionOrder} of {sectionTotal}</span>
            <span class="name">{sectionLong(section)}</span>
        </div>
        <div class="timer" class:low={remaining <= 300}>
            {formatClock(remaining)}
        </div>
        <button class="finish" on:click={endSection} disabled={ending}>
            End section
        </button>
    </div>

    {#if loading}
        <div class="center">Loading section…</div>
    {:else if total === 0}
        <div class="center">
            <p>No questions found for this section.</p>
            <button class="primary" on:click={endSection}>Continue</button>
        </div>
    {:else}
        <div class="nav-strip">
            {#each ordered as q, i (q.id)}
                <button
                    class="nav-dot"
                    class:answered={selected[q.id]}
                    class:current={i === index}
                    class:flagged={flagged[q.id]}
                    on:click={() => goto(i)}
                >
                    {i + 1}
                </button>
            {/each}
        </div>

        <div class="content" class:with-passage={!!current?.passageId}>
            {#if current?.passageId}
                <div class="passage-col">
                    <PassagePanel
                        passageSet={passageCache[current.passageId]}
                        loading={passageLoading && !passageCache[current.passageId]}
                    />
                </div>
            {/if}
            <div class="question-col">
                {#if current}
                    <QuestionView
                        question={current}
                        number={index + 1}
                        selected={selected[current.id] ?? ""}
                        eliminated={eliminated[current.id] ?? []}
                        flagged={flagged[current.id] ?? false}
                        revealed={false}
                        on:select={(e) => onSelect(e.detail)}
                        on:eliminate={(e) => onEliminate(e.detail)}
                        on:toggleFlag={onToggleFlag}
                    />
                {/if}
            </div>
        </div>

        <div class="footer">
            <button
                class="secondary"
                on:click={() => goto(index - 1)}
                disabled={index === 0}
            >
                Previous
            </button>
            <span class="counter">{answeredCount} / {total} answered</span>
            <button
                class="secondary"
                on:click={() => goto(index + 1)}
                disabled={index >= total - 1}
            >
                Next
            </button>
        </div>
    {/if}
</div>

<style lang="scss">
    .runner {
        display: flex;
        flex-direction: column;
        height: 100vh;
        overflow: hidden;
    }
    .topbar {
        display: flex;
        align-items: center;
        gap: 1rem;
        padding: 0.6rem 1rem;
        border-bottom: 1px solid var(--border-subtle);
        background: var(--canvas-elevated);
    }
    .progress {
        display: flex;
        flex-direction: column;
    }
    .progress .sec {
        font-size: 0.78rem;
        color: var(--fg-subtle);
    }
    .progress .name {
        font-weight: 600;
    }
    .timer {
        margin-left: auto;
        font-variant-numeric: tabular-nums;
        font-weight: 700;
        font-size: 1.3rem;
        padding: 0.15rem 0.7rem;
        border-radius: 6px;
        background: var(--canvas-inset);
    }
    .timer.low {
        color: #fff;
        background: #d1434b;
    }
    .finish {
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
        border-radius: 6px;
        padding: 0.4rem 0.8rem;
        cursor: pointer;
    }
    .nav-strip {
        display: flex;
        gap: 0.3rem;
        padding: 0.5rem 1rem;
        overflow-x: auto;
        border-bottom: 1px solid var(--border-subtle);
    }
    .nav-dot {
        flex: 0 0 auto;
        width: 1.9rem;
        height: 1.9rem;
        border-radius: 6px;
        border: 1px solid var(--border);
        background: var(--canvas-elevated);
        color: var(--fg-subtle);
        cursor: pointer;
        font-size: 0.8rem;
    }
    .nav-dot.answered {
        border-color: var(--border-focus);
        color: var(--fg);
        background: var(--canvas-inset);
    }
    .nav-dot.current {
        outline: 2px solid var(--border-focus);
        outline-offset: 1px;
        color: var(--fg);
    }
    .nav-dot.flagged {
        box-shadow: inset 0 -3px 0 #e0a34e;
    }
    .content {
        flex: 1;
        overflow: hidden;
        display: grid;
        grid-template-columns: 1fr;
    }
    .content.with-passage {
        grid-template-columns: 1fr 1fr;
    }
    .passage-col {
        border-right: 1px solid var(--border-subtle);
        overflow: hidden;
        background: var(--canvas);
    }
    .question-col {
        overflow-y: auto;
        padding: 1.25rem 1.5rem;
    }
    .footer {
        display: flex;
        gap: 0.75rem;
        align-items: center;
        justify-content: center;
        padding: 0.75rem 1rem;
        border-top: 1px solid var(--border-subtle);
        background: var(--canvas-elevated);
    }
    .counter {
        color: var(--fg-subtle);
        font-size: 0.85rem;
    }
    .center {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 1rem;
        align-items: center;
        justify-content: center;
        color: var(--fg-subtle);
    }
    button.primary,
    button.secondary {
        border-radius: 6px;
        padding: 0.5rem 1.4rem;
        cursor: pointer;
        border: 1px solid var(--border);
    }
    button.primary {
        background: var(--button-primary-bg);
        border-color: var(--button-primary-bg);
        color: #fff;
    }
    button.secondary {
        background: var(--button-bg);
        color: var(--fg);
    }
    button:disabled {
        opacity: 0.5;
        cursor: default;
    }
</style>
