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
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * A red check sees a call the library does not have even while the class under test is still to
 * be written (live run 63, 2026-10-02).
 *
 * <p>The re-authored test imported an implementation class no task had written yet and called a
 * builder method the library never had. The compiler reported the import and nothing else, the red
 * check read that as a healthy red, and eighty minutes of worker time went on a test that could
 * never compile. Here the real compiler is run over a small tree, with an invented library
 * compiled to a class folder outside it — so "its source is not in the project" is true.
 */
class TheRedCheckSeesPastTypesNotWrittenYetTest {

    private static final String TEST_FILE = "app-server/src/test/java/swarm/accept/EditTest.java";

    @TempDir
    Path world;

    Path tree;
    Path libraryClasses;

    @BeforeEach
    void buildTheLibraryAndTheTree() throws Exception {
        Path librarySource = world.resolve("library-src/com/harness/Harness.java");
        Files.createDirectories(librarySource.getParent());
        Files.writeString(librarySource, """
            package com.harness;

            public final class Harness {
                public static Builder builder() { return new Builder(); }
                public <T> T bean(Class<T> type) { return null; }
                public static final class Builder {
                    public Builder beans(Class<?>... types) { return this; }
                    public Harness start() { return new Harness(); }
                }
            }
            """);
        libraryClasses = Files.createDirectories(world.resolve("library-classes"));
        assertThat(javac(List.of(librarySource), null, libraryClasses)).isEmpty();
        tree = Files.createDirectories(world.resolve("tree"));
    }

    @Test
    void anInventedLibraryMethodHiddenBehindAMissingImportIsFound() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;

            import com.acme.server.EditServiceImpl;
            import com.harness.Harness;
            import java.nio.file.Path;

            class EditTest {
                void edits() {
                    Harness harness = Harness.builder().storePath(Path.of("x")).start();
                    EditServiceImpl service = harness.bean(EditServiceImpl.class);
                    service.update("a");
                }
            }
            """);
        String first = compile().apply(tree);
        assertThat(first).as("what run 63 saw: the missing import and not a word about storePath")
            .contains("com.acme.server").doesNotContain("storePath");

        RedCheckStubs.Outcome outcome = RedCheckStubs.read(tree, first, List.of(TEST_FILE),
            List.of(implementationTask()), compile());

        assertThat(outcome).isNotNull();
        assertThat(outcome.stubbed()).containsExactly("com.acme.server.EditServiceImpl");
        Reading reading = outcome.reading();
        assertThat(reading.isBroken()).isTrue();
        assertThat(reading.quoted()).contains("storePath").contains("com.harness.Harness.Builder")
            .contains("library type");
        assertThat(reading.broken()).as("update() on the stub is the task's to deliver, not a fault")
            .hasSize(1);
        assertThat(filesIn(tree)).as("the stubs and the file that stops the build are gone again")
            .containsExactly(TEST_FILE);
    }

    @Test
    void aTestThatOnlyNeedsWhatThePlanDeliversStaysAHealthyRed() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;

            import com.acme.server.EditServiceImpl;
            import com.harness.Harness;

            class EditTest {
                void edits() {
                    Harness harness = Harness.builder().beans(EditServiceImpl.class).start();
                    EditServiceImpl service = new EditServiceImpl("store");
                    String saved = service.update("a");
                    Runnable r = service;
                }
            }
            """);
        String first = compile().apply(tree);

        RedCheckStubs.Outcome outcome = RedCheckStubs.read(tree, first, List.of(TEST_FILE),
            List.of(implementationTask()), compile());

        assertThat(outcome).isNotNull();
        assertThat(outcome.reading().isBroken())
            .as("a constructor, a method and a supertype the empty stub lacks are not faults: "
                + outcome.reading().quoted())
            .isFalse();
        assertThat(filesIn(tree)).containsExactly(TEST_FILE);
    }

    @Test
    void nothingIsStubbedWhenNoMissingTypeIsThePlansToDeliver() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;

            import com.harness.Harness;

            class EditTest {
                void edits() { Harness.builder().storePath(null); }
            }
            """);

        assertThat(RedCheckStubs.read(tree, compile().apply(tree), List.of(TEST_FILE),
            List.of(implementationTask()), compile())).isNull();
    }

    // ---------------------------------------------------------------------------------- helpers

    private static Task implementationTask() {
        return new Task(UUID.randomUUID(), 1, "EditServiceImpl", "do it",
            Set.of("app-server/src/main/java/com/acme/server/EditServiceImpl.java"), Set.of(),
            List.of(), "app-server/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** The acceptance stage of this world: javac over every source in the tree, in javac's format. */
    private Function<Path, String> compile() {
        return root -> {
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
                String output = javac(sources, libraryClasses, world.resolve("out"));
                return output.isEmpty() ? null : output;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static String javac(List<Path> sources, Path classpath, Path out) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Files.createDirectories(out);
        List<String> options = new ArrayList<>(List.of("-d", out.toString()));
        if (classpath != null) {
            options.addAll(List.of("-cp", classpath.toString()));
        }
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, options,
                null, files.getJavaFileObjectsFromPaths(sources));
            // An annotation processor is present, as it is in any real build whose classpath holds
            // one (the test framework, the library itself). That is what makes javac stop at the
            // missing type: with a processor it reports the unresolved names and never attributes
            // a method body. Without one it would have gone on - and run 63 would not have happened.
            task.setProcessors(List.of(new NoOpProcessor()));
            task.call();
        }
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR && d.getSource() != null) {
                sb.append(Path.of(d.getSource().toUri()).toString().replace('\\', '/')).append(':')
                    .append(d.getLineNumber()).append(": error: ")
                    .append(d.getMessage(Locale.ROOT)).append('\n');
            }
        }
        return sb.toString();
    }

    @javax.annotation.processing.SupportedAnnotationTypes("*")
    private static final class NoOpProcessor extends javax.annotation.processing.AbstractProcessor {
        @Override
        public javax.lang.model.SourceVersion getSupportedSourceVersion() {
            return javax.lang.model.SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends javax.lang.model.element.TypeElement> annotations,
                               javax.annotation.processing.RoundEnvironment round) {
            return false;
        }
    }

    private void write(String relative, String body) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private static List<String> filesIn(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }
}
