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
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cause, not the symptom: a plan whose write set points at a source root the repository does
 * not have.
 *
 * <p>Run {@code 9bd237ff} on 2026-08-30 planned every task into
 * {@code src/main/java/com/zeroz4j/bookstore/...} against a repository whose root {@code pom.xml}
 * only aggregates three modules. The workers obeyed the write set exactly. Nothing between the
 * planner and the workers had ever looked at the repository's module layout, because until this
 * change the planner was never shown it and the validator never checked it.
 */
class PlanWritesIntoTheBuildTest {

    private final TaskGraphValidator validator = new TaskGraphValidator();

    @TempDir
    Path repo;

    @Test
    void aPlanWritingToASourceRootTheRepositoryDoesNotHaveIsRejected() throws IOException {
        BuildLayout.Layout layout = bookshelfDemoLayout();

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(task("shared app shell", Set.of("src/main/java/com/zeroz4j/bookstore/ui"))),
            null, layout);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations().get(0))
            .contains("shared app shell")
            .contains("the repository root compiles nothing")
            .contains("bookshelf-demo-shared/src/main/java");
    }

    @Test
    void thePlanTheRepositoryCanActuallyBuildIsAccepted() throws IOException {
        BuildLayout.Layout layout = bookshelfDemoLayout();

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(task("shared app shell",
                Set.of("bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client"))),
            null, layout);

        assertThat(verdict.ok()).isTrue();
        assertThat(verdict.warnings()).noneMatch(w -> w.contains("is not currently a module"));
    }

    @Test
    void aPlanIntendingANewModuleIsWarnedAboutRatherThanRejected() throws IOException {
        BuildLayout.Layout layout = bookshelfDemoLayout();

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(task("search module", Set.of("bookshelf-demo-search/src/main/java"))),
            null, layout);

        assertThat(verdict.ok()).isTrue();     // it may be creating that module
        assertThat(verdict.warnings()).anyMatch(w -> w.contains("is not currently a module"));
    }

    @Test
    void anUnreadableLayoutChecksNothing() {
        BuildLayout.Layout layout = BuildLayout.read(repo, "node");

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(task("anything", Set.of("src/main/java/com/zeroz4j/bookstore/ui"))), null, layout);

        assertThat(verdict.ok()).isTrue();
        assertThat(verdict.warnings()).noneMatch(w -> w.contains("is not currently a module"));
    }

    @Test
    void noLayoutAtAllBehavesExactlyAsBefore() {
        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(task("anything", Set.of("src/main/java/com/zeroz4j/bookstore/ui"))));

        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void thePlannerIsToldWhereTheCodeLives() throws IOException {
        String brief = RepoLayoutBrief.render(bookshelfDemoLayout());

        assertThat(brief)
            .contains("bookshelf-demo-shared/src/main/java")
            .contains("NO source directory at the repository root");
    }

    @Test
    void thePlannerIsToldNothingWhenNothingIsKnown() {
        assertThat(RepoLayoutBrief.forRepo(null, "maven")).isEmpty();
        assertThat(RepoLayoutBrief.render(BuildLayout.read(repo, "cargo"))).isEmpty();
    }

    // ------------------------------------------------------------ fixture

    private BuildLayout.Layout bookshelfDemoLayout() throws IOException {
        Files.writeString(repo.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.swarmcoder.demo</groupId>
              <artifactId>bookshelf-demo</artifactId>
              <version>1.0.0-SNAPSHOT</version>
              <packaging>pom</packaging>
              <modules>
                <module>bookshelf-demo-shared</module>
                <module>bookshelf-demo-client</module>
                <module>bookshelf-demo-server</module>
              </modules>
            </project>
            """);
        for (String module : List.of("bookshelf-demo-shared", "bookshelf-demo-client", "bookshelf-demo-server")) {
            Files.createDirectories(repo.resolve(module));
            Files.writeString(repo.resolve(module).resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(module));
        }
        return BuildLayout.read(repo, "maven");
    }

    private static TaskGraph graph(Task... tasks) {
        return new TaskGraph(UUID.randomUUID(), 1, null, List.of(tasks), List.of());
    }

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do " + title, writeSet, Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }
}
