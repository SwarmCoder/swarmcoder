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
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.LibraryDoc;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * The Researcher (author requirement 2026-07-14, web access approved): an LLM agent that
 * scours the open web, the Context7 MCP docs, and the local sources for knowledge the
 * project is missing — version-accurate library facts, framework idioms, pitfalls — and
 * files each finding as a PROPOSED {@link KnowledgeDoc} for operator review in the
 * Console's Knowledge editor. Same text TOOL protocol as the chat analyst (works on every
 * provider); budget-gated through the CloudGate; everything best-effort.
 */
public class ResearcherAgent {

    public static class LlmDocs {
        public List<LlmDoc> docs;
    }
    public static class LlmDoc {
        public String slug;
        public String title;
        public String body;
    }

    private static final Logger log = LoggerFactory.getLogger(ResearcherAgent.class);
    private static final int MAX_TOOL_ROUNDS = 16;
    private static final int MAX_DOCS_PER_MISSION = 5;

    private final VllmClient model;
    private final CloudGate cloudGate;
    private final ArtifactStore store;
    private final UUID projectId;
    private final KnowledgeCurator curator;
    private final Librarian librarian;
    private final WebAccess web;
    private final McpServers mcpServers; // nullable
    private final Path projectRoot; // nullable — manifest scan skipped without it
    private final ObjectMapper mapper = new ObjectMapper();

    public ResearcherAgent(VllmClient model, CloudGate cloudGate, ArtifactStore store,
                           UUID projectId, KnowledgeCurator curator, Librarian librarian,
                           WebAccess web, Path projectRoot) {
        this(model, cloudGate, store, projectId, curator, librarian, web, null, projectRoot);
    }

    public ResearcherAgent(VllmClient model, CloudGate cloudGate, ArtifactStore store,
                           UUID projectId, KnowledgeCurator curator, Librarian librarian,
                           WebAccess web, McpServers mcpServers, Path projectRoot) {
        this.model = model;
        this.cloudGate = cloudGate;
        this.store = store;
        this.projectId = projectId;
        this.curator = curator;
        this.librarian = librarian;
        this.web = web;
        this.mcpServers = mcpServers;
        this.projectRoot = projectRoot;
    }

    /**
     * Runs one research mission; returns a human-readable outcome summary. Blocking —
     * callers run it on a background thread.
     */
    public String research(String topic) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt()));
            messages.add(Map.of("role", "user", "content",
                topic == null || topic.isBlank()
                    ? "Survey this project's libraries and frameworks. Find the most valuable "
                      + "knowledge the curated set is missing and file it."
                    : "Research this topic for the project and file what you learn: " + topic));

            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                String reply = call(messages);
                String toolLine = firstToolLine(reply);
                if (toolLine == null || round == MAX_TOOL_ROUNDS) {
                    return file(reply);
                }
                String result = executeTool(toolLine);
                log.info("Researcher: {} ({} chars back)", toolLine, result.length());
                messages.add(Map.of("role", "assistant", "content", reply));
                messages.add(Map.of("role", "user", "content",
                    "TOOL RESULT for `" + toolLine + "`:\n" + result
                    + "\n\nContinue researching, or produce your final JSON findings."));
            }
            return "research ended without findings";
        } catch (Exception e) {
            log.warn("Research mission failed: {}", e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    private String call(List<Map<String, String>> messages) throws Exception {
        long estimate = messages.stream().mapToLong(m -> CloudGate.estimateTokens(m.get("content"))).sum();
        cloudGate.charge(estimate);
        String reply = model.as("knowledge").chatCompletionStream(messages, null, 0.3).collect(Collectors.joining());
        cloudGate.charge(CloudGate.estimateTokens(reply));
        return reply.strip();
    }

    private String systemPrompt() {
        List<String> existingSlugs = store.listKnowledgeDocs(projectId).stream()
            .map(KnowledgeDoc::slug).toList();
        String libraries = "";
        try {
            if (projectRoot != null) {
                libraries = ManifestParser.parse(projectRoot).stream()
                    .map(LibraryDoc::coordinate).limit(20).collect(Collectors.joining(", "));
            }
        } catch (Exception ignored) {
            // manifest is optional context
        }
        return "You are SwarmCoder's RESEARCHER: you gather knowledge that helps coding agents "
            + "work on this project — version-accurate library facts, framework idioms, "
            + "canonical examples, pitfalls with resolutions. Codebase-specific and durable "
            + "only; no generic advice.\n"
            + "PROJECT LIBRARIES: " + (libraries.isBlank() ? "(none detected)" : libraries) + "\n"
            + "ALREADY-CURATED SLUGS (do not duplicate): " + existingSlugs + "\n"
            + "TOOLS — to use one, reply with EXACTLY one line and nothing else:\n"
            + "TOOL web_search <query>\n"
            + "TOOL fetch_url <http(s) url>\n"
            + "TOOL context7_docs <library name>   (version-accurate docs service)\n"
            + "TOOL search_code <query>            (this project + its reference frameworks)\n"
            + "TOOL read_file <root>/<relative-path>\n"
            + (mcpServers == null || mcpServers.isEmpty() ? ""
                : "TOOL mcp_call <server>.<tool> <args>   (configured MCP servers; available "
                  + "tools:\n  " + mcpServers.listTools().replace("\n", "\n  ") + ")\n")
            + "You get each result back and may chain up to " + MAX_TOOL_ROUNDS + " tools.\n"
            + "WHEN DONE reply ONLY with JSON: {\"docs\":[{\"slug\":\"kebab-case\","
            + "\"title\":\"...\",\"body\":\"markdown — cite source URLs inline\"}]} — at most "
            + MAX_DOCS_PER_MISSION + " docs, empty list if nothing clears the bar. Facts must "
            + "come from tool results, never from memory alone.";
    }

    static String firstToolLine(String reply) {
        for (String line : reply.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            return stripped.startsWith("TOOL ") ? stripped : null;
        }
        return null;
    }

    private String executeTool(String toolLine) {
        try {
            String[] parts = toolLine.split("\\s+", 3);
            String tool = parts.length > 1 ? parts[1] : "";
            String argument = parts.length > 2 ? parts[2].strip() : "";
            return switch (tool) {
                case "web_search" -> web.search(argument);
                case "fetch_url" -> web.fetch(argument);
                case "context7_docs" -> librarian.fetchLibraryDocs(argument)
                    .map(docs -> docs.length() > 8_000 ? docs.substring(0, 8_000) + "…" : docs)
                    .orElse("Context7 has no docs for '" + argument + "' (or the MCP server is down)");
                case "search_code" -> curator.searchCode(argument, 40);
                case "read_file" -> curator.readFile(argument, 10_000);
                case "mcp_call" -> {
                    if (mcpServers == null) {
                        yield "error: no MCP servers configured";
                    }
                    String[] mcp = argument.split("\\s+", 2);
                    yield mcpServers.call(mcp[0], mcp.length > 1 ? mcp[1] : "");
                }
                default -> "error: unknown tool '" + tool + "'";
            };
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** Parses the final JSON and persists PROPOSED docs, slug-deduped. */
    private String file(String finalReply) {
        try {
            LlmDocs parsed = LlmJson.parse(mapper, finalReply, LlmDocs.class);
            if (parsed.docs == null || parsed.docs.isEmpty()) {
                return "research complete — nothing new worth filing";
            }
            Set<String> existing = store.listKnowledgeDocs(projectId).stream()
                .map(KnowledgeDoc::slug).collect(Collectors.toSet());
            List<String> filed = new ArrayList<>();
            for (LlmDoc doc : parsed.docs) {
                if (filed.size() >= MAX_DOCS_PER_MISSION || doc.body == null
                        || doc.body.isBlank() || doc.slug == null) {
                    continue;
                }
                String slug = doc.slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
                    .replaceAll("-+", "-").replaceAll("^-|-$", "");
                if (slug.isBlank() || existing.contains(slug)) {
                    continue;
                }
                store.saveKnowledgeDoc(new KnowledgeDoc(UUID.randomUUID(), projectId, slug,
                    doc.title == null ? slug : doc.title, doc.body.strip(),
                    "PROPOSED", "research", Instant.now(), Instant.now()));
                filed.add(slug);
            }
            log.info("Researcher filed {} PROPOSED doc(s): {}", filed.size(), filed);
            return filed.isEmpty() ? "research complete — nothing new worth filing"
                : "filed " + filed.size() + " proposal(s): " + String.join(", ", filed);
        } catch (Exception e) {
            return "research finished but findings were unparseable: " + e.getMessage();
        }
    }
}
