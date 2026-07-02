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
    import { createEventDispatcher } from "svelte";

    import { difficultyLabel, sectionShort, type PracticeQuestion } from "./lib";

    export let question: PracticeQuestion;
    export let selected = "";
    export let eliminated: string[] = [];
    /** Reveal correctness + explanation (practice feedback / post-test review). */
    export let revealed = false;
    /** Prevent changing the answer (locked after submit in practice mode). */
    export let disabled = false;
    export let flagged = false;
    export let number = 0;
    /** Show the eliminate (strike-through) affordance. */
    export let allowEliminate = true;

    const dispatch = createEventDispatcher<{
        select: string;
        eliminate: string;
        toggleFlag: void;
    }>();

    $: correctAnswer = question.correctAnswer;
    $: difficulty = difficultyLabel(question.difficulty);

    function choiceClass(label: string): string {
        if (revealed) {
            if (label === correctAnswer) {
                return "choice correct";
            }
            if (label === selected && label !== correctAnswer) {
                return "choice incorrect";
            }
            return "choice";
        }
        return label === selected ? "choice selected" : "choice";
    }
</script>

<div class="question">
    <div class="q-head">
        <span class="q-num">Question {number}</span>
        <span class="badges">
            <span class="badge section">{sectionShort(question.section)}</span>
            {#if difficulty}
                <span class="badge difficulty">{difficulty}</span>
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
                    class={choiceClass(choice.label)}
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
                        title="Eliminate this choice"
                        on:click={() => dispatch("eliminate", choice.label)}
                    >
                        {struck ? "undo" : "\u2715"}
                    </button>
                {/if}
            </div>
        {/each}
    </div>

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
    // Pre-submit "picked" state — an UNMISTAKABLE highlight: strong accent
    // (brand blue) 2px border + a solid-reading accent fill + bold text + a
    // filled radio. The previous version leaned on `box-shadow: inset` for the
    // ring, which does not paint reliably on the software-rendered Qt webview,
    // and a very faint 0.18 tint, so the selection looked unstyled. We use only
    // solid border/background/text here (no box-shadow) so it always renders,
    // and a hue (blue) distinct from the post-submit green/red below. This is
    // replaced by the correct/incorrect colouring once the answer is revealed
    // (choiceClass drops `selected` when revealed).
    .choice.selected {
        border-color: #2f6fed;
        background: rgba(47, 111, 237, 0.3);
        // keep the answer text on the theme foreground (readable in light AND
        // dark); only the leading label letter takes the accent colour.
        color: var(--fg);
        font-weight: 600;
    }
    .choice.selected .label {
        color: #2f6fed;
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
    .choice.struck .text {
        text-decoration: line-through;
        opacity: 0.5;
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
        border-color: #2f6fed;
        background: #2f6fed;
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
