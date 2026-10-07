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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model server is sent no more requests at once than it serves, whoever sends them
 * (2026-10-02): the judge's, the expert's and the test author's requests take the same places as
 * the workers' when they go to the workers' server, and a role on a server of its own takes none.
 */
class ServerPlacesTest {

    private static final String WORKERS = "http://workers.test:8000/v1";

    @AfterEach
    void forget() {
        ServerPlaces.forget(WORKERS);
    }

    @Test
    void aRegisteredServerIsSentOnlyAsManyRequestsAsItServes() throws Exception {
        ServerPlaces.register(WORKERS, 2);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(6);
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // Asked for with and without the /v1 suffix and a trailing slash: one server.
            String url = i % 2 == 0 ? WORKERS : "http://workers.test:8000/";
            callers.add(Thread.ofVirtual().start(() -> {
                try (ServerPlaces.Place place = ServerPlaces.enter(url)) {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                    done.countDown();
                }
            }));
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(peak.get()).as("six callers, a server that serves two").isEqualTo(2);
        assertThat(ServerPlaces.atOnce(WORKERS)).isEqualTo(2);
    }

    @Test
    void aServerNobodyRegisteredIsNotCounted() {
        ServerPlaces.register(WORKERS, 1);
        try (ServerPlaces.Place held = ServerPlaces.enter(WORKERS)) {
            // A cloud role's endpoint: its request does not wait for the workers' server.
            ServerPlaces.Place cloud = ServerPlaces.enter("https://api.cloud.test");
            cloud.close();
            assertThat(ServerPlaces.atOnce("https://api.cloud.test")).isZero();
        }
    }

    @Test
    void givingAPlaceBackTwiceGivesBackOne() throws Exception {
        ServerPlaces.register(WORKERS, 1);
        ServerPlaces.Place first = ServerPlaces.enter(WORKERS);
        first.close();
        first.close();
        ServerPlaces.Place second = ServerPlaces.enter(WORKERS);
        CountDownLatch entered = new CountDownLatch(1);
        Thread third = Thread.ofVirtual().start(() -> {
            try (ServerPlaces.Place place = ServerPlaces.enter(WORKERS)) {
                entered.countDown();
            }
        });
        assertThat(entered.await(200, TimeUnit.MILLISECONDS))
            .as("the server still has exactly one place, and it is taken").isFalse();
        second.close();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        third.join();
    }
}
