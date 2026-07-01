<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: a single summary metric on the stats dashboard (the app homepage).
Purely presentational — the number, its short caption and a "not enough data
yet" muted state are all decided by the caller (see dashboard.ts).
-->
<script lang="ts">
    export let label: string;
    export let value: string;
    export let sub: string;
    export let muted = false;
</script>

<div class="stat-card" class:muted>
    <div class="stat-label">{label}</div>
    <div class="stat-value">{value}</div>
    <div class="stat-sub">{sub}</div>
</div>

<style lang="scss">
    .stat-card {
        background: var(--canvas-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 12px;
        padding: 1rem 1.15rem;
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        min-width: 0;
    }
    .stat-card.muted {
        opacity: 0.9;
    }
    .stat-label {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        color: var(--fg-subtle);
    }
    .stat-value {
        font-size: 1.9rem;
        font-weight: 700;
        line-height: 1.05;
        color: var(--fg);
        font-variant-numeric: tabular-nums;
        overflow-wrap: anywhere;
    }
    .stat-card.muted .stat-value {
        color: var(--fg-subtle);
    }
    .stat-sub {
        font-size: 0.8rem;
        color: var(--fg-subtle);
    }
</style>
