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
package com.swarmcoder.console.api;

import com.zeroz4j.api.RmiService;
import com.zeroz4j.api.Secured;

import java.util.List;

/**
 * Read side of the Console (docs/OBSERVABILITY_DESIGN.md §4). Push topics carrying the
 * same DTOs: {@code swarm-sessions} (SessionSummaryDto on start/end) and
 * {@code swarm-events} (TraceEventDto per live step).
 */
@RmiService
@Secured
public interface ObserverService {

    List<RunSummaryDto> listRuns();

    List<SessionSummaryDto> activeSessions();

    /** Persisted or live session steps from {@code fromSeq}, oldest first, at most {@code max}. */
    List<TraceEventDto> sessionEvents(String sessionId, long fromSeq, int max);

    /**
     * The last step this session took — what the worker is doing right now, when it is still open.
     *
     * <p>Its own call rather than the last page of {@link #sessionEvents}: reading the tail is what
     * every caller actually wants, paging to it costs a full walk of a session that can hold
     * hundreds of steps, and two callers paging it their own way is two answers to "is this worker
     * alive". Null-shaped as an empty list so it crosses the wire without a nullable DTO.
     */
    List<TraceEventDto> lastStep(String sessionId);

    /** Persisted sessions, newest first — post-analysis browsing (design §5.3). */
    List<SessionSummaryDto> recentSessions(int max);

    /** Full content of a truncated payload (or verification log / screenshot text) by blob ref. */
    String blobText(String ref);

    /** The FULL payload of one event (by session + seq), resolving the blob store when needed. */
    String eventPayload(String sessionId, long seq);

    /**
     * The exact system prompt a session's worker received — the SESSION_OPENED payload,
     * blob-resolved. This is the rendered PromptBundle (## SEGMENT-framed) for the Prompt Lab.
     */
    String sessionPrompt(String sessionId);

    /** Aggregates over archived candidates + sessions — the improvement loop (design §5.4). */
    InsightsDto insights();

    /** "Have we seen this before?" over past session transcripts (spec §12.3 search_history). */
    List<SessionSummaryDto> searchHistory(String query, int max);
}


