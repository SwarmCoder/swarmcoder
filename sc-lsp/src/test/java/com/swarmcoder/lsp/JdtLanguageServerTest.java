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
package com.swarmcoder.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Validates the graceful-degradation contract without a live JDT LS: with no product installed,
 * the server is permanently unavailable and every call is a safe no-op that never throws. The
 * live-launch path is exercised only when a real JDT LS home is present (see docs — same
 * environment-gated caveat as the Docker sandbox).
 */
class JdtLanguageServerTest {

    @Test
    void unavailableWhenHomeIsNull(@TempDir Path workspace) {
        try (JdtLanguageServer lsp = new JdtLanguageServer(null, workspace)) {
            assertThat(lsp.isAvailable()).isFalse();
            assertThat(lsp.diagnostics(workspace.resolve("A.java"))).isEmpty();
        }
    }

    @Test
    void unavailableWhenHomeIsMissing(@TempDir Path workspace) {
        Path missing = workspace.resolve("no-such-jdtls");
        try (JdtLanguageServer lsp = new JdtLanguageServer(missing, workspace)) {
            assertThat(lsp.isAvailable()).isFalse();
        }
    }

    @Test
    void unavailableWhenHomeHasNoLauncher(@TempDir Path root) throws Exception {
        // A home directory that exists but lacks plugins/ + config_* must degrade, not crash.
        Path home = Files.createDirectories(root.resolve("jdtls"));
        Path workspace = Files.createDirectories(root.resolve("repo"));
        Files.writeString(workspace.resolve("App.java"), "public class App {}\n");
        try (JdtLanguageServer lsp = new JdtLanguageServer(home, workspace)) {
            assertThatCode(() -> {
                assertThat(lsp.diagnostics(workspace.resolve("App.java"))).isEmpty();
                assertThat(lsp.isAvailable()).isFalse();
            }).doesNotThrowAnyException();
        }
    }

    @Test
    void diagnosticsForFileOutsideWorkspaceAreEmpty(@TempDir Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("jdtls"));
        Path workspace = Files.createDirectories(root.resolve("repo"));
        Path outside = Files.writeString(root.resolve("Outside.java"), "class X {}\n");
        try (JdtLanguageServer lsp = new JdtLanguageServer(home, workspace)) {
            assertThat(lsp.diagnostics(outside)).isEmpty();
        }
    }

    @Test
    void unavailableFacadeIsANoOp(@TempDir Path workspace) {
        assertThat(LspService.UNAVAILABLE.isAvailable()).isFalse();
        assertThat(LspService.UNAVAILABLE.diagnostics(workspace.resolve("A.java"))).isEmpty();
        assertThatCode(LspService.UNAVAILABLE::close).doesNotThrowAnyException();
    }

    /**
     * Containment, unrelated to which task holds what: an edit the server proposes for a file
     * outside the worker's own checkout is refused as a whole, and nothing is written - not even
     * the part of it that lies inside.
     */
    @Test
    void anEditOutsideTheCheckoutIsRefusedAsAWholeAndNothingIsWritten(@TempDir Path root)
            throws Exception {
        Path workspace = Files.createDirectories(root.resolve("repo"));
        Path inside = Files.writeString(workspace.resolve("App.java"), "class App {}\n");
        Path outside = Files.writeString(root.resolve("Outside.java"), "class Outside {}\n");
        TextEdit edit = new TextEdit(new Range(new Position(0, 6), new Position(0, 9)), "Renamed");
        WorkspaceEdit proposed = new WorkspaceEdit(Map.of(
            inside.toUri().toString(), List.of(edit), outside.toUri().toString(), List.of(edit)));
        try (JdtLanguageServer lsp = new JdtLanguageServer(null, workspace)) {
            LspResult result = lsp.apply(proposed, "rename", null);

            assertThat(result.status()).isEqualTo(LspResult.Status.FAILED);
            assertThat(result.note()).contains("outside this workspace")
                .contains("nothing was changed");
            assertThat(Files.readString(inside)).isEqualTo("class App {}\n");
            assertThat(Files.readString(outside)).isEqualTo("class Outside {}\n");
        }
    }
}
