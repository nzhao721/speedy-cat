<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<script lang="ts">
    import { createEventDispatcher } from "svelte";

    import { type HelpItem } from "./types";

    export let title: string;
    export let url: string;
    export let linkLabel: string | undefined = undefined;
    export let startIndex = 0;
    export let helpSections: HelpItem[];
    export let fsrs = false;

    export const modalKey: string = Math.random().toString(36).substring(2);

    const dispatch = createEventDispatcher();

    dispatch("mount", { modal: null, carousel: null });
</script>
