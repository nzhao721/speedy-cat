<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the Practice Question Bank runner. Navigation is by run item: a
discrete item is a single question, while a CARS passage set is one reading
passage shown once beside ALL of its questions (answered independently). A–D/E
choices; optional elimination + flagging; the explanation is revealed per
question only after that question is submitted (immediate feedback). Every
answer is logged through record_practice_attempt; Finish ends the session and
surfaces the summary.
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
        type RunItem,
    } from "./lib";

    export let questions: PracticeQuestion[];
    export let sessionId: string;
    export let timeLimitSeconds = 0;

    const dispatch = createEventDispatcher<{ finished: PracticeSessionSummary }>();

    // Group passage-linked questions into whole units (CARS passage sets); each
    // discrete question is its own single-question item. Navigation is per item.
    const runItems: RunItem[] = groupIntoRunItems(questions);
    const allQuestions: PracticeQuestion[] = runItems.flatMap((item) => item.questions);
    const questionNumber = new Map<string, number>(
        allQuestions.map((q, i) => [q.id, i + 1]),
    );
    const itemOfQuestion = new Map<string, number>();
    runItems.forEach((item, i) =>
        item.questions.forEach((q) => itemOfQuestion.set(q.id, i)),
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
    let secondsOnItem = 0;
    let ticker: ReturnType<typeof setInterval> | undefined;

    $: current = runItems[index];
    $: totalItems = runItems.length;
    $: totalQuestions = allQuestions.length;
    $: remaining = timeLimitSeconds > 0 ? Math.max(0, timeLimitSeconds - elapsed) : 0;
    $: answeredCount = allQuestions.filter((q) => submitted[q.id]).length;
    $: firstNum = current ? (questionNumber.get(current.questions[0].id) ?? 1) : 0;
    $: lastNum = current
        ? (questionNumber.get(current.questions[current.questions.length - 1].id) ??
          firstNum)
        : 0;

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
        secondsOnItem += 1;
        if (timeLimitSeconds > 0 && elapsed >= timeLimitSeconds) {
            void finish();
        }
    }

    /** Distribute the time spent on the current item across the questions still
     * being worked on (a shared passage is read for all of them). */
    function flushItemTime(): void {
        const item = runItems[index];
        if (item && secondsOnItem > 0) {
            const pending = item.questions.filter((q) => !submitted[q.id]);
            const targets = pending.length > 0 ? pending : item.questions;
            const per = secondsOnItem / targets.length;
            for (const q of targets) {
                timeSpent[q.id] = (timeSpent[q.id] ?? 0) + per;
            }
        }
        secondsOnItem = 0;
    }

    async function ensurePassage(item: RunItem | undefined): Promise<void> {
        const pid = item?.passageId;
        if (!pid || passageCache[pid]) {
            return;
        }
        passageLoading = true;
        try {
            passageCache = {
                ...passageCache,
                [pid]: await fetchPassageSet(pid),
            };
        } catch {
            // leave uncached; the panel shows a fallback
        } finally {
            passageLoading = false;
        }
    }

    function goto(newIndex: number): void {
        if (newIndex < 0 || newIndex >= totalItems || newIndex === index) {
            return;
        }
        flushItemTime();
        index = newIndex;
        void ensurePassage(runItems[index]);
    }

    function onSelect(q: PracticeQuestion, label: string): void {
        if (submitted[q.id]) {
            return;
        }
        selected = { ...selected, [q.id]: label };
    }

    function onEliminate(q: PracticeQuestion, label: string): void {
        const list = eliminated[q.id] ?? [];
        const next = list.includes(label)
            ? list.filter((l) => l !== label)
            : [...list, label];
        eliminated = { ...eliminated, [q.id]: next };
    }

    function onToggleFlag(q: PracticeQuestion): void {
        flagged = { ...flagged, [q.id]: !flagged[q.id] };
    }

    async function submitQuestion(q: PracticeQuestion): Promise<void> {
        if (!q || submitted[q.id]) {
            return;
        }
        flushItemTime();
        const answer = selected[q.id] ?? "";
        const correct = answer !== "" && answer === q.correctAnswer;
        submitted = { ...submitted, [q.id]: true };
        try {
            await logPracticeAttempt({
                sessionId,
                questionId: q.id,
                selectedAnswer: answer,
                correct,
                timeOnQuestionSeconds: Math.round(timeSpent[q.id] ?? 0),
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
        flushItemTime();
        // Record any served-but-unsubmitted question as skipped so the summary
        // and per-topic tracking reflect the whole session.
        for (const q of allQuestions) {
            if (!submitted[q.id]) {
                submitted[q.id] = true;
                try {
                    await logPracticeAttempt({
                        sessionId,
                        questionId: q.id,
                        selectedAnswer: "",
                        correct: false,
                        timeOnQuestionSeconds: Math.round(timeSpent[q.id] ?? 0),
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
            {#if current && current.questions.length > 1}
                Passage set · Questions {firstNum}–{lastNum} of {totalQuestions}
            {:else}
                Question {firstNum} of {totalQuestions}
            {/if}
            <span class="sub">({answeredCount} submitted)</span>
        </div>
        {#if timeLimitSeconds > 0}
            <div class="timer" class:low={remaining <= 60}>
                {formatClock(remaining)}
            </div>
        {:else}
            <div class="timer elapsed">{formatClock(elapsed)}</div>
        {/if}
    </div>

    <div class="nav-strip">
        {#each allQuestions as q, i (q.id)}
            <button
                class="nav-dot {navStatus(q)}"
                class:current={itemOfQuestion.get(q.id) === index}
                class:flagged={flagged[q.id]}
                title={`Question ${i + 1}`}
                on:click={() => goto(itemOfQuestion.get(q.id) ?? 0)}
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
                {#each current.questions as q, qi (q.id)}
                    <div class="q-block">
                        <QuestionView
                            question={q}
                            number={questionNumber.get(q.id) ?? qi + 1}
                            selected={selected[q.id] ?? ""}
                            eliminated={eliminated[q.id] ?? []}
                            flagged={flagged[q.id] ?? false}
                            revealed={submitted[q.id] ?? false}
                            disabled={submitted[q.id] ?? false}
                            on:select={(e) => onSelect(q, e.detail)}
                            on:eliminate={(e) => onEliminate(q, e.detail)}
                            on:toggleFlag={() => onToggleFlag(q)}
                        />
                        {#if !submitted[q.id]}
                            <div class="q-actions">
                                <button
                                    class="primary"
                                    on:click={() => submitQuestion(q)}
                                    disabled={!selected[q.id]}
                                >
                                    Submit
                                </button>
                            </div>
                        {/if}
                    </div>
                    {#if qi < current.questions.length - 1}
                        <hr class="q-divider" />
                    {/if}
                {/each}
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
        <span class="counter">{answeredCount} / {totalQuestions} answered</span>
        {#if index >= totalItems - 1}
            <button class="primary" on:click={finish} disabled={finishing}>
                {finishing ? "Scoring…" : "Finish and Score"}
            </button>
        {:else}
            <button class="secondary" on:click={() => goto(index + 1)}>
                Next
            </button>
        {/if}
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
        display: flex;
        flex-direction: column;
    }
    .q-block {
        display: flex;
        flex-direction: column;
        gap: 1rem;
    }
    .q-actions {
        display: flex;
    }
    .q-divider {
        width: 100%;
        border: none;
        border-top: 1px solid var(--border-subtle);
        margin: 1.5rem 0;
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
