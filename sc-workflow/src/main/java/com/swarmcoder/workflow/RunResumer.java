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

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Continues, at startup, the runs that were mid-flight when the last process died — each through
 * its OWN project's engine (rule R3).
 *
 * <p>The defect this exists to make impossible: resume used to take every unfinished run in the
 * store and drive it through one engine — whichever the caller had, which at startup was always the
 * default project's. A run belonging to a second project was therefore continued against the FIRST
 * project's repository, with the first project's git service and the first project's locked
 * modules. That is a write to the wrong codebase and a containment failure at the same time
 * (§13 assumes policy is resolved from the tree the run belongs to), and it needed nobody to press
 * anything. Every persistence test used a single project, where the bug cannot show.
 *
 * <p>So the project is not an argument here, it is the routing key: this class asks the store which
 * projects have unfinished runs, asks the registry for each of those projects' engine, and hands
 * each run only to the engine built for it. Resuming a run through the wrong project's machinery is
 * no longer something a caller can do by omission.
 */
public final class RunResumer {

    private static final Logger log = LoggerFactory.getLogger(RunResumer.class);

    /** The per-project engine registry — {@code DependencyGraph}'s project contexts in production. */
    @FunctionalInterface
    public interface Engines {
        /**
         * The engine built for a project: its repository, its git service, its locked modules.
         *
         * @param projectId the run's project; null for runs persisted before multi-project existed,
         *                  which are the default project's by definition
         * @return that project's engine, or null when the project can no longer be opened (its
         *         folder moved, say) — those runs are left alone rather than resumed elsewhere
         */
        WorkflowEngine forProject(UUID projectId);
    }

    /** What was resumed, where, and under whose locks — the record the startup log is written from. */
    public record Resumed(UUID runId, UUID projectId, RunState state, Path repoPath,
                          List<String> lockedPaths) {
    }

    private final ArtifactStore store;
    private final Engines engines;

    public RunResumer(ArtifactStore store, Engines engines) {
        this.store = Objects.requireNonNull(store, "store");
        this.engines = Objects.requireNonNull(engines, "engines");
    }

    /**
     * Resumes every unfinished run in the store through the engine of the project it belongs to.
     *
     * <p>Before anything is resumed, {@link #abandonOrphans()} runs first: a run whose project id
     * no longer resolves in the store — the project was deleted, whether just now or before this
     * defense existed — is retired right here rather than handed to any engine. This is the fix for
     * the zombie-run defect (2026-09-03): a deleted project's EXECUTING run was still found by the
     * old {@code projectsWithUnfinishedRuns()}, and {@link Engines#forProject} opened it anyway
     * because the engine is built from the run's stored repository path, not from whether the
     * project record still exists — a folder the operator reuses for a new project stays openable
     * long after the project that used to own it is gone.
     */
    public List<Resumed> resumeAll() {
        abandonOrphans();
        List<Resumed> resumed = new ArrayList<>();
        for (UUID projectId : projectsWithUnfinishedRuns()) {
            WorkflowEngine engine;
            try {
                engine = engines.forProject(projectId);
            } catch (Exception e) {
                log.warn("Cannot open project {} to resume its runs — leaving them untouched: {}",
                    projectId, e.toString());
                continue;
            }
            if (engine == null) {
                // Deliberately NOT falling back to another project's engine. A run resumed against
                // the wrong repository writes to the wrong codebase with the wrong protections;
                // a run left where it is loses nothing and can be resumed on the next start.
                log.warn("No engine for project {} — its unfinished runs stay where they are "
                    + "rather than being resumed against another project's repository", projectId);
                continue;
            }
            for (Run run : engine.unfinishedRunsOf(projectId)) {
                log.info("Resuming run {} of project {} from state {}{} — repository {}, locked modules {}",
                    run.id(), projectId, run.state(),
                    run.pausedSince() == null ? "" : " (paused since " + run.pausedSince() + ")",
                    engine.repoPath(), engine.lockedPaths());
                resumed.add(new Resumed(run.id(), projectId, run.state(), engine.repoPath(),
                    engine.lockedPaths()));
                engine.advance(run);
            }
        }
        if (resumed.isEmpty()) {
            log.info("No unfinished runs to resume");
        } else {
            log.info("Resumed {} unfinished run(s) across {} project(s)", resumed.size(),
                resumed.stream().map(Resumed::projectId).distinct().count());
        }
        return List.copyOf(resumed);
    }

    /**
     * Retires every run whose project id no longer resolves in the store, before anything is
     * resumed. Covers both ways such a run can exist: a project deleted while the run was still
     * unfinished (going forward this is also handled at the point of deletion, but a run can still
     * be mid-flight in another process at that instant), and the pre-existing orphans a deleted
     * project left behind before deletion cleaned up after itself (the one-time sweep of what
     * already exists — folded in here rather than kept as a separate pass, since it is the same
     * check over the same rows).
     *
     * <p>A null project id is exempt by definition (javadoc, {@link Engines#forProject}): it names
     * runs persisted before multi-project existed, which belong to the default project and always
     * resolve.
     *
     * <p>Each retired run is logged once, by name, at WARN — an operator reading the log at start-up
     * needs to see exactly which run and which project, not a count. The one-line summary at the end
     * is for the case that count is what somebody is scanning for.
     */
    private void abandonOrphans() {
        List<Run> orphanRuns = new ArrayList<>();
        for (Run run : store.root().runs.values()) {
            if (run == null || run.projectId() == null || isTerminal(run.state())) {
                continue;
            }
            if (store.getProject(run.projectId()) != null) {
                continue;
            }
            orphanRuns.add(run);
            log.warn("Run {} belongs to project {} which no longer exists — marking it {}",
                run.id(), run.projectId(), RunState.ABANDONED);
        }
        if (orphanRuns.isEmpty()) {
            return;
        }
        Set<UUID> orphanRunIds = new HashSet<>();
        orphanRuns.forEach(run -> orphanRunIds.add(run.id()));
        List<UUID> droppedDecisions = new ArrayList<>();
        for (Decision decision : store.root().decisions.values()) {
            if (decision != null && decision.state() == DecisionState.PENDING
                    && decision.runId() != null && orphanRunIds.contains(decision.runId())) {
                droppedDecisions.add(decision.id());
            }
        }
        try {
            store.append(() -> {
                for (Run run : orphanRuns) {
                    store.root().runs.put(run.id(), run.withState(RunState.ABANDONED));
                }
                droppedDecisions.forEach(store.root().decisions::remove);
                return null;
            }).get();
        } catch (Exception e) {
            log.warn("Could not persist the orphan-run sweep — {} run(s) stay as they were: {}",
                orphanRuns.size(), e.toString());
            return;
        }
        log.info("Startup sweep: abandoned {} run(s) and removed {} pending decision(s) belonging "
            + "to project(s) no longer in the store", orphanRuns.size(), droppedDecisions.size());
    }

    private static boolean isTerminal(RunState state) {
        return state == RunState.DELIVERED || state == RunState.ABORTED
            || state == RunState.ABANDONED;
    }

    /** The projects owning at least one unfinished run; a null entry means "no project recorded". */
    private List<UUID> projectsWithUnfinishedRuns() {
        LinkedHashSet<UUID> projects = new LinkedHashSet<>();
        boolean projectless = false;
        for (Run run : store.root().runs.values()) {
            if (run == null || isTerminal(run.state())) {
                continue;
            }
            if (run.projectId() == null) {
                projectless = true;
            } else {
                projects.add(run.projectId());
            }
        }
        List<UUID> ordered = new ArrayList<>(projects);
        if (projectless) {
            // Runs from before multi-project existed: they belong to the default project, which the
            // registry answers for a null id.
            ordered.add(null);
        }
        return ordered;
    }
}
