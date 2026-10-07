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
package com.swarmcoder.verify;

import com.swarmcoder.domain.BuildReachability;
import com.swarmcoder.domain.BuildReachabilityStatus;
import com.swarmcoder.domain.VerificationReport;
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
 * The orphan-source-root gate, proved against the case that produced it.
 *
 * <p>Run {@code 9bd237ff-9bc4-4da7-970e-c51a46d5c05e} against {@code dev/bookshelf-demo} on
 * 2026-08-30 wrote six files, every one of them under a {@code src/main/java} at the repository
 * root. That repository's root {@code pom.xml} is {@code <packaging>pom</packaging>} over three
 * modules and has no {@code src/} of its own, so nothing compiled any of them — and
 * {@code mvn -o -q -B compile test-compile}, which the verification contract runs from that root,
 * exited 0 anyway because the three untouched modules were fine. Two tasks were selected and
 * marked delivered.
 *
 * <p>The layout below is that repository's, module for module. The first test is the real
 * candidate; the second is the same six classes put where the build would actually have compiled
 * them.
 */
class BuildReachabilityCheckTest {

    /** The six paths run 9bd237ff wrote, verbatim. */
    private static final Set<String> THE_REAL_BAD_CANDIDATE = Set.of(
        "src/main/java/com/zeroz4j/bookstore/domain/Book.java",
        "src/main/java/com/zeroz4j/bookstore/domain/BookInput.java",
        "src/main/java/com/zeroz4j/bookstore/domain/ReadingStatus.java",
        "src/main/java/com/zeroz4j/bookstore/ui/AppShell.java",
        "src/main/java/com/zeroz4j/bookstore/ui/BookTheme.java",
        "src/main/webapp/bookstore.css");

    /** The same work, inside the module the build actually compiles. */
    private static final Set<String> THE_CORRECTED_CANDIDATE = Set.of(
        "bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/model/Book.java",
        "bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/model/BookInput.java",
        "bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/model/ReadingStatus.java",
        "bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client/AppShell.java",
        "bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client/BookTheme.java",
        "bookshelf-demo-client/src/main/resources/bookstore.css");

    @TempDir
    Path repo;

    // ------------------------------------------------------------ the real case

    @Test
    void theSixFileCandidateThatWonIsRejected() throws IOException {
        bookshelfDemoLayout();

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven", THE_REAL_BAD_CANDIDATE);

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.ORPHANED);
        assertThat(result.orphanFiles()).containsExactlyInAnyOrderElementsOf(THE_REAL_BAD_CANDIDATE);
        assertThat(result.compiledRoots())
            .contains("bookshelf-demo-shared/src/main/java",
                "bookshelf-demo-client/src/main/java",
                "bookshelf-demo-server/src/main/java");

        // The message has to name the files and where the build does look, or the operator learns
        // nothing they could not have guessed from "verification failed".
        assertThat(result.describe())
            .contains("src/main/java/com/zeroz4j/bookstore/domain/Book.java")
            .contains("bookshelf-demo-shared/src/main/java");
    }

    @Test
    void aCandidateOutsideTheBuildDoesNotSurviveEvenWhenEveryStageIsGreen() throws IOException {
        bookshelfDemoLayout();

        VerificationReport report = greenReport();
        report.setBuildReachability(BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven", THE_REAL_BAD_CANDIDATE));

        assertThat(Verdicts.survived(report)).isFalse();
        assertThat(Verdicts.assess(report, List.of()).reason())
            .contains("will never compile or package");
    }

    @Test
    void theSameWorkInsideTheModulesIsAccepted() throws IOException {
        bookshelfDemoLayout();

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven", THE_CORRECTED_CANDIDATE);

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
        assertThat(result.orphanFiles()).isEmpty();

        VerificationReport report = greenReport();
        report.setBuildReachability(result);
        assertThat(Verdicts.survived(report)).isTrue();
    }

    // ------------------------------------------------------------ the two rules

    @Test
    void aCandidateEditingTheBuildFilesAndDocsIsNotFailedForIt() throws IOException {
        bookshelfDemoLayout();

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven",
            Set.of("pom.xml", "README.md", "docs/design.md", "Dockerfile", ".gitignore",
                "bookshelf-demo-shared/pom.xml"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
    }

    @Test
    void anAssetInsideAModuleTheBuildOwnsIsNotedButNotFailed() throws IOException {
        bookshelfDemoLayout();

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven",
            Set.of("bookshelf-demo-client/src/main/frontend/styles/app.css"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
        assertThat(result.notedFiles())
            .containsExactly("bookshelf-demo-client/src/main/frontend/styles/app.css");
    }

    @Test
    void aJavaFileInsideAModuleButOutsideItsSourceRootStillFails() throws IOException {
        bookshelfDemoLayout();

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven",
            Set.of("bookshelf-demo-client/src/main/frontend/Widget.java"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.ORPHANED);
    }

    @Test
    void aCandidateThatAddsItsOwnModuleIsAccepted() throws IOException {
        bookshelfDemoLayout();
        // The candidate registered a fourth module and wrote inside it. Reading the layout from the
        // candidate's own workspace is what makes this legitimate change legitimate.
        Files.writeString(repo.resolve("pom.xml"), aggregatorPom(
            "bookshelf-demo-shared", "bookshelf-demo-client", "bookshelf-demo-server",
            "bookshelf-demo-search"));
        Files.createDirectories(repo.resolve("bookshelf-demo-search"));
        Files.writeString(repo.resolve("bookshelf-demo-search/pom.xml"), modulePom("bookshelf-demo-search"));

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven",
            Set.of("pom.xml", "bookshelf-demo-search/pom.xml",
                "bookshelf-demo-search/src/main/java/com/swarmcoder/demo/bookshelf/search/Index.java"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
    }

    // ------------------------------------------------ what it does when it cannot tell

    @Test
    void noChangedFileListEstablishesNothing() throws IOException {
        bookshelfDemoLayout();

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven", Set.of());

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.UNDETERMINED);
        assertThat(result.explanation()).contains("No list of the files");
        assertThat(Verdicts.survived(withReachability(result))).isTrue();
    }

    @Test
    void aToolchainWhoseLayoutIsConfigurationEstablishesNothing() throws IOException {
        Files.writeString(repo.resolve("package.json"), "{\"name\":\"x\"}");

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "node", Set.of("lib/thing.ts"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.UNDETERMINED);
        assertThat(result.explanation()).contains("configuration rather than convention");
        assertThat(Verdicts.survived(withReachability(result))).isTrue();
    }

    @Test
    void anUnreadableLayoutEstablishesNothing() throws IOException {
        // Maven declared, no pom at all: the layout is unknown, and a candidate must not be failed
        // for it.
        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven",
            Set.of("src/main/java/com/example/Thing.java"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.UNDETERMINED);
        assertThat(Verdicts.survived(withReachability(result))).isTrue();
    }

    @Test
    void aReactorOfNothingButAggregatorsEstablishesNothing() throws IOException {
        Files.writeString(repo.resolve("pom.xml"), aggregatorPom("child"));
        Files.createDirectories(repo.resolve("child"));
        Files.writeString(repo.resolve("child/pom.xml"), aggregatorPom());

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven",
            Set.of("src/main/java/com/example/Thing.java"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.UNDETERMINED);
        assertThat(result.explanation()).contains("not one module");
    }

    @Test
    void anOldReportWithoutTheFieldSurvives() {
        assertThat(Verdicts.survived(greenReport())).isTrue();
    }

    // ------------------------------------------------------------ other layouts

    @Test
    void aSingleModuleMavenProjectCompilesItsOwnRoot() throws IOException {
        Files.writeString(repo.resolve("pom.xml"), modulePom("solo"));

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven",
            Set.of("src/main/java/com/example/Thing.java", "src/main/resources/app.properties"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
    }

    @Test
    void aWarModuleOwnsItsWebapp() throws IOException {
        Files.writeString(repo.resolve("pom.xml"), aggregatorPom("web"));
        Files.createDirectories(repo.resolve("web"));
        Files.writeString(repo.resolve("web/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>web</artifactId><version>1</version>
              <packaging>war</packaging>
            </project>
            """);

        BuildReachability result = BuildReachabilityCheck.check(
            new LocalProcessExecTarget(repo), "maven", Set.of("web/src/main/webapp/index.html"));

        assertThat(result.status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
    }

    @Test
    void aPomThatMovesItsSourceDirectoryIsBelieved() throws IOException {
        Files.writeString(repo.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
              <build><sourceDirectory>java</sourceDirectory></build>
            </project>
            """);

        LocalProcessExecTarget target = new LocalProcessExecTarget(repo);
        assertThat(BuildReachabilityCheck.check(target, "maven", Set.of("java/com/example/A.java"))
            .status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
        assertThat(BuildReachabilityCheck.check(target, "maven",
            Set.of("src/main/java/com/example/A.java")).status())
            .isEqualTo(BuildReachabilityStatus.ORPHANED);
    }

    @Test
    void aGradleMultiProjectIsRead() throws IOException {
        Files.writeString(repo.resolve("settings.gradle"), """
            rootProject.name = 'demo'
            include 'app'
            include(":lib")
            """);

        LocalProcessExecTarget target = new LocalProcessExecTarget(repo);
        assertThat(BuildReachabilityCheck.check(target, "gradle",
            Set.of("app/src/main/java/com/example/A.java")).status())
            .isEqualTo(BuildReachabilityStatus.REACHABLE);
        assertThat(BuildReachabilityCheck.check(target, "gradle",
            Set.of("src/main/java/com/example/A.java")).status())
            .isEqualTo(BuildReachabilityStatus.ORPHANED);
    }

    // ------------------------------------------------------------ fixture

    /** dev/bookshelf-demo, module for module: a root aggregator with no src/ and three modules. */
    private void bookshelfDemoLayout() throws IOException {
        Files.writeString(repo.resolve("pom.xml"),
            aggregatorPom("bookshelf-demo-shared", "bookshelf-demo-client", "bookshelf-demo-server"));
        for (String module : List.of("bookshelf-demo-shared", "bookshelf-demo-client", "bookshelf-demo-server")) {
            Files.createDirectories(repo.resolve(module));
            Files.writeString(repo.resolve(module).resolve("pom.xml"), modulePom(module));
        }
    }

    private static String aggregatorPom(String... modules) {
        StringBuilder sb = new StringBuilder("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.swarmcoder.demo</groupId>
              <artifactId>bookshelf-demo</artifactId>
              <version>1.0.0-SNAPSHOT</version>
              <packaging>pom</packaging>
              <modules>
            """);
        for (String module : modules) {
            sb.append("    <module>").append(module).append("</module>\n");
        }
        return sb.append("  </modules>\n</project>\n").toString();
    }

    private static String modulePom(String artifactId) {
        return """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.swarmcoder.demo</groupId>
                <artifactId>bookshelf-demo</artifactId>
                <version>1.0.0-SNAPSHOT</version>
              </parent>
              <artifactId>%s</artifactId>
            </project>
            """.formatted(artifactId);
    }

    private VerificationReport withReachability(BuildReachability reachability) {
        VerificationReport report = greenReport();
        report.setBuildReachability(reachability);
        return report;
    }

    /** Everything the pipeline can report, green — which is exactly how the bad candidate looked. */
    private static VerificationReport greenReport() {
        return new VerificationReport(UUID.randomUUID(), true, true, null, null, null, null,
            java.time.Duration.ZERO, "", null);
    }
}
