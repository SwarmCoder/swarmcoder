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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telling "my request was too big" apart from "the box is gone".
 *
 * <p><b>The defect this exists to keep dead.</b> On 2026-09-01 six workers were killed by the
 * client's fifteen-minute request timeout and every one of them was recorded as an endpoint outage.
 * That is the worst possible label: an outage means "wait indefinitely, this is not the candidate's
 * fault, and it is not evidence", so a run sat waiting for a server that was answering the other
 * workers in the same wave in six tenths of a second. The cause was the worker's own conversation
 * growing without bound until the prompt could no longer be prefilled inside the timeout.
 *
 * <p>The rule is deliberately narrow. Only a REQUEST timeout — the server took the request and did
 * not finish answering — can be re-judged, and only when something else proves the server was
 * serving at the time, or when we can see that we oversized the request ourselves. Everything else
 * keeps the classification it has today, because the incident that produced the current classifier
 * (a model-server restart recorded as 43 worker failures) must stay fixed.
 */
class SlowRequestIsNotAnOutageTest {

    private static final String ENDPOINT = "http://spark:30000";

    @BeforeEach
    void forgetPreviousActivity() {
        EndpointActivity.forget();
    }

    /** The exact incident: a request timeout while the same box was serving other workers. */
    @Test
    void aRequestTimeoutIsNotAnOutageWhenTheEndpointAnsweredSomebodyElseMeanwhile() {
        Instant sent = Instant.now().minusSeconds(900);
        EndpointActivity.succeeded(ENDPOINT); // another worker's turn completed while we waited

        EndpointOutage.Attempt attempt = new EndpointOutage.Attempt(ENDPOINT, sent, 0, 0);

        assertThat(EndpointOutage.isOutage(requestTimeout()))
            .describedAs("judged on the failure alone it still looks like an outage")
            .isTrue();
        assertThat(EndpointOutage.isOutage(requestTimeout(), attempt))
            .describedAs("judged with what the endpoint was doing at the time, it is not")
            .isFalse();
    }

    /** No evidence either way leaves the classification exactly as it has always been. */
    @Test
    void aRequestTimeoutWithNothingElseSucceedingIsStillAnOutage() {
        EndpointOutage.Attempt attempt =
            new EndpointOutage.Attempt(ENDPOINT, Instant.now().minusSeconds(900), 0, 0);

        assertThat(EndpointOutage.isOutage(requestTimeout(), attempt)).isTrue();
    }

    /** A success from BEFORE the request was sent says nothing about the time it sat unanswered. */
    @Test
    void aSuccessFromBeforeTheRequestWentOutIsNotEvidence() {
        EndpointActivity.succeeded(ENDPOINT);
        EndpointOutage.Attempt attempt =
            new EndpointOutage.Attempt(ENDPOINT, Instant.now().plusSeconds(60), 0, 0);

        assertThat(EndpointOutage.isOutage(requestTimeout(), attempt)).isTrue();
    }

    /** We sent more than this model is allowed to occupy: nobody else is to blame, evidence or not. */
    @Test
    void anOversizedRequestIsOurFaultWithNoEvidenceNeeded() {
        EndpointOutage.Attempt attempt = new EndpointOutage.Attempt(
            ENDPOINT, Instant.now().minusSeconds(900), 90_000, 32_768);

        assertThat(attempt.oversized()).isTrue();
        assertThat(EndpointOutage.isOutage(requestTimeout(), attempt)).isFalse();
    }

    /**
     * The negative that matters most: a real outage must stay an outage. A restarting model server
     * closes connections, and every other worker's call fails at the same moment — so successes
     * recorded a moment earlier must not talk the classifier out of it.
     */
    @Test
    void aRestartingServerIsStillAnOutageEvenWithRecentSuccesses() {
        EndpointActivity.succeeded(ENDPOINT);
        EndpointOutage.Attempt attempt =
            new EndpointOutage.Attempt(ENDPOINT, Instant.now().minusSeconds(1), 20_000, 32_768);

        assertThat(EndpointOutage.isOutage(
            new RuntimeException("wrapped", new ConnectionClosedException("Connection closed by peer")),
            attempt)).isTrue();
        assertThat(EndpointOutage.isOutage(
            new java.net.ConnectException("Connection refused"), attempt)).isTrue();
        assertThat(EndpointOutage.isOutage(
            new java.net.UnknownHostException("spark"), attempt)).isTrue();
    }

    /**
     * A connect timeout fires BEFORE the request is sent, so the server never saw it and cannot be
     * "busy with it". It is an unreachable box and stays one, even though its type is a subclass of
     * the request-timeout type.
     */
    @Test
    void aConnectTimeoutIsNotARequestTimeout() {
        EndpointActivity.succeeded(ENDPOINT);
        EndpointOutage.Attempt attempt =
            new EndpointOutage.Attempt(ENDPOINT, Instant.now().minusSeconds(1), 0, 0);

        assertThat(EndpointOutage.isRequestTimeout(new HttpConnectTimeoutException("connect"))).isFalse();
        assertThat(EndpointOutage.isOutage(new HttpConnectTimeoutException("connect"), attempt)).isTrue();
    }

    @Test
    void whatCountsAsARequestTimeout() {
        assertThat(EndpointOutage.isRequestTimeout(requestTimeout())).isTrue();
        assertThat(EndpointOutage.isRequestTimeout(new HttpTimeoutException("request timed out"))).isTrue();
        assertThat(EndpointOutage.isRequestTimeout(new java.net.SocketTimeoutException("read")))
            .describedAs("the JDK throws this for a connect timeout too, so it cannot mean this")
            .isFalse();
        assertThat(EndpointOutage.isRequestTimeout(new RuntimeException("bad request"))).isFalse();
    }

    /** However the same box is spelled in two config files, evidence about it must count once. */
    @Test
    void theSameEndpointSpelledTwoWaysIsOneEndpoint() {
        EndpointActivity.succeeded("http://spark:30000/v1/");
        assertThat(EndpointActivity.succeededSince("http://spark:30000",
            Instant.now().minusSeconds(5))).isTrue();
    }

    @Test
    void theOperatorIsToldWhatToDoAboutIt() {
        String sentence = EndpointOutage.slowRequestSentence(new EndpointOutage.Attempt(
            ENDPOINT, Instant.now(), 90_000, 32_768));

        assertThat(sentence)
            .contains("90000")
            .contains("32768")
            .contains("kept answering other requests normally")
            .contains("working context");
    }

    /**
     * Stands in for ktor's {@code HttpRequestTimeoutException}, which is what actually killed the
     * six workers. sc-inference has no ktor on its classpath, main or test, and must not grow one to
     * name an exception — which is why the classifier matches by simple name in the first place.
     */
    private static RuntimeException requestTimeout() {
        return new RuntimeException("Error from client: OpenAILLMClient",
            new HttpRequestTimeoutException());
    }

    private static final class HttpRequestTimeoutException extends IOException {
        HttpRequestTimeoutException() {
            super("Request timeout has expired [url=http://spark:30000/v1/chat/completions, "
                + "request_timeout=900000 ms]");
        }
    }

    /** Stands in for {@code org.apache.hc.core5.http.ConnectionClosedException}, same reasoning. */
    private static final class ConnectionClosedException extends IOException {
        ConnectionClosedException(String message) {
            super(message);
        }
    }
}
