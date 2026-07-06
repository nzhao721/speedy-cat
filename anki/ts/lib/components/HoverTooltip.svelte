<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<script lang="ts">
    let className = "";
    export { className as class };
    export let text: string;
    export let placement: "top" | "bottom" = "top";

    const tooltipId = `hover-tooltip-${Math.random().toString(36).slice(2, 11)}`;
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<span
    class="hover-tooltip {className}"
    class:placement-top={placement === "top"}
    class:placement-bottom={placement === "bottom"}
    tabindex="0"
    aria-describedby={tooltipId}
>
    <slot />
    <span id={tooltipId} role="tooltip" class="hover-tooltip-text">{text}</span>
</span>

<style lang="scss">
    .hover-tooltip {
        position: relative;
        display: inline-flex;
        align-items: center;
        vertical-align: inherit;
        cursor: help;

        .hover-tooltip-text {
            position: absolute;
            left: 50%;
            transform: translateX(-50%);
            z-index: 20;
            visibility: hidden;
            opacity: 0;
            pointer-events: none;
            width: max-content;
            max-width: 220px;
            white-space: normal;
            text-align: center;
            padding: 0.45rem 0.65rem;
            font-size: 0.78rem;
            line-height: 1.35;
            font-weight: normal;
            font-variant-numeric: normal;
            color: var(--fg);
            background: var(--canvas-elevated);
            border: 1px solid var(--border-subtle);
            border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
            transition:
                opacity 0.15s ease,
                visibility 0.15s ease;
        }

        &.placement-top .hover-tooltip-text {
            bottom: calc(100% + 6px);
        }

        &.placement-bottom .hover-tooltip-text {
            top: calc(100% + 6px);
        }

        &:hover .hover-tooltip-text,
        &:focus .hover-tooltip-text,
        &:focus-within .hover-tooltip-text,
        &:focus-visible .hover-tooltip-text {
            visibility: visible;
            opacity: 1;
        }

        &:focus {
            outline: none;
        }

        &:focus-visible {
            outline: 2px solid var(--border-focus, var(--button-primary-bg, #4c6ef5));
            outline-offset: 2px;
            border-radius: 4px;
        }
    }
</style>
