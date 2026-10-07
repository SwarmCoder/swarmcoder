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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;

/** The Researcher: text tool loop over web/context7/sources → PROPOSED knowledge docs. */
class ResearcherAgentTest {

    @TempDir
    Path storeDir;

    /** Serves a scripted queue of replies, one per chat call, and records the last request. */
    private static HttpServer scriptedLlm(Deque<String> replies, AtomicReference<String> lastRequest)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String reply = replies.isEmpty() ? "{\"docs\":[]}" : replies.poll();
            ObjectNode chunk = mapper.createObjectNode();
            ((ObjectNode) chunk.putArray("choices").addObject().put("index", 0))
                .putObject("delta").put("content", reply);
            byte[] body = ("data: " + mapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    @Test
    void researchesViaToolsThenFilesProposedDocs() throws Exception {
        Deque<String> replies = new ArrayDeque<>();
        replies.add("TOOL web_search Vaadin grid lazy loading");     // round 1: search
        replies.add("TOOL fetch_url https://example.com/vaadin");     // round 2: fetch
        replies.add("{\"docs\":[{\"slug\":\"grid-lazy-loading\",\"title\":\"Grid lazy loading\","
            + "\"body\":\"Use setItems(CallbackDataProvider) — source: https://example.com/vaadin\"}]}");
        AtomicReference<String> lastRequest = new AtomicReference<>();
        HttpServer llm = scriptedLlm(replies, lastRequest);

        // A WebAccess double records what the agent asked for.
        List<String> webCalls = new ArrayList<>();
        WebAccess web = new WebAccess() {
            @Override public String search(String query) {
                webCalls.add("search:" + query);
                return "Vaadin Docs — https://example.com/vaadin — lazy loading with callbacks";
            }
            @Override public String fetch(String url) {
                webCalls.add("fetch:" + url);
                return "Grids load lazily via setItems(CallbackDataProvider).";
            }
        };

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = UUID.randomUUID();
            VllmClient model = new VllmClient(
                "http://localhost:" + llm.getAddress().getPort(), "", "u", true);
            KnowledgeCurator curator = new KnowledgeCurator(List.of(), null, null);
            Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"),
                null, List.of(), null, null, null);
            ResearcherAgent researcher = new ResearcherAgent(model, new CloudGate(5_000_000, null),
                store, projectId, curator, librarian, web, null);

            String outcome = researcher.research("Vaadin grid lazy loading");

            assertThat(webCalls).containsExactly(
                "search:Vaadin grid lazy loading", "fetch:https://example.com/vaadin");
            assertThat(outcome).contains("grid-lazy-loading");
            List<KnowledgeDoc> docs = store.listKnowledgeDocs(projectId);
            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).status()).isEqualTo("PROPOSED");
            assertThat(docs.get(0).source()).isEqualTo("research");
            assertThat(docs.get(0).body()).contains("CallbackDataProvider").contains("example.com");
        } finally {
            llm.stop(0);
        }
    }

    @Test
    void webAccessStripsHtmlToReadableText() {
        String text = WebAccess.Standard.strip(
            "<html><head><style>.x{}</style></head><body><h1>Title</h1>"
            + "<p>Hello &amp; welcome</p><script>evil()</script></body></html>");
        assertThat(text).contains("Title").contains("Hello & welcome")
            .doesNotContain("evil").doesNotContain("<");
    }
}
