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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing the console answers with can be big enough to close the connection.
 *
 * <p>Since ZeroZ Stack 0.7.0 a single message over 4 MB is not a failed call — the server closes
 * the socket. From the operator's chair that is the whole console going blank and reconnecting,
 * with nothing on screen saying why and nothing in the call's own result to catch. Before 0.7.0 the
 * ceiling was whatever Helidon allowed, about 2 GB, so none of this had ever been bounded.
 *
 * <p>Two paths could genuinely go over. The extractor keeps up to ten million characters of a
 * document's text, which is two and a half times the limit on its own, and the list of a project's
 * documents used to carry every one of those texts at once.
 */
class WireBudgetTest {

    @Test
    void textLongerThanTheBudgetIsCutAndSaysSo() {
        String huge = "x".repeat(WireBudget.MAX_TEXT_CHARS + 50_000);

        String clamped = WireBudget.clamp(huge, "This document");

        assertThat(clamped.length()).isLessThan(WireBudget.MAX_TEXT_CHARS + 1_000);
        assertThat(clamped).contains("This document");
        assertThat(clamped).contains("only the first");
        // Two bytes of headroom per character and the whole thing still fits inside the
        // framework's limit with room for the object around it.
        assertThat(clamped.length() * 3).isLessThan(WireBudget.FRAMEWORK_MAX_MESSAGE_BYTES);
    }

    @Test
    void textInsideTheBudgetIsUntouched() {
        assertThat(WireBudget.clamp("a short spec", "This document")).isEqualTo("a short spec");
        assertThat(WireBudget.clamp(null, "This document")).isEmpty();
    }

    /**
     * The document list is names and sizes, never texts.
     *
     * <p>This is the one that would have broken first: a project with a handful of large specs
     * could not list its documents at all, because listing them meant sending every extracted text
     * in one message.
     */
    @Test
    void listingDocumentsDoesNotCarryTheirText(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> null, runId -> { }, runId -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));

            new DocumentUploadHandler().ingest("spec.md", "text/markdown",
                "# Checkout\n\nA guest must be able to pay without an account.\n".getBytes("UTF-8"));

            List<SourceDocument> listed = new BrdServiceImpl().sources();

            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).filename()).isEqualTo("spec.md");
            assertThat(listed.get(0).byteSize()).isPositive();
            assertThat(listed.get(0).extractedBy()).isEqualTo("passthrough");
            assertThat(listed.get(0).extractedText())
                .as("the text belongs to the one document being read, not to the list")
                .isNull();

            // And it is still readable one document at a time, which is how the wizard shows it.
            String text = new GuidedFlowServiceImpl()
                .documentText(listed.get(0).id().toString());
            assertThat(text).contains("without an account");
        } finally {
            ConsoleContext.set(null);
        }
    }
}
