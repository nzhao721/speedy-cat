<!--
Copyright: Ankitects Pty Ltd and contributors
License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
-->
<!--
SpeedyCAT: Full-Length Tests page. Orchestrates the exam lifecycle: pick a test
→ run each section under its own enforced countdown → show the scheduled break
between sections → submit for a per-section score report → optional stats/review.
-->
<script lang="ts">
    import { onDestroy } from "svelte";

    import BreakScreen from "./BreakScreen.svelte";
    import ExamReview from "./ExamReview.svelte";
    import ExamRunner from "./ExamRunner.svelte";
    import FullLengthReportView from "./FullLengthReport.svelte";
    import FullLengthStatsView from "./FullLengthStats.svelte";
    import TestPicker from "./TestPicker.svelte";
    import {
        abandonAttempt,
        availableFullLengthTests,
        beginFullLengthAttempt,
        confirmFullLengthStart,
        fetchCompletedAttempts,
        fetchFullLengthStats,
        fetchFullLengthTests,
        finishFullLengthAttempt,
        sectionLong,
        setFullLengthLockdown,
        type FullLengthAttemptSummary,
        type FullLengthBreak,
        type FullLengthReport,
        type FullLengthSection,
        type FullLengthStats,
        type FullLengthTest,
        type FullLengthTestSummary,
    } from "../practice/lib";

    type Phase = "picker" | "exam" | "break" | "report" | "stats" | "review";

    let phase: Phase = "picker";
    let tests: FullLengthTestSummary[] = [];
    let completed: FullLengthAttemptSummary[] = [];
    let testsLoading = true;
    let starting = false;

    let test: FullLengthTest | undefined;
    let attemptId = "";
    let sections: FullLengthSection[] = [];
    let breaksByAfter: Record<number, FullLengthBreak> = {};
    let sectionIndex = 0;
    let currentBreak: FullLengthBreak | undefined;
    let report: FullLengthReport | undefined;
    let stats: FullLengthStats | undefined;
    let reviewAttemptId = "";

    $: currentSection = sections[sectionIndex];
    $: nextSection = sections[sectionIndex + 1];
    $: lockdownActive = phase === "exam" || phase === "break";

    $: if (lockdownActive && attemptId) {
        setFullLengthLockdown(true, attemptId);
    } else {
        setFullLengthLockdown(false);
    }

    onDestroy(() => {
        setFullLengthLockdown(false);
    });

    async function loadTests(): Promise<void> {
        testsLoading = true;
        try {
            const [allTests, attempts] = await Promise.all([
                fetchFullLengthTests(),
                fetchCompletedAttempts(),
            ]);
            completed = attempts;
            tests = availableFullLengthTests(allTests, attempts);
        } catch {
            tests = [];
            completed = [];
        } finally {
            testsLoading = false;
        }
    }

    loadTests();

    async function selectTest(e: CustomEvent<string>): Promise<void> {
        if (!confirmFullLengthStart()) {
            return;
        }
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
            report = undefined;
            stats = undefined;
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

    async function openCompletedStats(e: CustomEvent<string>): Promise<void> {
        reviewAttemptId = e.detail;
        try {
            stats = await fetchFullLengthStats(reviewAttemptId);
            test = undefined;
            phase = "stats";
        } catch {
            // surfaced by postProto
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
            stats = await fetchFullLengthStats(attemptId);
            reviewAttemptId = attemptId;
            phase = "stats";
            void loadTests();
        } catch {
            // surfaced by postProto; stay where we are
        }
    }

    async function abandonExam(): Promise<void> {
        if (
            !globalThis.confirm(
                "Abandon this full-length test? Progress will be lost and "
                    + "the score will not count toward Readiness.",
            )
        ) {
            return;
        }
        try {
            await abandonAttempt(attemptId);
        } catch {
            // best-effort
        }
        reset();
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
        stats = undefined;
        reviewAttemptId = "";
        void loadTests();
    }

    function openReview(): void {
        phase = "review";
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
            on:abandon={abandonExam}
        />
    {/key}
{:else if phase === "break" && currentBreak}
    <BreakScreen
        breakInfo={currentBreak}
        nextSectionLabel={nextSection ? sectionLong(nextSection.section) : ""}
        on:done={onBreakDone}
    />
{:else if phase === "review" && reviewAttemptId}
    <ExamReview attemptId={reviewAttemptId} on:done={() => (phase = "stats")} />
{:else if phase === "stats" && stats}
    <FullLengthStatsView
        {stats}
        testTitle={test?.title ?? stats.testTitle}
        on:review={openReview}
        on:done={reset}
    />
{:else if phase === "report" && report}
    <FullLengthReportView {report} testTitle={test?.title ?? ""} on:done={reset} />
{:else}
    <TestPicker
        {tests}
        {completed}
        loading={testsLoading}
        {starting}
        on:select={selectTest}
        on:review={openCompletedStats}
        on:retry={loadTests}
    />
{/if}
