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

    import ExplanationChat from "./ExplanationChat.svelte";
    import HintLadder from "./HintLadder.svelte";
    import PassagePanel from "./PassagePanel.svelte";
    import QuestionView from "./QuestionView.svelte";
    import {
        buildUserVisiblePrompt,
        correctAnswerText,
        emptyExplanationProgress,
        explanationBypassedProgress,
        explanationFailureHint,
        hintPromptsFromQuestion,
        itemBlocksProgress,
        shouldUseExplanationGate,
        type ExplanationAiStatus,
        type ExplanationProgress,
    } from "./explanationGate";
    import {
        checkExplanation,
        fetchExplanationAiStatus,
    } from "./explanationCheck";
    import {
        applyHintAnswer,
        canShowFirstHintButton,
        canShowNextHintButton,
        canSubmitMain,
        emptyHintProgress,
        fetchPassageSet,
        finishPracticeSession,
        formatClock,
        groupIntoRunItems,
        hasHintLadder,
        hintLevelReached,
        isAssisted,
        logPracticeAttempt,
        passageHintWordCountForQuestion,
        passageWordCount,
        pendingHintIndex,
        primaryTopic,
        questionHints,
        canRevealNextHint,
        shouldTickQuestionHintTimer,
        startHintWrongCooldown,
        tickHintCooldowns,
        type CarsPassageSet,
        type HintProgress,
        type PracticeQuestion,
        type PracticeSessionSummary,
        type RunItem,
    } from "./lib";

    interface Props {
        questions: PracticeQuestion[];
        sessionId: string;
        timeLimitSeconds?: number;
    }

    const {
        questions,
        sessionId,
        timeLimitSeconds = 0,
    }: Props = $props();

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

    let index = $state(0);
    let selected = $state<Record<string, string>>({});
    let submitted = $state<Record<string, boolean>>({});
    let eliminated = $state<Record<string, string[]>>({});
    let flagged = $state<Record<string, boolean>>({});
    // SpeedyCAT graduated hint ladder: per-question progress (revealed tiers +
    // the answer chosen for each). Reactive so the ladder + gating update live.
    let hintProgress = $state<Record<string, HintProgress>>({});
    // Set after a wrong submit escalates into the ladder, to explain why the
    // main answer wasn't revealed.
    let hintNudge = $state<Record<string, boolean>>({});
    /** True once the learner submitted a wrong main answer (wrong-answer path). */
    let mainWrongFirst = $state<Record<string, boolean>>({});
    // Per-question timers for hint affordances (seconds while visible on screen).
    let secondsOnQuestion = $state<Record<string, number>>({});
    let secondsSinceHintComplete = $state<Record<string, number>>({});
    let hintCooldown = $state<Record<string, number>>({});
    /** Hint timers start on first viewport visibility; pause when scrolled away. */
    let questionEverVisible = $state<Record<string, boolean>>({});
    let questionCurrentlyVisible = $state<Record<string, boolean>>({});
    /** Tentative pick for the current (unanswered) hint subquestion, per question. */
    let hintPendingChoice = $state<Record<string, string>>({});
    // SpeedyCAT explanation gate: ~1-in-5 correct answers require a written
    // explanation verified by AI before the question is finalized.
    let explanationAiStatus = $state<ExplanationAiStatus>({
        available: false,
        aiOn: false,
    });
    let explanationProgress = $state<Record<string, ExplanationProgress>>({});
    let explanationCoaching = $state<Record<string, string>>({});
    const timeSpent: Record<string, number> = {};
    let passageCache = $state<Record<string, CarsPassageSet>>({});
    let passageLoading = $state(false);
    let finishing = $state(false);

    let elapsed = $state(0);
    let secondsOnItem = $state(0);
    let ticker: ReturnType<typeof setInterval> | undefined;
    // Per-question wrapper elements, so the hint ladder's "Return to main
    // question" shortcut can scroll that question's stem back into view.
    let questionEls = $state<Record<string, HTMLElement>>({});
    let questionColEl = $state<HTMLElement | undefined>();

    const visibilityObservers = new Map<string, IntersectionObserver>();

    function passageWordCountForItem(item: RunItem | undefined): number {
        if (!item?.passageId) {
            return 0;
        }
        const passage = passageCache[item.passageId]?.passage;
        return passageWordCount(passage?.wordCount, passage?.passage);
    }

    function hintPassageWordCount(
        item: RunItem | undefined,
        questionIndex: number,
    ): number | undefined {
        if (!item?.passageId) {
            return undefined;
        }
        const wc = passageWordCountForItem(item);
        return passageHintWordCountForQuestion(
            item.questions.length,
            questionIndex,
            wc,
        );
    }

    function observeQuestionVisibility(qid: string, el: HTMLElement): void {
        visibilityObservers.get(qid)?.disconnect();
        const root = questionColEl ?? null;
        const observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const visible = entry.isIntersecting;
                    questionCurrentlyVisible = {
                        ...questionCurrentlyVisible,
                        [qid]: visible,
                    };
                    if (visible && !questionEverVisible[qid]) {
                        questionEverVisible = {
                            ...questionEverVisible,
                            [qid]: true,
                        };
                    }
                }
            },
            { root, threshold: 0.08 },
        );
        observer.observe(el);
        visibilityObservers.set(qid, observer);
    }

    function clearVisibilityObservers(): void {
        for (const observer of visibilityObservers.values()) {
            observer.disconnect();
        }
        visibilityObservers.clear();
    }

    function questionVisibility(node: HTMLElement, qid: string): { destroy: () => void } {
        questionEls = { ...questionEls, [qid]: node };
        observeQuestionVisibility(qid, node);
        return {
            destroy(): void {
                visibilityObservers.get(qid)?.disconnect();
                visibilityObservers.delete(qid);
                const next = { ...questionEls };
                delete next[qid];
                questionEls = next;
            },
        };
    }

    const current = $derived(runItems[index]);
    const totalItems = $derived(runItems.length);
    const totalQuestions = $derived(allQuestions.length);
    const remaining = $derived(
        timeLimitSeconds > 0 ? Math.max(0, timeLimitSeconds - elapsed) : 0,
    );
    const answeredCount = $derived(
        allQuestions.filter((q) => submitted[q.id]).length,
    );
    const firstNum = $derived(
        current ? (questionNumber.get(current.questions[0].id) ?? 1) : 0,
    );
    const lastNum = $derived(
        current
            ? (questionNumber.get(current.questions[current.questions.length - 1].id) ??
                  firstNum)
            : 0,
    );

    onMount(() => {
        void ensurePassage(current);
        resetQuestionTimers(current);
        void fetchExplanationAiStatus().then((status) => {
            explanationAiStatus = status;
        });
        ticker = setInterval(tick, 1000);
    });

    // Re-root observers once the scrollable question column is mounted.
    $effect(() => {
        if (!questionColEl) {
            return;
        }
        for (const [qid, el] of Object.entries(questionEls)) {
            observeQuestionVisibility(qid, el);
        }
    });
    onDestroy(() => {
        if (ticker) {
            clearInterval(ticker);
        }
        clearVisibilityObservers();
    });

    function tick(): void {
        elapsed += 1;
        secondsOnItem += 1;
        tickQuestionTimers(current);
        hintCooldown = tickHintCooldowns(hintCooldown);
        if (timeLimitSeconds > 0 && elapsed >= timeLimitSeconds) {
            void finish();
        }
    }

    /** Reset per-question hint timers when landing on a run item. */
    function resetQuestionTimers(item: RunItem | undefined): void {
        if (!item) {
            return;
        }
        for (const q of item.questions) {
            if (!submitted[q.id]) {
                secondsOnQuestion = { ...secondsOnQuestion, [q.id]: 0 };
                secondsSinceHintComplete = {
                    ...secondsSinceHintComplete,
                    [q.id]: 0,
                };
                questionEverVisible = { ...questionEverVisible, [q.id]: false };
                questionCurrentlyVisible = {
                    ...questionCurrentlyVisible,
                    [q.id]: false,
                };
            }
        }
        // Re-observe after reset so first-visible detection runs again.
        for (const q of item.questions) {
            const el = questionEls[q.id];
            if (el) {
                observeQuestionVisibility(q.id, el);
            }
        }
    }

    /** Advance hint affordance timers for visible, unsubmitted questions. */
    function tickQuestionTimers(item: RunItem | undefined): void {
        if (!item) {
            return;
        }
        for (const q of item.questions) {
            if (submitted[q.id]) {
                continue;
            }
            const qid = q.id;
            if (
                !shouldTickQuestionHintTimer(
                    questionEverVisible[qid] ?? false,
                    questionCurrentlyVisible[qid] ?? false,
                )
            ) {
                continue;
            }
            secondsOnQuestion = {
                ...secondsOnQuestion,
                [qid]: (secondsOnQuestion[qid] ?? 0) + 1,
            };
            const prog = progressFor(q);
            const hints = questionHints(q);
            if (
                pendingHintIndex(prog) === -1
                && prog.revealed > 0
                && canRevealNextHint(hints, prog)
            ) {
                secondsSinceHintComplete = {
                    ...secondsSinceHintComplete,
                    [qid]: (secondsSinceHintComplete[qid] ?? 0) + 1,
                };
            } else {
                secondsSinceHintComplete = { ...secondsSinceHintComplete, [qid]: 0 };
            }
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

    const currentBlocksNavigation = $derived(
        current ? itemBlocksProgress(current.questions, explanationProgress) : false,
    );

    function goto(newIndex: number): void {
        if (newIndex < 0 || newIndex >= totalItems || newIndex === index) {
            return;
        }
        if (newIndex > index && currentBlocksNavigation) {
            return;
        }
        flushItemTime();
        index = newIndex;
        resetQuestionTimers(runItems[index]);
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

    /** Scroll a question's main view back into sight — the "Return to main
     * question" shortcut from the hint ladder. Same view (no navigation): the
     * main question renders above the ladder in the scrolling column. */
    function returnToMain(q: PracticeQuestion): void {
        questionEls[q.id]?.scrollIntoView({ behavior: "smooth", block: "start" });
    }

    /** The hint-ladder progress for a question (created lazily). */
    function progressFor(q: PracticeQuestion): HintProgress {
        return hintProgress[q.id] ?? emptyHintProgress();
    }

    /** Reveal the next hint tier (the "Request hint" / "Reveal next hint" action,
     * and the wrong-answer escalation both funnel through here). No-op once the
     * ladder is exhausted. */
    function revealNextHint(q: PracticeQuestion): void {
        const hints = questionHints(q);
        const prog = progressFor(q);
        if (prog.revealed >= hints.length) {
            return;
        }
        hintProgress = {
            ...hintProgress,
            [q.id]: { revealed: prog.revealed + 1, picks: { ...prog.picks } },
        };
        hintPendingChoice = { ...hintPendingChoice, [q.id]: "" };
        secondsSinceHintComplete = { ...secondsSinceHintComplete, [q.id]: 0 };
        hintCooldown = { ...hintCooldown, [q.id]: 0 };
    }

    function onRequestHint(q: PracticeQuestion): void {
        if (submitted[q.id]) {
            return;
        }
        hintNudge = { ...hintNudge, [q.id]: false };
        revealNextHint(q);
        secondsSinceHintComplete = { ...secondsSinceHintComplete, [q.id]: 0 };
        hintCooldown = { ...hintCooldown, [q.id]: 0 };
    }

    /** Record the answer to a subquestion. Wrong answers are rejected; the
     * learner must retry after a cooldown (handled in HintLadder). */
    function onAnswerHint(q: PracticeQuestion, index: number, label: string): void {
        const hints = questionHints(q);
        const hint = hints[index];
        if (!hint) {
            return;
        }
        const prog = progressFor(q);
        const result = applyHintAnswer(hint, index, label, prog);
        if (result.correct) {
            hintProgress = { ...hintProgress, [q.id]: result.progress };
            hintPendingChoice = { ...hintPendingChoice, [q.id]: "" };
            secondsSinceHintComplete = { ...secondsSinceHintComplete, [q.id]: 0 };
            hintCooldown = { ...hintCooldown, [q.id]: 0 };
        } else {
            hintCooldown = startHintWrongCooldown(hintCooldown, q.id);
            hintPendingChoice = { ...hintPendingChoice, [q.id]: "" };
        }
    }

    /** Reveal the main answer + log the attempt, stamping the hint tier reached. */
    async function finalizeQuestion(
        q: PracticeQuestion,
        answer: string,
        correct: boolean,
    ): Promise<void> {
        const level = hintLevelReached(questionHints(q), progressFor(q).revealed);
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
                hintLevelUsed: level,
                assisted: isAssisted(level),
                mainWrongFirst: mainWrongFirst[q.id] ?? false,
            });
        } catch {
            // network error already surfaced by postProto's alert
        }
    }

    async function submitQuestion(q: PracticeQuestion): Promise<void> {
        if (!q || submitted[q.id]) {
            return;
        }
        const prog = progressFor(q);
        // No-skip guard: cannot submit the main question while a revealed hint is
        // still unanswered (the button is also disabled in that state).
        if (!canSubmitMain(selected[q.id] ?? "", prog)) {
            return;
        }
        flushItemTime();
        const answer = selected[q.id] ?? "";
        const correct = answer !== "" && answer === q.correctAnswer;
        const hints = questionHints(q);
        // A WRONG answer does NOT immediately reveal the correct answer: instead
        // it escalates the hint ladder (reveal the next tier) and lets the
        // learner try again — climbing L1→L2→L3 across re-attempts — until they
        // get it right OR the ladder is exhausted, at which point the answer is
        // revealed. Correct answers finalize immediately (still stamped with the
        // hint tier used, so assisted-correct is penalized).
        if (!correct && hasHintLadder(q) && prog.revealed < hints.length) {
            hintNudge = { ...hintNudge, [q.id]: true };
            mainWrongFirst = { ...mainWrongFirst, [q.id]: true };
            revealNextHint(q);
            return;
        }
        if (
            correct
            && shouldUseExplanationGate(sessionId, q.id, explanationAiStatus)
        ) {
            explanationProgress = {
                ...explanationProgress,
                [q.id]: {
                    active: true,
                    failCount: 0,
                    lastFeedback: "",
                    passed: false,
                    checking: false,
                    bypassed: false,
                },
            };
            explanationCoaching = { ...explanationCoaching, [q.id]: "" };
            return;
        }
        await finalizeQuestion(q, answer, correct);
    }

    async function onSubmitExplanation(
        q: PracticeQuestion,
        text: string,
    ): Promise<void> {
        const prog = explanationProgress[q.id] ?? emptyExplanationProgress();
        if (!prog.active || prog.passed || prog.checking) {
            return;
        }
        explanationProgress = {
            ...explanationProgress,
            [q.id]: { ...prog, checking: true },
        };
        const result = await checkExplanation(
            q.stem,
            text,
            correctAnswerText(q),
        );
        if (!result) {
            // Genuine transient failure (network/proxy/AI unavailable): don't
            // trap the learner. They submitted an explanation, so clear the
            // gate (bypassed — not a verified pass) and let them advance.
            explanationProgress = {
                ...explanationProgress,
                [q.id]: explanationBypassedProgress(prog),
            };
            explanationCoaching = { ...explanationCoaching, [q.id]: "" };
            const answer = selected[q.id] ?? "";
            await finalizeQuestion(q, answer, true);
            return;
        }
        if (result.pass) {
            explanationProgress = {
                ...explanationProgress,
                [q.id]: {
                    ...prog,
                    checking: false,
                    passed: true,
                    lastFeedback: result.feedback,
                },
            };
            explanationCoaching = { ...explanationCoaching, [q.id]: "" };
            const answer = selected[q.id] ?? "";
            await finalizeQuestion(q, answer, true);
            return;
        }
        const failCount = prog.failCount + 1;
        explanationProgress = {
            ...explanationProgress,
            [q.id]: {
                ...prog,
                checking: false,
                failCount,
                lastFeedback: result.feedback,
            },
        };
        explanationCoaching = {
            ...explanationCoaching,
            [q.id]: explanationFailureHint(
                failCount,
                hintPromptsFromQuestion(q),
            ),
        };
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
        <div class="question-col" bind:this={questionColEl}>
            {#if current}
                {#each current.questions as q, qi (q.id)}
                    {@const prog = hintProgress[q.id] ?? emptyHintProgress()}
                    {@const hints = questionHints(q)}
                    {@const expProg = explanationProgress[q.id] ?? emptyExplanationProgress()}
                    {@const awaitingExplanation =
                        expProg.active && !expProg.passed && !expProg.bypassed}
                    {@const ladderActive = hasHintLadder(q) && !submitted[q.id] && !awaitingExplanation}
                    {@const hintPassageWc = hintPassageWordCount(current, qi)}
                    {@const showFirstStuck =
                        ladderActive
                        && canShowFirstHintButton(
                            secondsOnQuestion[q.id] ?? 0,
                            q,
                            prog,
                            false,
                            hintPassageWc,
                        )}
                    {@const showNextStuck =
                        ladderActive
                        && canShowNextHintButton(
                            secondsSinceHintComplete[q.id] ?? 0,
                            hints,
                            prog,
                            false,
                        )}
                    <div class="q-block">
                        <div use:questionVisibility={q.id}>
                            <QuestionView
                                question={q}
                                number={questionNumber.get(q.id) ?? qi + 1}
                                selected={selected[q.id] ?? ""}
                                eliminated={eliminated[q.id] ?? []}
                                flagged={flagged[q.id] ?? false}
                                revealed={submitted[q.id] ?? false}
                                disabled={(submitted[q.id] ?? false) || awaitingExplanation}
                                on:select={(e) => onSelect(q, e.detail)}
                                on:eliminate={(e) => onEliminate(q, e.detail)}
                                on:toggleFlag={() => onToggleFlag(q)}
                            >
                                {#snippet actions()}
                                    {#if awaitingExplanation}
                                        <span class="nudge explain-nudge">
                                            Correct — explain your reasoning below to continue.
                                        </span>
                                    {:else if !submitted[q.id]}
                                        {#if showFirstStuck}
                                            <button
                                                class="hint-stuck"
                                                on:click={() => onRequestHint(q)}
                                            >
                                                I'm stuck
                                            </button>
                                        {/if}
                                        {#if showNextStuck}
                                            <button
                                                class="hint-stuck"
                                                on:click={() => onRequestHint(q)}
                                            >
                                                I'm still stuck
                                            </button>
                                        {/if}
                                        {#if hintNudge[q.id] && !canSubmitMain(selected[q.id] ?? "", prog)}
                                            <span class="nudge">
                                                Not quite — work through the hint, then try again.
                                            </span>
                                        {/if}
                                        <button
                                            class="primary"
                                            on:click={() => submitQuestion(q)}
                                            disabled={!canSubmitMain(
                                                selected[q.id] ?? "",
                                                prog,
                                            )}
                                        >
                                            Submit
                                        </button>
                                    {/if}
                                {/snippet}
                            </QuestionView>
                        </div>
                        {#if awaitingExplanation || expProg.bypassed}
                            <ExplanationChat
                                opener={buildUserVisiblePrompt(q.stem)}
                                progress={expProg}
                                coachingHint={explanationCoaching[q.id] ?? ""}
                                on:submit={(e) => void onSubmitExplanation(q, e.detail)}
                            />
                        {/if}
                        {#if ladderActive}
                            <HintLadder
                                hints={hints}
                                progress={prog}
                                pendingChoice={hintPendingChoice[q.id] ?? ""}
                                hintCooldown={hintCooldown[q.id] ?? 0}
                                on:pendingChoiceChange={(e) => {
                                    hintPendingChoice = {
                                        ...hintPendingChoice,
                                        [q.id]: e.detail,
                                    };
                                }}
                                on:answerHint={(e) =>
                                    onAnswerHint(q, e.detail.index, e.detail.label)}
                                on:returnToMain={() => returnToMain(q)}
                            />
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
            <button
                class="secondary"
                on:click={() => goto(index + 1)}
                disabled={currentBlocksNavigation}
                title={currentBlocksNavigation
                    ? "Explain your correct answer before moving on"
                    : undefined}
            >
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
    .q-actions :global(.explain-nudge) {
        color: #2e9e4f;
        font-size: 0.85rem;
        font-weight: 600;
    }
</style>
