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
package com.swarmcoder.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.AcceptanceCompileErrors.DependencyNeed;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A package the module does not depend on yet is a healthy red when a task still to run owns the
 * module's build file (live run 64, 2026-10-02).
 *
 * <p>The test for the task that writes the server module imported a class from a library module
 * the server had no dependency on. The task had the server's build file in its write set so it
 * could add that dependency, and the red check parked the run: "no task's write set can create
 * it". The real compiler is run over a small tree here, so the errors are javac's own.
 */
class AMissingDependencyATaskCanAddIsAHealthyRedTest {

    private static final String TEST_FILE = "app-server/src/test/java/swarm/accept/EditTest.java";
    private static final String POM = "app-server/pom.xml";
    private static final String IMPL = "app-server/src/main/java/com/acme/server/EditServiceImpl.java";

    @TempDir
    Path world;

    Path tree;

    @BeforeEach
    void aServerModuleWithOneExistingClass() throws Exception {
        tree = Files.createDirectories(world.resolve("tree"));
        write("app-server/src/main/java/com/acme/server/Existing.java",
            "package com.acme.server;\n\npublic class Existing {}\n");
    }

    @Test
    void aPackageTheModuleLacksIsHealthyWhenATaskOwnsItsBuildFile() throws Exception {
        write(TEST_FILE, testUsingTheLibrary());
        String output = compile().apply(tree);
        assertThat(output).contains("package com.libdep.net does not exist")
            .contains("cannot find symbol");

        Reading reading = MiscompiledAcceptanceTest.read(tree, output, List.of(TEST_FILE),
            List.of(task(POM, IMPL)));

        assertThat(reading.isBroken()).as(reading.quoted()).isFalse();
        assertThat(reading.dependencyNeeds()).hasSize(1);
        DependencyNeed need = reading.dependencyNeeds().get(0);
        assertThat(need.packageName()).isEqualTo("com.libdep.net");
        assertThat(need.types()).containsExactly("DbNode");
        assertThat(need.buildFile()).isEqualTo(POM);
        assertThat(need.owners()).hasSize(1);
        assertThat(GreenfieldWorkflow.dependencyInstruction(need, "com.libdep:libdep-net"))
            .isEqualTo("The acceptance test needs package com.libdep.net (type DbNode), which is "
                + "not on this module's classpath yet; add the dependency that provides it "
                + "(com.libdep:libdep-net) to app-server/pom.xml.");
        assertThat(GreenfieldWorkflow.dependencyInstruction(need, null))
            .doesNotContain("(com.libdep");
    }

    @Test
    void theStubPassDoesNotTurnItBackIntoABrokenTest() throws Exception {
        write(TEST_FILE, testUsingTheLibrary());
        String first = compile().apply(tree);
        List<Task> plan = List.of(task(POM, IMPL));

        RedCheckStubs.Outcome outcome = RedCheckStubs.read(tree, first, List.of(TEST_FILE), plan,
            compile());

        assertThat(outcome).isNotNull();
        assertThat(outcome.stubbed()).containsExactly("com.acme.server.EditServiceImpl");
        assertThat(outcome.reading().isBroken()).as(outcome.reading().quoted()).isFalse();
    }

    @Test
    void withoutATaskThatOwnsTheBuildFileItIsStillABrokenTest() throws Exception {
        write(TEST_FILE, testUsingTheLibrary());
        String output = compile().apply(tree);

        Reading reading = MiscompiledAcceptanceTest.read(tree, output, List.of(TEST_FILE),
            List.of(task(IMPL)));

        assertThat(reading.isBroken()).isTrue();
        assertThat(reading.dependencyNeeds()).isEmpty();
        assertThat(reading.quoted()).contains("com.libdep.net");
    }

    @Test
    void aBuildFileOfAnotherModuleIsNotTheOne() throws Exception {
        write(TEST_FILE, testUsingTheLibrary());
        String output = compile().apply(tree);

        Reading reading = MiscompiledAcceptanceTest.read(tree, output, List.of(TEST_FILE),
            List.of(task("app-client/pom.xml", IMPL)));

        assertThat(reading.isBroken()).isTrue();
    }

    @Test
    void aMistypedSubPackageOfTheModulesOwnCodeIsNotADependency() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;

            import com.acme.server.typo.Thing;

            class EditTest {
                Thing thing;
            }
            """);
        String output = compile().apply(tree);
        assertThat(output).contains("package com.acme.server.typo does not exist");

        Reading reading = MiscompiledAcceptanceTest.read(tree, output, List.of(TEST_FILE),
            List.of(task(POM, IMPL)));

        assertThat(reading.isBroken()).as("the module already has code in com.acme.server").isTrue();
        assertThat(reading.dependencyNeeds()).isEmpty();
    }

    @Test
    void aNameTheTestNeverImportedIsStillBroken() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;

            import com.libdep.net.DbNode;

            class EditTest {
                DbNode node;
                Mystery mystery;
            }
            """);
        String output = compile().apply(tree);

        Reading reading = MiscompiledAcceptanceTest.read(tree, output, List.of(TEST_FILE),
            List.of(task(POM, IMPL)));

        assertThat(reading.dependencyNeeds()).hasSize(1);
        assertThat(reading.broken()).hasSize(1);
        assertThat(reading.quoted()).contains("Mystery").doesNotContain("DbNode");
    }

    // ---------------------------------------------------------------------------------- helpers

    private static String testUsingTheLibrary() {
        return """
            package swarm.accept;

            import com.acme.server.EditServiceImpl;
            import com.libdep.net.DbNode;

            class EditTest {
                DbNode node;
                EditServiceImpl service;
            }
            """;
    }

    private static Task task(String... writeSet) {
        return new Task(UUID.randomUUID(), 1, "EditServiceImpl with persistence", "do it",
            Set.of(writeSet), Set.of(), List.of(), "app-server/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** The acceptance stage of this world: javac over every source in the tree, in javac's format. */
    private Function<Path, String> compile() {
        return root -> {
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
                Path out = Files.createDirectories(world.resolve("out"));
                try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(diagnostics, null, null)) {
                    compiler.getTask(null, files, diagnostics, List.of("-d", out.toString()), null,
                        files.getJavaFileObjectsFromPaths(sources)).call();
                }
                StringBuilder sb = new StringBuilder();
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR && d.getSource() != null) {
                        sb.append(Path.of(d.getSource().toUri()).toString().replace('\\', '/'))
                            .append(':').append(d.getLineNumber()).append(": error: ")
                            .append(d.getMessage(Locale.ROOT)).append('\n');
                    }
                }
                return sb.isEmpty() ? null : sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private void write(String relative, String body) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

}
