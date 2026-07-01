<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the Practice Question Bank runner. One question at a time; A–D/E
choices; optional elimination + flagging; the explanation is revealed only
after the user submits (immediate feedback). CARS / passage-linked questions
show the reading passage beside the question. Every answer is logged through
record_practice_attempt; Finish ends the session and surfaces the summary.
-->
<script lang="ts">
    import { createEventDispatcher, onDestroy, onMount } from "svelte";

    import PassagePanel from "./PassagePanel.svelte";
    import QuestionView from "./QuestionView.svelte";
    import {
        fetchPassageSet,
        finishPracticeSession,
        formatClock,
        groupIntoRunItems,
        logPracticeAttempt,
        primaryTopic,
        type CarsPassageSet,
        type PracticeQuestion,
        type PracticeSessionSummary,
    } from "./lib";

    export let questions: PracticeQuestion[];
    export let sessionId: string;
    export let timeLimitSeconds = 0;

    const dispatch = createEventDispatcher<{ finished: PracticeSessionSummary }>();

    // Keep passage-linked questions adjacent, then flatten to a linear order.
    const ordered: PracticeQuestion[] = groupIntoRunItems(questions).flatMap(
        (item) => item.questions,
    );

    let index = 0;
    let selected: Record<string, string> = {};
    let submitted: Record<string, boolean> = {};
    let eliminated: Record<string, string[]> = {};
    let flagged: Record<string, boolean> = {};
    const timeSpent: Record<string, number> = {};
    let passageCache: Record<string, CarsPassageSet> = {};
    let passageLoading = false;
    let finishing = false;

    let elapsed = 0;
    let secondsOnCurrent = 0;
    let ticker: ReturnType<typeof setInterval> | undefined;

    $: current = ordered[index];
    $: total = ordered.length;
    $: remaining = timeLimitSeconds > 0 ? Math.max(0, timeLimitSeconds - elapsed) : 0;
    $: answeredCount = ordered.filter((q) => submitted[q.id]).length;

    onMount(() => {
        void ensurePassage(current);
        ticker = setInterval(tick, 1000);
    });
    onDestroy(() => {
        if (ticker) {
            clearInterval(ticker);
        }
    });

    function tick(): void {
        elapsed += 1;
        secondsOnCurrent += 1;
        if (timeLimitSeconds > 0 && elapsed >= timeLimitSeconds) {
            void finish();
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
            // leave uncached; the panel shows a fallback
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
        if (submitted[current.id]) {
            return;
        }
        selected = { ...selected, [current.id]: label };
    }

    function onEliminate(label: string): void {
        const list = eliminated[current.id] ?? [];
        const next = list.includes(label)
            ? list.filter((l) => l !== label)
            : [...list, label];
        eliminated = { ...eliminated, [current.id]: next };
    }

    function onToggleFlag(): void {
        flagged = { ...flagged, [current.id]: !flagged[current.id] };
    }

    async function submit(): Promise<void> {
        const q = current;
        if (!q || submitted[q.id]) {
            return;
        }
        flushTime();
        const answer = selected[q.id] ?? "";
        const correct = answer !== "" && answer === q.correctAnswer;
        submitted = { ...submitted, [q.id]: true };
        try {
            await logPracticeAttempt({
                sessionId,
                questionId: q.id,
                selectedAnswer: answer,
                correct,
                timeOnQuestionSeconds: timeSpent[q.id] ?? 0,
                section: q.section,
                topic: primaryTopic(q),
            });
        } catch {
            // network error already surfaced by postProto's alert
        }
    }

    async function finish(): Promise<void> {
        if (finishing) {
            return;
        }
        finishing = true;
        if (ticker) {
            clearInterval(ticker);
            ticker = undefined;
        }
        flushTime();
        // Record any served-but-unsubmitted question as skipped so the summary
        // and per-topic tracking reflect the whole session.
        for (const q of ordered) {
            if (!submitted[q.id]) {
                submitted[q.id] = true;
                try {
                    await logPracticeAttempt({
                        sessionId,
                        questionId: q.id,
                        selectedAnswer: "",
                        correct: false,
                        timeOnQuestionSeconds: timeSpent[q.id] ?? 0,
                        section: q.section,
                        topic: primaryTopic(q),
                    });
                } catch {
                    // ignore
                }
            }
        }
        try {
            const summary = await finishPracticeSession(sessionId);
            dispatch("finished", summary);
        } finally {
            finishing = false;
        }
    }

    function navStatus(q: PracticeQuestion): string {
        if (submitted[q.id]) {
            if (!selected[q.id]) {
                return "skip";
            }
            return selected[q.id] === q.correctAnswer ? "ok" : "bad";
        }
        return selected[q.id] ? "answered" : "";
    }
</script>

<div class="runner">
    <div class="topbar">
        <div class="progress">
            Question {index + 1} of {total}
            <span class="sub">({answeredCount} submitted)</span>
        </div>
        {#if timeLimitSeconds > 0}
            <div class="timer" class:low={remaining <= 60}>
                {formatClock(remaining)}
            </div>
        {:else}
            <div class="timer elapsed">{formatClock(elapsed)}</div>
        {/if}
        <button class="finish" on:click={finish} disabled={finishing}>
            Finish &amp; score
        </button>
    </div>

    <div class="nav-strip">
        {#each ordered as q, i (q.id)}
            <button
                class="nav-dot {navStatus(q)}"
                class:current={i === index}
                class:flagged={flagged[q.id]}
                title={`Question ${i + 1}`}
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
                    revealed={submitted[current.id] ?? false}
                    disabled={submitted[current.id] ?? false}
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
        {#if current && !submitted[current.id]}
            <button class="primary" on:click={submit} disabled={!selected[current.id]}>
                Submit
            </button>
        {/if}
        <button
            class="secondary"
            on:click={() => goto(index + 1)}
            disabled={index >= total - 1}
        >
            Next
        </button>
    </div>
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
        font-weight: 600;
    }
    .progress .sub {
        color: var(--fg-subtle);
        font-weight: 400;
        font-size: 0.85rem;
    }
    .timer {
        margin-left: auto;
        font-variant-numeric: tabular-nums;
        font-weight: 700;
        font-size: 1.2rem;
        padding: 0.15rem 0.6rem;
        border-radius: 6px;
        background: var(--canvas-inset);
    }
    .timer.low {
        color: #fff;
        background: #d1434b;
    }
    .timer.elapsed {
        color: var(--fg-subtle);
        font-weight: 600;
        font-size: 1rem;
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
    }
    .nav-dot.ok {
        background: rgba(46, 158, 79, 0.2);
        border-color: #2e9e4f;
        color: var(--fg);
    }
    .nav-dot.bad {
        background: rgba(209, 67, 75, 0.2);
        border-color: #d1434b;
        color: var(--fg);
    }
    .nav-dot.skip {
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
        justify-content: center;
        padding: 0.75rem 1rem;
        border-top: 1px solid var(--border-subtle);
        background: var(--canvas-elevated);
    }
    button.primary,
    button.secondary {
        border-radius: 6px;
        padding: 0.5rem 1.4rem;
        cursor: pointer;
        font-size: 0.95rem;
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
