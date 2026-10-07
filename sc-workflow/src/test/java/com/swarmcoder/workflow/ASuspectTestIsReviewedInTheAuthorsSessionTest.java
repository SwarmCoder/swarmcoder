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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 85 (2026-10-04), the first live send-back of a suspect test: the author answered that
 * its test was wrong and that it "cannot be corrected without guessing", handed in no file, and a
 * four-worker repair round ran against a test its own author had called wrong. The review was one
 * reply with no lookups. It is now a lookup session with {@code compile_test}, and what it came to
 * is read from what the session did: a compiled draft is a correction, a hand-in with nothing
 * compiled is the author standing by its test, neither is no usable answer.
 *
 * <p>The model is scripted; the agent runtime, the lookup and the tools are real.
 */
class ASuspectTestIsReviewedInTheAuthorsSessionTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm";
    private static final String TEST_PATH = ACCEPT_DIR + "/accept/StoreTest.java";
    private static final String OLD = "package swarm.accept;\nclass StoreTest { /* no producer */ }\n";
    private static final String CORRECTED =
        "package swarm.accept;\nclass StoreTest { /* lists StoreProducer */ }\n";
    private static final String EVIDENCE = "worker 0 and worker 1 both failed with WELD-001408";

    @TempDir
    Path repo;
    @TempDir
    Path cache;

    @Test
    void anAuthorThatFindsItsTestWrongLooksUpTheFixCompilesItAndHandsItIn() throws Exception {
        AtomicInteger oneReplies = new AtomicInteger();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> {
                oneReplies.incrementAndGet();
                return "{}";
            }, (turn, conversation) -> switch (turn) {
                case 1 -> ScriptedAgentLlm.Turn.call("read_file",
                    Map.of("path", "project/src/main/java/com/f/StoreProducer.java"));
                case 2 -> ScriptedAgentLlm.Turn.call("compile_test",
                    Map.of("path", TEST_PATH, "content", CORRECTED));
                default -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("wrote", "The test listed no store producer as a bean."));
            })) {
            TestAuthorClient author = author(llm);

            TestAuthorClient.Reviewed reviewed = author.reviewSuspectTest(repo, task(), null,
                TEST_PATH, OLD, EVIDENCE, "");

            assertThat(reviewed.answered()).as(reviewed.reason()).isTrue();
            assertThat(reviewed.testIsWrong()).isTrue();
            assertThat(reviewed.reason()).isEqualTo("The test listed no store producer as a bean.");
            assertThat(reviewed.corrected().paths()).containsExactly(TEST_PATH);
            assertThat(Files.readString(repo.resolve(TEST_PATH))).isEqualTo(CORRECTED);
            assertThat(llm.sessionRequests).hasSize(3);
            assertThat(llm.sessionRequests.get(0)).as("the question, the evidence and both ways "
                + "to answer are in the opening").contains(EVIDENCE, "HOW TO ANSWER",
                    "THE TEST IS WRONG", "THE TEST IS RIGHT");
            assertThat(llm.sessionRequests.get(1)).as("the lookup returned the real file")
                .contains("class StoreProducer");
            assertThat(oneReplies.get()).as("no reply without tools was needed").isZero();
        }
    }

    @Test
    void anAuthorThatHandsInWithNothingCompiledStandsByItsTest() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> ScriptedAgentLlm.Turn.call("report_done",
                Map.of("wrote", "The candidates never registered the producer.")))) {
            TestAuthorClient.Reviewed reviewed = author(llm).reviewSuspectTest(repo, task(), null,
                TEST_PATH, OLD, EVIDENCE, "");

            assertThat(reviewed.answered()).isTrue();
            assertThat(reviewed.testIsWrong()).isFalse();
            assertThat(reviewed.reason()).isEqualTo("The candidates never registered the producer.");
            assertThat(reviewed.corrected()).isNull();
            assertThat(Files.readString(repo.resolve(TEST_PATH))).as("nothing was written")
                .isEqualTo(OLD);
        }
    }

    @Test
    void aSessionThatEndsOnWordsAloneIsNoUsableAnswerAndSaysWhatItEndedOn() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> ScriptedAgentLlm.Turn.text(
                "It cannot be corrected without guessing."))) {
            TestAuthorClient.Reviewed reviewed = author(llm).reviewSuspectTest(repo, task(), null,
                TEST_PATH, OLD, EVIDENCE, "");

            assertThat(reviewed.answered()).isFalse();
            assertThat(reviewed.reason()).contains("neither a corrected test nor a hand-in",
                "It cannot be corrected without guessing.");
        }
    }

    private TestAuthorClient author(ScriptedAgentLlm llm) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java/com/f"));
        Files.writeString(repo.resolve("src/main/java/com/f/StoreProducer.java"),
            "package com.f;\npublic class StoreProducer {}\n");
        Files.createDirectories(repo.resolve(TEST_PATH).getParent());
        Files.writeString(repo.resolve(TEST_PATH), OLD);
        VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
        CloudGate gate = new CloudGate(0, null);
        TestAuthorClient author = new TestAuthorClient(client, gate);
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", repo, "local")), null, cache);
        author.setLookupAgent(new LookupAgent(curator, null, repo, gate, new KoogAgentRuntime(),
            null, null));
        return author;
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "store survives a restart", "make it survive",
            Set.of("src/main/java"), Set.of(), List.of(), ACCEPT_DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }
}
