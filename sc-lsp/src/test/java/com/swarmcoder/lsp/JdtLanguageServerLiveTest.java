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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The live path: a real JDT Language Server on a small Maven project. Skipped when no JDT LS is
 * installed (see {@link JdtLsInstall}); it calls no model and reaches no network.
 *
 * <p>One server for the whole class - the start is the expensive part and is measured once. The
 * refactoring tests run last because they change the fixture.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdtLanguageServerLiveTest {

    private static Path root;
    private static Path workspace;
    private static JdtLanguageServer lsp;

    @BeforeAll
    static void start() throws Exception {
        // Asked past the build's off switch: this test is the one that wants the real server.
        Path home = JdtLsInstall.locate(System.getProperty(JdtLsInstall.PROPERTY), null,
            JdtLsInstall.defaultToolsDir());
        assumeTrue(home != null && Files.isDirectory(home.resolve("plugins")),
            "no JDT LS installed under " + JdtLsInstall.defaultToolsDir());
        // A folder with a space in its name: the Windows host has them.
        root = Files.createTempDirectory("jdtls live ");
        workspace = Files.createDirectories(root.resolve("shop project"));
        write("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>shop</artifactId><version>1</version>
              <dependencies>
                <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                  <version>2.0.17</version></dependency>
              </dependencies>
            </project>
            """);
        write("src/main/java/com/example/shop/Greeter.java", """
            package com.example.shop;

            /** Says hello. */
            public interface Greeter {
                /** Greets one person by name. */
                String greet(String name);
            }
            """);
        write("src/main/java/com/example/shop/PoliteGreeter.java", """
            package com.example.shop;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            public class PoliteGreeter implements Greeter {
                private static final Logger log = LoggerFactory.getLogger(PoliteGreeter.class);

                @Override
                public String greet(String name) {
                    log.info("greeting {}", name);
                    return "Good day, " + name;
                }
            }
            """);
        write("src/main/java/com/example/shop/App.java", """
            package com.example.shop;

            import java.util.ArrayList;
            import java.util.List;

            public class App {
                public static void main(String[] args) {
                    Greeter greeter = new PoliteGreeter();
                    System.out.println(greeter.greet("Ada"));
                }
            }
            """);
        write("src/test/java/com/example/shop/Broken.java", """
            package com.example.shop;

            public class Broken {
                String run(Greeter greeter) {
                    return greeter.salutation("Ada");
                }
            }
            """);
        Path slf4j = Path.of(org.slf4j.Logger.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        lsp = new JdtLanguageServer(home, workspace, root.resolve("data"), List.of(slf4j), true);
    }

    @AfterAll
    static void stop() throws Exception {
        if (lsp != null) {
            lsp.close();
        }
        if (root != null && System.getenv("JDTLS_KEEP") != null) {
            System.out.println("JDTLS-KEPT " + root);
        } else if (root != null) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void write(String relative, String text) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static TreeSet<String> filesOfWorkspace() throws Exception {
        try (Stream<Path> walk = Files.walk(workspace)) {
            return new TreeSet<>(walk.map(p -> workspace.relativize(p).toString()).toList());
        }
    }

    private static LspResult timed(String what, Supplier<LspResult> query) {
        long began = System.nanoTime();
        LspResult result = query.get();
        System.out.println("JDTLS-TIMING " + what + " " + (System.nanoTime() - began) / 1_000_000
            + " ms\n" + result.render());
        return result;
    }

    @Test
    @Order(1)
    void startsAndListsTheMembersOfALibraryType() throws Exception {
        TreeSet<String> before = filesOfWorkspace();
        long began = System.nanoTime();
        LspResult members = lsp.members("org.slf4j.Logger");
        System.out.println("JDTLS-TIMING cold start " + lsp.coldStartMillis() + " ms (pid "
            + lsp.pid() + "), first query with start " + (System.nanoTime() - began) / 1_000_000
            + " ms\n" + members.render());
        assertThat(lsp.isAvailable()).isTrue();
        assertThat(members.status()).isEqualTo(LspResult.Status.OK);
        assertThat(members.note()).contains("org.slf4j.Logger").contains("library jar");
        assertThat(members.render()).contains("info(").contains("isDebugEnabled");
        assertThat(members.render().length()).isLessThan(9_000);

        List<String> names = lsp.memberNames("org.slf4j.Logger");
        assertThat(names).contains("info", "warn").doesNotContain("inventedMethod");
        assertThat(lsp.memberNames("PoliteGreeter")).contains("greet", "toString");
        // The server read the project and wrote nothing into it: no build ran.
        assertThat(filesOfWorkspace()).isEqualTo(before);
    }

    @Test
    @Order(2)
    void answersWithPlacesNotFileContents() {
        LspResult refs = timed("references", () -> lsp.references("Greeter#greet"));
        assertThat(refs.render()).contains("src/main/java/com/example/shop/App.java:9")
            .contains("greeter.greet(\"Ada\")");

        LspResult definition = timed("definition", () -> lsp.definition("com.example.shop.Greeter"));
        assertThat(definition.render()).contains("src/main/java/com/example/shop/Greeter.java:4");

        LspResult impls = timed("implementations", () -> lsp.implementations("Greeter"));
        assertThat(impls.render()).contains("PoliteGreeter.java:6");

        LspResult supers = timed("supertypes", () -> lsp.supertypes("PoliteGreeter"));
        assertThat(supers.render()).contains("Greeter.java").contains("java.lang.Object");

        LspResult subs = timed("subtypes", () -> lsp.subtypes("Greeter"));
        assertThat(subs.render()).contains("PoliteGreeter");

        LspResult callers = timed("callers", () -> lsp.callers("Greeter#greet"));
        assertThat(callers.render()).contains("App.java:9").contains("main(");

        LspResult callees = timed("callees", () -> lsp.callees("PoliteGreeter#greet"));
        assertThat(callees.render()).contains("org.slf4j.Logger").contains("info(");

        LspResult symbols = timed("symbols", () -> lsp.workspaceSymbols("*Greeter"));
        assertThat(symbols.render()).contains("com.example.shop.PoliteGreeter")
            .contains("com.example.shop.Greeter");

        LspResult outline = timed("outline", () -> lsp.outline(
            workspace.resolve("src/main/java/com/example/shop/PoliteGreeter.java")));
        assertThat(outline.render()).contains("greet(String)").contains("log");

        LspResult hover = timed("hover", () -> lsp.hover("Greeter#greet"));
        assertThat(hover.render()).contains("Greets one person by name").contains("String name");

        LspResult projectMembers = timed("members (project type)", () -> lsp.members("Greeter"));
        assertThat(projectMembers.render()).contains("greet(String name)")
            .contains("Greets one person by name");

        LspResult problems = timed("problems", () -> lsp.problems(
            workspace.resolve("src/test/java/com/example/shop/Broken.java")));
        assertThat(problems.note()).contains("1 error(s)");
        assertThat(problems.render()).contains("Broken.java:5").contains("salutation");

        assertThat(lsp.members("NoSuchTypeAnywhere").status()).isEqualTo(LspResult.Status.NOT_FOUND);
        assertThat(lsp.references("Greeter#nope").status()).isEqualTo(LspResult.Status.NOT_FOUND);
        assertThat(lsp.outline(root.resolve("outside.java")).status())
            .isEqualTo(LspResult.Status.NOT_FOUND);
    }

    @Test
    @Order(3)
    void renamesAcrossTheProjectAndOrganizesImports() throws Exception {
        // The caller's own rule about what may be written refuses the whole change.
        LspResult refused = lsp.rename("Greeter#greet", "salute",
            file -> file.endsWith("App.java") ? "it is locked." : null);
        assertThat(refused.status()).isEqualTo(LspResult.Status.FAILED);
        assertThat(refused.note()).contains("App.java").contains("Nothing was changed");
        assertThat(Files.readString(workspace.resolve("src/main/java/com/example/shop/Greeter.java")))
            .contains("String greet(String name)");

        LspResult renamed = timed("rename method", () -> lsp.rename("Greeter#greet", "salute"));
        assertThat(renamed.status()).isEqualTo(LspResult.Status.OK);
        assertThat(renamed.render()).contains("Greeter.java").contains("PoliteGreeter.java")
            .contains("App.java");
        assertThat(Files.readString(workspace.resolve("src/main/java/com/example/shop/App.java")))
            .contains("greeter.salute(\"Ada\")");
        assertThat(Files.readString(
            workspace.resolve("src/main/java/com/example/shop/PoliteGreeter.java")))
            .contains("public String salute(String name)");

        LspResult type = timed("rename type", () -> lsp.rename("PoliteGreeter", "CourteousGreeter"));
        assertThat(type.status()).isEqualTo(LspResult.Status.OK);
        assertThat(workspace.resolve("src/main/java/com/example/shop/CourteousGreeter.java"))
            .exists();
        assertThat(workspace.resolve("src/main/java/com/example/shop/PoliteGreeter.java"))
            .doesNotExist();
        assertThat(Files.readString(workspace.resolve("src/main/java/com/example/shop/App.java")))
            .contains("new CourteousGreeter()");

        Path app = workspace.resolve("src/main/java/com/example/shop/App.java");
        LspResult organized = timed("organize imports", () -> lsp.organizeImports(app));
        assertThat(organized.status()).isEqualTo(LspResult.Status.OK);
        assertThat(Files.readString(app)).doesNotContain("import java.util.ArrayList")
            .contains("public class App");

        // The server follows the change: the renamed method is found under its new name.
        assertThat(timed("references after rename", () -> lsp.references("Greeter#salute"))
            .render()).contains("App.java");
        assertThat(lsp.rename("org.slf4j.Logger#info", "x").status())
            .isNotEqualTo(LspResult.Status.OK);
        assertThat(lsp.rename("Greeter", "not a name").status()).isEqualTo(LspResult.Status.FAILED);
    }
}
