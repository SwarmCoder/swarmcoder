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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What deleting a project removed — or, from {@link ArtifactStore#projectContents}, what it WOULD
 * remove. Counts only; nothing here refers to the working tree, because deleting a project never
 * touches a single file on disk.
 *
 * <p>It exists so the confirm step can state the real cost ("requirements (12), stories (5)…")
 * instead of an unquantified "are you sure?", and so the server can log exactly what it destroyed.
 * A map rather than twenty int fields: the set of per-project roots grows with the schema, and
 * every root that is added should show up in the log without a signature change.
 */
public final class ProjectDeletionSummary {

    public static final String PROJECTS = "projects";
    public static final String RUNS = "runs";
    public static final String DESIGNS = "designs";
    public static final String TASK_GRAPHS = "taskGraphs";
    public static final String KNOWLEDGE_BRIEFS = "knowledgeBriefs";
    public static final String DECISIONS = "decisions";
    public static final String CANDIDATE_ARCHIVES = "candidateArchives";
    public static final String AGENT_SESSIONS = "agentSessions";
    public static final String CHATS = "chats";
    public static final String CHAT_MESSAGES = "chatMessages";
    public static final String KNOWLEDGE_DOCS = "knowledgeDocs";
    public static final String BRDS = "brds";
    public static final String BRD_REVISIONS = "brdRevisions";
    public static final String REQUIREMENTS = "requirements";
    public static final String STORIES = "stories";
    public static final String ITERATIONS = "iterations";
    public static final String TASKS = "tasks";
    public static final String SOURCE_DOCUMENTS = "sourceDocuments";
    public static final String CHANGE_EVENTS = "changeEvents";
    public static final String CRITERION_VERIFICATIONS = "criterionVerifications";
    public static final String GUIDED_FLOWS = "guidedFlows";
    public static final String FLOW_QUESTIONS = "flowQuestions";
    public static final String FLOW_PROPOSALS = "flowProposals";
    public static final String FLOW_DISCUSSION_TURNS = "flowDiscussionTurns";
    /** The project's rules — stated, learned or hand-written — of every status. */
    public static final String RULES = "rules";

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    ProjectDeletionSummary() {
    }

    /** Records a count; zero and negative counts are dropped so the log stays readable. */
    void add(String what, int howMany) {
        if (howMany > 0) {
            counts.merge(what, howMany, Integer::sum);
        }
    }

    public int count(String what) {
        Integer value = counts.get(what);
        return value == null ? 0 : value;
    }

    /** Non-zero counts, in removal order. */
    public Map<String, Integer> counts() {
        return Collections.unmodifiableMap(counts);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    /** The five the operator actually recognises, for the confirm sentence. */
    public int requirements() {
        return count(REQUIREMENTS);
    }

    public int stories() {
        return count(STORIES);
    }

    public int chats() {
        return count(CHATS);
    }

    public int runs() {
        return count(RUNS);
    }

    public int sourceDocuments() {
        return count(SOURCE_DOCUMENTS);
    }

    /** e.g. {@code "requirements=12, stories=5, chats=3"} — the INFO log line's payload. */
    @Override
    public String toString() {
        if (counts.isEmpty()) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }
}
