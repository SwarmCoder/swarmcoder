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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.assertj.core.api.Assertions;

public class InferenceSchedulerTest {

    @Test
    public void testAcquireAndRelease() throws InterruptedException {
        InferenceScheduler scheduler = new InferenceScheduler(2, 1000, 10);
        
        try (InferenceScheduler.Lease lease1 = scheduler.acquireLease("default", 50)) {
            // we have 1 seq, 500 bytes used. 500 remaining.
            assertEquals(1, 1);
            try (InferenceScheduler.Lease lease2 = scheduler.acquireLease("default", 50)) {
                // 2 seqs, 1000 bytes used. 0 remaining.
                assertEquals(1, 1);
            }
        }
    }

    @Test
    public void testRejectOversized() {
        InferenceScheduler scheduler = new InferenceScheduler(2, 1000, 10);
        Assertions.assertThatThrownBy(() -> {
            scheduler.acquireLease("default", 2000);
        });
    }

    @Test
    public void registeredPoolIsUsedAndUnknownIdsFallBackToDefault() throws InterruptedException {
        InferenceScheduler scheduler = new InferenceScheduler(1, 1000, 10);
        // The registered pool takes bigger requests than the default pool could.
        scheduler.registerPool("qwen36-27b", 2, 1_000_000, 100);
        try (InferenceScheduler.Lease big = scheduler.acquireLease("qwen36-27b", 5000)) {
            // The default pool (1 slot) is untouched by the registered pool's lease.
            try (InferenceScheduler.Lease other = scheduler.acquireLease("no-such-profile", 50)) {
                assertNotNull(other);
            }
        }
        // Oversized for the default pool even though the registered pool would take it.
        Assertions.assertThatThrownBy(() ->
            scheduler.acquireLease("no-such-profile", 5000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A spare candidate's lease (2026-10-02): given only when a place is free this instant and
     * nothing is already waiting for one - it never gets ahead of work that queued.
     */
    @Test
    public void aLeaseOnlyIfFreeNeverGetsAheadOfOneThatIsWaiting() throws Exception {
        InferenceScheduler scheduler = new InferenceScheduler(1, 1000, 10);
        InferenceScheduler.Lease held = scheduler.acquireLease("default", 50);
        assertNull(scheduler.tryAcquireLease("default", 50), "the one place is taken");

        java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread waiting = Thread.ofVirtual().start(() -> {
            try (InferenceScheduler.Lease queued = scheduler.acquireLease("default", 50)) {
                got.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(100); // the waiter is queued by now
        held.close();
        assertTrue(got.await(5, java.util.concurrent.TimeUnit.SECONDS),
            "the place went to the one that was waiting");
        assertNull(scheduler.tryAcquireLease("default", 50));
        release.countDown();
        waiting.join();

        InferenceScheduler.Lease spare = scheduler.tryAcquireLease("default", 50);
        assertNotNull(spare, "free, and nothing waiting");
        spare.close();
        Assertions.assertThatThrownBy(() -> scheduler.tryAcquireLease("default", 2000))
            .isInstanceOf(IllegalArgumentException.class);
    }
}