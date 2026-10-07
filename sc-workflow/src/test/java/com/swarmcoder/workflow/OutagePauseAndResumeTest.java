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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunPause;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outage day, reproduced (UX v3 §7, "the re-run of the worst session").
 *
 * <p>What used to happen when the Spark died mid-build: the architect returned null and the run
 * designed nothing, the planner returned null and the run swarmed the whole slice as one task, every
 * worker died and the run spent its one repair round on candidates that had never reached a model,
 * and the operator was left with dead approvals and stranded stories. Every one of those was the
 * engine treating "the model was not there" as an answer.
 *
 * <p>What must happen now: the build stops where it is, says so in words the operator can act on
 * (including <em>which</em> server), costs nothing while it waits, and carries on by itself when the
 * server comes back. Nothing to do, nothing to clean up.
 */
class OutagePauseAndResumeTest {

    @TempDir
    Path storeDir;

    /** Retry fast, or this test would spend a quarter of a minute asleep on purpose. */
    @BeforeEach
    void retryFast() {
        System.setProperty("swarmcoder.outage.firstWaitMillis", "150");
    }

    @AfterEach
    void restoreRetryCadence() {
        System.clearProperty("swarmcoder.outage.firstWaitMillis");
    }

    @Test
    void aBuildWaitsForItsModelServerAndFinishesWhenItComesBack() throws Exception {
        int port = freePort();
        UUID runId = UUID.randomUUID();
        CloudGate gate = new CloudGate(1_000_000, null);

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            VllmClient client = new VllmClient("http://localhost:" + port, "", "test-model", true);
            AgentRuntime unused = spec -> {
                throw new UnsupportedOperationException("no workers in this test");
            };
            WorkflowEngine engine = new WorkflowEngine(unused, run -> run, client, store, gate, null,
                CloudRoles.allOn(client, gate), null);

            // Nothing is listening on that port yet: the very first role call is an outage.
            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(),
                new RunReport(runId, "add multiply to Calculator")));

            Run paused = awaitPaused(store, runId);

            // It stopped where it was rather than advancing on nothing…
            assertThat(paused.state())
                .as("the stage that could not run is the stage it will retry")
                .isEqualTo(RunState.DESIGN);
            // …it is not pretending to be finished or broken…
            assertThat(RunPause.paused(paused)).isTrue();
            // …and it says which server, in words, without the operator reading a stack trace.
            assertThat(paused.pauseReason())
                .contains("paused")
                .contains("not answering")
                .contains("resumes by itself")
                .contains("localhost:" + port);
            assertThat(paused.pauseEndpoint()).contains("localhost:" + port);
            // Not yet escalated: a blip must not summon anybody.
            assertThat(RunPause.escalated(paused, Instant.now())).isFalse();
            // And the waiting is free. The prompt is charged before it is sent, so without a refund
            // an outage would bill every retry for tokens no model ever saw.
            assertThat(gate.used()).as("retries against a dead endpoint cost nothing").isZero();

            // The server comes back. Nobody presses anything.
            try (Endpoint endpoint = new Endpoint(port)) {
                awaitState(store, runId, RunState.DELIVERED);

                Run finished = store.root().runs.get(runId);
                assertThat(finished.pausedSince())
                    .as("the pause is cleared once it makes progress, or the card lies for ever")
                    .isNull();
                assertThat(finished.pauseReason()).isNull();
                assertThat(endpoint.calls).as("it really did talk to the recovered server")
                    .isPositive();
            }
        }
    }

    @Test
    void theBackoffGrowsButNeverExceedsItsCeiling() {
        System.clearProperty("swarmcoder.outage.firstWaitMillis");

        assertThat(OutagePause.backoff(1)).isEqualTo(OutagePause.FIRST_WAIT);
        assertThat(OutagePause.backoff(2)).isEqualTo(OutagePause.FIRST_WAIT.multipliedBy(2));
        // However long the outage lasts, it settles into a steady poll rather than growing without
        // bound — an endpoint that returns after two hours must still be noticed promptly.
        assertThat(OutagePause.backoff(50)).isEqualTo(OutagePause.MAX_WAIT);
        assertThat(OutagePause.backoff(5000)).isEqualTo(OutagePause.MAX_WAIT);
    }

    @Test
    void escalationIsMeasuredFromWhenTheEndpointWentAway_notFromTheLatestRetry() {
        Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
            null, null, null, null, null, Instant.now(), null);
        Instant now = Instant.now();
        run.setPausedSince(now.minus(Duration.ofMinutes(RunPause.ESCALATE_AFTER_MINUTES + 1)));

        assertThat(RunPause.escalated(run, now))
            .as("a pause this long is something a person should be asked about")
            .isTrue();

        // A hundred retries must not reset the clock, or a long outage would never escalate: the
        // pause is stamped once, at the first failure.
        Instant first = run.pausedSince();
        OutagePause.pause(run, new com.swarmcoder.inference.EndpointOutage(
            "http://localhost:1", "still down", null));
        assertThat(run.pausedSince()).isEqualTo(first);
        assertThat(RunPause.escalated(run, now)).isTrue();
    }

    private static Run awaitPaused(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (RunPause.paused(run)) {
                return run;
            }
            Thread.sleep(50);
        }
        Run last = store.root().runs.get(runId);
        throw new AssertionError("Run never paused; last state: "
            + (last == null ? "never persisted" : last.state()));
    }

    private static void awaitState(ArtifactStore store, UUID runId, RunState expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.state() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        Run last = store.root().runs.get(runId);
        throw new AssertionError("Run never reached " + expected + "; last state: "
            + (last == null ? "never persisted" : last.state())
            + (last != null && last.pauseReason() != null ? " (" + last.pauseReason() + ")" : ""));
    }

    /** A port that was just free — nothing is listening on it until {@link Endpoint} claims it. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * The model server coming back, on the port the run has been retrying.
     *
     * <p>It answers unusably on purpose: the point under test is the pause and the resume, so the
     * roles degrade through their ordinary refusal paths (minimal design, fallback plan) and the run
     * reaches its terminal state without a swarm.
     */
    private static final class Endpoint implements AutoCloseable {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final HttpServer server;
        volatile int calls;

        Endpoint(int port) throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                JsonNode ignored = MAPPER.readTree(exchange.getRequestBody());
                calls++;
                ObjectNode chunk = MAPPER.createObjectNode();
                ObjectNode choice = chunk.putArray("choices").addObject();
                choice.put("index", 0);
                choice.putObject("delta").put("content", "I decline to produce JSON.");
                byte[] body = ("data: " + MAPPER.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
