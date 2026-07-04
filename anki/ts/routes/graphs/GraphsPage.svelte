<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the dashboard route (app homepage). Three readiness pillars only;
stock Anki flashcard graphs are not shown here.
-->
<script lang="ts">
    import { writable } from "svelte/store";

    import ReadinessPillars from "./ReadinessPillars.svelte";
    import WithGraphData from "./WithGraphData.svelte";

    // SpeedyCAT: dashboard always covers the whole collection for all time.
    const search = writable("");
    const days = writable(0);
</script>

<WithGraphData {search} {days} let:sourceData>
    <div class="dashboard-head">
        <h1 class="page-title">Your MCAT Dashboard</h1>

        <ReadinessPillars {sourceData} />
    </div>
    <div class="spacer"></div>
</WithGraphData>

<style lang="scss">
    .dashboard-head {
        max-width: 1400px;
        margin: 0 auto;
        padding: 2rem 1em 0.5em;
        display: flex;
        flex-direction: column;
        gap: 1.25rem;
    }
    .page-title {
        margin: 0;
        font-size: 2.1rem;
        font-weight: 800;
        letter-spacing: -0.01em;
        color: var(--fg);
    }

    .spacer {
        height: 1.5em;
    }
</style>
