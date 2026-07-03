<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the graduated hint ladder for a practice question. Each hint is a
self-contained 4-choice SUBQUESTION that scaffolds toward the main question
without revealing its answer. The learner works through them ONE AT A TIME:
they MUST answer the currently-revealed subquestion (no skip / no
next-without-answering) before revealing the next tier or re-answering the main
question. Presentational only — the parent (PracticeRunner) owns the progress
state, the trigger paths (Request-hint button + wrong-answer escalation) and the
assisted/hint_level_used tracking.
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    import { hintAnswerCorrect, pendingHintIndex, type HintProgress, type HintSubquestion } from "./lib";

    export let hints: HintSubquestion[] = [];
    export let progress: HintProgress = { revealed: 0, picks: {} };
    /** Hide the interactive affordances (main question already answered). */
    export let locked = false;

    const dispatch = createEventDispatcher<{
        requestHint: void;
        answerHint: { index: number; label: string };
    }>();

    // Tentative selection for the current (not-yet-answered) subquestion, before
    // the learner commits it. Answered subquestions read their locked pick from
    // `progress.picks`.
    let choice: Record<number, string> = {};

    $: pending = pendingHintIndex(progress);
    $: allRevealed = progress.revealed >= hints.length;
    $: canRevealNext = !locked && progress.revealed < hints.length && pending === -1;

    function subClass(index: number, label: string): string {
        const answered = progress.picks[index];
        if (answered !== undefined) {
            const correct = hints[index]?.correctAnswer;
            if (label === correct) {
                return "sub-choice correct";
            }
            if (label === answered && label !== correct) {
                return "sub-choice incorrect";
            }
            return "sub-choice";
        }
        return label === choice[index] ? "sub-choice selected" : "sub-choice";
    }
</script>

<div class="hint-ladder">
    {#if progress.revealed === 0}
        <div class="ladder-intro">
            <span class="intro-text">Stuck? Work through a guided hint before answering.</span>
            <button class="hint-btn" disabled={locked} on:click={() => dispatch("requestHint")}>
                {"\u{1F4A1}"} Request a hint
            </button>
        </div>
    {:else}
        <div class="ladder-head">Guided hints</div>
        {#each hints.slice(0, progress.revealed) as hint, i (i)}
            {@const answered = progress.picks[i] !== undefined}
            <div class="sub" class:answered>
                <div class="sub-head">
                    <span class="sub-num">Hint {i + 1}</span>
                    <span class="sub-level">Level {hint.level || i + 1}</span>
                </div>
                <div class="sub-prompt">{hint.prompt}</div>
                <div class="sub-choices">
                    {#each hint.choices as c (c.label)}
                        <button
                            class={subClass(i, c.label)}
                            disabled={answered || locked || i !== progress.revealed - 1}
                            aria-pressed={c.label === (progress.picks[i] ?? choice[i])}
                            on:click={() => {
                                if (!answered && i === progress.revealed - 1) {
                                    choice = { ...choice, [i]: c.label };
                                }
                            }}
                        >
                            <span class="sub-label">{c.label}</span>
                            <span class="sub-text">{c.text}</span>
                            {#if answered && c.label === hint.correctAnswer}
                                <span class="sub-mark">{"\u2713"}</span>
                            {:else if answered && c.label === progress.picks[i]}
                                <span class="sub-mark">{"\u2717"}</span>
                            {/if}
                        </button>
                    {/each}
                </div>
                {#if answered}
                    <div
                        class="sub-verdict"
                        class:right={hintAnswerCorrect(hint, progress.picks[i] ?? "")}
                    >
                        {hintAnswerCorrect(hint, progress.picks[i] ?? "")
                            ? "Correct"
                            : `Not quite — the answer to this hint is ${hint.correctAnswer}`}
                    </div>
                    {#if hint.rationale}
                        <div class="sub-rationale">{hint.rationale}</div>
                    {/if}
                {:else if !locked}
                    <div class="sub-actions">
                        <button
                            class="hint-btn primary"
                            disabled={!choice[i]}
                            on:click={() =>
                                choice[i] && dispatch("answerHint", { index: i, label: choice[i] })}
                        >
                            Submit hint answer
                        </button>
                        <span class="sub-note">Answer this hint to continue.</span>
                    </div>
                {/if}
            </div>
        {/each}

        {#if canRevealNext}
            <button class="hint-btn" on:click={() => dispatch("requestHint")}>
                {"\u{1F4A1}"} Reveal next hint
            </button>
        {:else if allRevealed && pending === -1 && !locked}
            <div class="ladder-done">
                You've worked through all the hints — now answer the question above.
            </div>
        {/if}
    {/if}
</div>

<style lang="scss">
    .hint-ladder {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        padding: 0.85rem 1rem;
        border: 1px solid var(--border-subtle);
        border-radius: 10px;
        background: var(--canvas-inset);
    }
    .ladder-intro {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        flex-wrap: wrap;
    }
    .intro-text {
        color: var(--fg-subtle);
        font-size: 0.9rem;
    }
    .ladder-head {
        font-weight: 700;
        font-size: 0.95rem;
    }
    .sub {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        padding: 0.7rem 0.8rem;
        border-radius: 8px;
        border: 1px solid var(--border-subtle);
        background: var(--canvas-elevated);
    }
    .sub-head {
        display: flex;
        gap: 0.5rem;
        align-items: center;
    }
    .sub-num {
        font-weight: 700;
    }
    .sub-level {
        font-size: 0.72rem;
        padding: 0.1rem 0.45rem;
        border-radius: 999px;
        background: var(--canvas-inset);
        border: 1px solid var(--border-subtle);
        color: var(--fg-subtle);
    }
    .sub-prompt {
        white-space: pre-wrap;
        line-height: 1.45;
    }
    .sub-choices {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
    }
    .sub-choice {
        display: flex;
        align-items: center;
        gap: 0.55rem;
        text-align: left;
        padding: 0.5rem 0.7rem;
        border-radius: 7px;
        border: 2px solid var(--border);
        background: var(--canvas);
        color: var(--fg);
        cursor: pointer;
        line-height: 1.35;
    }
    .sub-choice:disabled {
        cursor: default;
    }
    .sub-choice.selected {
        border-color: #2f6fed;
        background: rgba(47, 111, 237, 0.28);
        font-weight: 600;
    }
    .sub-choice.correct {
        border-color: #1f9d4d;
        background: rgba(31, 157, 77, 0.26);
        font-weight: 600;
    }
    .sub-choice.incorrect {
        border-color: #d1434b;
        background: rgba(209, 67, 75, 0.26);
        font-weight: 600;
    }
    .sub-label {
        font-weight: 700;
        min-width: 1.1rem;
    }
    .sub-text {
        flex: 1;
        white-space: pre-wrap;
    }
    .sub-mark {
        font-weight: 700;
    }
    .sub-verdict {
        font-weight: 700;
        color: #d1434b;
        font-size: 0.9rem;
    }
    .sub-verdict.right {
        color: #2e9e4f;
    }
    .sub-rationale {
        white-space: pre-wrap;
        line-height: 1.45;
        color: var(--fg-subtle);
        font-size: 0.9rem;
    }
    .sub-actions {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        flex-wrap: wrap;
    }
    .sub-note {
        color: var(--fg-subtle);
        font-size: 0.8rem;
    }
    .ladder-done {
        color: var(--fg-subtle);
        font-size: 0.88rem;
        font-style: italic;
    }
    .hint-btn {
        align-self: flex-start;
        border-radius: 6px;
        padding: 0.45rem 1rem;
        cursor: pointer;
        font-size: 0.9rem;
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
    }
    .hint-btn.primary {
        background: var(--button-primary-bg);
        border-color: var(--button-primary-bg);
        color: #fff;
    }
    .hint-btn:disabled {
        opacity: 0.5;
        cursor: default;
    }
</style>
