<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<script lang="ts">
    import * as tr from "@generated/ftl";
    import { HelpPage } from "@tslib/help-page";
    import type Carousel from "bootstrap/js/dist/carousel";
    import type Modal from "bootstrap/js/dist/modal";

    import DynamicallySlottable from "$lib/components/DynamicallySlottable.svelte";
    import HelpModal from "$lib/components/HelpModal.svelte";
    import Item from "$lib/components/Item.svelte";
    import SettingTitle from "$lib/components/SettingTitle.svelte";
    import SwitchRow from "$lib/components/SwitchRow.svelte";
    import TitledContainer from "$lib/components/TitledContainer.svelte";
    import { type HelpItem, HelpItemScheduler } from "$lib/components/types";

    import FsrsOptions from "./FsrsOptions.svelte";
    import GlobalLabel from "./GlobalLabel.svelte";
    import type { DeckOptionsState } from "./lib";

    export let state: DeckOptionsState;
    export let api: Record<string, never>;

    let fsrsOptionsComponent: FsrsOptions | undefined;
    export function onPresetChange() {
        if (fsrsOptionsComponent) {
            fsrsOptionsComponent.onPresetChange();
        }
    }

    const fsrs = state.fsrs;
    // SpeedyCAT: FSRS is always on (the readiness Memory pillar is built on FSRS
    // retrievability and gives up when FSRS is off). Pin the store true so the
    // disabled toggle below can never save fsrs=false, and never surface the
    // "newly enabled" hint since FSRS is on from install.
    $: if (!$fsrs) {
        $fsrs = true;
    }
    const newlyEnabled = false;

    const settings = {
        fsrs: {
            title: "FSRS",
            help: tr.deckConfigFsrsTooltip(),
            url: HelpPage.DeckOptions.fsrs,
            global: true,
        },
        desiredRetention: {
            title: tr.deckConfigDesiredRetention(),
            help:
                tr.deckConfigDesiredRetentionTooltip() +
                "\n\n" +
                tr.deckConfigDesiredRetentionTooltip2(),
            sched: HelpItemScheduler.FSRS,
        },
        modelParams: {
            title: tr.deckConfigWeights(),
            help:
                tr.deckConfigWeightsTooltip2() +
                "\n\n" +
                tr.deckConfigComputeOptimalWeightsTooltip2(),
            sched: HelpItemScheduler.FSRS,
        },
        rescheduleCardsOnChange: {
            title: tr.deckConfigRescheduleCardsOnChange(),
            help: tr.deckConfigRescheduleCardsOnChangeTooltip(),
            sched: HelpItemScheduler.FSRS,
            global: true,
        },
        healthCheck: {
            title: tr.deckConfigHealthCheck(),
            help:
                tr.deckConfigAffectsEntireCollection() +
                "\n\n" +
                tr.deckConfigHealthCheckTooltip1() +
                "\n\n" +
                tr.deckConfigHealthCheckTooltip2(),
            sched: HelpItemScheduler.FSRS,
            global: true,
        },
    };
    const helpSections: HelpItem[] = Object.values(settings);

    let modal: Modal;
    let carousel: Carousel;

    function openHelpModal(index: number): void {
        modal.show();
        carousel.to(index);
    }
</script>

<TitledContainer title={"FSRS"}>
    <HelpModal
        title={"FSRS"}
        url={HelpPage.DeckOptions.fsrs}
        slot="tooltip"
        fsrs={$fsrs}
        {helpSections}
        on:mount={(e) => {
            modal = e.detail.modal;
            carousel = e.detail.carousel;
        }}
    />
    <DynamicallySlottable slotHost={Item} {api}>
        <Item>
            <!-- SpeedyCAT: FSRS is always on, so this toggle is disabled (and
            defaultValue is true so no revert-to-off is offered). The user can
            still tap the title for help. -->
            <SwitchRow bind:value={$fsrs} defaultValue={true} disabled={true}>
                <SettingTitle
                    on:click={() =>
                        openHelpModal(Object.keys(settings).indexOf("fsrs"))}
                >
                    <GlobalLabel title={settings.fsrs.title} />
                </SettingTitle>
            </SwitchRow>
        </Item>

        {#if $fsrs}
            <FsrsOptions
                bind:this={fsrsOptionsComponent}
                {state}
                {newlyEnabled}
                openHelpModal={(key) =>
                    openHelpModal(Object.keys(settings).indexOf(key))}
                {onPresetChange}
            />
        {/if}
    </DynamicallySlottable>
</TitledContainer>
