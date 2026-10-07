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
import com.swarmcoder.runtime.ApiLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 88 (DEVELOPER_CORRECTIONS section 60): a worker's checkout is cut from the run's
 * progress, so it holds what earlier tasks delivered; the syntax tree and the project's language
 * server hold the project as it was at the run's base. The second and third tasks asked
 * {@code shape_of} for the four types the first had added 27 times ("no such type", 57 to 73
 * characters), {@code find_symbol} 13 times ("0 type(s) match"), then ran {@code ls} and
 * {@code find} and read the four files whole 30 times.
 */
class AWorkerSeesWhatEarlierTasksDeliveredTest {

    private static final String DIR = "app-shared/src/main/java/com/hambook";
    private static final String QSO = DIR + "/Qso.java";
    private static final String SERVICE = DIR + "/LogbookService.java";
    private static final String FIELD = DIR + "/UiField.java";

    private static final String QSO_SOURCE =
        "package com.hambook;\n\npublic class Qso {\n    public String call() {\n"
            + "        return \"\";\n    }\n}\n";
    private static final String SERVICE_AT_BASE =
        "package com.hambook;\n\npublic interface LogbookService {\n    void add(Qso qso);\n}\n";

    @TempDir
    Path checkout;

    /** The project at the run's base: Qso and the service as it was; no UiField. */
    private final Map<String, String> project = new HashMap<>();

    private final ApiLookup atTheBase = new ApiLookup() {
        @Override public String lookup(String query) {
            return "";
        }
        @Override public String tree(String query, String argument) {
            return "the tree's " + query + " of " + argument + "\n";
        }
        @Override public String sourceInTree(String relativePath) {
            return project.get(relativePath);
        }
    };

    @BeforeEach
    void aCheckoutCutFromTheRunsProgress() throws Exception {
        project.put(QSO, QSO_SOURCE);
        project.put(SERVICE, SERVICE_AT_BASE);
        write(QSO, QSO_SOURCE.replace("\n", "\r\n")); // line endings are not a change
        // An earlier task of the run added a type and a method.
        write(SERVICE, SERVICE_AT_BASE.replace("}\n",
            "    LogbookScreenDescriptor getLogbookScreenDescriptor();\n}\n"));
        write(FIELD, """
            package com.hambook;

            /** One field of a screen. */
            public class UiField {
                private String name;

                public String getName() {
                    return name;
                }

                public void validate() {
                    if (name == null) {
                        throw new IllegalStateException("no name");
                    }
                }
            }
            """);
    }

    private void write(String relative, String text) throws Exception {
        Path file = checkout.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "server", "Return it.",
            Set.of(SERVICE), Set.of(), List.of(), "app-server/src/test/java/swarm",
            null, null, new SwarmPolicy(4, false, 0.2, 0.9, List.of()), TaskState.READY);
    }

    private WorkerToolbox toolbox() {
        return new WorkerToolbox(checkout, task(), atTheBase);
    }

    @Test
    void aTypeAnEarlierTaskAddedIsAnsweredFromTheCheckoutNotAsNoSuchType() {
        String shape = toolbox().shapeOf("com.hambook.UiField");

        assertThat(shape).doesNotContain("the tree's")
            .contains(FIELD + ":4-16")
            .contains("as your checkout holds it now")
            .contains("public class UiField {")
            .contains("private String name  // :5")
            .contains("public String getName()")
            .contains("public void validate()")
            .as("members, not bodies").doesNotContain("IllegalStateException");
    }

    @Test
    void aTypeChangedSinceTheTreeWasBuiltIsAnsweredWithItsNewMember() {
        assertThat(toolbox().shapeOf("LogbookService"))
            .contains("LogbookScreenDescriptor getLogbookScreenDescriptor()  // :5")
            .contains("void add(Qso qso)  // :4");
    }

    @Test
    void aTypeTheTreeHoldsAsItIsStaysWithTheTree() {
        assertThat(toolbox().shapeOf("com.hambook.Qso"))
            .startsWith("the tree's shape_of of com.hambook.Qso");
    }

    @Test
    void whatAFolderHoldsNamesTheNewAndTheChangedFilesAfterTheTreesList() {
        String byPath = toolbox().typesIn(DIR);

        assertThat(byPath).startsWith("the tree's types_in of " + DIR)
            .contains("public class UiField  " + FIELD + ":4  (new)")
            .contains("public interface LogbookService  " + SERVICE + ":3  (changed)")
            .doesNotContain(QSO + ":");
        assertThat(toolbox().typesIn("com.hambook")).as("asked by package")
            .contains(FIELD + ":4  (new)").doesNotContain(QSO + ":");
    }

    @Test
    void aSymbolSearchAlsoFindsTheTypesOnlyTheCheckoutHolds() {
        assertThat(toolbox().findSymbol("UiField")).startsWith("the tree's find_symbol of UiField")
            .contains("public class UiField  " + FIELD + ":4  (new)");
        assertThat(toolbox().findSymbol("*Field")).contains(FIELD + ":4");
        assertThat(toolbox().findSymbol("Qso")).as("the project's server already has it")
            .isEqualToIgnoringWhitespace("the tree's find_symbol of Qso");
    }

    @Test
    void theFirstThingAWorkerIsToldIsTheTreeThenTheMemberEdits() {
        String rules = SwarmDispatcher.buildBundle(task(), null, "", null, List.of())
            .sharedText();

        int steps = rules.indexOf("Work in small steps");
        assertThat(steps).isNotNegative();
        String first = rules.substring(steps, rules.indexOf("ALWAYS use repository-RELATIVE"));
        assertThat(first).doesNotContain("inspect with read").doesNotContain("reliable");
        assertThat(first.indexOf("shape_of")).isLessThan(first.indexOf("replace_member"));
        assertThat(first.indexOf("replace_member")).isLessThan(first.indexOf("write_file"));
    }

    @Test
    void aCommandThatWritesAFileIsNotCountedAsAShellRead() {
        assertThat(LookupMeter.ofShellCommand("cat > /tmp/Probe.java <<'EOF'\nclass Probe {}\nEOF"))
            .isNull();
        assertThat(LookupMeter.ofShellCommand("cat src/App.java")).isEqualTo(
            LookupMeter.Kind.SHELL_READ);
        assertThat(LookupMeter.ofShellCommand("find . -name '*.java' 2>/dev/null | head -5"))
            .isEqualTo(LookupMeter.Kind.SHELL_READ);
        assertThat(LookupMeter.ofShellCommand("ls src >/dev/null; cat pom.xml"))
            .isEqualTo(LookupMeter.Kind.SHELL_READ);
    }
}
