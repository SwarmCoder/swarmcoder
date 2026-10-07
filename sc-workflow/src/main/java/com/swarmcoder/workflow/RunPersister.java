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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.Run;
import com.swarmcoder.store.ArtifactStore;

/**
 * Persists every workflow state transition (rule R3, DEVELOPER_CORRECTIONS.md): the store is
 * the source of truth for run state — the TUI approval queue reads it, and crash-resume
 * depends on it. A persistence failure aborts the transition rather than continuing on
 * unpersisted state.
 */
public final class RunPersister {

    private final ArtifactStore store;

    public RunPersister(ArtifactStore store) {
        this.store = store;
    }

    /**
     * Durably records the run's current state and returns it, for transition chaining.
     *
     * <p><b>Two writes, not one.</b> Every {@code RunState} transition in this codebase moves to a
     * brand-new {@code Run} object ({@code Run.withState}, never a mutated existing one), so the
     * container-level {@code append} below — which registers the map's new entry and is what makes a
     * freshly-created run reachable at all after a restart — has always been enough for those. It is
     * NOT enough for a caller that mutates an EXISTING, already-known {@code Run} reference in place
     * (parking one, e.g. {@code GreenfieldWorkflow.parkRun}, which calls {@code run.setParkedAt} and
     * {@code setParkReason} directly rather than going through {@code withState}): EclipseStore's
     * lazy storer skips an already-known instance reached through an unchanged map binding, so the
     * mutation would sit correctly in memory for the rest of this process and be absent the moment
     * the store is reopened. {@link ArtifactStore#updateRun} is the one helper for that, and this is
     * one of its callers.
     */
    public Run save(Run run) {
        refuseUnearnedDelivery(run);
        try {
            // Every persist is a heartbeat. This is the single funnel a live workflow goes through,
            // so stamping here means a run's freshness needs no separate discipline to maintain —
            // and a run nobody is driving simply stops being stamped, which is exactly the signal
            // that was missing when a crash left stories saying "building now" indefinitely.
            run.setHeartbeatAt(java.time.Instant.now());
            store.append(() -> {
                store.root().runs.put(run.id(), run);
                return null;
            }).get();
            // Forces THIS run's current field values to disk even when it was already a known
            // instance before this call (a mutation-in-place, not a withState replacement) — see
            // this method's own javadoc.
            store.updateRun(run);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist run " + run.id()
                + " in state " + run.state(), e);
        }
        return run;
    }

    /**
     * The tripwire under the whole product: nothing may be recorded as DELIVERED that was never
     * planned, and therefore never designed, tested, built, judged or merged.
     *
     * <p>Four kinds of run — bugfix, refactor, docs and analysis — used to reach DELIVERED by
     * renaming their own state three or four times, with no architect, no plan, no acceptance test
     * and no worker. Those classes are gone, but "gone" is a fact about today's code and this is a
     * fact about the store: a task graph exists only after a design was made and sliced, and nothing
     * can be built, verified or merged without one. A run reaching DELIVERED without one delivered
     * nothing, and saying otherwise is the single most damaging thing this system can do — the
     * operator's only evidence that work happened is what the store says.
     *
     * <p>It throws rather than downgrading. The transition is abandoned, the run stays at the last
     * state it honestly reached, and the failure is loud enough to be found.
     */
    private static void refuseUnearnedDelivery(Run run) {
        if (run.state() == com.swarmcoder.domain.RunState.DELIVERED && !run.wasEverPlanned()) {
            throw new IllegalStateException("Refusing to record run " + run.id() + " (" + run.kind()
                + ") as DELIVERED: it has no task graph, so nothing was ever planned, built or "
                + "verified for it. A run may only be delivered by work that happened.");
        }
    }
}
