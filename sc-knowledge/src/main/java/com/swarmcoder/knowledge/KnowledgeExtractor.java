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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * The knowledge half of the post-run learning loop (author requirement 2026-07-14): after a
 * run, the utility model mines its sessions for DOCUMENTATION-worthy material — conventions
 * that emerged, canonical examples, best practices, API pitfalls — and persists each as a
 * PROPOSED {@link KnowledgeDoc} in the store (store-first: no markdown files). The
 * Console's Knowledge editor lists proposals for the operator to accept, edit, or delete;
 * ACTIVE docs feed every future brief and the chat analyst via the Librarian.
 */
public class KnowledgeExtractor {

    public static class LlmDocs {
        public List<LlmDoc> docs;
    }
    public static class LlmDoc {
        public String slug;
        public String title;
        public String body;
    }

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractor.class);
    private static final int MAX_TRANSCRIPT_CHARS = 20_000;
    private static final int MAX_NEW_PER_RUN = 3;

    private final VllmClient utility;
    private final CloudGate cloudGate;
    private final ArtifactStore store;
    private final UUID projectId;
    private final ObjectMapper mapper = new ObjectMapper();

    public KnowledgeExtractor(VllmClient utility, CloudGate cloudGate, ArtifactStore store,
                              UUID projectId) {
        this.utility = utility;
        this.cloudGate = cloudGate;
        this.store = store;
        this.projectId = projectId;
    }

    /** Proposes knowledge docs from a run's sessions; returns the slugs written. */
    public List<String> extractFromSessions(List<AgentSessionRecord> sessions) {
        if (store == null || utility == null || sessions.isEmpty()) {
            return List.of();
        }
        try {
            String transcript = digest(sessions);
            if (transcript.isBlank()) {
                return List.of();
            }
            String system = "You mine an agent-run transcript for DOCUMENTATION-worthy knowledge "
                + "that will help future coding agents on this repository: conventions that "
                + "emerged, a canonical example worth preserving (with the actual code from the "
                + "transcript), best practices, API pitfalls encountered and their resolutions. "
                + "Only durable, codebase-specific material — no generic advice, no one-off "
                + "details. Respond ONLY with JSON: {\"docs\":[{\"slug\":\"kebab-case\","
                + "\"title\":\"...\",\"body\":\"markdown\"}]}. Return an empty list when nothing "
                + "clears the bar — most runs teach nothing new.";
            cloudGate.charge(CloudGate.estimateTokens(system) + CloudGate.estimateTokens(transcript));
            String response = utility.as("knowledge").chatCompletionStream(List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", transcript)),
                LlmDocs.class, 0.2).collect(Collectors.joining());
            cloudGate.chargeOutput(CloudGate.estimateTokens(response));

            LlmDocs parsed = LlmJson.parse(mapper, response, LlmDocs.class);
            if (parsed.docs == null || parsed.docs.isEmpty()) {
                return List.of();
            }
            Set<String> existingSlugs = store.listKnowledgeDocs(projectId).stream()
                .map(KnowledgeDoc::slug).collect(Collectors.toSet());
            List<String> written = new ArrayList<>();
            for (LlmDoc doc : parsed.docs) {
                if (written.size() >= MAX_NEW_PER_RUN || doc.body == null || doc.body.isBlank()
                        || doc.slug == null) {
                    continue;
                }
                String slug = doc.slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
                    .replaceAll("-+", "-").replaceAll("^-|-$", "");
                if (slug.isBlank() || existingSlugs.contains(slug)) {
                    continue; // never re-propose a slug the operator already has (any status)
                }
                store.saveKnowledgeDoc(new KnowledgeDoc(UUID.randomUUID(), projectId, slug,
                    doc.title == null ? slug : doc.title, doc.body.strip(),
                    "PROPOSED", "extraction", Instant.now(), Instant.now()));
                written.add(slug);
            }
            if (!written.isEmpty()) {
                log.info("Knowledge extraction proposed {} doc(s): {}", written.size(), written);
            }
            return written;
        } catch (Exception e) {
            log.warn("Knowledge extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Failures first — that is where the documentation-worthy lessons live. */
    private static String digest(List<AgentSessionRecord> sessions) {
        StringBuilder sb = new StringBuilder();
        sessions.stream()
            .sorted((a, b) -> Boolean.compare(!"COMPLETED".equals(b.outcome()),
                !"COMPLETED".equals(a.outcome())))
            .forEach(session -> {
                if (sb.length() > MAX_TRANSCRIPT_CHARS) {
                    return;
                }
                sb.append("=== ").append(session.role()).append(" (").append(session.outcome())
                  .append(") ===\n");
                if (session.events() != null) {
                    for (TraceEvent event : session.events()) {
                        if (sb.length() > MAX_TRANSCRIPT_CHARS) {
                            break;
                        }
                        if (event.payload() != null && !event.payload().isBlank()) {
                            sb.append(event.kind()).append(' ')
                              .append(event.label() == null ? "" : event.label()).append(": ")
                              .append(event.payload(), 0, Math.min(600, event.payload().length()))
                              .append('\n');
                        }
                    }
                }
            });
        return sb.toString();
    }
}
