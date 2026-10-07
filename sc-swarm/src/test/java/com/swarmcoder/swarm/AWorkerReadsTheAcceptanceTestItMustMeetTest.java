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

import com.swarmcoder.domain.AuthoredTest;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.LookupMeter;
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
 * Owner's decision 2026-10-05 (DEVELOPER_CORRECTIONS section 61): a worker may READ the
 * acceptance test its task must satisfy. Run 88 paid a repair round of 1,031,748 prompt tokens
 * for a rule that existed only in the hidden test. Reading is not writing: the protected
 * directory stays closed to every write.
 */
class AWorkerReadsTheAcceptanceTestItMustMeetTest {

    private static final String DIR = "app-server/src/test/java/swarm";
    private static final String TEST = DIR + "/accept/UsabilityTest.java";
    private static final String SOURCE = """
        package swarm.accept;

        class UsabilityTest {
            @org.junit.jupiter.api.Test
            void usableFromKeyboard() {
                assertThat(shortcutOf("dateRangeFilter")).isNotNull();
            }

            @org.junit.jupiter.api.Test
            void another() {
            }

            private String shortcutOf(String field) {
                return field;
            }
        }
        """;

    @TempDir
    Path checkout;

    private Task task() {
        Task task = new Task(UUID.randomUUID(), 1, "server", "Return it.",
            Set.of("app-server/src/main/java/Service.java"), Set.of(), List.of(), DIR,
            null, null, new SwarmPolicy(4, false, 0.2, 0.9, List.of()), TaskState.READY);
        AuthoredTests authored = new AuthoredTests();
        authored.setFiles(new ArrayList<>(List.of(TEST)));
        authored.setTests(new ArrayList<>(List.of(
            new AuthoredTest("swarm.accept.UsabilityTest#usableFromKeyboard", TEST, "", ""))));
        task.setAuthoredTests(authored);
        task.setAuthoredTestPaths(List.of(TEST));
        return task;
    }

    private WorkerToolbox toolbox() {
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task());
        toolbox.setAcceptanceSource(path -> TEST.equals(path) ? SOURCE : null);
        return toolbox;
    }

    @Test
    void theClaimedMethodAndItsHelperAreReadFromTheCommittedTest() {
        String shown = toolbox().acceptanceTest("");

        assertThat(shown).contains("void usableFromKeyboard()")
            .contains("String shortcutOf(String field)")
            .contains("dateRangeFilter")
            .doesNotContain("void another()");
        assertThat(toolbox().acceptanceTest("UsabilityTest#usableFromKeyboard"))
            .contains("void usableFromKeyboard()");
        assertThat(toolbox().acceptanceTest("SomethingElse")).contains("No claimed test matches");
    }

    @Test
    void aTaskWithNoCommittedTestIsToldSoNotGivenAnInvention() {
        WorkerToolbox none = new WorkerToolbox(checkout, task());   // no source wired

        assertThat(none.acceptanceTest("")).contains("is not in the run's tests commit");
    }

    @Test
    void theReadIsAStructuredLookupOfItsOwnKindAndAToolTheWorkerHas() {
        assertThat(LookupMeter.Kind.ACCEPTANCE_TEST.structured()).isTrue();
        assertThat(toolbox().bindings()).extracting(b -> b.name()).contains("acceptance_test");
    }

    @Test
    void theProtectedDirectoryIsStillClosedToEveryWrite() throws Exception {
        Path file = checkout.resolve(TEST);
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        WorkerToolbox toolbox = toolbox();

        assertThat(toolbox.writeFile(TEST, "package swarm.accept; class UsabilityTest {}"))
            .startsWith("error:");
        assertThat(toolbox.writeFile(DIR + "/accept/NewTest.java", "class NewTest {}"))
            .startsWith("error:");
        assertThat(toolbox.applyDiff("--- a/" + TEST + "\n+++ b/" + TEST + "\n@@ -1,1 +1,1 @@\n"
            + "-package swarm.accept;\n+package swarm.other;\n")).startsWith("error");
        assertThat(toolbox.replaceMember("UsabilityTest#another",
            "@org.junit.jupiter.api.Test void another() { int x = 1; }")).startsWith("error");
        assertThat(toolbox.addMember("UsabilityTest", "void added() {}")).startsWith("error");
        assertThat(Files.readString(file)).isEqualTo(SOURCE);
    }

    /** Section 63: the journey the task claims is read the same way, whole, and never written. */
    @Test
    void theJourneyTheTaskClaimsIsReadWithTheTestAndCannotBeWritten() {
        String journeyPath = DIR + "/accept/add-contact.journey.yaml";
        String journey = "journey: A contact is added\nsteps:\n"
            + "  - click: \"role=button[name=\\\"Add contact\\\"]\"\n"
            + "  - expectVisible: \"text=DL1ABC\"\n";
        Task task = task();
        task.setJourneyPaths(List.of(journeyPath));
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task);
        toolbox.setAcceptanceSource(path -> TEST.equals(path) ? SOURCE
            : journeyPath.equals(path) ? journey : null);

        assertThat(toolbox.acceptanceTest(""))
            .as("asked with nothing: the test methods and the journey")
            .contains("void usableFromKeyboard()")
            .contains("--- journey " + journeyPath + " ---")
            .contains("role=button[name=\\\"Add contact\\\"]")
            .contains("You cannot change it");
        assertThat(toolbox.acceptanceTest("add-contact"))
            .as("asked by its name: the journey alone")
            .contains("expectVisible").doesNotContain("usableFromKeyboard");
        assertThat(toolbox.acceptanceTest("UsabilityTest")).doesNotContain("--- journey");
        assertThat(toolbox.writeFile(journeyPath, "journey: easier\nsteps: []\n"))
            .as("the protected directory is closed to a journey as to a test")
            .startsWith("error:");
    }

    @Test
    void thePromptNamesTheToolAndNoLongerSaysTheTestIsNotThere() {
        String prompt = SwarmDispatcher.buildBundle(task(), null, "", null, List.of()).sharedText();

        assertThat(prompt).contains("acceptance_test")
            .contains("are protected; never modify them")
            .doesNotContain("do not look for them")
            .doesNotContain("not in your checkout while you work")
            .as("no prompt-stuffing: the method body is not pasted")
            .doesNotContain("usableFromKeyboard");
    }
}
