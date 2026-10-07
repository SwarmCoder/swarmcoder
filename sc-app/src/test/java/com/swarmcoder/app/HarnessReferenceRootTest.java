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
package com.swarmcoder.app;

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cheap, model-free proof of {@link HarnessReferenceRoot} — the seam
 * {@link EndToEndLoopTest} uses so its workers get real reference documentation instead of the
 * "reference material: none" nudge run 4 hit on every wave-3 worker.
 *
 * <p>No model, no GPU: the Context7 client is built hosted-with-no-key (connects lazily, never at
 * construction — see {@link com.swarmcoder.knowledge.Context7Client}) and the primer model is
 * null, so nothing here ever makes a network or model call. The reference folder is a fixture
 * built fresh in {@link #referenceRoot} — small enough that {@code lookup_api} answers straight
 * from it without ever reaching the Context7 fall-through.
 */
class HarnessReferenceRootTest {

    @TempDir
    Path work;

    private Path projectRoot;
    private Path referenceRoot;
    private Path cacheDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty(HarnessReferenceRoot.REFERENCE_PROPERTY);
    }

    @Test
    void aConfiguredRootProducesALibrarianThatAnswersFromItsGuide() throws Exception {
        setUpFixtureRoot();
        System.setProperty(HarnessReferenceRoot.REFERENCE_PROPERTY, referenceRoot.toString());

        HarnessReferenceRoot.Setup setup = HarnessReferenceRoot.build(projectRoot,
            cacheDir.resolve("knowledge"));

        assertThat(setup.documentCount())
            .as("the one guide under docs/ was found and indexed")
            .isGreaterThanOrEqualTo(1);
        assertThat(setup.describe())
            .as("the setup ledger line names the folder and how many documents it holds")
            .contains(String.valueOf(setup.documentCount()))
            .contains(referenceRoot.toString());

        // lookup_api answers from the guide, not "not found — inspect the source".
        String answer = setup.librarian().lookupApi("WidgetFactory");
        assertThat(answer)
            .as("lookup_api found the guide's own section instead of falling through")
            .contains("WidgetFactory.create")
            .doesNotContain("Inspect the code directly");

        // The knowledge brief a real task would be given lists the guide by name.
        Task task = new Task(UUID.randomUUID(), 1, "Build a widget",
            "Use WidgetFactory to build a widget and wire it into the screen.",
            Set.of("src/main/java/com/example/Screen.java"), Set.of(), List.of(), "src/test",
            null, new TokenBudget(32_000, 4_000, 400_000, 24),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of("minimal-diff")), TaskState.READY);
        String brief = setup.librarian().assembleBrief(projectRoot, task).renderedMarkdown();
        assertThat(brief)
            .as("the worker's knowledge brief catalogues the guide by name — this is what turns "
                + "\"reference material: none\" into a real hint (WorkerLoop.referenceHint)")
            .contains("docs/guide.md");
    }

    @Test
    void aMissingRootFailsWithAClearMessageInsteadOfRunningSilentlyWithoutDocs() {
        Path missing = work.resolve("no-such-checkout");
        System.setProperty(HarnessReferenceRoot.REFERENCE_PROPERTY, missing.toString());

        assertThatThrownBy(() -> HarnessReferenceRoot.build(work.resolve("project"),
            work.resolve("knowledge")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(missing.toString())
            .hasMessageContaining("is the reference documentation this harness needs")
            .hasMessageContaining("-D" + HarnessReferenceRoot.REFERENCE_PROPERTY + "=<path>");
    }

    private void setUpFixtureRoot() throws Exception {
        projectRoot = work.resolve("project");
        referenceRoot = work.resolve("reference");
        cacheDir = work.resolve("cache");
        Files.createDirectories(projectRoot);
        Files.createDirectories(referenceRoot.resolve("docs"));
        Files.createDirectories(referenceRoot.resolve("src/main/java/com/example"));

        Files.writeString(referenceRoot.resolve("docs/guide.md"), """
            # Widget Guide

            ## Building a widget

            Call `WidgetFactory.create(name)` to build a new widget. This is the real API —
            do not invent one, and do not decompile the jar to find it.
            """);
        Files.writeString(referenceRoot.resolve("src/main/java/com/example/WidgetFactory.java"),
            """
            package com.example;

            /** Builds widgets. The real API a worker should call, not invent. */
            public class WidgetFactory {
                public static Widget create(String name) {
                    return new Widget(name);
                }
            }
            """);
    }
}
