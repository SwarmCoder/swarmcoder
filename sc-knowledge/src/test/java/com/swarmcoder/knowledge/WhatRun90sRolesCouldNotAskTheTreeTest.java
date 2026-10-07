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

import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.lsp.LspDiagnostic;
import com.swarmcoder.lsp.LspHit;
import com.swarmcoder.lsp.LspResult;
import com.swarmcoder.lsp.LspService;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 90 (DEVELOPER_CORRECTIONS section 66), on an invented project in a {@code @TempDir};
 * no model is called and no language server is started.
 *
 * <ul>
 *   <li>The architect read three classes of text constants whole (9,110 characters) to learn
 *       four labels: {@code texts_of} was offered to the test author of a screen task only.</li>
 *   <li>{@code outline_of} answered "give its path as the other tools print it" to a path from
 *       the repository root, which {@code read_file} takes; the test author then read the
 *       11,485-character file whole.</li>
 *   <li>The analyst is given the project's module folders from the object graph; that was only
 *       ever tested on a list of file names, never on a graph.</li>
 * </ul>
 */
class WhatRun90sRolesCouldNotAskTheTreeTest {

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;
    private final List<String> outlined = new ArrayList<>();

    private final LspService server = new LspService() {
        @Override public List<LspDiagnostic> diagnostics(Path file) {
            return List.of();
        }
        @Override public boolean isAvailable() {
            return true;
        }
        @Override public boolean isInstalled() {
            return true;
        }
        @Override public LspResult outline(Path file) {
            String asked = file.toString().replace('\\', '/');
            outlined.add(asked);
            return Files.isRegularFile(app.resolve(asked))
                ? LspResult.ok(asked + " declares:", List.of(new LspHit(asked, 3,
                    "class AddTexts")), 1)
                : LspResult.notFound("There is no file " + asked + " in this workspace.");
        }
        @Override public void close() {
        }
    };

    @BeforeEach
    void aProjectOfThreeModules() throws Exception {
        app = world.resolve("app");
        write("pom.xml", "<project><groupId>com.log</groupId><artifactId>log</artifactId>"
            + "<version>1</version><packaging>pom</packaging></project>");
        for (String module : List.of("log-client", "log-server", "log-shared")) {
            write(module + "/pom.xml", "<project><parent><groupId>com.log</groupId>"
                + "<artifactId>log</artifactId><version>1</version></parent><artifactId>"
                + module + "</artifactId></project>");
        }
        write("log-shared/src/main/java/com/log/Entry.java",
            "package com.log;\n\npublic class Entry {\n    public long startUtc;\n}\n");
        write("log-server/src/main/java/com/log/server/EntryStore.java",
            "package com.log.server;\n\npublic class EntryStore {\n"
                + "    public void save(com.log.Entry entry) { }\n}\n");
        write("log-client/src/main/java/com/log/client/i18n/AddTexts.java", """
            package com.log.client.i18n;

            public final class AddTexts {
                public static final String START_UTC = "Start (UTC, ms)";
                public static final String END_UTC = "End (UTC, ms)";

                private AddTexts() { }
            }
            """);
        curator = new KnowledgeCurator(List.of(new KnowledgeCurator.Root("project", app, "local")),
            null, world.resolve("cache"));
    }

    @Test
    void everyRoleMayAskForTheTextsOfATypeAndIsToldTheValuesWithoutTheFile() {
        ExpertTools architect = aRolesTools("architect");

        assertThat(architect.lookupBindings().stream().map(ToolBinding::name).toList())
            .contains("texts_of").doesNotHaveDuplicates();
        assertThat(architect.textsOf("com.log.client.i18n.AddTexts"))
            .contains("START_UTC").contains("Start (UTC, ms)").contains("End (UTC, ms)")
            .doesNotContain("private AddTexts()");
    }

    @Test
    void anOutlineIsGivenForAPathFromTheRepositoryRootAsReadFileTakesIt() {
        curator.languageServer(server, app);
        ExpertTools author = aRolesTools("test author");
        String file = "log-client/src/main/java/com/log/client/i18n/AddTexts.java";

        assertThat(author.outlineOf(file)).as("as run 90's test author asked")
            .contains("declares:").contains("class AddTexts");
        assertThat(author.outlineOf("project/" + file)).as("and as the other tools print it")
            .contains("class AddTexts");
        assertThat(outlined).containsExactly(file, file);
        assertThat(author.outlineOf("log-client/src/main/java/Nothing.java"))
            .contains("There is no file");
    }

    @Test
    void theModuleFoldersARuleMayBeRecordedForComeFromTheObjectGraph() {
        ProjectParts parts = ProjectParts.of(curator, app);

        assertThat(parts.modules()).containsExactly("log-client", "log-server", "log-shared");
        assertThat(parts.holds("log-client/src/main/java/com/log/client/i18n")).isTrue();
        assertThat(parts.holds("log-web")).isFalse();
    }

    private ExpertTools aRolesTools(String role) {
        return new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User(role, "hand in", 0, null, 0, 0));
    }

    private void write(String relative, String text) throws Exception {
        Path file = app.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
