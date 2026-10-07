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

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admission control per vLLM instance (spec §7.3): a sequence-slot semaphore plus a KV-cache
 * byte estimate ceiling per pool. Pools are registered from config ({@code spark.instances}) —
 * model identity never lives in code (rule S1). Unknown profile ids fall back to the default
 * pool built by the constructor.
 *
 * <p><b>The byte ceiling is a currency, not a memory budget.</b> Callers reserve
 * {@code servedContextTokens} per lease and the ceiling is registered as
 * {@code maxNumSeqs * servedContextTokens * kvBytesPerToken}, so {@code kvBytesPerToken} cancels
 * out of both sides: the ceiling admits exactly {@code maxNumSeqs} leases whatever it is set to,
 * and the semaphore is what actually binds. The product is routinely larger than any graphics card
 * in existence — about two terabytes on the 2026-08 box. Nobody should read a number out of here as
 * memory, and turning it into real memory would change what is admitted, which is a decision rather
 * than a tidy-up.
 */
public class InferenceScheduler {
    private static final Logger log = LoggerFactory.getLogger(InferenceScheduler.class);

    private static class InstancePool {
        final Semaphore sequenceLeases;
        final AtomicLong kvBytesInFlight;
        final long kvBytesCeiling;
        final int kvBytesPerTokenEstimate;

        InstancePool(int maxNumSeqs, long kvBytesCeiling, int kvBytesPerTokenEstimate) {
            this.sequenceLeases = new Semaphore(maxNumSeqs, true);
            this.kvBytesInFlight = new AtomicLong(0);
            this.kvBytesCeiling = kvBytesCeiling;
            this.kvBytesPerTokenEstimate = kvBytesPerTokenEstimate;
        }
    }

    private final ConcurrentHashMap<String, InstancePool> pools = new ConcurrentHashMap<>();

    public InferenceScheduler(int maxNumSeqs, long kvBytesCeiling, int kvBytesPerTokenEstimate) {
        pools.put("default", new InstancePool(maxNumSeqs, kvBytesCeiling, kvBytesPerTokenEstimate));
    }

    /** Registers (or replaces) the pool for one configured instance, keyed by its profile id. */
    public void registerPool(String profileId, int maxNumSeqs, long kvBytesCeiling,
                             int kvBytesPerTokenEstimate) {
        pools.put(profileId, new InstancePool(maxNumSeqs, kvBytesCeiling, kvBytesPerTokenEstimate));
    }

    /**
     * Registers the pool one worker model leases against, from that model's own figures: as many
     * leases as its server serves at once, each the size {@code WorkerLoop} reserves (the model's
     * served context). Every place that builds a scheduler for a real model — the app and the
     * live harnesses alike — goes through this one method.
     *
     * <p><b>Why it must be called.</b> A profile nobody registered leases against the default
     * pool, whose byte ceiling was written for a 65536-token model. A model served with a larger
     * context reserves more per lease than that ceiling divides into: at 1048576 tokens and 1024
     * bytes a token one lease is the whole gibibyte, so exactly ONE worker ran at a time and the
     * rest waited in silence. Harness run 66 (2026-10-02) ran four workers one after another on a
     * server that serves four at once for exactly this reason.
     */
    public void registerPool(String profileId, ModelQuirks quirks) {
        int atOnce = Math.max(1, quirks.maxConcurrentSequences());
        registerPool(profileId, atOnce,
            (long) atOnce * quirks.servedContextTokens() * quirks.kvBytesPerToken(),
            quirks.kvBytesPerToken());
    }

    /** A scheduler for one worker model, admitting what that model's server serves at once. */
    public static InferenceScheduler forWorkerModel(String profileId, ModelQuirks quirks) {
        InferenceScheduler scheduler = new InferenceScheduler(16, 1024L * 1024 * 1024, 1024);
        scheduler.registerPool(profileId, quirks);
        return scheduler;
    }

    /**
     * {@link #forWorkerModel(String, ModelQuirks)}, and the same number for REQUESTS to that
     * model's server whoever sends them ({@link ServerPlaces}): where every role runs on the
     * workers' server - the live harnesses - a judge or an expert call is then counted with the
     * workers' own, and the server is never sent more than it serves at once.
     */
    public static InferenceScheduler forWorkerModel(String profileId, ModelQuirks quirks,
                                                    String baseUrl) {
        ServerPlaces.register(baseUrl, Math.max(1, quirks.maxConcurrentSequences()));
        return forWorkerModel(profileId, quirks);
    }

    public Lease acquireLease(String profileId, int expectedTokens) throws InterruptedException {
        InstancePool pool = pools.getOrDefault(profileId, pools.get("default"));
        long expectedKvBytes = (long) expectedTokens * pool.kvBytesPerTokenEstimate;

        if (expectedKvBytes > pool.kvBytesCeiling) {
            throw new IllegalArgumentException("Expected tokens exceed KV cache capacity.");
        }

        pool.sequenceLeases.acquire();

        boolean saidSo = false;
        while (true) {
            long current = pool.kvBytesInFlight.get();
            if (current + expectedKvBytes <= pool.kvBytesCeiling) {
                if (pool.kvBytesInFlight.compareAndSet(current, current + expectedKvBytes)) {
                    break;
                }
            } else {
                if (!saidSo) {
                    // A free sequence slot and still no admission: the byte ceiling is what binds,
                    // which for a registered pool cannot happen. Said once per wait, because the
                    // silent version of this ran a four-wide server one request at a time.
                    saidSo = true;
                    log.warn("A request for model '{}' has a free place on its server but is "
                        + "waiting on the memory accounting ({} tokens reserved against a pool "
                        + "that holds {} tokens' worth){} — requests are running fewer at a time "
                        + "than the server allows.", profileId, expectedTokens,
                        pool.kvBytesCeiling / Math.max(1, pool.kvBytesPerTokenEstimate),
                        pools.containsKey(profileId) ? ""
                            : "; no pool was registered for this model, so it shares the default");
                }
                Thread.sleep(100);
            }
        }

        return new Lease(pool, expectedKvBytes);
    }

    /**
     * A lease only when one is free this instant AND nobody is already waiting for one; otherwise
     * null, at once. For work that should run only on a place that would otherwise be idle - a
     * second candidate of a task that already has one going (2026-10-02). It never takes a place
     * ahead of anything that queued in {@link #acquireLease}.
     */
    public Lease tryAcquireLease(String profileId, int expectedTokens) {
        InstancePool pool = pools.getOrDefault(profileId, pools.get("default"));
        long expectedKvBytes = (long) expectedTokens * pool.kvBytesPerTokenEstimate;
        if (expectedKvBytes > pool.kvBytesCeiling) {
            throw new IllegalArgumentException("Expected tokens exceed KV cache capacity.");
        }
        try {
            // The timed form with no time: unlike the bare tryAcquire(), it keeps to the queue.
            if (!pool.sequenceLeases.tryAcquire(0, java.util.concurrent.TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        while (true) {
            long current = pool.kvBytesInFlight.get();
            if (current + expectedKvBytes > pool.kvBytesCeiling) {
                pool.sequenceLeases.release();
                return null;
            }
            if (pool.kvBytesInFlight.compareAndSet(current, current + expectedKvBytes)) {
                return new Lease(pool, expectedKvBytes);
            }
        }
    }

    public void releaseLease(Lease lease) {
        lease.close();
    }

    public static class Lease implements AutoCloseable {
        private final InstancePool pool;
        private final long reservedBytes;
        private boolean closed = false;

        private Lease(InstancePool pool, long reservedBytes) {
            this.pool = pool;
            this.reservedBytes = reservedBytes;
        }

        @Override
        public void close() {
            if (!closed) {
                pool.kvBytesInFlight.addAndGet(-reservedBytes);
                pool.sequenceLeases.release();
                closed = true;
            }
        }
    }
}
