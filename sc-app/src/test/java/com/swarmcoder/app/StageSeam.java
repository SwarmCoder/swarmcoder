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
package com.swarmcoder.app;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.store.ArtifactStore;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The moment the workflow has durably recorded a run's entry into a state and not yet started that
 * state's stage — the seam for the three restart points before the build (2026-10-01).
 *
 * <h2>Why here, and why no product change</h2>
 *
 * <p>{@code GreenfieldWorkflow.advance} persists every transition through
 * {@code RunPersister.save} on the workflow's own thread, synchronously, before it calls the next
 * stage: {@code store.append(...).get()} then {@code store.updateRun(run).get()}. When
 * {@code updateRun} returns, the run is on disk in its new state and the loop has not yet looked at
 * it. Doing work inside {@code updateRun} therefore holds the whole run still — the only thing
 * driving it is the thread doing the work — exactly as {@link DispatchSeam} does at the top of
 * EXECUTING. {@code ArtifactStore} is an ordinary class, so the harness opens a subclass
 * ({@link Store}) that overrides that one method and changes nothing else.
 *
 * <p>Fires at most once per run and state: a design that is revised re-enters DESIGN, and a
 * parked or heartbeat write re-saves the same state, none of which is the moment this is about.
 * What is done here can never change what the run does next: a failure is printed loudly and
 * swallowed, because a snapshot that could not be saved is a lost convenience and a run that died
 * of it would be a lost measurement.
 */
final class StageSeam {

    /** Does nothing, for a walk that saves nothing. */
    static final StageSeam NONE = new StageSeam(Map.of());

    private final Map<RunState, Consumer<Run>> actions;
    private final Set<String> fired = ConcurrentHashMap.newKeySet();

    /**
     * @param actions what to do the first time each run is persisted in each of these states
     */
    StageSeam(Map<RunState, Consumer<Run>> actions) {
        this.actions = Map.copyOf(actions);
    }

    /** Told about EVERY persisted run, before the once-per-stage actions; null for nobody. */
    private volatile Consumer<Run> observer;

    /** This seam, also reporting every persisted run to {@code observer} (the run report's clock). */
    StageSeam observing(Consumer<Run> observer) {
        this.observer = observer;
        return this;
    }

    /** Called by {@link Store} after a run's fields were persisted. */
    void afterPersist(Run run) {
        if (run == null || run.id() == null || run.state() == null) {
            return;
        }
        Consumer<Run> watching = observer;
        if (watching != null) {
            try {
                watching.accept(run);
            } catch (RuntimeException e) {
                System.out.println("[E2E] the run report's clock failed on a persisted run: " + e);
            }
        }
        Consumer<Run> action = actions.get(run.state());
        if (action == null || !fired.add(key(run.id(), run.state()))) {
            return;
        }
        try {
            action.accept(run);
        } catch (Exception e) {
            System.out.println("[E2E] !!! the work done on entering " + run.state() + " FAILED, and "
                + "the run carries on without it: " + e);
            e.printStackTrace(System.out);
        }
    }

    /** True once a run has been persisted in this state and the action for it has been started. */
    boolean reached(UUID runId, RunState state) {
        return fired.contains(key(runId, state));
    }

    private static String key(UUID runId, RunState state) {
        return runId + "/" + state;
    }

    /** The product's own store with the seam behind {@code updateRun}. */
    static final class Store extends ArtifactStore {

        private final StageSeam seam;

        Store(Path storageDir, StageSeam seam) {
            super(storageDir);
            this.seam = seam == null ? NONE : seam;
        }

        @Override
        public Run updateRun(Run run) {
            Run persisted = super.updateRun(run);
            seam.afterPersist(persisted);
            return persisted;
        }
    }
}
