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
package com.swarmcoder.store;

import com.swarmcoder.domain.*;
import org.eclipse.serializer.reference.Lazy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StoreRoot {
    /** Projects the orchestrator knows about (multi-project). Runs link back via projectId. */
    public final Map<UUID, Project> projects = new HashMap<>();
    public final Map<UUID, Run> runs = new HashMap<>();
    public final Map<UUID, DesignDocument> designs = new HashMap<>();
    public final Map<UUID, TaskGraph> taskGraphs = new HashMap<>();
    public final Map<UUID, KnowledgeBrief> briefs = new HashMap<>();
    public final Map<UUID, LearnedGuideline> guidelines = new HashMap<>();
    public final Map<UUID, Decision> decisions = new HashMap<>();
    public final Map<UUID, Lazy<Object>> candidateArchives = new HashMap<>(); // bulky
    /** Complete agent session traces (AgentSessionRecord), bulky — see the accessor. */
    private Map<UUID, Lazy<Object>> agentSessions = new HashMap<>();
    /** Console chats (schema v3) — never touch the fields directly, see the accessors. */
    private Map<UUID, ChatSession> chats = new HashMap<>();
    private Map<UUID, ChatMessage> chatMessages = new HashMap<>();
    /** Curated knowledge (schema v4) — store-first, not markdown files. */
    private Map<UUID, KnowledgeDoc> knowledgeDocs = new HashMap<>();
    /** Per-project BRDs (schema v5), keyed by projectId — exactly one living BRD per project. */
    private Map<UUID, Brd> brds = new HashMap<>();
    /** Append-only BRD revision history (schema v6), keyed by projectId — the document's evolution. */
    private Map<UUID, List<BrdRevision>> brdRevisions = new HashMap<>();
    /** Backlog stories (schema v7), by story id — the scheduled slices of the requirement graph. */
    private Map<UUID, Story> stories = new HashMap<>();
    /** Iterations (schema v7), by iteration id — named ordered batches, no dates or points. */
    private Map<UUID, Iteration> iterations = new HashMap<>();
    /**
     * Canonical index of tasks by id (schema v7). These are the SAME instances already reachable
     * through {@link #taskGraphs}: EclipseStore persists an object graph by reference, so indexing
     * them here costs one copy, not two. It exists because a task now needs stable, directly
     * addressable identity (backlog panel, git linkage) rather than only being findable by walking
     * the run's graph.
     */
    private Map<UUID, Task> tasks = new HashMap<>();
    /** Uploaded requirement sources (schema v7), by document id — intake provenance. */
    private Map<UUID, SourceDocument> sourceDocuments = new HashMap<>();
    /**
     * Append-only audit journal for the planning layer (schema v7), keyed by projectId. Records how
     * requirements, criteria, stories, iterations and tasks got to their current state. Entries are
     * never mutated and never removed. Distinct from {@link #agentSessions}, which records what
     * AGENTS DID; this records what THE PLAN IS AND WAS.
     */
    private Map<UUID, List<ChangeEvent>> changeEvents = new HashMap<>();
    /**
     * Append-only criterion verification history (schema v7), keyed by criterion id. The criterion's
     * own lastVerified* fields are a cache of the head; this is the truth, and the only thing that
     * can answer "when did this start passing, and when did it regress?".
     */
    private Map<UUID, List<CriterionVerification>> criterionVerifications = new HashMap<>();
    /**
     * Guided flows (schema v8), by flow id — the wizard's server-owned state.
     *
     * <p>Persisted rather than held in the dialog because extraction takes minutes: a flow that
     * lives in the browser is one the operator learns not to open, since closing the window would
     * discard the work. Storing it means the wizard can be closed, re-opened, watched from a second
     * tab, and survives a crash as a resumable record.
     */
    private Map<UUID, GuidedFlow> guidedFlows = new HashMap<>();
    /** A flow's clarification questions, by flow id — data, so the wizard can render a form. */
    private Map<UUID, List<FlowQuestion>> flowQuestions = new HashMap<>();
    /** A flow's proposed changes, by flow id. Nothing here has touched the BRD yet. */
    private Map<UUID, List<FlowProposal>> flowProposals = new HashMap<>();
    /**
     * The project the operator was last in (schema v9), so a restart reopens where they left off
     * rather than dropping them into whichever project the config file happens to name.
     */
    private UUID lastProjectId;
    /**
     * Side conversations about one clarification question (schema v10), keyed by QUESTION id — not
     * by flow id, because a discussion belongs to the question it explains and is opened, added to
     * and read one question at a time.
     */
    private Map<UUID, List<FlowDiscussionTurn>> flowDiscussions = new HashMap<>();
    /**
     * What the machine decided on the operator's behalf while running unattended (schema v11),
     * keyed by projectId and append-only.
     *
     * <p>Keyed by PROJECT rather than by session, because the question it answers is "what has been
     * decided for me on this project?" - which spans every night that ever ran. The session id
     * lives on each entry, so one stretch can still be read apart from the next.
     */
    private Map<UUID, List<AutonomousDecision>> autonomousDecisions = new HashMap<>();
    /**
     * Evidence that a worker's {@code exec} tool call is about to run (schema v12), keyed by
     * session id and overwritten on every new call — see {@link com.swarmcoder.domain.PendingExec}
     * for why this exists and why it is written before the process starts rather than only when
     * the session closes.
     */
    private Map<UUID, PendingExec> pendingExecs = new HashMap<>();
    /**
     * Cloud token counts per run, story and project (schema v13), keyed by the scope's own id, so
     * budget limits hold across restarts. Pre-v13 stores load this as null.
     */
    private Map<UUID, com.swarmcoder.domain.CloudSpendRecord> cloudSpend = new HashMap<>();
    /** The limit that parked each run waiting for a budget answer (schema v13), keyed by run id. */
    private Map<UUID, com.swarmcoder.domain.CloudBreachRecord> cloudBreaches = new HashMap<>();
    public long schemaVersion = 13;

    /**
     * Class-evolution guard: stores written before schema v2 load this field as null
     * (EclipseStore does not run field initializers on load). Never touch the field directly.
     */
    public synchronized Map<UUID, Lazy<Object>> agentSessions() {
        if (agentSessions == null) {
            agentSessions = new HashMap<>();
        }
        return agentSessions;
    }

    /** Chat sessions (v3 null-guard: pre-v3 stores load this field as null). */
    public synchronized Map<UUID, ChatSession> chats() {
        if (chats == null) {
            chats = new HashMap<>();
        }
        return chats;
    }

    /** Chat messages, flat by message id; filter by chatId + order by seq to read a transcript. */
    public synchronized Map<UUID, ChatMessage> chatMessages() {
        if (chatMessages == null) {
            chatMessages = new HashMap<>();
        }
        return chatMessages;
    }

    /** Curated knowledge docs (v4 null-guard: pre-v4 stores load this field as null). */
    public synchronized Map<UUID, KnowledgeDoc> knowledgeDocs() {
        if (knowledgeDocs == null) {
            knowledgeDocs = new HashMap<>();
        }
        return knowledgeDocs;
    }

    /** Per-project BRDs, keyed by projectId (v5 null-guard: pre-v5 stores load this field as null). */
    public synchronized Map<UUID, Brd> brds() {
        if (brds == null) {
            brds = new HashMap<>();
        }
        return brds;
    }

    /** BRD revision history by projectId (v6 null-guard: pre-v6 stores load this field as null). */
    public synchronized Map<UUID, List<BrdRevision>> brdRevisions() {
        if (brdRevisions == null) {
            brdRevisions = new HashMap<>();
        }
        return brdRevisions;
    }

    /** Backlog stories by id (v7 null-guard: pre-v7 stores load this field as null). */
    public synchronized Map<UUID, Story> stories() {
        if (stories == null) {
            stories = new HashMap<>();
        }
        return stories;
    }

    /** Iterations by id (v7 null-guard). */
    public synchronized Map<UUID, Iteration> iterations() {
        if (iterations == null) {
            iterations = new HashMap<>();
        }
        return iterations;
    }

    /** Task index by id (v7 null-guard) — same instances as those inside {@link #taskGraphs}. */
    public synchronized Map<UUID, Task> tasks() {
        if (tasks == null) {
            tasks = new HashMap<>();
        }
        return tasks;
    }

    /** Uploaded source documents by id (v7 null-guard). */
    public synchronized Map<UUID, SourceDocument> sourceDocuments() {
        if (sourceDocuments == null) {
            sourceDocuments = new HashMap<>();
        }
        return sourceDocuments;
    }

    /** Append-only planning-layer audit journal by projectId (v7 null-guard). */
    public synchronized Map<UUID, List<ChangeEvent>> changeEvents() {
        if (changeEvents == null) {
            changeEvents = new HashMap<>();
        }
        return changeEvents;
    }

    /** Append-only criterion verification history by criterion id (v7 null-guard). */
    public synchronized Map<UUID, List<CriterionVerification>> criterionVerifications() {
        if (criterionVerifications == null) {
            criterionVerifications = new HashMap<>();
        }
        return criterionVerifications;
    }

    /** Guided flows by id (v8 null-guard: pre-v8 stores load this field as null). */
    public synchronized Map<UUID, GuidedFlow> guidedFlows() {
        if (guidedFlows == null) {
            guidedFlows = new HashMap<>();
        }
        return guidedFlows;
    }

    /** Flow questions by flow id (v8 null-guard). */
    public synchronized Map<UUID, List<FlowQuestion>> flowQuestions() {
        if (flowQuestions == null) {
            flowQuestions = new HashMap<>();
        }
        return flowQuestions;
    }

    /** Flow proposals by flow id (v8 null-guard). */
    public synchronized Map<UUID, List<FlowProposal>> flowProposals() {
        if (flowProposals == null) {
            flowProposals = new HashMap<>();
        }
        return flowProposals;
    }

    /**
     * Per-question discussion turns by question id (v10 null-guard: pre-v10 stores load this field
     * as null, because EclipseStore does not run field initializers on load).
     */
    public synchronized Map<UUID, List<FlowDiscussionTurn>> flowDiscussions() {
        if (flowDiscussions == null) {
            flowDiscussions = new HashMap<>();
        }
        return flowDiscussions;
    }

    /**
     * Autonomous decisions by project id (v11 null-guard: pre-v11 stores load this field as null,
     * because EclipseStore does not run field initializers on load).
     */
    public synchronized Map<UUID, List<AutonomousDecision>> autonomousDecisions() {
        if (autonomousDecisions == null) {
            autonomousDecisions = new HashMap<>();
        }
        return autonomousDecisions;
    }

    /** Pending exec evidence by session id (v12 null-guard: pre-v12 stores load this field as null). */
    public synchronized Map<UUID, PendingExec> pendingExecs() {
        if (pendingExecs == null) {
            pendingExecs = new HashMap<>();
        }
        return pendingExecs;
    }

    /** Cloud token counts by scope id (v13 null-guard). */
    public synchronized Map<UUID, com.swarmcoder.domain.CloudSpendRecord> cloudSpend() {
        if (cloudSpend == null) {
            cloudSpend = new HashMap<>();
        }
        return cloudSpend;
    }

    /** Budget limits that parked a run, by run id (v13 null-guard). */
    public synchronized Map<UUID, com.swarmcoder.domain.CloudBreachRecord> cloudBreaches() {
        if (cloudBreaches == null) {
            cloudBreaches = new HashMap<>();
        }
        return cloudBreaches;
    }

    /** The last project switched to, or null on a pre-v9 store or a first run. */
    public synchronized UUID lastProjectId() {
        return lastProjectId;
    }

    public synchronized void setLastProjectId(UUID lastProjectId) {
        this.lastProjectId = lastProjectId;
    }
}
