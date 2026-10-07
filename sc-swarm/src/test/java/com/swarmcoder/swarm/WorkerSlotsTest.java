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
package com.swarmcoder.swarm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ceiling on workers running at once — the one number that cannot be exceeded by a story, a
 * project or a settings file asking for more.
 *
 * <p>What is being proved is BOTH halves: the machine never carries more than the ceiling, and
 * nothing asked for is dropped to achieve that. A limit that silently shrank a swarm would change
 * what the run is evidence about.
 */
class WorkerSlotsTest {

    @AfterEach
    void unlimited() {
        WorkerSlots.configure(0);   // the default every other test in this module runs under
    }

    @Test
    void unsetMeansNoCeilingAtAll() {
        WorkerSlots.configure(0);
        assertThat(WorkerSlots.shared().ceiling()).isZero();
        assertThat(WorkerSlots.shared().withSlot("one", () -> "ran")).isEqualTo("ran");
    }

    @Test
    void neverMoreThanTheCeilingAreRunningAtOnce() throws Exception {
        WorkerSlots.configure(3);
        AtomicInteger live = new AtomicInteger();
        AtomicInteger highWater = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(12);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 12; i++) {
                pool.submit(() -> WorkerSlots.shared().withSlot("worker", () -> {
                    int now = live.incrementAndGet();
                    highWater.updateAndGet(h -> Math.max(h, now));
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    live.decrementAndGet();
                    finished.incrementAndGet();
                    done.countDown();
                    return null;
                }));
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(highWater.get())
            .describedAs("the machine never carried more than the ceiling")
            .isLessThanOrEqualTo(3);
        assertThat(finished.get())
            .describedAs("every worker asked for still ran — waiting, never dropped")
            .isEqualTo(12);
        assertThat(WorkerSlots.shared().running()).isZero();
        assertThat(WorkerSlots.shared().waiting()).isZero();
    }

    /** A worker that throws must still hand its place back, or the machine bleeds slots. */
    @Test
    void aFailedWorkerReleasesItsPlace() {
        WorkerSlots.configure(1);
        try {
            WorkerSlots.shared().withSlot("doomed", () -> {
                throw new IllegalStateException("the model went away");
            });
        } catch (IllegalStateException expected) {
            // the point is what happens next
        }
        assertThat(WorkerSlots.shared().running()).isZero();
        assertThat(WorkerSlots.shared().withSlot("next", () -> "ran")).isEqualTo("ran");
    }
}
