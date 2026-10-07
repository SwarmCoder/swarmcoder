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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.DeclarableArtifacts;
import com.swarmcoder.knowledge.RulesVersusManifest;
import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan side of "a swarm can add its own dependency".
 *
 * <p>Run {@code ede2068b}, 2026-09-03: the project's technical rules said persistence goes through
 * {@code zerozstack-store-eclipsestore}, the server pom declared no such dependency, and every
 * write set stopped at {@code src/main/java}. Four workers found the contradiction independently,
 * none could act on it, all four were killed having written nothing, and a person then added one
 * line to a pom. Two things had to change and both are asserted here: the build file of a module
 * belongs to the task that writes that module, and a rule naming a missing dependency is work
 * rather than a reason to stop.
 */
class BuildFilesInTheJobTest {

    @TempDir
    Path repo;
    @TempDir
    Path m2;

    // ------------------------------------------------------ the build file comes with the module

    @Test
    void aTaskThatWritesAModulesSourcesMayWriteItsBuildFile() throws Exception {
        BuildLayout.Layout layout = reactor();
        TaskGraph graph = graph(
            task("server work", Set.of("server/src/main/java/com/example/server")),
            task("shared work", Set.of("shared/src/main/java/com/example/shared")));

        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);

        assertThat(graph.tasks().get(0).writeSet()).contains("server/pom.xml");
        assertThat(graph.tasks().get(1).writeSet()).contains("shared/pom.xml");
        assertThat(graph.tasks().get(0).writeSet())
            .as("only the module it writes — not the whole build")
            .doesNotContain("shared/pom.xml", "pom.xml");
    }

    @Test
    void anUnrestrictedTaskIsLeftAloneBecauseItAlreadyMayWriteEverything() throws Exception {
        TaskGraph graph = graph(task("do it all", Set.of()));

        BuildFilesInTheJob.expandWriteSets(graph, reactor(), repo);

        assertThat(graph.tasks().get(0).writeSet()).isEmpty();
    }

    @Test
    void aGradleModulesBuildFileIsTheOneItActuallyHas() throws Exception {
        Files.createDirectories(repo.resolve("server"));
        Files.writeString(repo.resolve("server/build.gradle"), "plugins { id 'java' }\n");
        BuildLayout.Layout gradle = new BuildLayout.Layout("gradle",
            List.of("server/src/main/java"), List.of("server"), List.of(), "");

        assertThat(BuildFilesInTheJob.buildFilesOf("server", gradle, repo))
            .containsExactly("server/build.gradle");
    }

    /**
     * Two concurrent tasks in the same module share that module's build file, and the write-set
     * disjointness rule ignores it. Taking it from one of them would bring back the exact defect
     * this change removes — a task that cannot declare what its own code needs.
     */
    @Test
    void twoConcurrentTasksInOneModuleBothGetItsBuildFileAndThePlanIsStillValid() throws Exception {
        BuildLayout.Layout layout = reactor();
        TaskGraph graph = graph(
            task("loans", Set.of("server/src/main/java/com/example/server/loans")),
            task("shelves", Set.of("server/src/main/java/com/example/server/shelves")));

        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);
        TaskGraphValidator.Verdict verdict = new TaskGraphValidator().validate(graph, null, layout);

        assertThat(graph.tasks().get(0).writeSet()).contains("server/pom.xml");
        assertThat(graph.tasks().get(1).writeSet()).contains("server/pom.xml");
        assertThat(verdict.ok()).isTrue();
        assertThat(verdict.violations()).isEmpty();
    }

    @Test
    void twoTasksSharingASourceDirectoryIsStillAViolation() throws Exception {
        BuildLayout.Layout layout = reactor();
        TaskGraph graph = graph(
            task("loans", Set.of("server/src/main/java/com/example/server")),
            task("shelves", Set.of("server/src/main/java/com/example/server")));

        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);

        assertThat(new TaskGraphValidator().validate(graph, null, layout).violations())
            .as("two tasks writing the same class is not a merge conflict, it is two plans")
            .anyMatch(v -> v.contains("overlap on write path"));
    }

    // ------------------------------------------ a rule naming a missing dependency becomes work

    @Test
    void theRuleNamesTheModuleSoTheDeclarationGoesThere() throws Exception {
        BuildLayout.Layout layout = reactor();
        installOffline("com.example.stack", "stack-store", "2.3.0");
        TaskGraph graph = graph(
            task("persist the shelf", Set.of("server/src/main/java/com/example/server")));
        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(graph,
            List.of(finding("stack-store",
                "- Storage is an object graph\n  The server keeps the objects and persists them "
                    + "through `stack-store`.")),
            catalog(layout), layout, Map.of("server", 1, "shared", 0), Map.of(), repo);

        assertThat(outcome.parks()).isFalse();
        assertThat(outcome.declarations()).singleElement().satisfies(declaration -> {
            assertThat(declaration.module()).isEqualTo("server");
            assertThat(declaration.buildFile()).isEqualTo("server/pom.xml");
            assertThat(declaration.reason()).isEqualTo("the rule names it");
            assertThat(declaration.version()).isEqualTo("2.3.0");
        });
        Task worker = outcome.graph().tasks().get(0);
        assertThat(worker.instructions())
            .contains("Also declare `com.example.stack:stack-store` in server/pom.xml")
            .contains("NO <version> element");
        assertThat(worker.writeSet())
            .as("the file the instruction names must be one the worker may write")
            .contains("server/pom.xml");
    }

    @Test
    void whenTheRuleDoesNotSayTheLeastFurnishedModuleThePlanWritesToIsChosenAndSaidSo()
            throws Exception {
        BuildLayout.Layout layout = reactor();
        installOffline("com.example.stack", "stack-store", "2.3.0");
        TaskGraph graph = graph(
            task("model", Set.of("shared/src/main/java/com/example/shared")),
            task("wiring", Set.of("server/src/main/java/com/example/server")));
        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(graph,
            List.of(finding("stack-store", "- Persistence is via `stack-store`.")),
            catalog(layout), layout, Map.of("server", 7, "shared", 1), Map.of(), repo);

        assertThat(outcome.declarations()).singleElement().satisfies(declaration -> {
            assertThat(declaration.module()).isEqualTo("shared");
            assertThat(declaration.reason()).contains("fewest dependencies");
        });
        assertThat(outcome.graph().tasks().get(0).instructions()).contains("shared/pom.xml");
    }

    /**
     * The rule (deliberately, as {@code whenTheRuleDoesNotSay...} above already proves) names no
     * module. Left to fewest-dependencies alone, "shared" (0 declared dependencies) would beat
     * "server" (5) — but the artifact itself is named {@code stack-store}: on the real project
     * (2026-09-03, run {@code ede2068b}) the equivalent was {@code zerozstack-store-eclipsestore},
     * and the module that ought to receive a persistence artifact is the one already wired into
     * this build's server-side foundation, not merely the one with the fewest lines in its pom.
     */
    @Test
    void anArtifactNamingItsOwnTierPrefersTheModuleAlreadyWiredIntoThatTierOverFewestDependencies()
            throws Exception {
        BuildLayout.Layout layout = reactor();
        installOffline("com.example.stack", "stack-store", "2.3.0");
        TaskGraph graph = graph(task("wiring", Set.of("shared/src/main/java/com/example/shared")));
        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);
        Map<String, List<String>> coordinatesByModule = Map.of(
            "shared", List.of(),
            "server", List.of("com.zeroz4j:zerozstack-server-core"));

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(graph,
            List.of(finding("stack-store", "- Persistence is via `stack-store`.")),
            catalog(layout), layout, Map.of("server", 5, "shared", 0), coordinatesByModule, repo);

        assertThat(outcome.parks()).isFalse();
        assertThat(outcome.declarations()).singleElement().satisfies(declaration -> {
            assertThat(declaration.module())
                .as("server already depends on this build's server tier — that beats shared's "
                    + "lower dependency count")
                .isEqualTo("server");
            assertThat(declaration.reason()).contains("names its own tier");
        });
    }

    /** Nothing plans that module: a small task of its own, ahead of everything else. */
    @Test
    void whenNoTaskWritesThatModuleAnEnablerTaskIsPrepended() throws Exception {
        BuildLayout.Layout layout = reactor();
        installOffline("com.example.stack", "stack-store", "2.3.0");
        TaskGraph graph = graph(
            task("client only", Set.of("shared/src/main/java/com/example/shared")));
        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(graph,
            List.of(finding("stack-store", "- The server persists through `stack-store`.")),
            catalog(layout), layout, Map.of("server", 1, "shared", 0), Map.of(), repo);

        List<Task> tasks = outcome.graph().tasks();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).title()).isEqualTo("Declare stack-store in server");
        assertThat(tasks.get(0).writeSet()).containsExactly("server/pom.xml");
        assertThat(outcome.graph().dependencies())
            .as("everything waits on it, so the dependency is there before anything compiles")
            .anyMatch(edge -> edge.from().equals(tasks.get(0).id())
                && edge.to().equals(tasks.get(1).id()));
        assertThat(new TaskGraphValidator().validate(outcome.graph(), null, layout).ok()).isTrue();
    }

    /** The single-task fallback graph writes anywhere, so it just gets told. */
    @Test
    void anUnrestrictedTaskIsSimplyToldToDeclareIt() throws Exception {
        BuildLayout.Layout layout = reactor();
        installOffline("com.example.stack", "stack-store", "2.3.0");
        TaskGraph graph = graph(task("Implement Goal", Set.of()));

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(graph,
            List.of(finding("stack-store", "- The server persists through `stack-store`.")),
            catalog(layout), layout, Map.of("server", 1, "shared", 0), Map.of(), repo);

        assertThat(outcome.graph().tasks()).hasSize(1);
        assertThat(outcome.graph().tasks().get(0).instructions())
            .contains("Also declare `com.example.stack:stack-store` in server/pom.xml");
    }

    /**
     * The one thing work cannot fix. Candidate builds run with no network against the local Maven
     * repository alone, so an artifact that is not there cannot be obtained by any worker, any
     * candidate or any retry. The brief names the artifact and the directory a person must put it
     * in, and nothing else about this is a park any more.
     */
    @Test
    void anArtifactTheOfflineRepositoryDoesNotHoldParksTheRunNamingItAndTheDirectory()
            throws Exception {
        BuildLayout.Layout layout = reactor();     // nothing installed into m2

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(
            graph(task("persist", Set.of("server/src/main/java/com/example/server"))),
            List.of(finding("stack-store", "- The server persists through `stack-store`.")),
            catalog(layout), layout, Map.of("server", 1), Map.of(), repo);

        assertThat(outcome.parks()).isTrue();
        assertThat(outcome.parkBrief())
            .contains("stack-store")
            .contains(m2.resolve("repository").toString())
            .contains("no module of this build declares it")
            .contains("NO network");
        assertThat(outcome.declarations()).isEmpty();
    }

    @Test
    void rulesAndBuildThatAgreeChangeNothing() throws Exception {
        TaskGraph graph = graph(task("work", Set.of("server/src/main/java/com/example/server")));

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(graph, List.of(),
            null, reactor(), Map.of(), Map.of(), repo);

        assertThat(outcome.parks()).isFalse();
        assertThat(outcome.declarations()).isEmpty();
        assertThat(outcome.graph()).isSameAs(graph);
    }

    // ------------------------------------------------------------------------------- fixtures

    /** A two-module Maven reactor importing a BOM, with nothing installed until asked. */
    private BuildLayout.Layout reactor() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <modules><module>shared</module><module>server</module></modules>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example.stack</groupId>
                    <artifactId>stack-bom</artifactId>
                    <version>2.3.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
        for (String module : List.of("shared", "server")) {
            Path dir = Files.createDirectories(repo.resolve(module));
            Files.createDirectories(dir.resolve("src/main/java"));
            Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(module));
        }
        writeBom();
        return BuildLayout.read(repo, "maven");
    }

    private DeclarableArtifacts.Catalog catalog(BuildLayout.Layout layout) {
        return DeclarableArtifacts.scan(repo, layout.compilingModules(), m2.resolve("repository"));
    }

    private void writeBom() throws Exception {
        Path dir = Files.createDirectories(
            m2.resolve("repository/com/example/stack/stack-bom/2.3.0"));
        Files.writeString(dir.resolve("stack-bom-2.3.0.pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example.stack</groupId>
              <artifactId>stack-bom</artifactId>
              <version>2.3.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example.stack</groupId>
                    <artifactId>stack-store</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    }

    private void installOffline(String groupId, String artifactId, String version)
            throws Exception {
        Path dir = Files.createDirectories(m2.resolve("repository")
            .resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version));
        Files.writeString(dir.resolve(artifactId + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
            </project>
            """.formatted(groupId, artifactId, version));
        Files.writeString(dir.resolve(artifactId + "-" + version + ".jar"), "jar");
    }

    private static RulesVersusManifest.Finding finding(String artifact, String rule) {
        return new RulesVersusManifest.Finding(rule, artifact,
            List.of("shared/pom.xml", "server/pom.xml"));
    }

    private static TaskGraph graph(Task... tasks) {
        return new TaskGraph(UUID.randomUUID(), 1L, null, new java.util.ArrayList<>(List.of(tasks)),
            new java.util.ArrayList<TaskEdge>());
    }

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1L, title, "do the thing",
            new java.util.LinkedHashSet<>(writeSet), Set.of(), List.of(), "src/test/java/swarm",
            null, null, new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.PENDING);
    }
}
