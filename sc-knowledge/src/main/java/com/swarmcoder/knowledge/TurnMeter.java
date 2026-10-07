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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.KillReason;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The turn guard of a session that looks things up — the expert's, and since 2026-10-02 the
 * architect's, the planner's and the test author's ({@link LookupAgent}): charges each turn's
 * tokens to the run's budget as the server reports them, tells the toolbox which turn it is on (for
 * the countdown on lookup results), and hands the trace any countdown note the last lookup carried.
 *
 * <p>{@code tokensUsed} on a {@code TurnInfo} is this session's running total, so the delta is
 * what this turn added — including the tool outputs it re-read, which is why a tool call is not
 * charged a second time when it happens. Charging per turn rather than once at the end is what
 * makes the budget a limit and not a bill: a session that runs past the cap is stopped on its
 * next turn instead of being discovered afterwards.
 */
final class TurnMeter implements AgentRuntime.TurnGuard {

    private final CloudGate cloudGate;
    private final AtomicLong charged;
    /** Nullable: a closing turn has no lookups, so nothing to count down. */
    private final ExpertTools tools;
    final AtomicLong modelTokens = new AtomicLong();

    TurnMeter(CloudGate cloudGate, AtomicLong charged, ExpertTools tools) {
        this.cloudGate = cloudGate;
        this.charged = charged;
        this.tools = tools;
    }

    /** A kept conversation goes on: its next run's token count starts from zero again. */
    void startsAnotherRun() {
        modelTokens.set(0);
    }

    @Override
    public Optional<KillReason> check(TurnInfo info) {
        if (tools != null) {
            tools.onTurn(info.turnIndex());
        }
        long total = Math.max(0, info.tokensUsed());
        long delta = Math.max(0, total - modelTokens.get());
        modelTokens.set(Math.max(modelTokens.get(), total));
        if (delta == 0) {
            return Optional.empty();
        }
        try {
            cloudGate.charge(delta);
            charged.addAndGet(delta);
            return Optional.empty();
        } catch (CloudGate.BudgetExhaustedException e) {               // noqa
            charged.addAndGet(delta);
            return Optional.of(KillReason.BUDGET_EXCEEDED);
        }
    }

    @Override
    public Optional<String> steeringGiven() {
        return tools == null ? Optional.empty() : tools.takeSteering();
    }
}
