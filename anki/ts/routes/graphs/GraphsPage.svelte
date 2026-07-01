<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: the stats route rendered as the app's homepage dashboard. A hero
header and a row of summary "stat cards" (flashcard memory + practice accuracy +
full-length score, all assembled in dashboard.ts) sit on top; MCAT-specific
practice and full-length breakdown panels come next; the (trimmed) flashcard
graphs follow. Every number is deterministic and traces to an existing backend
RPC — see dashboard.ts.
-->
<script lang="ts">
    import { bridgeCommand } from "@tslib/bridgecommand";
    import { onMount } from "svelte";
    import type { Component } from "svelte";
    import { writable } from "svelte/store";

    import { pageTheme } from "$lib/sveltelib/theme";

    import {
        collectionCard,
        fullLengthCard,
        loadFullLengthOverview,
        loadPracticeOverview,
        memoryCard,
        practiceCard,
        studiedTodayCard,
    } from "./dashboard";
    import type { FullLengthOverview, PracticeOverview } from "./dashboard";
    import FullLengthPanel from "./FullLengthPanel.svelte";
    import PracticePanel from "./PracticePanel.svelte";
    import RangeBox from "./RangeBox.svelte";
    import StatCard from "./StatCard.svelte";
    import WithGraphData from "./WithGraphData.svelte";

    export let initialSearch: string;
    export let initialDays: number;

    const search = writable(initialSearch);
    const days = writable(initialDays);

    export let graphs: Component<any>[];
    /** See RangeBox */
    export let controller: Component<any> | null = RangeBox;

    // Practice + full-length figures are independent of the flashcard graph
    // search/date filters, so they load once here. Each traces to an existing
    // PracticeService RPC via dashboard.ts (getTopicStats / getReadiness /
    // listFullLengthTests) — no AI, no new backend calls.
    let practiceOverview: PracticeOverview | null = null;
    let practiceError = false;
    let fullLengthOverview: FullLengthOverview | null = null;
    let fullLengthError = false;

    onMount(() => {
        loadPracticeOverview()
            .then((o) => (practiceOverview = o))
            .catch(() => (practiceError = true));
        loadFullLengthOverview()
            .then((o) => (fullLengthOverview = o))
            .catch(() => (fullLengthError = true));
    });

    function browserSearch(event: CustomEvent) {
        bridgeCommand(`browserSearch: ${$search} ${event.detail.query}`);
    }
</script>

<WithGraphData {search} {days} let:sourceData let:loading let:prefs let:revlogRange>
    <div class="dashboard-head">
        <header class="hero">
            <h1>Your MCAT dashboard</h1>
            <p class="tagline">
                A snapshot of where you stand — flashcard memory, practice
                accuracy and full-length scores — all computed from your own
                study data. No AI, and every score shows its range.
            </p>
        </header>

        <div class="summary-cards">
            {#each [studiedTodayCard(sourceData), memoryCard(sourceData), collectionCard(sourceData), practiceCard(practiceOverview, practiceError), fullLengthCard(fullLengthOverview, fullLengthError)] as card (card.label)}
                <StatCard
                    label={card.label}
                    value={card.value}
                    sub={card.sub}
                    muted={card.muted}
                />
            {/each}
        </div>

        <div class="detail-panels">
            <PracticePanel overview={practiceOverview} error={practiceError} />
            <FullLengthPanel
                overview={fullLengthOverview}
                error={fullLengthError}
            />
        </div>

        <h2 class="section-title">Flashcard statistics</h2>
    </div>

    {#if controller}
        <svelte:component this={controller} {search} {days} {loading} />
    {/if}

    <div class="graphs-container">
        {#if sourceData && revlogRange}
            {#each graphs as graph}
                <svelte:component
                    this={graph}
                    {sourceData}
                    {prefs}
                    {revlogRange}
                    nightMode={$pageTheme.isDark}
                    on:search={browserSearch}
                />
            {/each}
        {/if}
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
    .hero h1 {
        margin: 0 0 0.3rem;
        font-size: 1.9rem;
    }
    .tagline {
        margin: 0;
        max-width: 720px;
        color: var(--fg-subtle);
    }
    .summary-cards {
        display: grid;
        gap: 1rem;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    }
    .detail-panels {
        display: grid;
        gap: 1rem;
        grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
    }
    .section-title {
        margin: 0.5rem 0 0;
        font-size: 1.2rem;
        color: var(--fg);
    }
    .graphs-container {
        display: grid;
        gap: 1em;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        // required on Safari to stretch whole width
        width: calc(100vw - 3em);
        margin-left: 1em;
        margin-right: 1em;

        @media only screen and (max-width: 600px) {
            width: calc(100vw - 1rem);
            margin-left: 0.5rem;
            margin-right: 0.5rem;
        }

        @media only screen and (max-width: 1400px) {
            grid-template-columns: 1fr 1fr;
        }
        @media only screen and (max-width: 1200px) {
            grid-template-columns: 1fr;
        }
        @media only screen and (max-width: 600px) {
            font-size: 12px;
        }

        @media only print {
            // grid layout does not honor page-break-inside
            display: block;
            margin-top: 3em;
        }
    }

    .spacer {
        height: 1.5em;
    }
</style>
