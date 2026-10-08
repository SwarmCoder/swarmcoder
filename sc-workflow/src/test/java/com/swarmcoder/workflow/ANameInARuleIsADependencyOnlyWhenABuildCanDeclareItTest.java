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
 * A run stops for a missing dependency only when the name resolves to an artifact a build can
 * declare and a person can install. A word that merely stands where a dependency's name could
 * stand is noted and the run goes on.
 *
 * <p>Live run 98 (2026-10-08): a project's rule about its browser module ended "... and only uses
 * TeaVM-compilable classes". The token after "uses" was taken for a library, no pom declared
 * it, the local Maven repository did not hold it, and the run stopped at PLAN asking a person to
 * install a library called {@code TeaVM-compilable}. Nobody can.
 */
class ANameInARuleIsADependencyOnlyWhenABuildCanDeclareItTest {

    @TempDir
    Path repo;
    @TempDir
    Path m2;

    /** The rules as the run was briefed with them: one wording, one real artifact. */
    private static final String RULES = "HOW THIS PROJECT MUST BE BUILT\n\n"
        + "- Browser code is Java compiled to JavaScript by TeaVM\n"
        + "  The client module is compiled from Java to JavaScript by TeaVM. Not everything on "
        + "the server side is available.\n"
        + "  Why it exists: so the browser code really runs as compiled Java via TeaVM and only "
        + "uses TeaVM-compilable classes\n"
        + "  A HARD rule: breaking it stops the work.\n"
        + "- Storage is an object graph\n"
        + "  The server persists through stack-store.\n"
        + "  A HARD rule: breaking it stops the work.\n";

    private static final List<String> POMS = List.of("shared/pom.xml", "server/pom.xml");

    @Test
    void theWordingOfRun98IsNotedAndTheRealArtifactInTheSameRulesBecomesWork() throws Exception {
        BuildLayout.Layout layout = reactor();
        installOffline("com.example.stack", "stack-store", "2.3.0");
        List<RulesVersusManifest.Finding> findings =
            RulesVersusManifest.check(RULES, List.of(), POMS);
        assertThat(findings).extracting(RulesVersusManifest.Finding::artifact)
            .as("the extractor reads wording and still yields both names; which of them is a "
                + "dependency is decided from the build")
            .containsExactly("TeaVM-compilable", "stack-store");

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(
            graph(task("persist", Set.of("server/src/main/java/com/example/server"))),
            findings, catalog(layout), layout, Map.of("server", 1), Map.of(), repo);

        assertThat(outcome.parks()).as("nobody can install an adjective").isFalse();
        assertThat(outcome.declarations()).extracting(BuildFilesInTheJob.Declaration::coordinate)
            .containsExactly("com.example.stack:stack-store");
        assertThat(outcome.notes()).hasSize(1);
        assertThat(outcome.noted("TeaVM-compilable")).isTrue();
        assertThat(outcome.noted("stack-store")).isFalse();
        assertThat(outcome.notes().get(0).text())
            .contains("`TeaVM-compilable`")
            .contains("Taken as ordinary wording")
            .contains("write it as group:artifact");
    }

    @Test
    void aBareNameNoDependencyManagementKnowsIsANoteHoweverItIsSetOff() throws Exception {
        BuildLayout.Layout layout = reactor();
        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(
            "- Reads\n  Every query is `read-only` and goes via some-unknown-thing.\n",
            List.of(), POMS);
        assertThat(findings).hasSize(2);

        TaskGraph graph = graph(task("work", Set.of("server/src/main/java/com/example/server")));
        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(graph, findings,
            catalog(layout), layout, Map.of("server", 1), Map.of(), repo);

        assertThat(outcome.parks()).isFalse();
        assertThat(outcome.declarations()).isEmpty();
        assertThat(outcome.notes()).extracting(BuildFilesInTheJob.Note::name)
            .containsExactly("read-only", "some-unknown-thing");
        assertThat(outcome.graph().tasks()).as("nothing is added to the plan for a word")
            .hasSize(1);
    }

    /** The case the check was built for, with the files missing: still the one stop. */
    @Test
    void anArtifactABomManagesThatIsNotOnTheDiskStillStopsTheRunSayingWhatToInstall()
            throws Exception {
        BuildLayout.Layout layout = reactor();     // the BOM pins stack-store; nothing installed

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(
            graph(task("persist", Set.of("server/src/main/java/com/example/server"))),
            RulesVersusManifest.check(RULES, List.of(), POMS),
            catalog(layout), layout, Map.of("server", 1), Map.of(), repo);

        assertThat(outcome.parks()).isTrue();
        assertThat(outcome.parkBrief())
            .contains("Install `com.example.stack:stack-store:2.3.0")
            .contains(m2.resolve("repository").toString())
            .doesNotContain("TeaVM-compilable");
        assertThat(outcome.noted("TeaVM-compilable")).isTrue();
    }

    @Test
    void aNameWrittenWithItsGroupIsADependencyWhateverTheRepositoryHolds() throws Exception {
        BuildLayout.Layout layout = reactor();
        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(
            "- Reports\n  PDF output is produced with org.example.pdf:pdf-writer.\n",
            List.of(), POMS);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).group()).isEqualTo("org.example.pdf");
        assertThat(findings.get(0).statedAsCoordinate()).isTrue();

        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(
            graph(task("report", Set.of("server/src/main/java/com/example/server"))),
            findings, catalog(layout), layout, Map.of("server", 1), Map.of(), repo);

        assertThat(outcome.parks()).as("coordinates are something a person can install").isTrue();
        assertThat(outcome.parkBrief()).contains("Install `org.example.pdf:pdf-writer`");
        assertThat(outcome.notes()).isEmpty();
    }

    // ------------------------------------------------------------------------------- fixtures

    /** A two-module Maven reactor importing a BOM that pins stack-store; nothing installed. */
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
        Path bom = Files.createDirectories(
            m2.resolve("repository/com/example/stack/stack-bom/2.3.0"));
        Files.writeString(bom.resolve("stack-bom-2.3.0.pom"), """
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
        return BuildLayout.read(repo, "maven");
    }

    private DeclarableArtifacts.Catalog catalog(BuildLayout.Layout layout) {
        return DeclarableArtifacts.scan(repo, layout.compilingModules(), m2.resolve("repository"));
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
