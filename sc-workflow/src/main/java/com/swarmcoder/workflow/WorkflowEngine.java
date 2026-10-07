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
import java.util.UUID;
import java.util.Set;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.SwarmEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.ContextLedger;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import java.nio.file.Path;
import java.util.function.Consumer;

public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final GreenfieldWorkflow greenfield;

    private final SwarmEngine swarmEngine;
    private final ArtifactStore store;
    private final RunPersister persister;
    private final ExecutorService executor;
    /** This engine's project repository — what its runs are driven against. Null when disabled. */
    private final Path repoPath;
    /** The locked modules in force for this engine's project. */
    private final List<String> lockedPaths;

    public WorkflowEngine(AgentRuntime runtime, SwarmEngine swarmEngine, VllmClient vllmClient, ArtifactStore store) {
        this(runtime, swarmEngine, vllmClient, store, new CloudGate(0, null), null, null, null);
    }

    public WorkflowEngine(AgentRuntime runtime, SwarmEngine swarmEngine, VllmClient vllmClient, ArtifactStore store, CloudGate cloudGate, Path repoPath) {
        this(runtime, swarmEngine, vllmClient, store, cloudGate, repoPath, null, null);
    }

    public WorkflowEngine(AgentRuntime runtime, SwarmEngine swarmEngine, VllmClient vllmClient, ArtifactStore store, CloudGate cloudGate, Path repoPath, CloudRoles roles, GitService gitService) {
        this(runtime, swarmEngine, vllmClient, store, cloudGate, repoPath, roles, gitService, null);
    }

    public WorkflowEngine(AgentRuntime runtime, SwarmEngine swarmEngine, VllmClient vllmClient, ArtifactStore store, CloudGate cloudGate, Path repoPath, CloudRoles roles, GitService gitService, Librarian librarian) {
        this(runtime, swarmEngine, vllmClient, store, cloudGate, repoPath, roles, gitService, librarian, List.of());
    }

    /**
     * @param protectedPaths operator-declared locked modules (config {@code protectedPaths});
     *                       FINAL_INTEGRATION parks a run whose winning diff touches one
     */
    public WorkflowEngine(AgentRuntime runtime, SwarmEngine swarmEngine, VllmClient vllmClient, ArtifactStore store, CloudGate cloudGate, Path repoPath, CloudRoles roles, GitService gitService, Librarian librarian, List<String> protectedPaths) {
        this.swarmEngine = swarmEngine;
        this.store = store;
        this.persister = new RunPersister(store);
        this.greenfield = new GreenfieldWorkflow(runtime, swarmEngine, vllmClient, store, persister, cloudGate, repoPath, roles, gitService, librarian, protectedPaths);
        this.repoPath = repoPath;
        this.lockedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void setEventLogger(Consumer<String> logger) {
        this.greenfield.setEventLogger(logger);
    }

    /**
     * Where the red check, the test author's compile and final integration run their builds: a
     * container per tree. Only needed when the swarm engine is a stand-in; a real
     * {@code SwarmEngineImpl} brings its own, from its sandbox.
     */
    public void setBuildBoxes(com.swarmcoder.verify.BuildBoxes boxes) {
        this.greenfield.setBuildBoxes(boxes);
    }

    public void setContextLedger(ContextLedger ledger) {
        this.greenfield.setContextLedger(ledger);
    }

    /**
     * The project's standing rules — how it must be built — as the architect, the test author and
     * every worker will be told them. Rendered from the project's ACTIVE guidelines by whoever owns
     * the files; empty for a project that has none, and every prompt is then what it always was.
     */
    public void setProjectRules(java.util.function.Supplier<String> rules) {
        this.greenfield.setProjectRules(rules);
    }

    /**
     * How many characters of "the code this change is about" a BUGFIX or ENHANCEMENT run's briefs
     * may carry. 0 — the default — means the design's own ceiling.
     *
     * <p>Stated by a caller that has measured what its inference endpoint actually serves, because
     * the design sized this channel as a share of the working context and a smaller server gives a
     * smaller share of it. See {@code ChangeNeighbourhood.forWorkingContext}.
     */
    public void setNeighbourhoodChars(int chars) {
        this.greenfield.setNeighbourhoodChars(chars);
    }

    public void advance(Run run) {
        advanceAsync(run);
    }

    /**
     * {@link #advance} with a handle that completes when the run has stopped moving — delivered,
     * aborted, or parked and persisted.
     *
     * <p>Exists for tests that then close the store. A park sets {@code parkedAt} on the run object
     * IN MEMORY and only afterwards writes it, so polling the run for that field says "parked"
     * while the write is still to come; a test that closed the store on that signal was closing it
     * mid-write, which is how a park's own reason came to look intermittently unpersistable.
     */
    public java.util.concurrent.Future<?> advanceAsync(Run run) {
        return executor.submit(() -> advanceSync(run));
    }

    /**
     * The repository this engine's runs are driven against, or null when the project has none.
     * Read by {@link RunResumer}, which has to be able to say which tree a resumed run went to.
     */
    public Path repoPath() {
        return repoPath;
    }

    /** The locked modules in force for this engine's project. */
    public List<String> lockedPaths() {
        return lockedPaths;
    }

    /**
     * The unfinished runs of ONE project, oldest first — what {@link RunResumer} hands back to this
     * engine at startup (rule R3: a crash mid-run continues from its last persisted state).
     *
     * <p>Scoped to a project on purpose. This used to be {@code resumeAll()}: every unfinished run
     * in the store, driven through whichever engine was asked — and at startup that was always the
     * DEFAULT project's engine, with the default project's repository, git service and locked
     * modules. A second project's run was therefore continued against the first project's code, with
     * the wrong protections. Selecting by project makes handing a run to the wrong engine take a
     * deliberate act rather than an omission.
     *
     * <p>APPROVAL is not excluded: it is no longer a place a run can be left (UX v3 §2.3 / §6). Runs
     * still in that state come from an older build and are retired by the workflow's APPROVAL case
     * on the first step. A run that was PAUSED waiting for a model endpoint resumes here too, and
     * that is the whole point: an outage that outlives the process is picked up again on restart,
     * still at the stage it had reached.
     *
     * @param projectId the project whose runs to resume; null resumes the runs of no project at all
     *                  (runs persisted before multi-project existed)
     */
    public List<Run> unfinishedRunsOf(UUID projectId) {
        return store.root().runs.values().stream()
            .filter(r -> !isTerminal(r.state()))
            .filter(r -> java.util.Objects.equals(projectId, r.projectId()))
            .sorted(java.util.Comparator.comparing(Run::startedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
            .toList();
    }

    /**
     * Frees stories left saying "building now" by a process that died while they were.
     *
     * <p>A story leaves RUNNING only when its workflow reaches the delivery check, so a crash, a
     * kill, or an endpoint that never came back stranded it there permanently: {@code promoteStory}
     * refuses anything but DRAFT and {@code startSession} anything but READY, so nothing could move
     * it and the board showed work in flight that no longer existed. One operator sat on three of
     * them for fifteen hours.
     *
     * <p>Called at startup, AFTER {@link #resumeAll}, so a story whose run was genuinely revived is
     * left alone — only the ones with nothing driving them are freed. The move is to READY rather
     * than BLOCKED because being interrupted says nothing about whether the work is sound: it is
     * ready to be built, and the change journal records why it came back.
     */
    public void reconcileStrandedStories() {
        Set<UUID> live = store.root().runs.values().stream()
            .filter(r -> !isTerminal(r.state()))
            .map(Run::id)
            .collect(java.util.stream.Collectors.toSet());
        int freed = 0;
        for (Story story : List.copyOf(store.root().stories().values())) {
            if (story.state() != StoryState.RUNNING) {
                continue;
            }
            // A story is stranded when NO run of its own is still going. "Going" includes a run
            // waiting out a model-endpoint outage — something IS driving it, it just cannot make
            // progress yet — so freeing its story here would let a second swarm start against work
            // that is merely paused. (It previously also included runs parked at APPROVAL, a state
            // that no longer exists; such runs are retired on their first step after startup.)
            boolean anyLive = story.runIds().stream().anyMatch(live::contains);
            if (anyLive) {
                continue;
            }
            story.setState(StoryState.READY);
            store.saveStory(story);
            try {
                store.recordChange(story.projectId(), "system", ChangeEntityType.STORY, story.id(),
                    ChangeKind.STATE_CHANGED, "state", "RUNNING", "READY",
                    story.key() + " was building when the process stopped, and no run of it is "
                        + "still going — freed so it can be built again", null);
            } catch (Exception e) {
                // The journal is an audit aid. Never leave a story stranded because it could not
                // be written.
                log.warn("Could not journal the recovery of story {}: {}", story.key(), e.toString());
            }
            freed++;
        }
        if (freed > 0) {
            log.info("Freed {} story(s) that were left building by a stopped process", freed);
        }
    }

    /** DELIVERED, ABORTED and ABANDONED (a deleted project's run) are all dead ends. */
    private static boolean isTerminal(RunState state) {
        return state == RunState.DELIVERED || state == RunState.ABORTED
            || state == RunState.ABANDONED;
    }

    private void advanceSync(Run run) {
        if (isTerminal(run.state())) {
            return;
        }
        // The initial state is durable before any stage executes.
        run = persister.save(run);

        // One delivery path for every kind of work that IS a build. Bugfix and refactor used to have
        // classes of their own that renamed the run's state a few times and then marked it
        // DELIVERED, having designed nothing, planned nothing, written no test and dispatched no
        // worker. What actually differs between the kinds is what the design roles are told, and
        // that is RunBrief — a string, not a workflow.
        switch (run.kind()) {
            case GREENFIELD, ENHANCEMENT, BUGFIX, REFACTOR -> greenfield.advance(run);
            // Not builds, and never were. Nothing they produce can be stated as a check that a test
            // proves, so the red-check, the verification pipeline and the merge audit have nothing
            // to work with — which is how these two came to report delivery without doing anything.
            // Intake refuses them now; a run of this kind can only be one an older build persisted,
            // and the honest end for it is ABORTED, not DELIVERED.
            case DOCS, ANALYSIS -> retire(run);
        }
    }

    /**
     * Ends a run of a kind this build no longer performs, without ever claiming it delivered.
     */
    private void retire(Run run) {
        log.warn("Run {} is a {} run — a kind this build does not perform. Marking it ABORTED; it "
            + "was never designed, planned, tested or built.", run.id(), run.kind());
        persister.save(run.withState(RunState.ABORTED));
    }
}
