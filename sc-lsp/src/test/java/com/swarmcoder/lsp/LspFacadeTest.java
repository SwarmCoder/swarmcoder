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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the language-server facade that need no server: where the product is looked for,
 * what a role is told when there is none, how an edit is written, how source folders are found.
 */
class LspFacadeTest {

    @Test
    void theProductIsFoundByPropertyThenSettingThenTheNewestInstalledVersion(@TempDir Path tools)
            throws Exception {
        Files.createDirectories(tools.resolve("1.9.0").resolve("plugins"));
        Files.createDirectories(tools.resolve("1.61.0").resolve("plugins"));
        Files.createDirectories(tools.resolve("not-a-product"));

        assertThat(JdtLsInstall.locate(null, null, tools)).isEqualTo(tools.resolve("1.61.0"));
        assertThat(JdtLsInstall.locate(null, "C:/Program Files/jdt ls", tools))
            .isEqualTo(Path.of("C:/Program Files/jdt ls"));
        assertThat(JdtLsInstall.locate("D:/other", "C:/Program Files/jdt ls", tools))
            .isEqualTo(Path.of("D:/other"));
        assertThat(JdtLsInstall.locate(null, " ", tools.resolve("missing"))).isNull();
    }

    @Test
    void everyQueryOfAServerThatIsNotInstalledSaysSoAndNoneThrows(@TempDir Path workspace) {
        for (LspService lsp : List.of(LspService.UNAVAILABLE,
                new JdtLanguageServer(null, workspace),
                new JdtLanguageServer(workspace.resolve("no-such-product"), workspace))) {
            assertThat(lsp.isInstalled()).isFalse();
            for (LspResult result : List.of(lsp.references("A#b"), lsp.definition("A"),
                    lsp.implementations("A"), lsp.supertypes("A"), lsp.subtypes("A"),
                    lsp.callers("A#b"), lsp.callees("A#b"), lsp.workspaceSymbols("A"),
                    lsp.outline(workspace.resolve("A.java")), lsp.members("A"), lsp.hover("A"),
                    lsp.problems(workspace.resolve("A.java")), lsp.rename("A", "B"),
                    lsp.organizeImports(workspace.resolve("A.java")))) {
                assertThat(result.status()).isEqualTo(LspResult.Status.NOT_AVAILABLE);
                assertThat(result.answered()).isFalse();
                assertThat(result.render()).startsWith("The Java language server is not available");
            }
            assertThat(lsp.memberNames("A")).isEmpty();
            lsp.close();
        }
        assertThat(LspService.UNAVAILABLE.members("A").note()).contains("none is installed");
    }

    @Test
    void anAnswerIsPlacesWithOneLineEachAndSaysHowManyItLeftOut() {
        LspResult result = LspResult.ok("2 reference(s) to `A#b`:",
            List.of(new LspHit("src/A.java", 12, "b();"), new LspHit("", 0, "void b()")), 5);

        assertThat(result.render()).isEqualTo(
            "2 reference(s) to `A#b`:\nsrc/A.java:12  b();\nvoid b()\n(3 more not shown)\n");
    }

    @Test
    void editsAreMadeLastFirstSoEarlierPositionsStayTrue() {
        String text = "import a.B;\r\nimport a.C;\r\n\r\nclass X { void go() { go(); } }\r\n";

        String edited = JdtLanguageServer.edited(text, List.of(
            new TextEdit(new Range(new Position(3, 15), new Position(3, 17)), "run"),
            new TextEdit(new Range(new Position(3, 22), new Position(3, 24)), "run"),
            new TextEdit(new Range(new Position(1, 0), new Position(2, 0)), "")));

        assertThat(edited).isEqualTo("import a.B;\r\n\r\nclass X { void run() { run(); } }\r\n");
    }

    @Test
    void sourceFoldersAreFoundFromEachFilesPackage(@TempDir Path root) throws Exception {
        Path main = root.resolve("app server/src/main/java/com/shop/App.java");
        Path test = root.resolve("app server/src/test/java/com/shop/AppTest.java");
        Path odd = root.resolve("tools/gen/Gen.java");
        Files.createDirectories(main.getParent());
        Files.createDirectories(test.getParent());
        Files.createDirectories(odd.getParent());
        Files.writeString(main, "package com.shop;\nclass App {}\n");
        Files.writeString(test, "// header\npackage com.shop;\nclass AppTest {}\n");
        Files.writeString(odd, "package gen;\nclass Gen {}\n");

        assertThat(JdtLanguageServer.sourceFolders(root, Set.of(main, test, odd)))
            .containsExactly("app server/src/main/java", "app server/src/test/java", "tools");
    }

    @Test
    void documentationLosesItsMarkup() {
        assertThat(JdtLanguageServer.plain("Make a new [Builder](jdt://contents/x.jar/a/B.class) "
            + "for `this`.\n\n * **Parameters:**\n   * **level**\n\nSource: *[x](file:///a)*"))
            .isEqualTo("Make a new Builder for this. Parameters: level");
    }
}
