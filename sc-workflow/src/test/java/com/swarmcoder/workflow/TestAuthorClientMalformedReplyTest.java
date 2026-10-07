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
 * The test author surviving a reply that will not parse — the run {@code 9e69a8ec…} incident
 * (2026-09-03): one paid call, one JSON parse error, no retry, the raw reply thrown away, and the
 * run parked blaming "the testAuthor endpoint" for a failure the endpoint had nothing to do with.
 */
class TestAuthorClientMalformedReplyTest {

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
    void aMalformedReplyIsRetriedOnceWithTheErrorFedBack() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondConversation = new AtomicReference<>("");
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return "I'll write a test for that now.";
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

            assertThat(calls.get()).as("exactly one retry, not more").isEqualTo(2);
            assertThat(authored.failureReason()).isNull();
            assertThat(authored.paths()).containsExactly("src/test/java/swarm/accept/MultiplyAcceptTest.java");
            assertThat(secondConversation.get())
                .contains("I'll write a test for that now.")
                .contains("That was not valid JSON")
                .contains("Reply with only the JSON object");
        }
    }

    @Test
    void aSecondMalformedReplyIsKeptAsABlobAndTheReasonIsHonest() throws Exception {
        List<byte[]> stored = new ArrayList<>();
        BlobSink capture = content -> {
            stored.add(content);
            return "deadbeef";
        };
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            calls.incrementAndGet();
            return "{\\\"files\\\": [broken";   // still not valid JSON on retry either
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null),
                capture);

            TestAuthorClient.Authored authored = author.authorTests(repo, task(), null);

            assertThat(calls.get()).as("one call, one retry, then stop").isEqualTo(2);
            assertThat(authored.paths()).isEmpty();
            assertThat(authored.failureReason())
                .contains("not valid JSON (twice)")
                .contains("kept as blob deadbeef")
                .doesNotContain("endpoint");
            assertThat(stored).hasSize(1);
            assertThat(new String(stored.get(0), StandardCharsets.UTF_8)).isEqualTo("{\\\"files\\\": [broken");
        }
    }
}
