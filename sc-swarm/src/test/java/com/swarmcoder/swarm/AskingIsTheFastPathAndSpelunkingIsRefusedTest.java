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
import com.swarmcoder.runtime.ExpertHelp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Must ask for help", enforced by the toolbox rather than requested by the prompt.
 *
 * <p><b>Why mechanically.</b> The worker prefix has told workers not to unpack or decompile jars
 * for weeks. Measured across five runs of a plain harness, with the framework's own working
 * implementation in the prompt: 66 shell commands on {@code jar xf}, {@code jar tf} and
 * {@code javap} in one run and 19 in another, over source jars, an annotation processor's bytecode
 * and a CDI producer — all of it confirming an API the worker had already been handed, and neither
 * run wrote a single file. Prose did not change that. A refusal does.
 *
 * <p>No model is called: the expert here is a fake, which is also how it must be in every test.
 */
@ModelCodeOnThisPc
class AskingIsTheFastPathAndSpelunkingIsRefusedTest {

    @TempDir
    Path worktree;

    WorkerToolbox toolbox;
    List<String> questions;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(worktree.resolve("src/main/java"));
        Files.writeString(worktree.resolve("src/main/java/App.java"), "class App { }\n");
        toolbox = new WorkerToolbox(worktree, task());
        questions = new ArrayList<>();
        toolbox.setExpert(new ExpertHelp() {
            @Override
            public Answer askExpert(String question, String whatITried) {
                questions.add(question);
                return Answer.deterministic("session.append(entry);");
            }

            @Override
            public Answer requestSkeleton(String typeOrTask) {
                questions.add("skeleton:" + typeOrTask);
                return Answer.deterministic("public class Book { }");
            }
        });
    }

    @Test
    void takingTheFrameworkApartIsRefusedAndTheWorkerIsToldWhatToDoInstead() {
        for (String command : List.of(
                "javap -p com.zeroz4j.db.net.ZeroZDbNode",
                "jar tf ~/.m2/repository/com/zeroz4j/zerozstack-shared-api.jar",
                "cd /tmp && jar xf api.jar",
                "unzip -o lib/framework.jar -d /tmp/x",
                "find / -name '*.jar' 2>/dev/null")) {
            assertThat(toolbox.exec(command))
                .as("refused: " + command)
                .startsWith(WorkerToolbox.REVERSE_ENGINEERING_REFUSED)
                .contains("ask_expert");
        }
    }

    @Test
    void ordinaryWorkIsUntouched() {
        for (String command : List.of("mvn -o -q -B test", "grep -rn Book src",
                "find src -name '*.java'", "ls target")) {
            assertThat(toolbox.exec(command))
                .as("allowed: " + command)
                .doesNotStartWith(WorkerToolbox.REVERSE_ENGINEERING_REFUSED);
        }
    }

    @Test
    void aRefusedCommandStillCountsAsALookSoItCannotBeUsedToBuyTurns() {
        int before = toolbox.investigationToolCalls();

        toolbox.exec("javap -p Anything");

        assertThat(toolbox.investigationToolCalls()).isEqualTo(before + 1);
    }

    @Test
    void bothHelpTOolsAreOfferedToTheModel() {
        List<String> names = toolbox.bindings().stream().map(ToolBinding::name).toList();

        assertThat(names).contains("ask_expert", "request_skeleton");
        String description = toolbox.bindings().stream()
            .filter(b -> b.name().equals("ask_expert")).findFirst().orElseThrow().description();
        assertThat(description)
            .as("a worker has to be told that asking is allowed, or it will not")
            .contains("ASK FOR HELP")
            .contains("not a failure");
    }

    @Test
    void askingStillWorksWhenReadingIsPaused() {
        while (!toolbox.readingPaused()) {
            toolbox.read("src/main/java/App.java");
        }

        assertThat(toolbox.read("src/main/java/App.java"))
            .as("an ordinary read is paused")
            .contains("Reading is paused");
        assertThat(toolbox.askExpert("How do I persist a list?", "a plain ArrayList"))
            .as("the one thing that can still get a worker out of the hole must not be refused")
            .contains("session.append(entry);");
        assertThat(toolbox.requestSkeleton("Book"))
            .contains("public class Book { }");
        assertThat(questions).containsExactly("How do I persist a list?", "skeleton:Book");
    }

    @Test
    void thePauseTextSaysWhichToolsStillWork() {
        assertThat(WorkerToolbox.READ_PAUSE_TEXT)
            .contains("ask_expert")
            .contains("request_skeleton");
    }

    @Test
    void everyHelpCallIsRecordedForTheCandidateAndTheJudge() {
        toolbox.askExpert("How do I persist a list?", null);
        toolbox.requestSkeleton("Book");

        assertThat(toolbox.helpCalls()).hasSize(2);
        assertThat(WorkerLoop.formatHelpCalls(toolbox.helpCalls()))
            .containsExactly("1. answered from this codebase's own code (free)",
                "2. answered from this codebase's own code (free)");
    }

    @Test
    void theRecordSaysWhetherTheExpertLookedTheAnswerUpOrRecalledIt() {
        // The expert is an agent session with read-only tools over this codebase now, so an answer
        // either was read out of real files or was not. That is the single most useful thing about
        // it, and both readers of this record - the judge and the operator on the run graph - are
        // being told it here. A one-turn answer with no lookups is a model's memory; four lookups
        // is four files in this project.
        List<ExpertHelp.Answer> calls = List.of(
            new ExpertHelp.Answer("use append()", ExpertHelp.Source.MODEL, 1200, 3,
                List.of("public_shape", "read_file", "dependency_declaring")),
            ExpertHelp.Answer.deterministic("a call site from this codebase"),
            ExpertHelp.Answer.none("the expert ran out of turns", 30,
                List.of("lookup_docs", "read_file")));

        assertThat(WorkerLoop.formatHelpCalls(calls))
            .containsExactly(
                "1. answered by the expert model after 3 turns and 3 lookups (public_shape, "
                    + "read_file, dependency_declaring) (1200 tokens)",
                "2. answered from this codebase's own code (free)",
                "3. could not be answered after 30 turns and 2 lookups (lookup_docs, read_file)");
        assertThat(WorkerLoop.helpChip(calls))
            .as("one line for the worker's card on the run graph")
            .isEqualTo("asked the expert 3x (5 lookups)");
        assertThat(WorkerLoop.helpChip(List.of(ExpertHelp.Answer.deterministic("free"))))
            .as("a free answer looked nothing up, and a card saying \"0 lookups\" is noise")
            .isEqualTo("asked the expert 1x");
        assertThat(WorkerLoop.helpChip(List.of()))
            .as("a worker that never asked says nothing at all")
            .isEmpty();
    }

    @Test
    void aBuildErrorNamingAFrameworkSymbolSaysToAskRatherThanGuess() {
        toolbox.setFrameworkPackages(List.of("com.zeroz4j"));

        assertThat(toolbox.frameworkErrorHint(
                "App.java:[12,9] cannot find symbol: class ZeroZDbNode\n"
                + "  location: package com.zeroz4j.db.net"))
            .contains("ask_expert")
            .contains("not your own code");
        assertThat(toolbox.frameworkErrorHint("App.java:[3,1] cannot find symbol: class Bok"))
            .as("a typo in the worker's own code is its own problem")
            .isEmpty();
    }

    @Test
    void withNoExpertWiredNothingPretendsToAnswer() {
        WorkerToolbox bare = new WorkerToolbox(worktree, task());

        assertThat(bare.askExpert("anything", null))
            .contains("No expert is configured");
    }

    private Task task() {
        return new Task(UUID.randomUUID(), 1L, "Add a book list", "Do the thing.",
            Set.of("src/main/java/**"), Set.of(), List.of(), "src/test/java/swarm/accept",
            null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }
}
