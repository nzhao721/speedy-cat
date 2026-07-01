<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the reading-passage pane shown beside a CARS / full-length passage
set. The passage text is fetched lazily by the parent (via getCarsPassageSet)
and passed in; this component just renders it in a scrollable column.
-->
<script lang="ts">
    import { sectionShort, type CarsPassageSet } from "./lib";

    export let passageSet: CarsPassageSet | undefined = undefined;
    export let loading = false;

    $: passage = passageSet?.passage;
</script>

<div class="passage-pane">
    {#if loading}
        <div class="placeholder">Loading passage…</div>
    {:else if passage}
        <div class="p-head">
            {#if passage.title}
                <div class="title">{passage.title}</div>
            {/if}
            <div class="meta">
                <span class="badge">{sectionShort(passage.section)}</span>
                {#if passage.discipline}
                    <span class="badge">{passage.discipline}</span>
                {/if}
                {#if passage.wordCount}
                    <span class="wc">{passage.wordCount} words</span>
                {/if}
            </div>
        </div>
        <div class="body">{passage.passage}</div>
        {#if passage.sourceName}
            <div class="source">Source: {passage.sourceName}</div>
        {/if}
    {:else}
        <div class="placeholder">No passage.</div>
    {/if}
</div>

<style lang="scss">
    .passage-pane {
        height: 100%;
        overflow-y: auto;
        padding: 1rem 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
    }
    .p-head {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        border-bottom: 1px solid var(--border-subtle);
        padding-bottom: 0.5rem;
    }
    .title {
        font-weight: 700;
        font-size: 1.1rem;
    }
    .meta {
        display: flex;
        gap: 0.4rem;
        align-items: center;
        flex-wrap: wrap;
    }
    .badge {
        font-size: 0.75rem;
        padding: 0.15rem 0.5rem;
        border-radius: 999px;
        background: var(--canvas-inset);
        border: 1px solid var(--border-subtle);
        color: var(--fg-subtle);
    }
    .wc {
        font-size: 0.75rem;
        color: var(--fg-subtle);
    }
    .body {
        white-space: pre-wrap;
        line-height: 1.65;
        font-size: 1rem;
    }
    .source {
        font-size: 0.78rem;
        color: var(--fg-subtle);
        border-top: 1px solid var(--border-subtle);
        padding-top: 0.5rem;
    }
    .placeholder {
        color: var(--fg-subtle);
        font-style: italic;
    }
</style>
