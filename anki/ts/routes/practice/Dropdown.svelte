<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: a small in-page dropdown for the Practice setup filters. It renders
its option list as a normal absolutely-positioned element (sized to its
content) instead of a native <select> popup — the native popup renders badly in
the desktop webview (software rendering leaves a growing strip of empty white
space under the options). Supports single-select (choose + close) and
multi-select (toggle checkboxes, stays open). Selection is array-based for both
modes; the parent owns the state and updates it from the `change` event.
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    type Value = number | string;

    export let options: { value: Value; label: string }[] = [];
    export let selected: Value[] = [];
    export let multiple = false;
    /** Shown on the toggle when nothing is selected (e.g. "All sections"). */
    export let placeholder = "";
    export let ariaLabel: string | undefined = undefined;
    export let disabled = false;

    const dispatch = createEventDispatcher<{ change: { selected: Value[] } }>();

    let open = false;
    let root: HTMLElement;

    $: selectedSet = new Set(selected);
    $: summary = computeSummary(selected, options, multiple, placeholder);

    function computeSummary(
        sel: Value[],
        opts: { value: Value; label: string }[],
        multi: boolean,
        ph: string,
    ): string {
        if (sel.length === 0) {
            return ph;
        }
        if (!multi || sel.length === 1) {
            return opts.find((o) => o.value === sel[0])?.label ?? ph;
        }
        return `${sel.length} selected`;
    }

    function toggleOpen(): void {
        if (!disabled) {
            open = !open;
        }
    }

    function close(): void {
        open = false;
    }

    function choose(value: Value): void {
        if (multiple) {
            const next = selectedSet.has(value)
                ? selected.filter((v) => v !== value)
                : [...selected, value];
            dispatch("change", { selected: next });
        } else {
            dispatch("change", { selected: [value] });
            close();
        }
    }

    function onWindowClick(event: MouseEvent): void {
        if (open && root && !root.contains(event.target as Node)) {
            close();
        }
    }

    function onKeydown(event: KeyboardEvent): void {
        if (event.key === "Escape" && open) {
            event.stopPropagation();
            close();
        }
    }
</script>

<svelte:window on:click={onWindowClick} />

<div class="dd" class:open bind:this={root}>
    <button
        type="button"
        class="dd-toggle"
        class:disabled
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        {disabled}
        on:click|stopPropagation={toggleOpen}
        on:keydown={onKeydown}
    >
        <span class="dd-summary" class:placeholder={selected.length === 0}>
            {summary}
        </span>
        <span class="dd-chevron" aria-hidden="true">▾</span>
    </button>

    {#if open}
        <div class="dd-menu" role="listbox" aria-multiselectable={multiple}>
            {#each options as opt (opt.value)}
                {@const isSel = selectedSet.has(opt.value)}
                <button
                    type="button"
                    class="dd-option"
                    class:selected={isSel}
                    role="option"
                    aria-selected={isSel}
                    on:click|stopPropagation={() => choose(opt.value)}
                >
                    {#if multiple}
                        <span class="dd-check" class:on={isSel} aria-hidden="true">
                            {isSel ? "\u2713" : ""}
                        </span>
                    {/if}
                    <span class="dd-label">{opt.label}</span>
                </button>
            {/each}
        </div>
    {/if}
</div>

<style lang="scss">
    .dd {
        position: relative;
    }
    .dd-toggle {
        width: 100%;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem;
        padding: 0.5rem;
        border-radius: 6px;
        border: 1px solid var(--border);
        background: var(--canvas-inset);
        color: var(--fg);
        cursor: pointer;
        font-size: 0.9rem;
        text-align: left;
    }
    .dd-toggle.disabled {
        opacity: 0.6;
        cursor: default;
    }
    .dd-summary {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
    .dd-summary.placeholder {
        color: var(--fg-subtle);
    }
    .dd-chevron {
        flex: 0 0 auto;
        color: var(--fg-subtle);
        font-size: 0.7rem;
    }
    // The menu is a plain in-page element that sizes to its content (a flex
    // column of options capped by max-height), so there is never any extra
    // empty space below the options.
    .dd-menu {
        position: absolute;
        top: calc(100% + 2px);
        left: 0;
        right: 0;
        z-index: 30;
        max-height: 260px;
        overflow-y: auto;
        display: flex;
        flex-direction: column;
        gap: 1px;
        padding: 0.25rem;
        background: var(--canvas-elevated);
        border: 1px solid var(--border);
        border-radius: 6px;
        box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25);
    }
    .dd-option {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        width: 100%;
        padding: 0.4rem 0.5rem;
        border: none;
        border-radius: 4px;
        background: transparent;
        color: var(--fg);
        cursor: pointer;
        text-align: left;
        font-size: 0.9rem;
    }
    .dd-option:hover {
        background: var(--canvas-inset);
    }
    .dd-option.selected {
        background: var(--selected-bg, rgba(80, 130, 240, 0.12));
    }
    .dd-check {
        flex: 0 0 auto;
        width: 1rem;
        font-weight: 700;
        color: var(--border-focus);
    }
    .dd-label {
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
</style>
