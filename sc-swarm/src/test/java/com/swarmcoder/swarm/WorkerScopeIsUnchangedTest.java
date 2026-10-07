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
package com.swarmcoder.swarm;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.PromptBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build path was not touched, and this is what says so (DEVELOPER_CORRECTIONS.md §21,
 * "must not change").
 *
 * <p><b>Why it matters.</b> The analyst now has a tool for looking requirements up, because it is
 * one instance making a handful of calls and it drowns without one. Workers must NOT get the same
 * treatment, and the reason is money rather than taste: the N members of a task group are dispatched
 * with a byte-identical prompt prefix so the model server computes that prefill once and reuses it N
 * times. A per-worker tool call is divergent by nature, so the moment one worker looks something up
 * its prompt stops matching its siblings' and every one of them pays full price. The project made
 * exactly this call once before, for the code analyser.
 *
 * <p>A worker also has no use for a requirements search: its scope is one task, one write set and
 * the checks it was given by name.
 */
@ModelCodeOnThisPc
class WorkerScopeIsUnchangedTest {

    @TempDir
    Path repo;

    /**
     * The worker's tools, and nothing that reaches the requirement graph. Ten since 2026-10-02 (find_example added to the nine of 2026-10-01:
     * the six this was written for, the two help-desk tools, and {@code dispute_rule} (harness runs
     * 53 and 55) — none of which searches requirements.
     */
    @Test
    void aWorkerHasElevenToolsAndNoneOfThemSearchesRequirements() {
        WorkerToolbox toolbox = new WorkerToolbox(repo, task());

        List<String> names = toolbox.bindings().stream().map(ToolBinding::name).toList();

        assertThat(names).containsExactly(
            "acceptance_test", "shape_of", "body_of", "types_in", "usages_of", "build_of", "resources_of", "doc_outline", "doc_section",
            "doc_search", "replace_member", "add_member", "remove_member",
            "exec", "read", "apply_diff", "write_file", "lookup_api", "ask_expert",
            "request_skeleton", "find_example", "dispute_rule", "report_done");
        for (ToolBinding binding : toolbox.bindings()) {
            assertThat(binding.name().toLowerCase()).doesNotContain("requirement");
            assertThat(binding.description().toLowerCase())
                .as("nothing offers a worker the BRD")
                .doesNotContain("requirement");
        }
    }

    /**
     * Every worker in a group sees the same prefix, byte for byte, and what makes one worker
     * different is appended strictly after it.
     */
    @Test
    void everyWorkerOfATaskGroupSharesOnePrefixHash() {
        PromptBundle bundle = groupBundle();
        PromptBundle rebuilt = groupBundle();

        assertThat(bundle.prefixHash())
            .as("the same task builds the same prefix — a changed hash is a cold cache")
            .isEqualTo(rebuilt.prefixHash());

        String workerOne = bundle.forWorker("minimal diff", "temperature 0.2");
        String workerTwo = bundle.forWorker("rewrite freely", "temperature 0.9");
        assertThat(workerOne).startsWith(bundle.sharedText());
        assertThat(workerTwo).startsWith(bundle.sharedText());
        assertThat(workerOne.substring(0, bundle.sharedText().length()))
            .isEqualTo(workerTwo.substring(0, bundle.sharedText().length()));

        // And nothing the analyst's new machinery produces is in it.
        assertThat(bundle.sharedText())
            .doesNotContain("SEARCH ")
            .doesNotContain("CURRENT BRD");
    }

    /** The shape SwarmDispatcher builds for a task group. */
    private static PromptBundle groupBundle() {
        Task task = task();
        return PromptBundle.builder()
            .systemRole("You are a software engineering worker agent.")
            .workflowRules("Work in small steps.")
            .taskInstructions("Task: " + task.title() + "\n" + task.instructions() + "\n"
                + "You may ONLY modify these paths: " + task.writeSet().stream().sorted().toList())
            .build();
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "add multiply", "Implement multiplication.",
            Set.of("src/main/java/App.java"), Set.of(), List.of(), "src/test/java/swarm/accept",
            null, null, new SwarmPolicy(4, false, 0.2, 0.9, List.of()), TaskState.READY);
    }
}
