<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the graduated hint ladder for a practice question. Each hint is a
self-contained 4-choice SUBQUESTION that scaffolds toward the main question
without revealing its answer. The learner works through them ONE AT A TIME:
they MUST answer the currently-revealed subquestion correctly (no skip / no
next-without-answering) before revealing the next tier or re-answering the main
question. Presentational only — the parent (PracticeRunner) owns the progress
state, the timed "I'm stuck" / "I'm still stuck" triggers on the main question,
and the assisted/hint_level_used tracking.
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    import {
        canReturnToMain,
        canSubmitHintAnswer,
        hintChoicesEnabled,
        hintDisplayOrder,
        pendingHintIndex,
        type HintProgress,
        type HintSubquestion,
    } from "./lib";

    export let hints: HintSubquestion[] = [];
    export let progress: HintProgress = { revealed: 0, picks: {} };
    /** Tentative selection for the current (not-yet-answered) subquestion. */
    export let pendingChoice = "";
    /** Hide the interactive affordances (main question already answered). */
    export let locked = false;
    /** Seconds remaining before a wrong hint answer can be resubmitted. */
    export let hintCooldown = 0;

    const dispatch = createEventDispatcher<{
        pendingChoiceChange: string;
        answerHint: { index: number; label: string };
        returnToMain: void;
    }>();

    $: pending = pendingHintIndex(progress);
    $: allRevealed = progress.revealed >= hints.length;
    $: canSubmit =
        pending >= 0
        && canSubmitHintAnswer(pendingChoice, hintCooldown);
</script>

{#if progress.revealed > 0}
<div class="hint-ladder">
        <div class="ladder-head">Guided hints</div>
        <!-- Most-recently-revealed tier first (reverse of progression order): the
             ladder still advances L1 -> L2 -> L3 with no skipping, only the
             DISPLAY is reversed so the newest hint sits directly under the main
             question. `i` remains each tier's true ladder index. -->
        {#each hintDisplayOrder(progress.revealed) as i (i)}
            {@const hint = hints[i]}
            {#if hint}
                {@const answered = progress.picks[i] !== undefined}
                <div class="sub" class:answered>
                    <div class="sub-head">
                        <span class="sub-num">Hint {i + 1}</span>
                        <span class="sub-level">Level {hint.level || i + 1}</span>
                    </div>
                    <div class="sub-prompt">{hint.prompt}</div>
                    <div class="sub-choices">
                        {#each hint.choices as c (c.label)}
                            {@const isCurrent = pending === i}
                            {@const picked = progress.picks[i] ?? (isCurrent ? pendingChoice : "")}
                            {@const choicesEnabled = hintChoicesEnabled(
                                answered,
                                locked,
                                isCurrent,
                                hintCooldown,
                            )}
                            {@const isSelected =
                                !answered && isCurrent && c.label === pendingChoice}
                            <button
                                class="sub-choice"
                                class:selected={isSelected}
                                class:correct={answered && c.label === hint.correctAnswer}
                                disabled={!choicesEnabled}
                                aria-pressed={c.label === picked}
                                on:click={() => {
                                    if (choicesEnabled) {
                                        dispatch("pendingChoiceChange", c.label);
                                    }
                                }}
                            >
                                <span class="radio" aria-hidden="true"></span>
                                <span class="sub-label">{c.label}</span>
                                <span class="sub-text">{c.text}</span>
                                {#if answered && c.label === hint.correctAnswer}
                                    <span class="sub-mark">{"\u2713"}</span>
                                {/if}
                            </button>
                        {/each}
                    </div>
                    {#if answered}
                        <div class="sub-verdict right">Correct</div>
                        {#if hint.rationale}
                            <div class="sub-rationale">{hint.rationale}</div>
                        {/if}
                        {#if canReturnToMain(progress, i, locked)}
                            <div class="sub-actions">
                                <button
                                    class="hint-btn primary return-main"
                                    on:click={() => dispatch("returnToMain")}
                                >
                                    Return to main question
                                </button>
                            </div>
                        {/if}
                    {:else if !locked && pending === i}
                        <div class="sub-actions">
                            <button
                                class="hint-btn primary"
                                disabled={!canSubmit}
                                on:click={() =>
                                    pendingChoice
                                    && dispatch("answerHint", { index: i, label: pendingChoice })}
                            >
                                Submit hint answer
                            </button>
                            {#if hintCooldown > 0}
                                <span class="sub-note cooldown">
                                    Not quite — try again in {hintCooldown}s.
                                </span>
                            {:else}
                                <span class="sub-note">Answer this hint to continue.</span>
                            {/if}
                        </div>
                    {/if}
                </div>
            {/if}
        {/each}

        {#if allRevealed && pending === -1 && !locked}
            <div class="ladder-done">
                You've worked through all the hints — now answer the question above.
            </div>
        {/if}
</div>
{/if}

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
        gap: 0.5rem;
    }
    .sub-choice {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        text-align: left;
        padding: 0.7rem 0.9rem;
        border-radius: 8px;
        // 2px base border on every state so the selected/correct colouring only
        // swaps the colour (no layout shift), and reads clearly on the
        // software-rendered Qt webview.
        border: 2px solid var(--border);
        background: var(--canvas-elevated);
        color: var(--fg);
        cursor: pointer;
        line-height: 1.4;
        appearance: none;
        transition:
            background 0.1s,
            border-color 0.1s;
    }
    .sub-choice:hover:not(:disabled) {
        border-color: var(--border-focus);
    }
    .sub-choice:focus-visible {
        outline: 2px solid var(--border-focus);
        outline-offset: 2px;
    }
    .sub-choice:disabled {
        cursor: default;
        // Do not let the UA or ancestor `button:disabled { opacity: … }` wash
        // out the pre-submit selected highlight on the active hint tier.
        opacity: 1;
    }
    // Pre-submit "picked" state — IDENTICAL to the main question's
    // `.choice.selected` (QuestionView.svelte): the theme accent
    // (`--button-primary-bg`) border + label + radio and the theme's
    // `--selected-bg` selection tint for the fill.
    .sub-choice.selected {
        border-color: var(--button-primary-bg);
        background: var(--selected-bg);
        color: var(--fg);
        font-weight: 600;
    }
    .sub-choice.selected .sub-label {
        color: var(--button-primary-bg);
        font-weight: 700;
    }
    .sub-choice.selected .sub-text {
        font-weight: 700;
    }
    .sub-choice.selected .radio {
        border-color: var(--button-primary-bg);
        background: var(--button-primary-bg);
    }
    .sub-choice.correct .radio {
        border-color: #1f9d4d;
        background: #1f9d4d;
    }
    .radio {
        flex: 0 0 auto;
        width: 1.15rem;
        height: 1.15rem;
        border-radius: 50%;
        border: 2px solid var(--border-strong, var(--border));
        background: transparent;
        display: inline-flex;
        align-items: center;
        justify-content: center;
    }
    .sub-choice.selected .radio::after,
    .sub-choice.correct .radio::after {
        content: "";
        width: 0.42rem;
        height: 0.42rem;
        border-radius: 50%;
        background: #fff;
    }
    .sub-choice.correct {
        border-color: #1f9d4d;
        background: rgba(31, 157, 77, 0.26);
        font-weight: 600;
    }
    .sub-label {
        font-weight: 700;
        min-width: 1.2rem;
    }
    .sub-text {
        flex: 1;
        white-space: pre-wrap;
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
    .sub-note.cooldown {
        color: #b26a00;
        font-weight: 600;
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
