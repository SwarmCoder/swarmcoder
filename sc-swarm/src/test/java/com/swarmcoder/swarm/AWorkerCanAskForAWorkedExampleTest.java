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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.ApiLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker has {@code find_example(what, kind)}, the librarian's by-use example the planning roles
 * already have, and the worker prompt says when to use it.
 */
class AWorkerCanAskForAWorkedExampleTest {

    @TempDir
    Path worktree;

    @Test
    void theToolIsOfferedAndAsksTheLibrarianForAnImplementationOrATest() {
        List<String> asked = new ArrayList<>();
        ApiLookup librarian = new ApiLookup() {
            @Override public String lookup(String query) {
                return "docs";
            }
            @Override public String findExample(String what, boolean wantTest, String forWhom) {
                asked.add(what + "|" + wantTest);
                return "// a whole file that uses " + what;
            }
        };
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task(), librarian);

        assertThat(toolbox.bindings().stream().map(ToolBinding::name)).contains("find_example");
        assertThat(toolbox.findExample("Book BookRepository store a list", "implementation"))
            .contains("a whole file that uses Book BookRepository");
        assertThat(toolbox.findExample("Book BookRepository", "test")).contains("a whole file");

        assertThat(asked).containsExactly(
            "Book BookRepository store a list|false", "Book BookRepository|true");
        assertThat(toolbox.investigationToolCalls())
            .as("a look at an example counts as a look, like lookup_api").isEqualTo(2);
    }

    @Test
    void withoutALibrarianItSaysSoAndAnEmptyQuestionIsRefused() {
        WorkerToolbox bare = new WorkerToolbox(worktree, task());

        assertThat(bare.findExample("Book", "implementation")).contains("not configured");
        assertThat(bare.findExample(" ", "test")).startsWith("error:");
    }

    @Test
    void theWorkerPromptSaysWhenToUseIt() {
        String prompt = SwarmDispatcher.buildBundle(task(), null, "", null, List.of()).sharedText();

        assertThat(prompt).contains("find_example")
            .contains("how this project already does it");
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1L, "Add a book list", "Do the thing.",
            Set.of("src/main/java/**"), Set.of(), List.of(), "src/test/java/swarm/accept",
            null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }
}
