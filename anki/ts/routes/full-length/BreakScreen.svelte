<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: a scheduled full-length break. Shows its own countdown; an optional
break can be skipped early, and every break auto-advances when the timer
reaches zero. Mirrors the real MCAT's scheduled breaks between sections.
-->
<script lang="ts">
    import { createEventDispatcher, onDestroy, onMount } from "svelte";

    import { formatClock, type FullLengthBreak } from "../practice/lib";

    export let breakInfo: FullLengthBreak;
    export let nextSectionLabel = "";

    const dispatch = createEventDispatcher<{ done: void }>();

    let remaining = breakInfo.durationSeconds;
    let ticker: ReturnType<typeof setInterval> | undefined;
    let done = false;

    onMount(() => {
        ticker = setInterval(() => {
            remaining -= 1;
            if (remaining <= 0) {
                end();
            }
        }, 1000);
    });
    onDestroy(() => {
        if (ticker) {
            clearInterval(ticker);
        }
    });

    function end(): void {
        if (done) {
            return;
        }
        done = true;
        if (ticker) {
            clearInterval(ticker);
            ticker = undefined;
        }
        dispatch("done");
    }
</script>

<div class="break">
    <div class="card">
        <div class="label">{breakInfo.label || "Break"}</div>
        <div class="clock">{formatClock(remaining)}</div>
        <p class="hint">
            {#if breakInfo.optional}
                This break is optional — you can skip it when you're ready.
            {:else}
                Your next section will begin automatically when the break ends.
            {/if}
        </p>
        {#if nextSectionLabel}
            <p class="next">Up next: {nextSectionLabel}</p>
        {/if}
        {#if breakInfo.optional}
            <button class="primary" on:click={end}>Skip break &amp; continue</button>
        {:else}
            <button class="secondary" on:click={end}>End break early</button>
        {/if}
    </div>
</div>

<style lang="scss">
    .break {
        height: 100vh;
        display: flex;
        align-items: center;
        justify-content: center;
        background: var(--canvas);
    }
    .card {
        text-align: center;
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 14px;
        padding: 3rem 3.5rem;
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        align-items: center;
    }
    .label {
        font-size: 1.2rem;
        font-weight: 600;
        color: var(--fg-subtle);
    }
    .clock {
        font-size: 3.5rem;
        font-weight: 700;
        font-variant-numeric: tabular-nums;
    }
    .hint {
        color: var(--fg-subtle);
        margin: 0;
        max-width: 320px;
    }
    .next {
        margin: 0;
        font-weight: 600;
    }
    button {
        margin-top: 0.5rem;
        border-radius: 6px;
        padding: 0.6rem 1.6rem;
        cursor: pointer;
        font-size: 0.95rem;
    }
    button.primary {
        border: 1px solid var(--button-primary-bg);
        background: var(--button-primary-bg);
        color: #fff;
    }
    button.secondary {
        border: 1px solid var(--border);
        background: var(--button-bg);
        color: var(--fg);
    }
</style>
