<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<script lang="ts">
    import CalendarGraph from "./CalendarGraph.svelte";
    import CardCounts from "./CardCounts.svelte";
    import DifficultyGraph from "./DifficultyGraph.svelte";
    import FutureDue from "./FutureDue.svelte";
    import GraphsPage from "./GraphsPage.svelte";
    import HourGraph from "./HourGraph.svelte";
    import IntervalsGraph from "./IntervalsGraph.svelte";
    import RetrievabilityGraph from "./RetrievabilityGraph.svelte";
    import ReviewsGraph from "./ReviewsGraph.svelte";
    import StabilityGraph from "./StabilityGraph.svelte";
    import TrueRetention from "./TrueRetention.svelte";

    // SpeedyCAT: the graphs route is the app homepage/dashboard for a single,
    // read-only MCAT deck (users don't add or author cards). Graphs that only
    // make sense for multi-deck or card-authoring workflows are dropped:
    // TodayStats (now the "Studied today" summary card), AddedGraph (cards are
    // bundled, never hand-added), EaseGraph (legacy SM-2 ease, superseded by the
    // FSRS difficulty graph) and ButtonsGraph. What remains tells the study
    // progress + FSRS memory story for the one MCAT deck.
    const graphs = [
        FutureDue,
        CalendarGraph,
        ReviewsGraph,
        CardCounts,
        IntervalsGraph,
        StabilityGraph,
        DifficultyGraph,
        RetrievabilityGraph,
        TrueRetention,
        HourGraph,
    ];
</script>

<GraphsPage {graphs} />
