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
package com.swarmcoder.inference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Is the model server there? Against a real local HTTP server, because the interesting answers are
 * about sockets and the interesting bug would be mocked away.
 *
 * <p>The distinction this file exists to protect is {@code DOWN} versus {@code UNKNOWN}. A build pauses
 * on DOWN and proceeds on UNKNOWN, so collapsing them either hangs every run in a setup that reaches its
 * workers by some route this cannot see, or dispatches eight workers at a box that is switched off.
 */
class EndpointReachabilityTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void anEndpointThatAnswersIsUpAndItsModelsAreReadBack() throws Exception {
        start(200, "{\"data\":[{\"id\":\"qwen36-27b\"},{\"id\":\"gemma4-31b\"}]}");

        EndpointReachability.Probe probe = new EndpointReachability().probe(baseUrl());

        assertThat(probe.status()).isEqualTo(EndpointReachability.Status.UP);
        assertThat(probe.status().isOutage()).isFalse();
        assertThat(probe.models()).isEqualTo("qwen36-27b, gemma4-31b");
        assertThat(probe.endpoint()).endsWith("/v1/models");
        assertThat(probe.detail()).isNull();
    }

    @Test
    void anErrorStatusIsStillReachable() throws Exception {
        // A 500 is a box that answered. Treating it as down would pause builds against an endpoint
        // that is plainly running, and the operator would be told to restart something already up.
        start(500, "nope");

        EndpointReachability.Probe probe = new EndpointReachability().probe(baseUrl());

        assertThat(probe.status()).isEqualTo(EndpointReachability.Status.UP);
        assertThat(probe.detail())
            .describedAs("reachable, but the operator is told it is not serving a model list")
            .contains("HTTP 500");
    }

    @Test
    void nothingListeningIsDown() {
        // Port 1 on loopback: nothing is there, and nothing can be.
        EndpointReachability.Probe probe = new EndpointReachability().probe("http://127.0.0.1:1");

        assertThat(probe.status()).isEqualTo(EndpointReachability.Status.DOWN);
        assertThat(probe.status().isOutage()).isTrue();
        assertThat(probe.endpoint())
            .describedAs("'down' without the endpoint it was measured against is unactionable")
            .contains("127.0.0.1:1");
        assertThat(probe.detail()).isNotBlank();
    }

    @Test
    void nothingConfiguredIsUnknownAndNotAnOutage() {
        EndpointReachability probe = new EndpointReachability();
        for (String nothing : List.of("", "   ")) {
            assertThat(probe.probe(nothing).status())
                .isEqualTo(EndpointReachability.Status.UNKNOWN);
        }
        assertThat(probe.probe(null).status()).isEqualTo(EndpointReachability.Status.UNKNOWN);
        assertThat(probe.probe(null).status().isOutage())
            .describedAs("a build must never pause waiting for a box nobody named")
            .isFalse();
    }

    @Test
    void aMalformedUrlIsUnknownRatherThanDown() {
        // Fail open: the worst a defect in here may do is return the old behaviour, never stop a build.
        assertThat(new EndpointReachability().probe("not a url at all").status())
            .isEqualTo(EndpointReachability.Status.UNKNOWN);
    }

    @Test
    void theAnswerIsCachedSoPollingDoesNotHammerTheBox() throws Exception {
        AtomicInteger hits = start(200, "{\"data\":[]}");
        EndpointReachability probe = new EndpointReachability();

        for (int i = 0; i < 20; i++) {
            assertThat(probe.probe(baseUrl()).status()).isEqualTo(EndpointReachability.Status.UP);
        }

        assertThat(hits.get())
            .describedAs("the health dot polls every few seconds and the engine asks per run; one "
                + "request for twenty questions is the point of the cache")
            .isEqualTo(1);
    }

    @Test
    void aTrailingSlashOrAnExplicitV1BothResolveToTheSameEndpoint() throws Exception {
        start(200, "{\"data\":[]}");
        EndpointReachability probe = new EndpointReachability();
        String bare = baseUrl();

        // Config in the wild carries all three spellings; none of them may read as a different box.
        assertThat(probe.probe(bare).endpoint())
            .isEqualTo(probe.probe(bare + "/").endpoint())
            .isEqualTo(probe.probe(bare + "/v1").endpoint());
    }

    // --- several endpoints ------------------------------------------------------------------------

    @Test
    void oneReachableFamilyIsEnough() throws Exception {
        start(200, "{\"data\":[]}");

        EndpointReachability.Probe probe = new EndpointReachability()
            .probeAny(List.of("http://127.0.0.1:1", baseUrl()));

        assertThat(probe.status())
            .describedAs("a swarm split across two families still has somewhere to run when one is "
                + "down; requiring all of them would pause a build that could have proceeded")
            .isEqualTo(EndpointReachability.Status.UP);
    }

    @Test
    void allConfiguredAndNoneAnsweringIsDown() {
        EndpointReachability.Probe probe = new EndpointReachability()
            .probeAny(List.of("http://127.0.0.1:1", "http://127.0.0.1:2"));

        assertThat(probe.status()).isEqualTo(EndpointReachability.Status.DOWN);
        assertThat(probe.endpoint())
            .describedAs("the first one that failed is named, so the message points somewhere")
            .contains("127.0.0.1:1");
    }

    @Test
    void noEndpointsAtAllIsUnknown() {
        assertThat(new EndpointReachability().probeAny(List.of()).status())
            .isEqualTo(EndpointReachability.Status.UNKNOWN);
    }

    // --- fixture ---------------------------------------------------------------------------------

    /** A loopback server on an ephemeral port; the counter is how many requests actually arrived. */
    private AtomicInteger start(int status, String body) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return hits;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
