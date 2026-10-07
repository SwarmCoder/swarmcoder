/*
 * Copyright 2026 Franz Schöning
 * Project: https://github.com/SwarmCoder/swarmcoder
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.swarmcoder.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBundleTest {

    private static PromptBundle.Builder base() {
        return PromptBundle.builder()
            .systemRole("You are a worker.")
            .workflowRules("Use tools; call report_done.")
            .taskInstructions("Task: add multiply\nOnly modify src.");
    }

    @Test
    void identicalInputsProduceIdenticalHashAndBytes() {
        PromptBundle a = base().build();
        PromptBundle b = base().build();

        assertThat(a.sharedText()).isEqualTo(b.sharedText());
        assertThat(a.prefixHash()).isEqualTo(b.prefixHash());
    }

    @Test
    void anySegmentChangeChangesTheHash() {
        PromptBundle a = base().build();
        PromptBundle b = base().taskInstructions("Task: add multiply\nOnly modify src!").build();
        PromptBundle c = base().projectConstraints("Prefer records.").build();

        assertThat(b.prefixHash()).isNotEqualTo(a.prefixHash());
        assertThat(c.prefixHash()).isNotEqualTo(a.prefixHash());
    }

    @Test
    void segmentOrderIsFixedRegardlessOfBuilderCallOrder() {
        PromptBundle a = PromptBundle.builder()
            .taskInstructions("T").systemRole("S").workflowRules("W").build();
        PromptBundle b = PromptBundle.builder()
            .systemRole("S").workflowRules("W").taskInstructions("T").build();

        assertThat(a.sharedText()).isEqualTo(b.sharedText());
        assertThat(a.sharedText().indexOf("SYSTEM_ROLE"))
            .isLessThan(a.sharedText().indexOf("WORKFLOW_RULES"));
        assertThat(a.sharedText().indexOf("WORKFLOW_RULES"))
            .isLessThan(a.sharedText().indexOf("TASK_INSTRUCTIONS"));
    }

    @Test
    void workerContentIsAppendedAfterTheSharedPrefixNeverInterleaved() {
        PromptBundle bundle = base().build();

        String worker = bundle.forWorker("minimal diff approach", null);

        assertThat(worker).startsWith(bundle.sharedText());
        assertThat(worker).contains("## PERSONA");
        // The shared prefix is untouched by per-worker content:
        assertThat(worker.substring(0, bundle.sharedText().length())).isEqualTo(bundle.sharedText());
    }

    @Test
    void blankSegmentsAreSkipped() {
        PromptBundle bundle = PromptBundle.builder()
            .systemRole("S").projectConstraints("  ").taskInstructions("T").build();

        assertThat(bundle.sharedText()).doesNotContain("PROJECT_CONSTRAINTS");
        assertThat(bundle.segments()).hasSize(2);
    }

    /**
     * The prefix reports its own size — the number a worker's chip shows and an operator watches.
     *
     * <p>Nobody had measured the prefix until 2026-09-02, because the reasoning about it was all
     * about prefill TIME, which the shared prefix already makes nearly free. The cost that
     * matters is ROOM: the prefix is charged against the model's working context and it is the
     * floor {@code HistoryTrim} can never compact away.
     */
    @Test
    void theBundleReportsWhatItCostsInTokens() {
        PromptBundle bundle = base().knowledgeBrief("x".repeat(4_000)).build();

        assertThat(bundle.estimatedTokens()).isEqualTo(bundle.sharedText().length() / 4);
        assertThat(bundle.segmentTokens())
            .extracting(PromptBundle.SegmentSize::kind)
            .containsExactly(PromptBundle.SegmentKind.SYSTEM_ROLE,
                PromptBundle.SegmentKind.WORKFLOW_RULES,
                PromptBundle.SegmentKind.TASK_INSTRUCTIONS,
                PromptBundle.SegmentKind.KNOWLEDGE_BRIEF);
        assertThat(bundle.sizeSummary()).contains("KNOWLEDGE_BRIEF=1000");
    }

    /**
     * Reporting the size must not change the prefix. Byte-identity across a task's workers is
     * the whole reason this class exists, so a measurement that perturbed it would cost far more
     * than any saving it revealed.
     */
    @Test
    void measuringTheBundleDoesNotDisturbIt() {
        PromptBundle a = base().build();
        String textBefore = a.sharedText();
        String hashBefore = a.prefixHash();

        a.estimatedTokens();
        a.segmentTokens();
        a.sizeSummary();

        assertThat(a.sharedText()).isEqualTo(textBefore);
        assertThat(a.prefixHash()).isEqualTo(hashBefore);
        assertThat(a.forWorker("minimal-diff", null)).startsWith(textBefore);
        assertThat(a.forWorker("defensive-edges", null)).startsWith(textBefore);
    }

    /**
     * Five segments, all of them populated by something.
     *
     * <p>{@code DESIGN_EXCERPT} and {@code REPO_MAP} were in this enum with builder methods from
     * the first day and in three months nothing ever called either. Every attempt to answer "what
     * does a worker's prefix cost" had to rediscover that before it could start, and "how big is
     * the repository map a worker gets?" has an answer only because they are gone: there is no
     * such map, and a worker lists what it needs with its tools.
     */
    @Test
    void thereAreNoSegmentsNothingEverFills() {
        assertThat(PromptBundle.SegmentKind.values())
            .containsExactly(PromptBundle.SegmentKind.SYSTEM_ROLE,
                PromptBundle.SegmentKind.WORKFLOW_RULES,
                PromptBundle.SegmentKind.PROJECT_CONSTRAINTS,
                PromptBundle.SegmentKind.TASK_INSTRUCTIONS,
                PromptBundle.SegmentKind.KNOWLEDGE_BRIEF);
    }
}
