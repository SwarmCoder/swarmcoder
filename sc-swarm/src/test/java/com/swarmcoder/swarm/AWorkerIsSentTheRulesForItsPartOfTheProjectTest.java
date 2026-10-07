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

import com.swarmcoder.domain.RuleScope;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.PromptBundle;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workers of a task are opened with the rules for the part of the project the task may write
 * (owner's decision 2026-10-07, section 65), and the workflow rules no longer repeat what the
 * tool descriptions sent beside them already say. Run 89: about 8,500 tokens of opening on each
 * of 27 calls for a two-line change, 2,883 of them the project's 38 rules.
 */
class AWorkerIsSentTheRulesForItsPartOfTheProjectTest {

    private static final String ALL = "HOW THIS PROJECT MUST BE BUILT\n- every rule\n";
    private static final String SERVER = "HOW THIS PROJECT MUST BE BUILT\n- the server's rules\n";

    @TempDir
    Path dir;

    @AfterEach
    void meterOff() {
        RunMeter.reset();
        RunMeter.disable();
    }

    private SwarmEngineImpl engine(ArtifactStore store) {
        return new SwarmEngineImpl(new VllmClient("http://127.0.0.1:9", "", "none", true), store,
            new InferenceScheduler(8, 1024 * 1024 * 1024, 1024),
            new KoogAgentRuntime(new TraceHub(null)), new ModelProfileRegistry(List.of()),
            GitService.disabled(), content -> null, new CloudGate(1_000_000, null));
    }

    private static Task task(Set<String> writeSet, String testDir) {
        return new Task(UUID.randomUUID(), 1, "Show only the seven filter fields", "Do it.",
            writeSet, Set.of(), List.of(), testDir, null, null,
            new SwarmPolicy(2, false, 0.2, 0.6, List.of()), TaskState.PENDING);
    }

    @Test
    void theRulesAreChosenByTheWriteSetAndTheFolderOfTheTaskTests() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            SwarmEngineImpl engine = engine(store);
            List<Collection<String>> asked = new ArrayList<>();
            engine.setWorkerRules(paths -> {
                asked.add(paths);
                return new RuleScope.Briefing(SERVER, 12, 38);
            });
            RunMeter.enable();
            RunMeter.reset();
            Task task = task(Set.of("server/src/main/java/app/LogbookServiceImpl.java",
                "server/pom.xml"), "acceptance/src/test/java/swarm/accept");

            String sent = engine.rulesForWorkersOf(task, ALL);

            assertThat(sent).isEqualTo(SERVER);
            assertThat(asked).hasSize(1);
            assertThat(asked.get(0)).containsExactly("server/pom.xml",
                "server/src/main/java/app/LogbookServiceImpl.java",
                "acceptance/src/test/java/swarm/accept");
            assertThat(RunMeter.spans()).extracting(RunMeter.Span::name)
                .as("the run report reads the count from here")
                .containsExactly("worker rules|" + task.id() + "|12|38");

            PromptBundle scoped = SwarmDispatcher.buildBundle(task, null, null, sent, List.of());
            PromptBundle everything = SwarmDispatcher.buildBundle(task, null, null, ALL, List.of());
            assertThat(scoped.sharedText()).contains("the server's rules")
                .doesNotContain("every rule");
            assertThat(scoped.prefixHash()).isNotEqualTo(everything.prefixHash());
        }
    }

    @Test
    void aTaskWithNoWriteSetAsksForEveryPartAndAnEngineWithNoChoiceSendsEveryRule()
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            SwarmEngineImpl engine = engine(store);
            Task anywhere = task(Set.of(), "acceptance/src/test/java/swarm/accept");

            assertThat(engine.rulesForWorkersOf(anywhere, ALL))
                .as("nothing chooses: the text every role is given").isSameAs(ALL);

            List<Collection<String>> asked = new ArrayList<>();
            engine.setWorkerRules(paths -> {
                asked.add(paths);
                return new RuleScope.Briefing(ALL, 38, 38);
            });
            engine.rulesForWorkersOf(anywhere, ALL);
            assertThat(asked.get(0)).as("no write set is not narrowed by the tests' folder")
                .isEmpty();

            engine.setWorkerRules(paths -> {
                throw new IllegalStateException("no tree");
            });
            assertThat(engine.rulesForWorkersOf(anywhere, ALL))
                .as("a choice that fails sends every rule, never none").isSameAs(ALL);
        }
    }

    /**
     * The workflow rules name each tool where the order of work needs it and leave what a tool
     * returns to the tool's own description. Before: 1,390 tokens (run 89).
     */
    @Test
    void theWorkflowRulesDoNotRepeatTheToolDescriptions() {
        PromptBundle bundle = SwarmDispatcher.buildBundle(task(Set.of("server"), null), null,
            null, null, List.of());
        PromptBundle.SegmentSize workflow = bundle.segmentTokens().stream()
            .filter(s -> s.kind().name().equals("WORKFLOW_RULES")).findFirst().orElseThrow();
        String text = bundle.sharedText();

        assertThat(workflow.tokens()).as("estimated tokens of the workflow rules").isLessThan(1_100);
        // Said by the tool descriptions, which are sent beside the opening on every call.
        assertThat(text).doesNotContain("shape_of for a type's members")
            .doesNotContain("doc_outline lists the documents")
            .doesNotContain("replace_member replaces ONE method")
            .doesNotContain("you get back one whole file")
            .doesNotContain("you get back real code");
        // Still said here, once: the order to work in, what is refused, and what stops reading.
        assertThat(text).contains("shape_of, body_of, types_in, usages_of")
            .contains("replace_member, add_member or remove_member")
            .contains("call lookup_api FIRST").contains("ask_expert").contains("request_skeleton")
            .contains("find_example").contains("dispute_rule")
            .contains("Do NOT unpack or decompile jars").contains("REFUSED")
            .contains("Do not use find, grep, ls or cat through exec")
            .contains("ONLY an artifact your knowledge brief lists as available")
            .contains("After sixteen reads without a write, reading pauses until you write");
        for (String tool : List.of("find_example", "dispute_rule", "request_skeleton")) {
            assertThat(text.split(tool, -1).length - 1).as(tool + " is named once").isEqualTo(1);
        }
    }
}
