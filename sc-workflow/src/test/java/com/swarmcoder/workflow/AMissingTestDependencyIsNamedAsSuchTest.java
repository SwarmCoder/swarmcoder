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
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.verify.AcceptanceCompileErrors.DependencyNeed;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
 * Live local build, 2026-10-14 (section 111): the server module's build file declared no
 * dependency at all, and the server task's test imported JUnit. javac said "package ... does not
 * exist" on each import and "static import only from classes and interfaces" on each static
 * import, and the run parked saying "everything this line names already exists". The real
 * compiler runs here over a small tree, so every error is javac's own.
 */
class AMissingTestDependencyIsNamedAsSuchTest {

    private static final String TEST_FILE = "app-server/src/test/java/swarm/accept/EditTest.java";
    private static final String POM = "app-server/pom.xml";
    private static final String ARTIFACT = "com.libdep:libdep-check";

    @TempDir
    Path world;

    Path tree;
    Path repository;

    @BeforeEach
    void aServerModuleWhoseBuildFileDeclaresNothing() throws Exception {
        tree = Files.createDirectories(world.resolve("tree"));
        write("pom.xml", "<project><modules><module>app-shared</module>"
            + "<module>app-server</module></modules></project>\n");
        // Another module of the tree declares the library; the server's own build file does not.
        write("app-shared/pom.xml", "<project><dependencies><dependency>"
            + "<groupId>com.libdep</groupId><artifactId>libdep-check</artifactId>"
            + "<version>1.0</version><scope>test</scope></dependency></dependencies></project>\n");
        write(POM, "<project><artifactId>app-server</artifactId></project>\n");
        write("app-server/src/main/java/com/acme/server/Existing.java",
            "package com.acme.server;\n\npublic class Existing {}\n");
        repository = Files.createDirectories(world.resolve("repository"));
        Path jar = Files.createDirectories(repository.resolve("com/libdep/libdep-check/1.0"))
            .resolve("libdep-check-1.0.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("com/libdep/check/Verify.class"));
            zip.write(new byte[] {0});
            zip.closeEntry();
        }
        write(TEST_FILE, """
            package swarm.accept;

            import com.acme.server.Existing;
            import com.libdep.check.Probe;

            import static com.libdep.check.Verify.that;

            class EditTest {
                Existing existing;
                Probe probe;
                void checks() { that(1); }
            }
            """);
    }

    @Test
    void theCompilerSaysWhatTheLiveBuildSaw() {
        assertThat(compile()).contains("package com.libdep.check does not exist")
            .contains("static import only from classes and interfaces");
    }

    @Test
    void aLibraryTheModuleDoesNotDeclareIsNamedWithItsBuildFileAndArtifact() {
        // The live shape: the task owns the module's whole source root and not its build file.
        Task server = task("app-server/src/main/java");

        Reading reading = MiscompiledAcceptanceTest.read(tree, compile(), List.of(TEST_FILE),
            List.of(server), repository);

        assertThat(reading.isBroken()).as(reading.quoted()).isFalse();
        assertThat(reading.undeclared()).hasSize(1);
        DependencyNeed need = reading.undeclared().get(0);
        assertThat(need.packageName()).isEqualTo("com.libdep.check");
        assertThat(need.buildFile()).isEqualTo(POM);
        assertThat(need.artifact()).isEqualTo(ARTIFACT);

        // When nobody can be given the build file, it is still never called a misuse.
        Reading nobody = reading.withUndeclaredAsBroken(List.of());
        assertThat(nobody.isBroken()).isTrue();
        assertThat(nobody.quoted())
            .contains("is a package of the library " + ARTIFACT)
            .contains(POM + ", the build file of the module the test is in, does not declare it")
            .doesNotContain("everything this line names already exists")
            .doesNotContain("missing import");
    }

    @Test
    void withoutKnowingTheLibraryTheFollowOnErrorsAreStillNotAMisuse() {
        Reading reading = MiscompiledAcceptanceTest.read(tree, compile(), List.of(TEST_FILE),
            List.of(task("app-server/src/main/java")), null);

        assertThat(reading.quoted()).doesNotContain("everything this line names already exists")
            .doesNotContain("missing import");
    }

    @Test
    void theTaskWhoseTestItIsMayWriteThatBuildFileAndStillNotItsTestOrTheContract() {
        Task server = task("app-server/src/main/java/com/acme/server");
        Reading reading = MiscompiledAcceptanceTest.read(tree, compile(), List.of(TEST_FILE),
            List.of(server), repository);

        List<String> granted = UndeclaredDependencies.grantable(reading, server, tree, List.of());

        assertThat(granted).containsExactly(POM);
        Set<String> grown = UndeclaredDependencies.grown(server, granted);
        assertThat(PathPolicy.check(POM, grown, server.acceptanceTestDir(), List.of()).allowed())
            .isTrue();
        assertThat(PathPolicy.check(TEST_FILE, grown, server.acceptanceTestDir(), List.of())
            .allowed()).as("an acceptance test").isFalse();
        assertThat(PathPolicy.check(".swarmcoder/verify.yaml", grown, server.acceptanceTestDir(),
            List.of()).allowed()).as("the verification contract").isFalse();
        assertThat(UndeclaredDependencies.grantable(reading, server, tree, List.of("app-server")))
            .as("a module the operator locked is not given").isEmpty();
        assertThat(UndeclaredDependencies.note(server, reading, granted))
            .contains("com.libdep.check (library " + ARTIFACT + ")").contains(POM)
            .contains("may not change an acceptance test or the verification contract");
    }

    @Test
    void aTaskThatOwnsTheBuildFileIsToldWhichArtifactToDeclare() {
        Task server = task(POM, "app-server/src/main/java");
        Reading reading = MiscompiledAcceptanceTest.read(tree, compile(), List.of(TEST_FILE),
            List.of(server), repository);

        assertThat(reading.isBroken()).as(reading.quoted()).isFalse();
        assertThat(reading.undeclared()).isEmpty();
        DependencyNeed need = reading.dependencyNeeds().get(0);
        assertThat(need.owners()).containsExactly(server);
        assertThat(GreenfieldWorkflow.dependencyInstruction(need, need.artifact()))
            .contains("(" + ARTIFACT + ") to " + POM);
    }

    @Test
    void aRealMisuseOfAnExistingTypeIsStillOne() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;

            import com.acme.server.Existing;

            class EditTest {
                void checks() { new Existing(42); }
            }
            """);
        Reading reading = MiscompiledAcceptanceTest.read(tree, compile(), List.of(TEST_FILE),
            List.of(task("app-server/src/main/java")), repository);

        assertThat(reading.isBroken()).isTrue();
        assertThat(reading.undeclared()).isEmpty();
        assertThat(reading.quoted()).contains("everything this line names already exists");
    }

    // ---------------------------------------------------------------------------------- helpers

    private static Task task(String... writeSet) {
        return new Task(UUID.randomUUID(), 1, "Server module", "do it", Set.of(writeSet),
            Set.of(), List.of(), "app-server/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** javac over every source in the tree with nothing on the classpath, in javac's format. */
    private String compile() {
        try (Stream<Path> walk = Files.walk(tree)) {
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
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(String relative, String body) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
