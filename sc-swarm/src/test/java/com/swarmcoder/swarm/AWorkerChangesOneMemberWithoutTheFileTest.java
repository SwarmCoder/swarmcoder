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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 82, 2026-10-04: workers read 36 files whole (107,143 characters) and nothing from
 * the tree. Their tree queries were not wired in the harness (see {@code WorkerLookupsTest});
 * and a worker that changes a file had to read all of it, because a diff needs the lines around
 * the change and write_file needs the whole file, and to read it again after its own change,
 * because the tree shows the project as it was when the task started.
 */
class AWorkerChangesOneMemberWithoutTheFileTest {

    @TempDir
    Path checkout;

    private static final String APP = "src/main/java/com/shop/App.java";
    private static final String OTHER = "src/main/java/com/shop/Other.java";

    /** The tree as it was when the task started: it still says what App#run was. */
    private final ApiLookup staleTree = new ApiLookup() {
        @Override public String lookup(String query) {
            return "";
        }
        @Override public String tree(String query, String argument) {
            return "the tree's " + query + " of " + argument;
        }
    };

    @BeforeEach
    void aCheckout() throws Exception {
        StringBuilder app = new StringBuilder("""
            package com.shop;

            public class App {

                public String run() {
                    return "old";
                }
            """);
        for (int i = 0; i < 100; i++) {
            app.append("\n    public int step").append(i).append("() {\n        return ").append(i)
                .append(";\n    }\n");
        }
        write(APP, app.append("}\n").toString());
        write(OTHER, "package com.shop;\n\npublic class Other {\n    int x() {\n        return 1;\n"
            + "    }\n}\n");
    }

    private void write(String relative, String text) throws Exception {
        Path file = checkout.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "change it", "Change the method.",
            Set.of(APP), Set.of(), List.of(), "src/test/java/swarm/accept",
            null, null, new SwarmPolicy(4, false, 0.2, 0.9, List.of()), TaskState.READY);
    }

    @Test
    void theMemberEditsAndTheDocumentQueriesAreOfferedBeforeTheShellAndWholeFiles() {
        List<String> names = new WorkerToolbox(checkout, task(), staleTree).bindings().stream()
            .map(ToolBinding::name).toList();

        assertThat(names).contains("replace_member", "add_member", "doc_outline", "doc_section",
            "doc_search");
        assertThat(names.indexOf("replace_member")).isLessThan(names.indexOf("exec"));
        assertThat(names.indexOf("doc_section")).isLessThan(names.indexOf("read"));
        assertThat(names.indexOf("replace_member")).isLessThan(names.indexOf("write_file"));
    }

    @Test
    void aMemberIsReplacedAndReadBackFromTheCheckoutWithoutTheFileBeingRead() throws Exception {
        RunMeter.enable();
        LookupMeter.reset();
        try {
            WorkerToolbox toolbox = new WorkerToolbox(checkout, task(), staleTree);

            String replaced = toolbox.replaceMember("App#run",
                "public String run() {\n    return \"new\";\n}");
            String added = toolbox.addMember("App", "public int extra() {\n    return 7;\n}");
            String now = toolbox.oneBodyOf("App#run");

            assertThat(replaced).startsWith("replaced run in " + APP);
            assertThat(added).startsWith("added extra to App in " + APP);
            assertThat(Files.readString(checkout.resolve(APP)))
                .contains("    public String run() {\n        return \"new\";\n    }")
                .contains("    public int extra() {\n        return 7;\n    }\n}")
                .contains("public int step99()").doesNotContain("\"old\"");
            assertThat(now).as("the checkout as it is now, not the tree of the task's start")
                .contains("return \"new\";").doesNotContain("the tree's");
            assertThat(toolbox.oneBodyOf("FrameworkType#open"))
                .as("what the checkout does not hold is the tree's").startsWith("the tree's body_of");
            assertThat(toolbox.docSection("docs/a.md#1")).startsWith("the tree's doc_section");
            assertThat(LookupMeter.counts())
                .extracting(c -> c.role() + " " + c.kind() + " " + c.calls())
                .as("no whole file was read for any of it")
                .containsExactly("worker TREE 2", "worker DOCUMENT 1");
        } finally {
            RunMeter.disable();
            LookupMeter.reset();
        }
    }

    @Test
    void aMemberEditIsHeldToTheWritePolicy() throws Exception {
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task(), staleTree);
        assertThat(toolbox.replaceMember("Other#x", "int x() {\n    return 2;\n}"))
            .as("outside the write set: written, and noted for the reviewer")
            .startsWith("replaced x in " + OTHER).contains("outside your write set");

        WorkerToolbox locked = new WorkerToolbox(checkout, task(), staleTree, null, null,
            List.of(OTHER));
        assertThat(locked.replaceMember("Other#x", "int x() {\n    return 3;\n}"))
            .startsWith("error:");
        assertThat(Files.readString(checkout.resolve(OTHER))).contains("return 2;")
            .doesNotContain("return 3;");
    }

    @Test
    void aWholeFileIsStillReadWholeAndTheAnswerNamesTheQueryAndTheEdit() {
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task(), staleTree);

        String whole = toolbox.read(APP);

        assertThat(whole).contains("public int step99()")
            .contains("shape_of App lists its 101 members")
            .contains("body_of App#<member>")
            .contains("replace_member and add_member change one member by name");
        assertThat(toolbox.read(OTHER)).as("a short file gets no note")
            .doesNotContain("[You read");
    }

    /** Live run 89: an unused helper is taken out by name; no file read, no diff. */
    @Test
    void aMemberIsRemovedByNameUnderTheSameWritePolicy() throws Exception {
        RunMeter.enable();
        LookupMeter.reset();
        try {
            WorkerToolbox toolbox = new WorkerToolbox(checkout, task(), staleTree);

            assertThat(toolbox.removeMember("App#step7")).startsWith("removed step7 from " + APP);
            assertThat(Files.readString(checkout.resolve(APP))).doesNotContain("step7()")
                .contains("public int step6()", "public int step8()");
            assertThat(LookupMeter.counts()).as("nothing was read for it").isEmpty();
        } finally {
            RunMeter.disable();
            LookupMeter.reset();
        }
        WorkerToolbox locked = new WorkerToolbox(checkout, task(), staleTree, null, null,
            List.of(OTHER));
        assertThat(locked.removeMember("Other#x")).startsWith("error:");
        assertThat(Files.readString(checkout.resolve(OTHER))).contains("int x()");
        assertThat(locked.bindings().stream().map(ToolBinding::name).toList())
            .containsSubsequence("replace_member", "add_member", "remove_member", "exec");
    }
}
