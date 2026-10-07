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

import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.console.api.SessionSummaryDto;
import com.swarmcoder.console.api.TraceEventDto;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.ChatMessage;
import com.swarmcoder.domain.ChatSession;
import com.swarmcoder.domain.KnowledgeDoc;
import java.util.UUID;

/** Domain → wire-DTO mapping for the Console. */
final class Dtos {

    private static final int SNIPPET_CHARS = 400;

    private Dtos() { }




    static RunSummaryDto of(Run run) {
        return of(run, 0);
    }

    /**
     * @param tokensUsed what this run's sessions have spent, totalled by the caller in one pass —
     *                   the pipeline board's building cards show cost alongside phase and elapsed
     *                   time (UX v3 §3.1)
     */
    static RunSummaryDto of(Run run, long tokensUsed) {
        RunSummaryDto dto = new RunSummaryDto();
        dto.setTokensUsed(tokensUsed);
        dto.setRunId(run.id().toString());
        dto.setKind(run.kind() == null ? "" : run.kind().name());
        dto.setState(run.state() == null ? "" : run.state().name());
        dto.setGoal(run.report() == null || run.report().summary() == null ? "" : run.report().summary());
        dto.setStartedAtMillis(run.startedAt() == null ? 0 : run.startedAt().toEpochMilli());
        // Carried so the client can tell a run in progress from one nobody is driving. Runs that
        // predate the heartbeat report 0, which reads as "unknown" rather than "stalled" — an old
        // run must not be accused of being dead on the strength of a field it never had.
        dto.setHeartbeatAtMillis(
            run.heartbeatAt() == null ? 0 : run.heartbeatAt().toEpochMilli());
        // A paused run looks exactly like a working one from the client's side — same state, live
        // heartbeat — so without these two it would go on claiming to be building for the whole
        // outage. The sentence is composed server-side so there is one wording, not one per screen.
        dto.setPausedSinceMillis(
            run.pausedSince() == null ? 0 : run.pausedSince().toEpochMilli());
        dto.setPauseReason(run.pauseReason());
        // A parked run looks exactly like a working one otherwise — same state, and a heartbeat
        // that may still be fresh from the moment the stage parked. Carried for the same reason the
        // pause pair is: without it the client cannot say "stopped" until the heartbeat ages out.
        dto.setParkedAtMillis(run.parkedAt() == null ? 0 : run.parkedAt().toEpochMilli());
        dto.setParkReason(run.parkReason());
        return dto;
    }

    static SessionSummaryDto of(AgentSessionRecord record) {
        SessionSummaryDto dto = new SessionSummaryDto();
        dto.setSessionId(record.id().toString());
        dto.setRunId(record.runId() == null ? "" : record.runId().toString());
        dto.setTaskId(record.taskId() == null ? "" : record.taskId().toString());
        dto.setWorkerIndex(record.workerIndex());
        dto.setRole(record.role() == null ? "" : record.role());
        dto.setModel(record.modelProfileId() == null ? "" : record.modelProfileId());
        dto.setOutcome(record.outcome() == null ? "" : record.outcome());
        dto.setKillReason(record.killReason() == null ? "" : record.killReason().name());
        dto.setTurns(record.turns());
        dto.setTokens(record.totalTokens());
        dto.setOpenedAtMillis(record.openedAt() == null ? 0 : record.openedAt().toEpochMilli());
        dto.setClosedAtMillis(record.closedAt() == null ? 0 : record.closedAt().toEpochMilli());
        return dto;
    }

    static TraceEventDto of(UUID sessionId, TraceEvent event) {
        TraceEventDto dto = new TraceEventDto();
        dto.setSessionId(sessionId.toString());
        dto.setSeq(event.seq());
        dto.setAtMillis(event.at() == null ? 0 : event.at().toEpochMilli());
        dto.setKind(event.kind() == null ? "" : event.kind().name());
        dto.setLabel(event.label() == null ? "" : event.label());
        String payload = event.payload() == null ? "" : event.payload();
        dto.setPayloadSnippet(payload.length() > SNIPPET_CHARS
            ? payload.substring(0, SNIPPET_CHARS) + "…" : payload);
        dto.setPayloadRef(event.payloadRef() == null ? "" : event.payloadRef());
        dto.setTokens(event.tokensUsed());
        return dto;
    }

}
