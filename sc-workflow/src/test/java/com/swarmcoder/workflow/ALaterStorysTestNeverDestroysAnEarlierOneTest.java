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
package com.swarmcoder.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 59: run 86's test author wrote its test under the name an earlier
 * story's passing acceptance test already had, and the write replaced it.
 */
class ALaterStorysTestNeverDestroysAnEarlierOneTest {

    private static final String DIR = "app-server/src/test/java/swarm";
    private static final String FILE = DIR + "/accept/LogbookTest.java";

    private static final String EARLIER = """
        package swarm.accept;

        import org.junit.jupiter.api.Test;

        class LogbookTest {
            @Test
            void recordsAnEntry() {
                assertEquals(1, 1);
            }
        }
        """;

    @TempDir
    Path tree;

    @BeforeEach
    void anEarlierStoryCommittedItsTest() throws Exception {
        Files.createDirectories(tree.resolve(DIR + "/accept"));
        Files.writeString(tree.resolve(FILE), EARLIER);
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "earlier story");
        EarlierAcceptanceTests.remember(tree, DIR);
    }

    private void git(String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
            "git", "-C", tree.toString()));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aHandInThatReplacesTheFileWithOnlyItsOwnTestIsSentBackNamingWhatItWouldRemove() {
        String replacement = EARLIER.replace("recordsAnEntry", "listsEntriesByDate");

        String objection = EarlierAcceptanceTests.objection(tree, FILE, replacement);

        assertThat(objection).contains("removed: LogbookTest#recordsAnEntry()")
            .contains("NEW class name");
    }

    @Test
    void aHandInThatKeepsTheEarlierMethodsAndAddsItsOwnIsAccepted() {
        String added = EARLIER.replace("    }\n}\n",
            "    }\n\n    @Test\n    void listsEntriesByDate() {}\n}\n");

        assertThat(added).contains("listsEntriesByDate");
        assertThat(EarlierAcceptanceTests.objection(tree, FILE, added)).isNull();
    }

    @Test
    void aNewClassNameIsNotHeldToAnything() {
        assertThat(EarlierAcceptanceTests.objection(tree, DIR + "/accept/ShelfTest.java",
            EARLIER.replace("LogbookTest", "ShelfTest"))).isNull();
    }

    @Test
    void theEarlierTestsAreWhatTheRedCheckKeepsOutOfItsExpectedFailures() {
        assertThat(EarlierAcceptanceTests.ids(tree, DIR))
            .containsExactly("swarm.accept.LogbookTest#recordsAnEntry");
    }

    @Test
    void aSupersededAttemptPutsTheEarlierTestBackInsteadOfDeletingTheFile() throws Exception {
        Files.writeString(tree.resolve(FILE), "package swarm.accept;\nclass LogbookTest {}\n");

        assertThat(EarlierAcceptanceTests.restore(tree, FILE)).isTrue();

        assertThat(Files.readString(tree.resolve(FILE))).isEqualTo(EARLIER);
        assertThat(EarlierAcceptanceTests.restore(tree, DIR + "/accept/ShelfTest.java")).isFalse();
    }
}
