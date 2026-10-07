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

import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.zeroz4j.server.UploadResult;
import com.zeroz4j.server.Zeroz4jServer;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uploading a requirements document lands a {@link SourceDocument} in the store, and the
 * unauthenticated endpoint it used to arrive through is gone.
 *
 * <p>This replaces {@code IngestEndpointTest}, which proved that a hand-rolled
 * {@code POST /api/ingest} JAX-RS resource was reachable. It was — by anybody, with no credentials
 * at all. Uploads now go to the framework's own address, which refuses a file without a one-time
 * pass issued over the live authenticated connection, so a plain HTTP POST is no longer a way in
 * and there is no resource of ours left to prove discoverable.
 *
 * <p>What is worth proving instead is the two halves that are still ours: given a file the handler
 * does the right thing with it, and CDI can find the handler at all.
 */
class DocumentUploadTest {

    @Test
    void anUploadedDocumentIsExtractedAndPersistedAgainstTheCurrentProject(@TempDir Path dir)
            throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> null, runId -> { }, runId -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));

            DocumentUploadHandler handler = new DocumentUploadHandler();

            UploadResult accepted = handler.ingest("spec.md", "text/markdown",
                "# Checkout\n\nA guest must be able to pay without an account.\n"
                    .getBytes(StandardCharsets.UTF_8));

            assertThat(accepted.isAccepted()).isTrue();
            assertThat(accepted.getMessage()).contains("read by passthrough");

            List<SourceDocument> documents = store.listSourceDocuments(projectId);
            assertThat(documents).hasSize(1);
            assertThat(documents.get(0).filename()).isEqualTo("spec.md");
            assertThat(documents.get(0).extractedText()).contains("without an account");

            // An unreadable file is the operator's mistake to fix, so it comes back refused with a
            // sentence they can act on, and nothing is persisted. The framework shows that sentence
            // beside the file in the upload box.
            UploadResult refused = handler.ingest("payload.bin", "application/octet-stream",
                new byte[] {1, 2, 3});

            assertThat(refused.isAccepted()).isFalse();
            assertThat(refused.getMessage()).isNotBlank();
            assertThat(store.listSourceDocuments(projectId)).hasSize(1);
        } finally {
            ConsoleContext.set(null);
        }
    }

    /**
     * The framework can find the handler, and the endpoint that used to take unauthenticated bytes
     * answers nothing.
     *
     * <p>Both halves fail silently otherwise: a handler CDI cannot see means every upload comes
     * back "no handler", and a JAX-RS resource left behind by accident is an open door that no unit
     * test would ever mention.
     */
    @Test
    void theFrameworkFindsTheHandlerAndTheOldOpenEndpointIsGone(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> null, runId -> { }, runId -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));

            try (Zeroz4jServer server = Zeroz4jServer.start(0, "Test Console")) {
                assertThat(CDI.current().select(DocumentUploadHandler.class).isResolvable())
                    .as("the upload handler must be a CDI bean, or every upload is refused")
                    .isTrue();

                HttpResponse<String> gone = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                            + "/api/ingest?filename=spec.md"))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                            "# spec\n".getBytes(StandardCharsets.UTF_8)))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());

                assertThat(gone.statusCode())
                    .as("POST /api/ingest took attacker-supplied bytes with no credentials; "
                        + "it must not answer any more")
                    .isNotEqualTo(200);
                assertThat(store.listSourceDocuments(projectId)).isEmpty();
            }
        } finally {
            ConsoleContext.set(null);
        }
    }
}
