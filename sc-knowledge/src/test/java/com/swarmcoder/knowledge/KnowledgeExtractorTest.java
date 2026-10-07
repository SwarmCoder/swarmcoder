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
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Post-run knowledge proposals: PROPOSED KnowledgeDoc OBJECTS in the store, never files. */
class KnowledgeExtractorTest {

    @TempDir
    Path storeDir;

    private static HttpServer scriptedLlm(String reply) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
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

    private static AgentSessionRecord session() {
        UUID id = UUID.randomUUID();
        return new AgentSessionRecord(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
            "worker-0", "qwen", 0.2, Instant.now(), Instant.now(), "COMPLETED", null, 5, 300,
            List.of(new TraceEvent(0, Instant.now(), TraceEventKind.TOOL_RESULT, "exec",
                "components must extend Div and register via addClassName", null, 100)));
    }

    @Test
    void proposesDocsInTheStoreAndNeverReproposesAnExistingSlug() throws Exception {
        String reply = "{\"docs\":[{\"slug\":\"component-pattern\",\"title\":\"Component pattern\","
            + "\"body\":\"Components extend Div and style via addClassName.\"}]}";
        HttpServer server = scriptedLlm(reply);
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = UUID.randomUUID();
            VllmClient utility = new VllmClient(
                "http://localhost:" + server.getAddress().getPort(), "", "u", true);
            KnowledgeExtractor extractor = new KnowledgeExtractor(
                utility, new CloudGate(1_000_000, null), store, projectId);

            assertThat(extractor.extractFromSessions(List.of(session())))
                .containsExactly("component-pattern");
            List<KnowledgeDoc> docs = store.listKnowledgeDocs(projectId);
            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).status()).isEqualTo("PROPOSED");
            assertThat(docs.get(0).source()).isEqualTo("extraction");
            assertThat(docs.get(0).body()).contains("extend Div");

            // The slug now exists (any status) — no re-proposal, no duplicates.
            assertThat(extractor.extractFromSessions(List.of(session()))).isEmpty();
            assertThat(store.listKnowledgeDocs(projectId)).hasSize(1);
        } finally {
            server.stop(0);
        }
    }
}
