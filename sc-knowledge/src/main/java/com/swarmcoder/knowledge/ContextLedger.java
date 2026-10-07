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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

/**
 * The Context Ledger's post-run learning (spec §12.3/§12.4): when a run finishes, its agent
 * sessions are indexed into the searchable history and mined for durable guidelines. Both
 * are best-effort — a failure here never blocks delivery. In-session checkpoint/rollback and
 * the debug-fork quarantine (§12.1/§12.2) are largely realized by the swarm's repair round
 * (a fresh context seeded from a compact failure brief); true intra-session rollback awaits
 * Koog snapshot integration (see sc-runtime/NOTES.md).
 */
public class ContextLedger {

    private static final Logger log = LoggerFactory.getLogger(ContextLedger.class);

    private final ArtifactStore store;
    private final HistoryRag historyRag;                  // nullable
    private final GuidelineExtractor extractor;           // nullable
    private final KnowledgeExtractor knowledgeExtractor;  // nullable
    private final boolean extractionEnabled;

    public ContextLedger(ArtifactStore store, HistoryRag historyRag,
                         GuidelineExtractor extractor, boolean extractionEnabled) {
        this(store, historyRag, extractor, null, extractionEnabled);
    }

    public ContextLedger(ArtifactStore store, HistoryRag historyRag,
                         GuidelineExtractor extractor, KnowledgeExtractor knowledgeExtractor,
                         boolean extractionEnabled) {
        this.store = store;
        this.historyRag = historyRag;
        this.extractor = extractor;
        this.knowledgeExtractor = knowledgeExtractor;
        this.extractionEnabled = extractionEnabled;
    }

    /** Indexes one completed session into searchable history (called on session end). */
    public void indexSession(AgentSessionRecord session) {
        if (historyRag != null && session != null) {
            historyRag.index(session);
        }
    }

    /** "Have we seen this before?" over past sessions (spec §12.3 search_history). */
    public List<HistoryRag.Hit> searchHistory(String query, int k) {
        return historyRag == null ? List.of() : historyRag.search(query, k);
    }

    /**
     * Mines a completed run's sessions for durable guidelines (spec §12.4) and, when a
     * knowledge extractor is wired, PROPOSED knowledge docs (conventions/examples/best
     * practices for the Console's Knowledge editor).
     */
    public List<String> extractGuidelines(UUID runId) {
        if (!extractionEnabled || runId == null) {
            return List.of();
        }
        List<AgentSessionRecord> sessions = sessionsForRun(runId);
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<String> written = new ArrayList<>();
        if (extractor != null) {
            written.addAll(extractor.extractFromSessions(sessions));
        }
        if (knowledgeExtractor != null) {
            written.addAll(knowledgeExtractor.extractFromSessions(sessions));
        }
        if (!written.isEmpty()) {
            log.info("Run {}: extracted {} PROPOSED guideline(s)/knowledge doc(s)", runId, written.size());
        }
        return written;
    }

    private List<AgentSessionRecord> sessionsForRun(UUID runId) {
        List<AgentSessionRecord> sessions = new ArrayList<>();
        for (Lazy<Object> lazy : store.root().agentSessions().values()) {
            if (Lazy.get(lazy) instanceof AgentSessionRecord record && runId.equals(record.runId())) {
                sessions.add(record);
            }
        }
        return sessions;
    }
}
