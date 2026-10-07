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
import com.swarmcoder.inference.LookupMeter;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.ApiLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner's decision, 2026-10-04: a worker navigates and refactors through the Java language
 * server - deterministic tools, no model. What is held here is the worker's side of it: which
 * tools it is offered and in which order, that a refactoring's writes are held to the same policy
 * as write_file, and that the run's record counts these calls as their own kind. The server
 * itself is exercised by {@code JdtLanguageServerLiveTest}.
 */
class AWorkerUsesTheLanguageServerTest {

    @TempDir
    Path checkout;

    private final List<String> left = new ArrayList<>();

    /** A project with a language server: a rename touches App.java and Other.java. */
    private final ApiLookup withServer = new ApiLookup() {
        @Override public String lookup(String query) {
            return "";
        }
        @Override public boolean languageServer() {
            return true;
        }
        @Override public String tree(String query, String argument) {
            return query + " of " + argument;
        }
        @Override public boolean answeredByLanguageServer() {
            return true;
        }
        @Override public InCheckout inCheckout(Path where, String action, String argument,
                                               String second, Function<String, String> refusal) {
            List<String> files = List.of("src/main/java/App.java", "src/main/java/Other.java");
            for (String file : files) {
                String why = refusal.apply(file);
                if (why != null) {
                    return new InCheckout("refused for " + file + ": " + why, List.of());
                }
            }
            return new InCheckout(action + " " + argument + " -> " + second + ": 2 file(s)", files);
        }
        @Override public void leaveCheckout(Path where) {
            left.add(where.toString());
        }
    };

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "rename it", "Rename the method.",
            Set.of("src/main/java/App.java"), Set.of(), List.of(), "src/test/java/swarm/accept",
            null, null, new SwarmPolicy(4, false, 0.2, 0.9, List.of()), TaskState.READY);
    }

    @Test
    void theServersToolsSitWithTheTreesBeforeTheShellAndWholeFiles() {
        List<String> names = new WorkerToolbox(checkout, task(), withServer).bindings().stream()
            .map(ToolBinding::name).toList();

        assertThat(names.subList(0, 23)).containsExactly("acceptance_test", "shape_of", "body_of", "types_in",
            "usages_of", "build_of", "resources_of", "implementations_of", "supertypes_of", "callers_of",
            "find_symbol", "doc_of", "problems_in", "rename_symbol", "organize_imports",
            "doc_outline", "doc_section", "doc_search", "replace_member", "add_member", "remove_member",
            "exec",
            "read");
        assertThat(names).doesNotHaveDuplicates().endsWith("report_done");
    }

    @Test
    void aRenameIsHeldToTheWritePolicy() {
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task(), withServer);

        String answer = toolbox.renameSymbol("App#run", "start");

        assertThat(answer).startsWith("rename App#run -> start: 2 file(s)")
            .as("a file outside the write set is written and noted, as write_file does")
            .contains("outside your write set");
        assertThat(toolbox.outOfWriteSetPaths()).containsExactly("src/main/java/Other.java");

        WorkerToolbox locked = new WorkerToolbox(checkout, task(), withServer, null, null,
            List.of("src/main/java/Other.java"));
        assertThat(locked.renameSymbol("App#run", "start"))
            .startsWith("refused for src/main/java/Other.java");
        assertThat(locked.outOfWriteSetPaths()).isEmpty();
    }

    @Test
    void theCallsAreCountedAsLanguageServerQueriesAndTheServerIsLeftWhenTheWorkerEnds() {
        RunMeter.enable();
        LookupMeter.reset();
        try {
            WorkerToolbox toolbox = new WorkerToolbox(checkout, task(), withServer);
            assertThat(toolbox.callersOf("App#run")).startsWith("callers_of of App#run");
            assertThat(toolbox.problemsIn("src/main/java/App.java")).contains("problems");
            toolbox.leaveLanguageServer();

            assertThat(LookupMeter.counts())
                .extracting(c -> c.role() + " " + c.kind() + " " + c.calls())
                .containsExactly("worker LANGUAGE_SERVER 2");
            assertThat(left).containsExactly(checkout.toString());
        } finally {
            RunMeter.disable();
            LookupMeter.reset();
        }
    }

    @Test
    void withoutAServerNoSuchToolIsOfferedAndACallSaysSoPlainly() {
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task());

        assertThat(toolbox.bindings().stream().map(ToolBinding::name).toList())
            .doesNotContain("rename_symbol", "problems_in", "callers_of", "find_symbol");
        assertThat(toolbox.renameSymbol("App#run", "start"))
            .contains("The Java language server is not available here");
    }
}
