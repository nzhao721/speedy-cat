<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: presentational multiple-choice question. Shared by the Practice
Questions runner (immediate feedback) and the Full-Length exam runner (no
feedback until the test is submitted). All flow control (submit, navigation,
timing) lives in the parent; this component only renders and emits events.
-->
<script lang="ts">
    import { createEventDispatcher, type Snippet } from "svelte";

    import Icon from "$lib/components/Icon.svelte";
    import { mdiUndo } from "$lib/components/icons";

    import { difficultyLabel, sectionShort, type PracticeQuestion } from "./lib";

    interface Props {
        question: PracticeQuestion;
        selected?: string;
        eliminated?: string[];
        /** Reveal correctness + explanation (practice feedback / post-test review). */
        revealed?: boolean;
        /** Prevent changing the answer (locked after submit in practice mode). */
        disabled?: boolean;
        flagged?: boolean;
        number?: number;
        /** Show the eliminate (strike-through) affordance. */
        allowEliminate?: boolean;
        /** Section + difficulty pill badges (hidden during full-length practice). */
        showMetaBadges?: boolean;
        actions?: Snippet;
    }

    const {
        question,
        selected = "",
        eliminated = [],
        revealed = false,
        disabled = false,
        flagged = false,
        number = 0,
        allowEliminate = true,
        showMetaBadges = true,
        actions,
    }: Props = $props();

    const dispatch = createEventDispatcher<{
        select: string;
        eliminate: string;
        toggleFlag: void;
    }>();

    const correctAnswer = $derived(question.correctAnswer);
    const difficulty = $derived(difficultyLabel(question.difficulty));
</script>

<div class="question">
    <div class="q-head">
        <span class="q-num">Question {number}</span>
        <span class="badges">
            {#if showMetaBadges}
                <span class="badge section">{sectionShort(question.section)}</span>
                {#if difficulty}
                    <span class="badge difficulty">{difficulty}</span>
                {/if}
            {/if}
            <button
                class="flag"
                class:on={flagged}
                title="Flag for review"
                on:click={() => dispatch("toggleFlag")}
            >
                {flagged ? "\u2691 Flagged" : "\u2690 Flag"}
            </button>
        </span>
    </div>

    <div class="stem">{question.stem}</div>

    <div class="choices">
        {#each question.choices as choice (choice.label)}
            {@const struck = eliminated.includes(choice.label)}
            <div class="choice-row">
                <button
                    class="choice"
                    class:selected={!revealed && choice.label === selected}
                    class:correct={revealed && choice.label === correctAnswer}
                    class:incorrect={revealed
                        && choice.label === selected
                        && choice.label !== correctAnswer}
                    class:struck
                    disabled={disabled && !revealed}
                    aria-pressed={choice.label === selected}
                    on:click={() => !disabled && dispatch("select", choice.label)}
                >
                    <span class="radio" aria-hidden="true"></span>
                    <span class="label">{choice.label}</span>
                    <span class="text">{choice.text}</span>
                    {#if revealed && choice.label === correctAnswer}
                        <span class="mark">{"\u2713"}</span>
                    {:else if revealed && choice.label === selected}
                        <span class="mark">{"\u2717"}</span>
                    {/if}
                </button>
                {#if allowEliminate && !revealed && !disabled}
                    <button
                        class="elim"
                        class:on={struck}
                        title={struck ? "Restore this choice" : "Eliminate this choice"}
                        on:click={() => dispatch("eliminate", choice.label)}
                    >
                        {#if struck}
                            <Icon icon={mdiUndo} />
                        {:else}
                            {"\u2715"}
                        {/if}
                    </button>
                {/if}
            </div>
        {/each}
    </div>

    {#if actions}
        <div class="q-actions">
            {@render actions()}
        </div>
    {/if}

    {#if revealed}
        <div class="explanation">
            <div class="verdict" class:right={selected === correctAnswer}>
                {#if selected === correctAnswer}
                    Correct
                {:else if selected}
                    Incorrect — correct answer is {correctAnswer}
                {:else}
                    Not answered — correct answer is {correctAnswer}
                {/if}
            </div>
            <div class="exp-text">{question.explanation}</div>
            {#if question.sourceName}
                <div class="source">
                    Source: {question.sourceName}{question.sourceLicense
                        ? ` — ${question.sourceLicense}`
                        : ""}
                </div>
            {/if}
        </div>
    {/if}
</div>

<style lang="scss">
    .question {
        display: flex;
        flex-direction: column;
        gap: 1rem;
    }
    .q-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 0.5rem;
        flex-wrap: wrap;
    }
    .q-num {
        font-weight: 600;
        font-size: 1.05rem;
    }
    .badges {
        display: flex;
        gap: 0.4rem;
        align-items: center;
    }
    .badge {
        font-size: 0.75rem;
        padding: 0.15rem 0.5rem;
        border-radius: 999px;
        background: var(--canvas-inset);
        border: 1px solid var(--border-subtle);
        color: var(--fg-subtle);
    }
    .badge.section {
        color: var(--fg);
    }
    .flag {
        font-size: 0.8rem;
        padding: 0.2rem 0.55rem;
        border-radius: 6px;
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg-subtle);
        cursor: pointer;
    }
    .flag.on {
        color: #b26a00;
        border-color: #e0a34e;
        background: rgba(224, 163, 78, 0.15);
    }
    .stem {
        white-space: pre-wrap;
        line-height: 1.5;
        font-size: 1.05rem;
    }
    .choices {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
    }
    .q-actions {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        flex-wrap: wrap;
    }
    .q-actions :global(.hint-stuck) {
        border-radius: 6px;
        padding: 0.45rem 1rem;
        cursor: pointer;
        font-size: 0.9rem;
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
    }
    .q-actions :global(.nudge) {
        color: #b26a00;
        font-size: 0.85rem;
        font-weight: 600;
    }
    .q-actions :global(button.primary) {
        border-radius: 6px;
        padding: 0.45rem 1rem;
        cursor: pointer;
        font-size: 0.9rem;
        border: 1px solid var(--button-primary-bg);
        background: var(--button-primary-bg);
        color: #fff;
    }
    .q-actions :global(button.primary:disabled) {
        opacity: 0.5;
        cursor: default;
    }
    .choice-row {
        display: flex;
        align-items: stretch;
        gap: 0.4rem;
    }
    .choice {
        flex: 1;
        display: flex;
        align-items: center;
        gap: 0.6rem;
        text-align: left;
        padding: 0.7rem 0.9rem;
        border-radius: 8px;
        // 2px base border on every state so the selected/correct/incorrect
        // colouring only swaps the colour (no layout shift), and reads clearly
        // on the software-rendered Qt webview.
        border: 2px solid var(--border);
        background: var(--canvas-elevated);
        color: var(--fg);
        cursor: pointer;
        line-height: 1.4;
        transition:
            background 0.1s,
            border-color 0.1s;
    }
    .choice:hover:not(:disabled) {
        border-color: var(--border-focus);
    }
    .choice:focus-visible {
        outline: 2px solid var(--border-focus);
        outline-offset: 2px;
    }
    .choice:disabled {
        cursor: default;
    }
    // Pre-submit "picked" state — an UNMISTAKABLE highlight that uses the app's
    // THEME accent (the same `--button-primary-bg` the primary buttons use) for
    // the 2px border + label + radio, and the theme's `--selected-bg` selection
    // tint for the fill. This is the single source of truth reused verbatim by
    // the hint subquestions (HintLadder.svelte) and the full-length runner, so
    // selection colouring tracks the theme (light/dark) everywhere. We use only
    // solid border/background/text (no box-shadow, which does not paint reliably
    // on the software-rendered Qt webview). It is replaced by the
    // correct/incorrect colouring once the answer is revealed (`class:selected`
    // is false when revealed).
    .choice.selected {
        border-color: var(--button-primary-bg);
        background: var(--selected-bg);
        // keep the answer text on the theme foreground (readable in light AND
        // dark); only the leading label letter takes the accent colour.
        color: var(--fg);
        font-weight: 600;
    }
    .choice.selected .label {
        color: var(--button-primary-bg);
        font-weight: 700;
    }
    .choice.selected .text {
        font-weight: 700;
    }
    .choice.correct {
        border-color: #1f9d4d;
        background: rgba(31, 157, 77, 0.28);
        font-weight: 600;
    }
    .choice.incorrect {
        border-color: #d1434b;
        background: rgba(209, 67, 75, 0.28);
        font-weight: 600;
    }
    // Eliminated choice: a single line across the full row (radio + label + text),
    // not just through the answer text.
    .choice.struck {
        position: relative;
        opacity: 0.5;
    }
    .choice.struck::after {
        content: "";
        position: absolute;
        left: 0;
        right: 0;
        top: 50%;
        height: 2px;
        background: currentColor;
        pointer-events: none;
        transform: translateY(-50%);
    }
    // Radio affordance: a hollow circle that fills to show the active state —
    // accent (blue) when picked pre-submit, green/red once revealed.
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
    .choice.selected .radio {
        border-color: var(--button-primary-bg);
        background: var(--button-primary-bg);
    }
    .choice.correct .radio {
        border-color: #1f9d4d;
        background: #1f9d4d;
    }
    .choice.incorrect .radio {
        border-color: #d1434b;
        background: #d1434b;
    }
    .choice.selected .radio::after,
    .choice.correct .radio::after,
    .choice.incorrect .radio::after {
        content: "";
        width: 0.42rem;
        height: 0.42rem;
        border-radius: 50%;
        background: #fff;
    }
    .label {
        font-weight: 700;
        min-width: 1.2rem;
    }
    .text {
        flex: 1;
        white-space: pre-wrap;
    }
    .mark {
        font-weight: 700;
    }
    .elim {
        width: 2.4rem;
        border-radius: 8px;
        border: 1px solid var(--border-subtle);
        background: var(--button-bg);
        color: var(--fg-subtle);
        cursor: pointer;
        font-size: 0.8rem;
        display: inline-flex;
        align-items: center;
        justify-content: center;
    }
    .elim :global(svg) {
        width: 1rem;
        height: 1rem;
        display: block;
    }
    .elim.on {
        color: var(--fg);
    }
    .explanation {
        border-top: 1px solid var(--border-subtle);
        padding-top: 0.8rem;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
    }
    .verdict {
        font-weight: 700;
        color: #d1434b;
    }
    .verdict.right {
        color: #2e9e4f;
    }
    .exp-text {
        white-space: pre-wrap;
        line-height: 1.5;
    }
    .source {
        font-size: 0.8rem;
        color: var(--fg-subtle);
    }
</style>
