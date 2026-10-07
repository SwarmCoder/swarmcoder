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
package com.swarmcoder.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the extractor records, and what it refuses — one guideline per lesson.
 *
 * <p>A real project's folder had grown to 47 rules stating about twenty lessons: seven ways of
 * saying "look the API up instead of picking apart compiled classes", six of "write code early and
 * let the build tell you what is wrong", four of "git does not work here". The bodies below are
 * copied verbatim from that folder, so these tests measure the gate against the duplicates that
 * actually got through. The model is a scripted local endpoint; nothing here is paid for.
 */
class GuidelineExtractorTest {

    // Three real rules from dev/bookshelf-demo. All three say the same thing.
    private static final String NOT_JAR_DIVING =
        "Consult the project documentation via lookup_api (topics like RmiService, Component, and "
        + "the docs listed in the brief) before writing code; do not try to infer APIs by "
        + "unpacking jars or searching the local Maven repo.";
    private static final String OVER_JAVAP =
        "Use lookup_api to obtain real library documentation instead of inspecting compiled "
        + "classes or jars with javap.";
    private static final String FOR_DOCS =
        "Use the lookup_api tool to retrieve project documentation instead of searching compiled "
        + "jars or classes, because the docs are indexed and available only through that tool.";

    @TempDir
    Path storeDir;

    /** Scripted SSE endpoint that also records the prompt it was sent. */
    private static HttpServer scriptedLlm(String reply, AtomicReference<String> promptSeen)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            promptSeen.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            ObjectNode chunk = mapper.createObjectNode();
            JsonNode choice = chunk.putArray("choices").addObject().put("index", 0);
            ((ObjectNode) choice).putObject("delta").put("content", reply);
            byte[] body = ("data: " + mapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static AgentSessionRecord failedSession() {
        return new AgentSessionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), 0, "worker-0", "qwen", 0.2, Instant.now(), Instant.now(),
            "KILLED", KillReason.COMPILE_FAIL_TWICE, 5, 300,
            List.of(new TraceEvent(0, Instant.now(), TraceEventKind.TOOL_RESULT, "exec",
                "javap: cannot find class; the docs were never consulted", null, 100)));
    }

    private static VllmClient utility(HttpServer server) {
        return new VllmClient("http://localhost:" + server.getAddress().getPort(), "", "u", true);
    }

    @Test
    void aNewLessonIsRecordedProposedOrActiveUnderAutoPromote() throws Exception {
        String reply = "{\"guidelines\":[{\"slug\":\"use-jackson\",\"scope\":\"PROJECT\","
            + "\"body\":\"Use Jackson for JSON, not Gson, in this repository.\"}]}";
        HttpServer server = scriptedLlm(reply, new AtomicReference<>());
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules proposedTo = new ProjectRules(store, UUID.randomUUID());
            assertThat(new GuidelineExtractor(utility(server), new CloudGate(1_000_000, null),
                proposedTo).extractFromSessions(List.of(failedSession())))
                .containsExactly("use-jackson");
            LearnedGuideline rule = proposedTo.all().get(0);
            assertThat(rule.status()).isEqualTo(GuidelineStatus.PROPOSED);
            assertThat(rule.provenance().source()).isEqualTo("extraction");
            assertThat(rule.markdownBody()).contains("Jackson");
            assertThat(proposedTo.renderActive(12_000))
                .describedAs("a proposal enters no prompt until a person decides")
                .isNull();

            ProjectRules promotedTo = new ProjectRules(store, UUID.randomUUID());
            new GuidelineExtractor(utility(server), new CloudGate(1_000_000, null), promotedTo, true)
                .extractFromSessions(List.of(failedSession()));
            assertThat(promotedTo.all().get(0).status()).isEqualTo(GuidelineStatus.ACTIVE);
        } finally {
            server.stop(0);
        }
    }

    /**
     * The rules the project already has are put in front of the model, so it can see that the
     * lesson it just found is one the project already states. Nothing else can catch a duplicate
     * worded differently: these two real rules share four content words out of eleven and twenty
     * four, and mean exactly the same thing.
     */
    @Test
    void showsTheModelWhatTheProjectAlreadySaysAndDropsWhatItNames() throws Exception {
        AtomicReference<String> prompt = new AtomicReference<>("");
        String reply = "{\"guidelines\":[{\"slug\":\"use-lookup-api-over-javap\","
            + "\"scope\":\"PROJECT\",\"body\":\"" + OVER_JAVAP + "\","
            + "\"duplicateOf\":\"use-lookup-api-not-jar-diving\"}]}";
        HttpServer server = scriptedLlm(reply, prompt);
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.propose("use-lookup-api-not-jar-diving", NOT_JAR_DIVING, false);
            List<String> written = new GuidelineExtractor(utility(server),
                new CloudGate(1_000_000, null), rules).extractFromSessions(List.of(failedSession()));

            // The prompt names the rule and quotes it — without that the model cannot judge.
            assertThat(prompt.get()).contains("use-lookup-api-not-jar-diving");
            assertThat(prompt.get()).contains("do not try to infer APIs by unpacking jars");
            // And the lesson the model said was already covered is not written.
            assertThat(written).isEmpty();
            assertThat(rules.all()).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    /**
     * The backstop, for a model that ignores the instruction: a near-copy is refused on shared
     * content words alone. These two real rules share six content words of eleven and fourteen —
     * an overlap coefficient of 0.545, above the 0.5 gate. Under the old whole-body Jaccard they
     * scored 0.286 and sailed through.
     */
    @Test
    void refusesANearCopyTheModelDidNotFlag() throws Exception {
        String reply = "{\"guidelines\":[{\"slug\":\"use-lookup-api-over-javap\","
            + "\"scope\":\"PROJECT\",\"body\":\"" + OVER_JAVAP + "\"}]}";
        HttpServer server = scriptedLlm(reply, new AtomicReference<>());
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.propose("use-lookup-api-for-docs", FOR_DOCS, false);
            List<String> written = new GuidelineExtractor(utility(server),
                new CloudGate(1_000_000, null), rules).extractFromSessions(List.of(failedSession()));

            assertThat(written).isEmpty();
            assertThat(rules.all()).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    /** A lesson the project does not yet state is still written — the gate is not a wall. */
    @Test
    void stillWritesAGenuinelyNewLesson() throws Exception {
        String reply = "{\"guidelines\":[{\"slug\":\"textfield-withlabel\",\"scope\":\"PROJECT\","
            + "\"body\":\"Give a TextField a visible caption with withLabel(), never with the "
            + "constructor string, which only sets the placeholder.\"}]}";
        HttpServer server = scriptedLlm(reply, new AtomicReference<>());
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.propose("use-lookup-api-for-docs", FOR_DOCS, false);
            rules.propose("use-lookup-api-not-jar-diving", NOT_JAR_DIVING, false);
            List<String> written = new GuidelineExtractor(utility(server),
                new CloudGate(1_000_000, null), rules).extractFromSessions(List.of(failedSession()));

            assertThat(written).containsExactly("textfield-withlabel");
            assertThat(rules.all()).hasSize(3);
            assertThat(rules.all().stream().map(LearnedGuideline::slug))
                .contains("textfield-withlabel");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyExtractionWritesNothing() throws Exception {
        HttpServer server = scriptedLlm("{\"guidelines\":[]}", new AtomicReference<>());
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            assertThat(new GuidelineExtractor(utility(server), new CloudGate(0, null), rules)
                .extractFromSessions(List.of(failedSession()))).isEmpty();
            assertThat(rules.all()).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
