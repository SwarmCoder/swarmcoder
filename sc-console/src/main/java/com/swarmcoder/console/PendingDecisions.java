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
package com.swarmcoder.console;

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Story;

import com.swarmcoder.store.ArtifactStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * How many decisions nobody has answered — and the ONLY definition of that in the Console.
 *
 * <p>It exists because the number was about to be computed twice. The status strip has said
 * "37 to approve" for as long as it has existed ({@code HealthServiceImpl} counting
 * {@code DecisionState.PENDING} inline), the Build stage was about to start saying it too, and two
 * inline loops over the same map is exactly how one surface comes to insist there are thirty-seven
 * and another that there are none. There is one predicate here, and both callers use it.
 *
 * <h2>What "pending" means</h2>
 *
 * <p>{@link DecisionState} has two values. PENDING is "nobody has written a response yet"; RESOLVED
 * is "somebody has". There is no third state and no expiry, so an unanswered decision stays owed
 * for ever — which is what makes a silent UI about them so expensive.
 *
 * <h2>What one actually BLOCKS — which is not what you would guess</h2>
 *
 * <p><b>Nothing in the engine waits on a decision.</b> No thread parks on one, no run polls for
 * one, and {@code ControlServiceImpl.resolveDecision} rewrites the row and touches nothing else:
 * answering does not restart anything. Saying "runs are waiting on you" would therefore be false,
 * and any sentence built on this count must not say it.
 *
 * <p>What IS true is where they come from. Every producer mints one at the point work stopped:
 *
 * <ul>
 *   <li>{@code GreenfieldWorkflow.queueDecision} — red-check failed, final integration failed, or
 *       an enforced quality gate blocked delivery. In all three the workflow {@code return}s and
 *       the run stays parked in {@code TEST_AUTHORING} or {@code FINAL_INTEGRATION} with no thread
 *       attached; only {@code RunResumer.resumeAll()} at process start ever picks it up again,
 *       and that ignores decisions entirely.</li>
 *   <li>{@code SwarmEngineImpl.queueBlockedDecision} — zero candidates survived the swarm and the
 *       repair round, so the task is marked BLOCKED and abandoned. The run carries on without it.</li>
 *   <li>{@code CloudGate.onExhausted} — the cloud token cap was breached; the gate now throws on
 *       every charge. This one carries {@code runId == null} by construction.</li>
 * </ul>
 *
 * <p>So the honest claim is "work stopped here and nobody has said what to do about it", not "a
 * thread is blocked on your answer". {@code ReadinessPublisher} words Build's sentence accordingly.
 */
final class PendingDecisions {

    private PendingDecisions() {}

    /**
     * Every unanswered decision in the store, across every project.
     *
     * <p>This is the number the status strip renders as "N to approve", unchanged — the strip's
     * other counters (runs, workers, sessions) are process-wide too, so scoping only this one would
     * make the strip disagree with itself.
     */
    static int all(ArtifactStore store) {
        if (store == null) {
            return 0;
        }
        int pending = 0;
        for (Decision decision : store.root().decisions.values()) {
            if (isPending(decision)) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * The unanswered decisions this project owes.
     *
     * <p>{@link Decision} has no project of its own — only a nullable {@code runId} — so the
     * project is joined through the run, exactly as {@code ArtifactStore.projectContents} does when
     * it works out what a project deletion has to take with it. Two joins, because either half can
     * be missing on real data: the run's own {@code projectId} (null on rows persisted before Run
     * carried one), and this project's stories' {@code runIds} (which survive whatever the run says
     * about itself). {@code ReadinessPublisher.anyRunRecorded} already pairs them the same way, and
     * for the same reason.
     *
     * <p><b>Unattributable decisions count here too.</b> A decision with no {@code runId} — every
     * {@code BUDGET_EXTENSION} is one, by construction — or one pointing at a run this store no
     * longer holds cannot be joined to any project. Dropping it would make it invisible on every
     * stage in the Console while the status strip went on counting it, which is precisely the
     * "thirty-seven owed and the UI is silent" failure this exists to end. So it is counted against
     * whichever project is being asked about. The consequence is deliberate and small: on a
     * multi-project install an exhausted cloud budget shows on each project's Build stage, which is
     * true — the budget is process-wide and the next run of any project will hit it.
     *
     * <p>The upshot for the common case, one project: this returns exactly what {@link #all} does,
     * so the Build badge and the status strip cannot contradict each other.
     */
    static int forProject(ArtifactStore store, UUID projectId, List<Story> stories) {
        if (store == null || projectId == null) {
            return 0;
        }
        Set<UUID> runsHere = new HashSet<>();
        for (Run run : store.root().runs.values()) {
            if (run != null && projectId.equals(run.projectId()) && run.id() != null) {
                runsHere.add(run.id());
            }
        }
        if (stories != null) {
            for (Story story : stories) {
                if (story != null && story.runIds() != null) {
                    runsHere.addAll(story.runIds());
                }
            }
        }
        int pending = 0;
        for (Decision decision : store.root().decisions.values()) {
            if (!isPending(decision)) {
                continue;
            }
            UUID runId = decision.runId();
            // Unattributable (no run, or a run this store no longer holds) counts as this
            // project's — see the javadoc: the alternative is a decision no stage ever mentions.
            if (runId == null || !store.root().runs.containsKey(runId)
                || runsHere.contains(runId)) {
                pending++;
            }
        }
        return pending;
    }

    private static boolean isPending(Decision decision) {
        return decision != null && decision.state() == DecisionState.PENDING;
    }
}
