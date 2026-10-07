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
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.verify.BlobSink;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The moment a run has entered EXECUTING and not yet dispatched a single worker — and something
 * the live harness can do at exactly that moment, once per run.
 *
 * <h2>Why here (2026-09-25)</h2>
 *
 * <p>The harness saves a snapshot at this point ({@code -Dswarmcoder.e2e.saveAtBuild}) so that
 * later runs can start from it and spend their model time on the workers alone. The point has to
 * be one where the snapshot is both complete and still: everything the front half produced is in
 * the store, and nothing is writing to the store or the repository while it is copied.
 *
 * <p>{@code GreenfieldWorkflow.advance} gives exactly that at the top of its EXECUTING stage. The
 * TEST_AUTHORING stage returns the run in EXECUTING, the loop persists it synchronously
 * ({@code RunPersister.save} waits for the write), and the very next thing it does is call
 * {@code swarmEngine.executeRun(run)} — on the workflow's own thread, which is the only thing
 * driving the run. Everything before that call has finished (the tests are committed, the red
 * check's worktrees removed), and nothing after it has started. Doing the work INSIDE that call,
 * before handing on, therefore holds the whole run still for as long as the work takes, with no
 * change to the product.
 *
 * <p>It is a subclass of {@link SwarmEngineImpl}, not a wrapper around it, for one reason: the
 * workflow checks {@code instanceof SwarmEngineImpl} to wire the per-wave red check and to re-verify
 * candidates after a test repair. A wrapper would switch both off silently and the walk would be
 * measuring a product with two of its gates missing. The same move as the harness's
 * {@code BudgetStampingArchitect}: override the one method the workflow calls, change nothing
 * else.
 *
 * <p>What is done here can never change what the run does next. A failure is printed loudly and
 * swallowed: a snapshot that could not be saved is a lost convenience, and a run that died of it
 * would be a lost measurement.
 */
final class DispatchSeam {

    /** Does nothing, for a walk that saves nothing — the ordinary full walk and every resume. */
    static final DispatchSeam NONE = new DispatchSeam(null);

    private final Consumer<Run> action;
    private final Set<UUID> fired = ConcurrentHashMap.newKeySet();

    /**
     * @param action what to do the first time each run reaches the seam; null for nothing
     */
    DispatchSeam(Consumer<Run> action) {
        this.action = action;
    }

    /**
     * Called by the swarm engine before it dispatches anything. Fires at most once per run: a run
     * whose acceptance test was repaired re-enters EXECUTING and calls the engine again, and by
     * then workers have already run, so that is no longer the moment this is about.
     */
    void beforeDispatch(Run run) {
        if (action == null || run == null || run.state() != RunState.EXECUTING
                || !fired.add(run.id())) {
            return;
        }
        try {
            action.accept(run);
        } catch (RuntimeException e) {
            System.out.println("[E2E] !!! the work done at the moment of dispatch FAILED, and the "
                + "run carries on without it: " + e);
            e.printStackTrace(System.out);
        }
    }

    /** True once the seam has been reached for this run. */
    boolean reached(UUID runId) {
        return fired.contains(runId);
    }

    /**
     * The product's own swarm engine with the seam in front of {@link #executeRun}. Same
     * constructor, same everything; see the class javadoc for why a subclass.
     */
    static final class SwarmEngineAtTheSeam extends SwarmEngineImpl {

        private final DispatchSeam seam;

        SwarmEngineAtTheSeam(DispatchSeam seam, VllmClient judgeClient, ArtifactStore store,
                             InferenceScheduler scheduler, AgentRuntime runtime,
                             ModelProfileRegistry profiles, GitService git, BlobSink blobSink,
                             CloudGate cloudGate, Supplier<String> guidelines, ApiLookup apiLookup,
                             Supplier<ExpertHelp> expertFactory, List<String> frameworkPackages) {
            super(judgeClient, store, scheduler, runtime, profiles, git, blobSink, cloudGate,
                guidelines, apiLookup, expertFactory, frameworkPackages);
            this.seam = seam == null ? NONE : seam;
        }

        @Override
        public Run executeRun(Run run) {
            seam.beforeDispatch(run);
            return super.executeRun(run);
        }
    }
}
