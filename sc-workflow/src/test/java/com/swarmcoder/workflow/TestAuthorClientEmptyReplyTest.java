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
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.verify.BlobSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test author surviving a reply that parses but writes nothing — harness run 16
 * (2026-09-03): a valid JSON reply with an empty {@code files} array, no retry, and the run
 * parked on "Nothing recorded during test authoring explains why", which was true only because
 * nothing had ever asked. The malformed-JSON case and the vocabulary-mismatch case both already
 * got a "re-ask once" allowance; this is the last front-half gap for the test author.
 */
class TestAuthorClientEmptyReplyTest {

    @TempDir
    Path repo;

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "multiply", "add multiply",
            Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "multiply works",
                "swarm.accept.MultiplyAcceptTest")),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    @Test
    void anEmptyReplyIsReaskedOnceNamingTheChecks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondConversation = new AtomicReference<>("");
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return "{\"files\":[]}";
            }
            secondConversation.set(conversation);
            return """
                {"files":[{"path":"src/test/java/swarm/accept/MultiplyAcceptTest.java",
                 "content":"package swarm.accept; class MultiplyAcceptTest {}"}],"wrote":[]}
                """;
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null));

            TestAuthorClient.Authored authored = author.authorTests(repo, task(), null);

            assertThat(calls.get()).as("exactly one re-ask, not more").isEqualTo(2);
            assertThat(authored.failureReason()).isNull();
            assertThat(authored.paths()).containsExactly("src/test/java/swarm/accept/MultiplyAcceptTest.java");
            assertThat(secondConversation.get())
                .as("told what it returned nothing for, and what to do about it")
                .contains("You returned no test file")
                .contains("multiply works")
                .contains("swarm.accept.MultiplyAcceptTest")
                .contains("reply with the same JSON object with the files filled in");
        }
    }

    @Test
    void anEmptyReplyTwiceIsFailedWithTheReasonAndABlobRef() throws Exception {
        List<byte[]> stored = new ArrayList<>();
        BlobSink capture = content -> {
            stored.add(content);
            return "deadbeef";
        };
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            calls.incrementAndGet();
            return "{\"files\":[]}"; // still empty on the re-ask
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null),
                capture);

            TestAuthorClient.Authored authored = author.authorTests(repo, task(), null);

            assertThat(calls.get()).as("one call, one re-ask, then stop").isEqualTo(2);
            assertThat(authored.paths()).isEmpty();
            assertThat(authored.failureReason())
                .isEqualTo("the test author returned no test file twice; its last reply is kept "
                    + "as blob deadbeef");
            assertThat(stored).hasSize(1);
            assertThat(new String(stored.get(0), StandardCharsets.UTF_8)).isEqualTo("{\"files\":[]}");
        }
    }

    @Test
    void aTaskWithNoChecksAndAnEmptyReplyIsNeverAsked() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            calls.incrementAndGet();
            return "{\"files\":[]}";
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null));
            Task noChecks = new Task(UUID.randomUUID(), 1, "enabler", "set things up",
                Set.of("src/main"), Set.of(), List.of(),
                "src/test/java/swarm", null, null,
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);

            TestAuthorClient.Authored authored = author.authorTests(repo, noChecks, null);

            assertThat(calls.get()).as("nothing was due, so nothing was asked").isZero();
            assertThat(authored).isSameAs(TestAuthorClient.Authored.NOTHING);
        }
    }
}
