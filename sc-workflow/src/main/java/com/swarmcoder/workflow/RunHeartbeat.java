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

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a run's "somebody is on this" stamp fresh while its workers are working.
 *
 * <h2>The fault this fixes</h2>
 *
 * <p>The stamp was written in exactly one place: {@link RunPersister}, which runs on a workflow
 * state transition. That is right for every stage that is a sequence of short steps, and wrong for
 * the one stage that is not. A swarm building one task holds the run at EXECUTING for as long as
 * the slowest worker takes — forty-five minutes is ordinary — and nothing transitions in that time,
 * so nothing stamps.
 *
 * <p>The consequence was measured on 2026-09-01: {@code swarm_status} reported "Nothing has touched
 * this run for 29m — the process driving it is gone" about a run whose four workers had produced a
 * step thirty-five seconds earlier. The one signal the operator has for telling a working build
 * from a dead one said "dead" about a healthy one, which is worse than having no signal at all.
 *
 * <p>So the stamp is written from the place that actually knows: every worker step passes through
 * the {@link TraceHub}, every session names the run it belongs to, and a step is the definition of
 * something driving a run. Throttled to one write per run per {@link #MIN_GAP_MILLIS}, because a
 * busy swarm emits several steps a second and the stamp only has to be fresher than the five
 * minutes after which a run reads as stalled.
 *
 * <p>It writes the run object itself rather than putting it back in the store's map. Putting it
 * back would let a heartbeat taken from an older copy overwrite a state a workflow transition had
 * just written; storing the instance in place cannot.
 */
public final class RunHeartbeat implements TraceHub.Listener {

    /** At most one stamp per run in this window. Well under the five minutes that reads as dead. */
    static final long MIN_GAP_MILLIS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(RunHeartbeat.class);

    private final ArtifactStore store;
    /** sessionId -> the run it is working for; only sessions that name a run are held. */
    private final Map<UUID, UUID> runOfSession = new ConcurrentHashMap<>();
    /** runId -> when it was last stamped from here. */
    private final Map<UUID, Long> lastStamp = new ConcurrentHashMap<>();

    public RunHeartbeat(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public void sessionStarted(AgentSessionRecord snapshot) {
        if (snapshot == null || snapshot.runId() == null) {
            return;
        }
        runOfSession.put(snapshot.id(), snapshot.runId());
        touch(snapshot.runId(), true);
    }

    @Override
    public void event(UUID sessionId, TraceEvent event) {
        UUID runId = runOfSession.get(sessionId);
        if (runId != null) {
            touch(runId, false);
        }
    }

    @Override
    public void sessionEnded(AgentSessionRecord complete) {
        if (complete == null) {
            return;
        }
        UUID runId = runOfSession.remove(complete.id());
        if (runId != null) {
            touch(runId, true);
        }
    }

    /** @param force stamp even inside the throttle window — used when a session opens or closes */
    private void touch(UUID runId, boolean force) {
        long now = System.currentTimeMillis();
        Long previous = lastStamp.get(runId);
        if (!force && previous != null && now - previous < MIN_GAP_MILLIS) {
            return;
        }
        lastStamp.put(runId, now);
        try {
            Run run = store.root().runs.get(runId);
            if (run == null) {
                return;
            }
            // Stamped in memory first, then persisted. Every reader — the pipeline board, the
            // agent tools, crash resume — reads the run out of the store's own object graph, so
            // the stamp has to be true the instant it is taken rather than whenever the writer
            // thread gets to it.
            run.setHeartbeatAt(Instant.now());
            store.updateRunLater(run);
        } catch (Exception e) {
            // Never break a worker over a heartbeat. A missed stamp costs one stale reading; an
            // exception thrown back into the trace hub costs the step that was being reported.
            log.debug("Could not stamp the heartbeat of run {}: {}", runId, e.toString());
        }
    }
}
