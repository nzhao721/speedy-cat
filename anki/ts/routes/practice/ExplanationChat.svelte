<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    import {
        EXPLANATION_INSTRUCTION,
        type ExplanationProgress,
    } from "./explanationGate";

    interface Props {
        progress: ExplanationProgress;
        coachingHint?: string;
        disabled?: boolean;
    }

    const {
        progress,
        coachingHint = "",
        disabled = false,
    }: Props = $props();

    const dispatch = createEventDispatcher<{
        submit: string;
    }>();

    let draft = $state("");

    function feedbackText(): string {
        const lines: string[] = [];
        if (coachingHint.trim()) {
            lines.push(coachingHint);
        }
        if (progress.lastFeedback && !progress.passed && !progress.bypassed) {
            lines.push(progress.lastFeedback);
        }
        if (progress.passed) {
            lines.push("Thanks — your explanation shows you understand why.");
        } else if (progress.bypassed && progress.lastFeedback) {
            lines.push(progress.lastFeedback);
        }
        return lines.filter((line) => line.trim() !== "").join("\n\n");
    }

    const feedback = $derived(feedbackText());
    const feedbackEmphasized = $derived(
        progress.lastFeedback.trim() !== "" && !progress.passed && !progress.bypassed,
    );
    const feedbackSuccess = $derived(progress.passed);

    function onSubmit(): void {
        const text = draft.trim();
        if (!text || progress.checking || disabled) {
            return;
        }
        dispatch("submit", text);
        draft = "";
    }
</script>

<div class="explanation-chat" aria-live="polite">
    <p class="instruction">{EXPLANATION_INSTRUCTION}</p>

    {#if feedback}
        <div
            class="feedback-box"
            class:emphasized={feedbackEmphasized}
            class:success={feedbackSuccess}
            aria-label="Feedback"
        >
            {feedback}
        </div>
    {/if}

    {#if !progress.passed && !progress.bypassed}
        <label class="input-wrap">
            <span class="sr-only">Your explanation</span>
            <textarea
                bind:value={draft}
                rows="4"
                placeholder="Write a few sentences explaining why your answer is correct…"
                disabled={disabled || progress.checking}
            ></textarea>
        </label>
        <div class="actions">
            <button
                class="primary"
                disabled={disabled || progress.checking || draft.trim() === ""}
                on:click={onSubmit}
            >
                {progress.checking ? "Checking…" : "Submit explanation"}
            </button>
        </div>
    {/if}
</div>

<style lang="scss">
    .explanation-chat {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        border: 1px solid var(--border-subtle);
        border-radius: 10px;
        padding: 1rem;
        background: var(--canvas-inset);
    }
    .instruction {
        margin: 0;
        line-height: 1.45;
        font-size: 0.95rem;
        color: var(--fg);
    }
    .feedback-box {
        white-space: pre-wrap;
        line-height: 1.45;
        padding: 0.7rem 0.85rem;
        border-radius: 10px;
        max-width: 100%;
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
    }
    .feedback-box.emphasized {
        background: color-mix(in srgb, #e0a34e 15%, var(--canvas-elevated));
        border-color: #e0a34e;
    }
    .feedback-box.success {
        background: color-mix(in srgb, #2e9e4f 15%, var(--canvas-elevated));
        border-color: #2e9e4f;
    }
    .input-wrap textarea {
        width: 100%;
        box-sizing: border-box;
        border-radius: 8px;
        border: 1px solid var(--border);
        padding: 0.65rem 0.75rem;
        font: inherit;
        line-height: 1.45;
        resize: vertical;
        background: var(--canvas-elevated);
        color: var(--fg);
    }
    .actions {
        display: flex;
        justify-content: flex-end;
    }
    button.primary {
        border-radius: 6px;
        padding: 0.45rem 1rem;
        cursor: pointer;
        font-size: 0.9rem;
        border: 1px solid var(--button-primary-bg);
        background: var(--button-primary-bg);
        color: #fff;
    }
    button.primary:disabled {
        opacity: 0.5;
        cursor: default;
    }
    .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        padding: 0;
        margin: -1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        border: 0;
    }
</style>
