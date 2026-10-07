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
package com.swarmcoder.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one gate every cloud-role call passes through (spec §6.2, rule R6): mechanical
 * enforcement of {@code Budget.maxCloudTokens}. The whole business case is that cloud spend
 * scales with task count, not implementation tokens — the gate keeps that true even when a
 * bug retries in a loop.
 *
 * <p>Accounting is estimate-based (chars/4) until the inference clients parse usage frames;
 * estimates only ever need to be the right order of magnitude to stop a runaway loop.
 */
public final class CloudGate {

    /** Thrown when a charge would exceed the budget; callers park a BUDGET_EXTENSION decision. */
    public static final class BudgetExhaustedException extends RuntimeException {
        public BudgetExhaustedException(long used, long max) {
            super("Cloud budget exhausted: " + used + " of " + max + " tokens used");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CloudGate.class);

    private final long maxCloudTokens;
    private final AtomicLong used = new AtomicLong();
    private final AtomicBoolean exhaustedSignalled = new AtomicBoolean();
    private final Runnable onExhausted;

    /**
     * @param maxCloudTokens hard cap; {@code <= 0} disables enforcement (logged once)
     * @param onExhausted    invoked exactly once when the cap is first breached — wired to
     *                       queue a {@code DecisionKind.BUDGET_EXTENSION} decision
     */
    public CloudGate(long maxCloudTokens, Runnable onExhausted) {
        this.maxCloudTokens = maxCloudTokens;
        this.onExhausted = onExhausted == null ? () -> { } : onExhausted;
        if (maxCloudTokens <= 0) {
            log.warn("Cloud budget cap disabled (budgets.maxCloudTokensPerRun <= 0)");
        }
    }

    /** Records usage and throws once the cap is exceeded. */
    public void charge(long tokens) {
        long total = used.addAndGet(Math.max(0, tokens));
        if (maxCloudTokens > 0 && total > maxCloudTokens) {
            if (exhaustedSignalled.compareAndSet(false, true)) {
                log.error("Cloud budget exhausted ({} of {} tokens) — parking BUDGET_EXTENSION decision",
                    total, maxCloudTokens);
                onExhausted.run();
            }
            throw new BudgetExhaustedException(total, maxCloudTokens);
        }
    }

    /**
     * Gives back a charge for a call that never reached the model.
     *
     * <p>The gate charges the prompt BEFORE sending, so that a runaway loop is stopped before it
     * spends rather than after. That is right for calls that happen, and wrong for calls that do
     * not: when the endpoint is unreachable the workflow retries the same call until it returns
     * (UX v3 §2.4), and charging every one of those attempts would let an outage eat a whole run's
     * budget without a single token having been generated. Refunding keeps auto-retry inside the
     * original budget, which is the condition attached to it by rule 6.
     *
     * <p>Only ever called with an amount this caller just charged.
     */
    public void refund(long tokens) {
        if (tokens <= 0) {
            return;
        }
        used.updateAndGet(current -> Math.max(0, current - tokens));
    }

    /** Rough token estimate for text whose true usage is not reported (chars/4). */
    public static long estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    public long used() {
        return used.get();
    }

    /** The configured cloud-token cap; {@code <= 0} means uncapped. */
    public long cap() {
        return maxCloudTokens;
    }

    public boolean exhausted() {
        return maxCloudTokens > 0 && used.get() > maxCloudTokens;
    }
}
