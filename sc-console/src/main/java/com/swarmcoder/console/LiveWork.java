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

import com.swarmcoder.console.api.TraceEventDto;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.TraceEvent;

import java.util.List;
import java.util.UUID;

/**
 * The one derivation of "what is this worker doing right now".
 *
 * <p>A worker that is alive and a worker that hung look identical from its session record alone:
 * both are open, neither has closed. What separates them is the last step it took and how long ago
 * — and that answer was being worked out in one place only, inside the MCP {@code swarm_status}
 * tool, by paging every event of every session and keeping the last one. The run graph in the
 * browser did not have it at all, which is why a screen full of workers could show nothing.
 *
 * <p>Now the last step is read here, once, and three readers share it: {@code swarm_status},
 * {@code run_diagnosis} and the run graph. A second derivation would be a second opinion about
 * whether the swarm is alive.
 */
final class LiveWork {

    private LiveWork() { }

    /**
     * The last step a session took, or null when it has taken none.
     *
     * <p>Reads the tail directly rather than paging from the front: a session's events are held in
     * order, so the last one is the last element.
     */
    static TraceEventDto lastStep(AgentSessionRecord record) {
        if (record == null || record.events() == null || record.events().isEmpty()) {
            return null;
        }
        List<TraceEvent> events = record.events();
        TraceEvent last = events.get(events.size() - 1);
        return Dtos.of(record.id(), last);
    }

    /** The last step of the session with this id, live or persisted; null when there is none. */
    static TraceEventDto lastStep(UUID sessionId) {
        AgentSessionRecord record = ConsoleContext.get().traceHub().activeSessions().stream()
            .filter(session -> session.id().equals(sessionId))
            .findFirst()
            .orElse(null);
        if (record == null) {
            var lazy = ConsoleContext.get().store().root().agentSessions().get(sessionId);
            record = lazy == null ? null
                : (AgentSessionRecord) org.eclipse.serializer.reference.Lazy.get(lazy);
        }
        return lastStep(record);
    }
}
