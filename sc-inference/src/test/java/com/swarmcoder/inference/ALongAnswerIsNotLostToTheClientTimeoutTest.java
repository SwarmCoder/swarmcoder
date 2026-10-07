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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 74: five worker turns were still being written when the client stopped waiting at 900
 * seconds, and were thrown away with their workers; the log blamed the size of conversations of
 * about 12,000 tokens; and one such timeout was read as a server outage and paused the run.
 */
class ALongAnswerIsNotLostToTheClientTimeoutTest {

    private static final String ENDPOINT = "http://model-server.invalid:8000";

    @BeforeEach
    void forget() {
        EndpointActivity.forget();
    }

    @AfterEach
    void restoreTheProbe() {
        EndpointOutage.answersNowIs(null);
        System.clearProperty(RequestTimeouts.PROPERTY);
    }

    private static ModelQuirks shape(double loadedTokensPerSecond) {
        return new ModelQuirks("a model", 32768, true, null, null, false, false, false,
            1048576, 262144, 1024, 4, false, loadedTokensPerSecond);
    }

    @Test
    void aTurnMayAskOnlyForWhatTheServerCanWriteBeforeTheClientStopsWaiting() {
        assertThat(shape(11).turnOutputTokens(900))
            .as("11 tokens a second for 900 seconds, less the share kept for reading the prompt")
            .isEqualTo(7920);
        assertThat(shape(11).turnOutputTokens(3600)).isEqualTo(31680);
        assertThat(shape(11).turnOutputTokens(7200))
            .as("never more than the model's own allowance").isEqualTo(32768);
        assertThat(shape(0.5).turnOutputTokens(900))
            .as("never so little that a file cannot be written").isEqualTo(2048);
    }

    @Test
    void anUnmeasuredRateDerivesNothing() {
        assertThat(shape(0).turnOutputTokens(900)).isEqualTo(32768);
        assertThat(ModelQuirks.DEFAULTS.loadedTokensPerSecond()).isZero();
    }

    @Test
    void theShapeOfTheModelThatRunSeventyFourUsedCarriesItsMeasuredRate() {
        ModelQuirks ds4 = ModelShapes.get("deepseek-v4-flash-ds4");
        assertThat(ds4.loadedTokensPerSecond()).isGreaterThan(0);
        assertThat(ds4.turnOutputTokens(RequestTimeouts.DEFAULT_SECONDS))
            .isLessThan(ds4.maxOutputTokens());
        assertThat(ds4.withThinking(false).loadedTokensPerSecond())
            .as("a narrow override keeps the rate").isEqualTo(ds4.loadedTokensPerSecond());
    }

    @Test
    void theTimeoutIsConfigurable() {
        assertThat(RequestTimeouts.workerRequestSeconds()).isEqualTo(1800);
        System.setProperty(RequestTimeouts.PROPERTY, "1200");
        assertThat(RequestTimeouts.workerRequestSeconds()).isEqualTo(1200);
        System.setProperty(RequestTimeouts.PROPERTY, "-5");
        assertThat(RequestTimeouts.workerRequestSeconds()).isEqualTo(1800);
    }

    @Test
    void aTimeoutWithNobodyElseAskingIsNotAnOutageWhenTheServerAnswersNow() {
        EndpointOutage.Attempt attempt =
            new EndpointOutage.Attempt(ENDPOINT, Instant.now().minusSeconds(900), 12_000, 262_144);
        Throwable timeout = new RuntimeException("Error from client",
            new HttpTimeoutException("request timed out"));

        EndpointOutage.answersNowIs(endpoint -> true);
        assertThat(EndpointOutage.isOutage(timeout, attempt))
            .as("the server answers: one request took too long, nothing is out").isFalse();

        EndpointOutage.answersNowIs(endpoint -> false);
        assertThat(EndpointOutage.isOutage(timeout, attempt))
            .as("the server does not answer: that is an outage").isTrue();
    }

    @Test
    void aSmallConversationIsNotBlamedForTheTimeout() {
        String sentence = EndpointOutage.slowRequestSentence(
            new EndpointOutage.Attempt(ENDPOINT, Instant.now(), 11_490, 262_144), 900, 7920);

        assertThat(sentence)
            .contains("11490").contains("262144").contains("900 seconds")
            .contains("its size is not the cause")
            .contains("not streamed")
            .contains(RequestTimeouts.PROPERTY)
            .doesNotContain("lower this model's working context");
        assertThat(new ContextDeath(com.swarmcoder.domain.KillReason.TIMEOUT, 8).sentence())
            .doesNotContain("grown too big");
    }

    @Test
    void aLargeConversationStillGetsTheOldAdvice() {
        assertThat(EndpointOutage.slowRequestSentence(
            new EndpointOutage.Attempt(ENDPOINT, Instant.now(), 200_000, 262_144), 900, 7920))
            .contains("lower this model's working context");
    }
}
