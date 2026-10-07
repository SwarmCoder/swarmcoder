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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "The model said no" vs "the model was not there" — the distinction the whole failure policy rests
 * on (UX v3 §2.4).
 *
 * <p>The classifier has an asymmetric cost, and these tests pin both directions. A missed outage
 * degrades to the old behaviour: the run concludes something from a failure, which is bad but
 * survivable and visible. A FALSE outage is worse: the run would wait for ever for an endpoint that
 * is answering perfectly well and simply refusing the request. So the default is "refusal", and
 * {@link #anAnswerWeDislikeIsNotAnOutage} is the more important of the two.
 */
class EndpointOutageTest {

    @Test
    void nothingListeningIsAnOutage() throws Exception {
        VllmClient client = new VllmClient(
            "http://localhost:" + closedPort(), null, "test-model", true);

        assertThatThrownBy(() -> client.chatCompletionStream(
                List.of(Map.of("role", "user", "content", "hello")), null, 0.2))
            .isInstanceOf(EndpointOutage.class)
            .hasMessageContaining("localhost");
    }

    @Test
    void aServerErrorIsAnOutage_becauseWaitingIsTheRightResponse() throws Exception {
        // 503 is a model still loading or an engine at capacity: the box is there, it cannot serve
        // this yet, and it will be able to later.
        try (Endpoint endpoint = new Endpoint(503, "model is loading")) {
            VllmClient client = new VllmClient(endpoint.baseUrl(), null, "test-model", true);

            assertThatThrownBy(() -> client.chatCompletionStream(
                    List.of(Map.of("role", "user", "content", "hello")), null, 0.2))
                .isInstanceOf(EndpointOutage.class)
                .hasMessageContaining("503");
        }
    }

    @Test
    void tooManyRequestsIsAnOutage() throws Exception {
        try (Endpoint endpoint = new Endpoint(429, "slow down")) {
            VllmClient client = new VllmClient(endpoint.baseUrl(), null, "test-model", true);

            assertThatThrownBy(() -> client.chatCompletionStream(
                    List.of(Map.of("role", "user", "content", "hello")), null, 0.2))
                .isInstanceOf(EndpointOutage.class);
        }
    }

    /**
     * A 400 is the endpoint answering on the merits — our request was wrong. Retrying it for thirty
     * minutes would be a build that never fails and never progresses, with a card claiming a server
     * outage that an operator would go and look for and not find.
     */
    @Test
    void aBadRequestIsNotAnOutage() throws Exception {
        try (Endpoint endpoint = new Endpoint(400, "body Field required")) {
            VllmClient client = new VllmClient(endpoint.baseUrl(), null, "test-model", true);

            assertThatThrownBy(() -> client.chatCompletionStream(
                    List.of(Map.of("role", "user", "content", "hello")), null, 0.2))
                .isNotInstanceOf(EndpointOutage.class);
        }
    }

    @Test
    void anAnswerWeDislikeIsNotAnOutage() {
        // The shapes a refusal actually arrives in: unparseable JSON, a schema mismatch, a model
        // declining. None of them may make a run wait for a server that is up.
        assertThat(EndpointOutage.isOutage(
            new RuntimeException("Unrecognized token 'I': was expecting a JSON value"))).isFalse();
        assertThat(EndpointOutage.isOutage(new IllegalArgumentException("No enum constant"))).isFalse();
        assertThat(EndpointOutage.isOutage(new NullPointerException())).isFalse();
    }

    @Test
    void transportFailuresAreRecognisedThroughTheCauseChain() {
        // Agent frameworks wrap the transport several layers deep; the outer layer says nothing.
        assertThat(EndpointOutage.isOutage(
            new RuntimeException("agent step failed",
                new IllegalStateException("llm call failed",
                    new ConnectException("Connection refused"))))).isTrue();
        assertThat(EndpointOutage.isOutage(
            new UncheckedIOException(new IOException("stream closed",
                new HttpConnectTimeoutException("timed out"))))).isTrue();
    }

    /**
     * A circular cause chain must not hang the classifier, which is consulted on every failure.
     *
     * <p>The JDK forbids an exception being its own cause, but it does not forbid a cycle: this pair
     * is legal, and a naive walk down {@code getCause()} never returns. Both directions are checked
     * because the classifier walks the chain twice — once to decide, once to name the root.
     */
    @Test
    void aCircularCauseChainTerminates() {
        RuntimeException inner = new RuntimeException("inner");
        RuntimeException outer = new RuntimeException("outer", inner);
        inner.initCause(outer);

        assertThat(EndpointOutage.isOutage(outer)).isFalse();
        assertThat(EndpointOutage.from("http://spark:8000", outer)).isNull();

        // And a cycle that DOES contain a transport failure is still classified correctly.
        ConnectException refused = new ConnectException("Connection refused");
        RuntimeException wrapper = new RuntimeException("agent step failed", refused);
        refused.initCause(wrapper);
        assertThat(EndpointOutage.isOutage(wrapper)).isTrue();
        assertThat(EndpointOutage.from("http://spark:8000", wrapper)).isNotNull();
    }

    @Test
    void fromNamesTheEndpointSoTheOperatorKnowsWhichBoxIsDown() {
        EndpointOutage outage = EndpointOutage.from("http://spark:8000",
            new RuntimeException("wrapped", new ConnectException("Connection refused")));

        assertThat(outage).isNotNull();
        assertThat(outage.endpoint()).isEqualTo("http://spark:8000");
        assertThat(outage.getMessage()).contains("http://spark:8000").contains("ConnectException");
    }

    @Test
    void fromReturnsNullForARefusal_soCallersCannotAccidentallyTreatOneAsAnOutage() {
        assertThat(EndpointOutage.from("http://spark:8000",
            new RuntimeException("the model said no"))).isNull();
    }

    /**
     * A model-server restart, exactly as it arrived on 2026-08-29: 43 in-flight workers, every one
     * of them recorded as a genuine failure with its task pushed to BLOCKED, and the operator asked
     * to say what they had decided about a network outage.
     *
     * <p>The trace was
     * {@code ai.koog...LLMClientException: Error from client: OpenAILLMClient — Connection closed by
     * peer}, caused by {@code org.apache.hc.core5.http.ConnectionClosedException}. That class
     * extends plain {@link IOException}, so none of the type checks matched, its simple name was not
     * in the by-name list either, and the run concluded worker failure from an empty socket.
     */
    @Test
    void aModelServerRestartIsAnOutage_notFortyThreeWorkerFailures() {
        assertThat(EndpointOutage.isOutage(llmClientException(
            "Error from client: OpenAILLMClient\nConnection closed by peer",
            new ConnectionClosedException("Connection closed by peer")))).isTrue();

        EndpointOutage outage = EndpointOutage.from("http://spark:30000", llmClientException(
            "Error from client: OpenAILLMClient\nConnection closed by peer",
            new ConnectionClosedException("Connection closed by peer")));
        assertThat(outage).isNotNull();
        assertThat(outage.endpoint()).isEqualTo("http://spark:30000");
        assertThat(outage.getMessage()).contains("ConnectionClosedException");
    }

    /** The two siblings that arrive from the same transport when a box goes down. */
    @Test
    void theOtherApacheTransportDeathsAreOutagesToo() {
        assertThat(EndpointOutage.isOutage(
            llmClientException("wrapped", new NoHttpResponseException()))).isTrue();
        assertThat(EndpointOutage.isOutage(
            llmClientException("wrapped", new RequestNotExecutedException()))).isTrue();
    }

    /**
     * The negative that matters more than any of the above: widening the classifier must not have
     * turned an ordinary bad answer into a reason to wait for ever.
     */
    @Test
    void anOrdinaryFailureIsStillNotAnOutage() {
        assertThat(EndpointOutage.isOutage(new RuntimeException("bad request"))).isFalse();
        assertThat(EndpointOutage.from("http://spark:30000",
            new RuntimeException("bad request"))).isNull();
        assertThat(EndpointOutage.isOutage(
            llmClientException("Error from client: OpenAILLMClient",
                new IllegalStateException("bad request")))).isFalse();
    }

    /**
     * Koog's own wrapper, reproduced rather than imported: sc-inference deliberately has no
     * dependency on Koog or ktor (see {@code EndpointOutage}'s by-name matcher), so the outer layer
     * is stood up locally. Only the cause is classified, so the wrapper's own type is irrelevant —
     * what matters is that it carries the transport failure underneath, which is what the real one
     * does.
     */
    private static RuntimeException llmClientException(String message, Throwable cause) {
        return new RuntimeException(message, cause);
    }

    /**
     * Stands in for {@code org.apache.hc.core5.http.ConnectionClosedException}. sc-inference has no
     * Apache httpcore5 on its classpath, main or test, and must not grow one to name an exception —
     * which is the entire reason the classifier matches by simple name. Declaring a local class with
     * the same simple name and the same supertype is therefore not a shortcut, it is the exact case
     * the matcher is built for, and a test that imported the real class would prove less: it would
     * not prove the by-name path works.
     */
    private static final class ConnectionClosedException extends IOException {
        ConnectionClosedException(String message) {
            super(message);
        }
    }

    /** Stands in for {@code org.apache.hc.core5.http.NoHttpResponseException}, same reasoning. */
    private static final class NoHttpResponseException extends IOException {
    }

    /** Stands in for {@code org.apache.hc.core5.http.RequestNotExecutedException}, same reasoning. */
    private static final class RequestNotExecutedException extends IOException {
    }

    /** A port that was just free — connecting to it fails fast. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** An endpoint that answers every request with one fixed status. */
    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;

        Endpoint(int status, String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
