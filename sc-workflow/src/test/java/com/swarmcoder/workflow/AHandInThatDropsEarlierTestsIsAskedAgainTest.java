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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 60, the two leftovers of section 59.
 *
 * <p>A hand-in refused for dropping an earlier story's test used to end the call: the earlier
 * file stayed, and the story had no test. And which tests are "earlier" was a copy in memory,
 * gone after a process restart. The model is scripted ({@link ScriptedAgentLlm}); the agent
 * runtime, the lookups and the tools are the real ones.
 */
class AHandInThatDropsEarlierTestsIsAskedAgainTest {

    private static final String DIR = "src/test/java/swarm";
    private static final String EARLIER_FILE = DIR + "/accept/LogbookTest.java";
    private static final String NEW_FILE = DIR + "/accept/UsabilityTest.java";

    private static final String EARLIER = """
        package swarm.accept;

        import org.junit.jupiter.api.Test;

        public class LogbookTest {
            @Test
            public void recordsAnEntry() {
            }
        }
        """;

    @TempDir
    Path app;
    @TempDir
    Path cache;

    @BeforeEach
    void anEarlierStoryCommittedItsTest() throws Exception {
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(app.resolve("src/main/java/com/acme/shop/Catalog.java"),
            "package com.acme.shop;\n\npublic class Catalog {\n}\n");
        write(app.resolve(EARLIER_FILE), EARLIER);
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "earlier story");
        EarlierAcceptanceTests.forget(); // a new process
    }

    @Test
    void theAuthorIsToldInItsOwnConversationAndTheEarlierFileStaysAsItWas() throws Exception {
        EarlierAcceptanceTests.pin(app, "HEAD");
        String replacing = EARLIER.replace("recordsAnEntry", "usableFromKeyboard");
        String ownClass = replacing.replace("LogbookTest", "UsabilityTest");
        String claim = "the screen is usable from the keyboard => "
            + "swarm.accept.UsabilityTest#usableFromKeyboard";
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"files\":[],\"wrote\":[]}",
                (turn, conversation) -> switch (turn) {
                    // Run 86's mistake: the new test under the earlier story's class name.
                    case 1 -> ScriptedAgentLlm.Turn.call("compile_test",
                        Map.of("path", EARLIER_FILE, "content", replacing));
                    case 2 -> ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote", claim));
                    // Asked again, in the same conversation: a class of its own.
                    case 3 -> ScriptedAgentLlm.Turn.call("compile_test",
                        Map.of("path", NEW_FILE, "content", ownClass));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote", claim));
                })) {
            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            CloudGate gate = new CloudGate(0, null);
            TestAuthorClient author = new TestAuthorClient(client, gate);
            author.setLookupAgent(new LookupAgent(new KnowledgeCurator(
                List.of(new KnowledgeCurator.Root("project", app, "local")), null, cache),
                null, app, gate, new KoogAgentRuntime(), null, null));

            TestAuthorClient.Authored authored = author.repairBrokenTest(app, task(), null,
                Map.of(NEW_FILE, ownClass.replace("usableFromKeyboard", "old")),
                "It does not compile.");

            assertThat(llm.sessionRequests)
                .as("two turns, then two more of the SAME session - no new session, no fallback")
                .hasSize(4);
            assertThat(llm.sessionRequests.get(2))
                .contains("THIS IS THE SAME CONVERSATION")
                .contains("NOT taken")
                .contains("LogbookTest#recordsAnEntry");
            assertThat(authored.failureReason()).isNull();
        }
        assertThat(Files.readString(app.resolve(EARLIER_FILE)))
            .as("the earlier story's test is exactly as it was").isEqualTo(EARLIER);
        assertThat(Files.readString(app.resolve(NEW_FILE)))
            .as("and the story has its test, in a class of its own")
            .contains("class UsabilityTest").contains("usableFromKeyboard");
    }

    @Test
    void whatIsEarlierIsReadFromThePinnedBaseCommitSoARestartLosesNothing() throws Exception {
        String base = gitOut("rev-parse", "HEAD");
        // The story's own test is committed on top of the base, as on the run's tests ref.
        write(app.resolve(NEW_FILE), EARLIER.replace("LogbookTest", "UsabilityTest")
            .replace("recordsAnEntry", "usableFromKeyboard"));
        git("add", "-A");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "this story");

        assertThat(EarlierAcceptanceTests.earlier(app, EARLIER_FILE))
            .as("after a restart nothing is remembered").isNull();

        // What the workflow does on every step, from the persisted run.
        EarlierAcceptanceTests.pin(app, base);

        assertThat(EarlierAcceptanceTests.ids(app, DIR))
            .as("this story's own committed test is not an earlier one")
            .containsExactly("swarm.accept.LogbookTest#recordsAnEntry");
        assertThat(EarlierAcceptanceTests.objection(app, EARLIER_FILE,
            EARLIER.replace("recordsAnEntry", "somethingElse")))
            .contains("removed: LogbookTest#recordsAnEntry()");
        assertThat(EarlierAcceptanceTests.objection(app, NEW_FILE, "class UsabilityTest {}"))
            .as("its own file may be rewritten").isNull();

        Files.writeString(app.resolve(EARLIER_FILE), "class LogbookTest {}\n");
        assertThat(EarlierAcceptanceTests.restore(app, EARLIER_FILE)).isTrue();
        assertThat(Files.readString(app.resolve(EARLIER_FILE))).isEqualTo(EARLIER);
    }

    // -------------------------------------------------------------------------------------------

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Keyboard use", "The screen is usable from keys.",
            Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "the screen is usable from the keyboard",
                "swarm.accept.UsabilityTest#usableFromKeyboard")),
            DIR, null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }

    private void git(String... args) throws Exception {
        gitOut(args);
    }

    private String gitOut(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", app.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes()).strip();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return out;
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
