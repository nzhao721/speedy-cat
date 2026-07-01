<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: Full-Length Tests page. Orchestrates the exam lifecycle: pick a test
→ run each section under its own enforced countdown → show the scheduled break
between sections → submit for a per-section report. Section order and the
scheduled breaks come straight from the backend test definition.
-->
<script lang="ts">
    import BreakScreen from "./BreakScreen.svelte";
    import ExamRunner from "./ExamRunner.svelte";
    import FullLengthReportView from "./FullLengthReport.svelte";
    import TestPicker from "./TestPicker.svelte";
    import {
        beginFullLengthAttempt,
        fetchFullLengthTests,
        finishFullLengthAttempt,
        sectionLong,
        type FullLengthBreak,
        type FullLengthReport,
        type FullLengthSection,
        type FullLengthTest,
        type FullLengthTestSummary,
    } from "../practice/lib";

    type Phase = "picker" | "exam" | "break" | "report";

    let phase: Phase = "picker";
    let tests: FullLengthTestSummary[] = [];
    let testsLoading = true;
    let starting = false;

    let test: FullLengthTest | undefined;
    let attemptId = "";
    let sections: FullLengthSection[] = [];
    let breaksByAfter: Record<number, FullLengthBreak> = {};
    let sectionIndex = 0;
    let currentBreak: FullLengthBreak | undefined;
    let report: FullLengthReport | undefined;

    $: currentSection = sections[sectionIndex];
    $: nextSection = sections[sectionIndex + 1];

    async function loadTests(): Promise<void> {
        testsLoading = true;
        try {
            tests = await fetchFullLengthTests();
        } catch {
            tests = [];
        } finally {
            testsLoading = false;
        }
    }

    loadTests();

    async function selectTest(e: CustomEvent<string>): Promise<void> {
        starting = true;
        try {
            const res = await beginFullLengthAttempt(e.detail);
            attemptId = res.attemptId;
            test = res.test;
            sections = (test?.sections ?? []).slice().sort((a, b) => a.order - b.order);
            breaksByAfter = {};
            for (const b of test?.breaks ?? []) {
                breaksByAfter[b.afterSection] = b;
            }
            sectionIndex = 0;
            if (sections.length === 0) {
                await submit();
            } else {
                phase = "exam";
            }
        } catch {
            // startFullLengthAttempt surfaced the error already
        } finally {
            starting = false;
        }
    }

    function onSectionDone(): void {
        const order = currentSection?.order ?? 0;
        const scheduled = breaksByAfter[order];
        if (scheduled && sectionIndex + 1 < sections.length) {
            currentBreak = scheduled;
            phase = "break";
        } else {
            void advance();
        }
    }

    function onBreakDone(): void {
        currentBreak = undefined;
        void advance();
    }

    async function advance(): Promise<void> {
        sectionIndex += 1;
        if (sectionIndex < sections.length) {
            phase = "exam";
        } else {
            await submit();
        }
    }

    async function submit(): Promise<void> {
        try {
            report = await finishFullLengthAttempt(attemptId);
            phase = "report";
        } catch {
            // surfaced by postProto; stay where we are
        }
    }

    function reset(): void {
        phase = "picker";
        test = undefined;
        attemptId = "";
        sections = [];
        breaksByAfter = {};
        sectionIndex = 0;
        currentBreak = undefined;
        report = undefined;
        void loadTests();
    }
</script>

{#if phase === "exam" && currentSection}
    {#key sectionIndex}
        <ExamRunner
            {attemptId}
            testId={test?.testId ?? ""}
            section={currentSection.section}
            durationSeconds={currentSection.durationSeconds}
            sectionOrder={currentSection.order}
            sectionTotal={sections.length}
            on:sectionDone={onSectionDone}
        />
    {/key}
{:else if phase === "break" && currentBreak}
    <BreakScreen
        breakInfo={currentBreak}
        nextSectionLabel={nextSection ? sectionLong(nextSection.section) : ""}
        on:done={onBreakDone}
    />
{:else if phase === "report" && report}
    <FullLengthReportView {report} testTitle={test?.title ?? ""} on:done={reset} />
{:else}
    <TestPicker
        {tests}
        loading={testsLoading}
        {starting}
        on:select={selectTest}
        on:retry={loadTests}
    />
{/if}
