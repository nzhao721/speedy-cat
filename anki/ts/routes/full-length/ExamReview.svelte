<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: post-test review — same exam UI with answers revealed, free navigation
across all sections, no timer, no scoring changes.
-->
<script lang="ts">
    import { createEventDispatcher, onMount } from "svelte";

    import PassagePanel from "../practice/PassagePanel.svelte";
    import QuestionView from "../practice/QuestionView.svelte";
    import {
        fetchFullLengthReview,
        fetchPassageSet,
        sectionLong,
        type FullLengthReviewItem,
        type GetFullLengthReviewResponse,
        type McatSection,
        type PracticeQuestion,
    } from "../practice/lib";

    export let attemptId: string;

    const dispatch = createEventDispatcher<{ done: void }>();

    let loading = true;
    let review: GetFullLengthReviewResponse | undefined;
    let sectionOrder: McatSection[] = [];
    let sectionIndex = 0;
    let index = 0;
    let passageCache: Record<string, Awaited<ReturnType<typeof fetchPassageSet>>> = {};
    let passageLoading = false;

    $: items = review?.items ?? [];
    $: currentSection = sectionOrder[sectionIndex];
    $: sectionItems = items.filter(
        (item) => item.question?.section === currentSection,
    );
    $: ordered = sectionItems
        .map((item) => item.question)
        .filter((q): q is PracticeQuestion => !!q);
    $: current = ordered[index];
    $: currentItem = sectionItems[index] as FullLengthReviewItem | undefined;

    onMount(async () => {
        try {
            review = await fetchFullLengthReview(attemptId);
            const sections = review.test?.sections ?? [];
            sectionOrder = sections
                .slice()
                .sort((a, b) => a.order - b.order)
                .map((s) => s.section);
            if (sectionOrder.length === 0 && items.length > 0) {
                sectionOrder = [...new Set(items.map((i) => i.question?.section).filter(
                    (s): s is McatSection => s !== undefined,
                ))];
            }
            void ensurePassage(ordered[index]);
        } finally {
            loading = false;
        }
    });

    async function ensurePassage(q: PracticeQuestion | undefined): Promise<void> {
        if (!q?.passageId || passageCache[q.passageId]) {
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

    function selectSection(newSectionIndex: number): void {
        if (newSectionIndex < 0 || newSectionIndex >= sectionOrder.length) {
            return;
        }
        sectionIndex = newSectionIndex;
        index = 0;
        void ensurePassage(ordered[0]);
    }

    function goto(newIndex: number): void {
        if (newIndex < 0 || newIndex >= ordered.length || newIndex === index) {
            return;
        }
        index = newIndex;
        void ensurePassage(ordered[index]);
    }
</script>

<div class="review">
    <div class="topbar">
        <div class="progress">
            <span class="sec">Review mode</span>
            <span class="name">{sectionLong(currentSection)}</span>
        </div>
        <button class="finish" on:click={() => dispatch("done")}>Done</button>
    </div>

    {#if loading}
        <div class="center">Loading review…</div>
    {:else if ordered.length === 0}
        <div class="center">No questions to review.</div>
    {:else}
        <div class="section-tabs">
            {#each sectionOrder as sec, i (sec)}
                <button
                    class="tab"
                    class:active={i === sectionIndex}
                    on:click={() => selectSection(i)}
                >
                    {sectionLong(sec)}
                </button>
            {/each}
        </div>

        <div class="nav-strip">
            {#each ordered as q, i (q.id)}
                <button
                    class="nav-dot"
                    class:correct={sectionItems[i]?.correct}
                    class:wrong={sectionItems[i] && !sectionItems[i].correct}
                    class:current={i === index}
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
                {#if current && currentItem}
                    <div class="answer-banner" class:correct={currentItem.correct}>
                        Your answer: {currentItem.selectedAnswer || "—"}
                        · Correct: {current.correctAnswer}
                    </div>
                    <QuestionView
                        question={current}
                        number={index + 1}
                        selected={currentItem.selectedAnswer}
                        eliminated={[]}
                        flagged={false}
                        revealed={true}
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
            <span class="counter">{index + 1} / {ordered.length}</span>
            <button
                class="secondary"
                on:click={() => goto(index + 1)}
                disabled={index >= ordered.length - 1}
            >
                Next
            </button>
        </div>
    {/if}
</div>

<style lang="scss">
    .review {
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
    .finish {
        margin-left: auto;
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
        border-radius: 6px;
        padding: 0.4rem 0.8rem;
        cursor: pointer;
    }
    .section-tabs {
        display: flex;
        gap: 0.4rem;
        padding: 0.5rem 1rem;
        overflow-x: auto;
        border-bottom: 1px solid var(--border-subtle);
    }
    .tab {
        border: 1px solid var(--border);
        background: var(--canvas-elevated);
        border-radius: 6px;
        padding: 0.35rem 0.75rem;
        cursor: pointer;
        font-size: 0.82rem;
        white-space: nowrap;
    }
    .tab.active {
        border-color: var(--border-focus);
        background: var(--canvas-inset);
        font-weight: 600;
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
    .nav-dot.correct {
        border-color: #3a9d5c;
        background: rgba(58, 157, 92, 0.12);
    }
    .nav-dot.wrong {
        border-color: #d1434b;
        background: rgba(209, 67, 75, 0.12);
    }
    .nav-dot.current {
        outline: 2px solid var(--border-focus);
        outline-offset: 1px;
        color: var(--fg);
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
    .answer-banner {
        margin-bottom: 1rem;
        padding: 0.6rem 0.85rem;
        border-radius: 8px;
        font-size: 0.88rem;
        background: rgba(209, 67, 75, 0.1);
        border: 1px solid rgba(209, 67, 75, 0.35);
    }
    .answer-banner.correct {
        background: rgba(58, 157, 92, 0.1);
        border-color: rgba(58, 157, 92, 0.35);
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
        align-items: center;
        justify-content: center;
        color: var(--fg-subtle);
    }
    button.secondary {
        border-radius: 6px;
        padding: 0.5rem 1.4rem;
        cursor: pointer;
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
    }
    button:disabled {
        opacity: 0.5;
        cursor: default;
    }
</style>
