<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<script lang="ts">
    import Icon from "$lib/components/Icon.svelte";
    import IconConstrain from "$lib/components/IconConstrain.svelte";

    import type { SummarizedLogQueues } from "./types";

    export let summary: SummarizedLogQueues;

    $: notes = summary.queues.map((queue) => queue.notes).flat();
</script>

{#if notes.length}
    <li>
        <IconConstrain>
            <Icon icon={summary.icon} />
        </IconConstrain>
        {summary.summaryTemplate({ count: notes.length })}
    </li>
{/if}

<style lang="scss">
    li {
        list-style-type: none;
    }
</style>
