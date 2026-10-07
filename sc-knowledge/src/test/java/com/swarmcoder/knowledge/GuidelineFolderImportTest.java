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

import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Project;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule files a checkout carries from before rules were store objects are read ONCE, collapsed
 * to one rule per lesson, and never read again (author decision 2026-09-02).
 *
 * <p>The bodies below are copied from the operator's own {@code dev/bookshelf-demo} folder, which
 * held 81 files for about eleven rules: the same technical document applied four times, worded a
 * little differently each time, with the earlier statements left RETIRED on disk.
 */
class GuidelineFolderImportTest {

    // Four readings of one rule from bookshelf-tech-requirements.md, as the analyst worded them.
    private static final String JAR_1 = "The server jar must not be shaded or fat; module jars "
        + "stay separate on a plain classpath.";
    private static final String JAR_2 = "Never make the server jar shaded or fat. Keep module "
        + "jars separate on a plain classpath.";
    private static final String JAR_3 = "Server jar stays unshaded — module jars stay separate "
        + "on a plain classpath, never a fat jar.";
    // Three extraction rules that say one thing.
    private static final String SAVE_1 = "Every changed level of the object graph must be saved "
        + "explicitly; saving a parent does not save the nested objects inside it.";
    private static final String SAVE_2 = "Explicitly save every level of nesting that changed — "
        + "a save on the parent does not cascade into nested objects.";
    // A lesson unlike any other.
    private static final String GIT = "Do not use git commands in this workspace; the .git file "
        + "points at an unreachable path.";

    @TempDir
    Path dir;

    @Test
    void importsOnceCollapsesRewordingsAndNeverReadsTheFolderAgain() throws Exception {
        Path guidelines = dir.resolve("repo/.swarmcoder/guidelines");
        write(guidelines, "server-jar-must-not-be-shaded-or-fat", "retired", "stated", JAR_1);
        write(guidelines, "never-make-the-server-jar-shaded-or-fat", "retired", "stated", JAR_2);
        write(guidelines, "server-jar-stays-unshaded", "active", "stated", JAR_3);
        write(guidelines, "server-jar-stays-unshaded-2", "retired", "stated", JAR_3);
        write(guidelines, "every-changed-level-must-be-saved", "proposed", "extraction", SAVE_1);
        write(guidelines, "explicitly-save-every-level", "proposed", "extraction", SAVE_2);
        write(guidelines, "git-unavailable", "proposed", "extraction", GIT);

        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            Project project = store.ensureProject("demo", dir.resolve("repo").toString(), List.of());
            ProjectRules rules = new ProjectRules(store, project.id());
            // The store already indexes one of the files, as a store written while rules were
            // files would: it must be folded in with the rest, keeping its id.
            rules.stateRule("Server jar stays unshaded", JAR_3, "bookshelf-tech-requirements.md");
            java.util.UUID kept = rules.activeRules().get(0).id();

            GuidelineFolderImport.Result result =
                GuidelineFolderImport.importOnce(store, project, rules, guidelines);

            assertThat(result).isNotNull();
            assertThat(result.filesRead()).isEqualTo(7);
            assertThat(result.inStore()).isEqualTo(1);
            List<LearnedGuideline> all = rules.all();
            assertThat(all).describedAs("eight candidates, three lessons: %s", result.lines())
                .hasSize(3);
            assertThat(result.kept()).isEqualTo(3);
            assertThat(result.collapsed()).isEqualTo(5);

            LearnedGuideline jar = all.stream()
                .filter(r -> r.markdownBody().contains("jar")).findFirst().orElseThrow();
            assertThat(jar.id()).describedAs("the store's own row survived").isEqualTo(kept);
            assertThat(jar.status()).describedAs("in force, because one of them was").isEqualTo(GuidelineStatus.ACTIVE);
            assertThat(jar.provenance().source()).isEqualTo("stated");

            LearnedGuideline save = all.stream()
                .filter(r -> r.markdownBody().contains("nest")).findFirst().orElseThrow();
            assertThat(save.status()).isEqualTo(GuidelineStatus.PROPOSED);
            assertThat(save.provenance().source()).isEqualTo("extraction");
            assertThat(save.markdownBody()).describedAs("the longer wording survives").isEqualTo(SAVE_1);

            assertThat(all.stream().filter(r -> r.markdownBody().equals(GIT)))
                .describedAs("a lesson unlike any other is untouched").hasSize(1);
            assertThat(result.lines()).hasSize(2);

            // Never again: the files stay, the mark is durable, a new file is not read.
            try (Stream<Path> files = Files.walk(guidelines)) {
                assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(7);
            }
            assertThat(store.getProject(project.id()).guidelineFilesImported()).isTrue();
            write(guidelines, "late-arrival", "active", "stated", "A rule written after the import.");
            assertThat(GuidelineFolderImport.importOnce(store, project, rules, guidelines)).isNull();
            assertThat(rules.all()).hasSize(3);
        }
    }

    @Test
    void aProjectWithNoFolderIsMarkedAndImportsNothing() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            Project project = store.ensureProject("bare", dir.resolve("bare").toString(), List.of());
            ProjectRules rules = new ProjectRules(store, project.id());
            GuidelineFolderImport.Result result = GuidelineFolderImport.importOnce(store, project,
                rules, dir.resolve("bare/.swarmcoder/guidelines"));
            assertThat(result.filesRead()).isZero();
            assertThat(rules.all()).isEmpty();
            assertThat(project.guidelineFilesImported()).isTrue();
        }
    }

    /** A hand-written file with a check keeps it; a machine file's check is dropped (§13.1). */
    @Test
    void aFileWithACheckKeepsItOnlyWhenAPersonWroteIt() throws Exception {
        Path guidelines = dir.resolve("repo/.swarmcoder/guidelines");
        Files.createDirectories(guidelines.resolve("project"));
        Files.writeString(guidelines.resolve("project/no-records.md"),
            "---\nstatus: active\ncheck: \"! grep -rq 'public record' src\"\ncheckTimeoutSeconds: 30\n"
                + "title: No records\n---\nNever declare a Java record.\n");
        Files.writeString(guidelines.resolve("project/machine.md"),
            "---\nstatus: active\nsource: extraction\ncheck: rm -rf /\n---\n"
                + "A model wrote this and put a command on it.\n");
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            Project project = store.ensureProject("demo", dir.resolve("repo").toString(), List.of());
            ProjectRules rules = new ProjectRules(store, project.id());
            GuidelineFolderImport.importOnce(store, project, rules, guidelines);

            assertThat(rules.activeChecks()).hasSize(1);
            assertThat(rules.activeChecks().get(0).slug()).isEqualTo("no-records");
            assertThat(rules.activeChecks().get(0).effectiveTimeoutSeconds()).isEqualTo(30);
            LearnedGuideline human = rules.all().stream()
                .filter(r -> r.slug().equals("no-records")).findFirst().orElseThrow();
            assertThat(human.title()).isEqualTo("No records");
            assertThat(human.provenance().source()).isEqualTo("human");
            LearnedGuideline machine = rules.all().stream()
                .filter(r -> r.slug().equals("machine")).findFirst().orElseThrow();
            assertThat(machine.checkCommand()).isNull();
        }
    }

    private static void write(Path guidelines, String slug, String status, String source,
                              String body) throws Exception {
        Files.createDirectories(guidelines.resolve("project"));
        Files.writeString(guidelines.resolve("project/" + slug + ".md"),
            "---\nstatus: " + status + "\nsource: " + source
                + "\ndocument: bookshelf-tech-requirements.md\n---\n" + body + "\n");
    }
}
