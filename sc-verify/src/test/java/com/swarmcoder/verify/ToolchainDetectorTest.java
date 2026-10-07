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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the on-ramp detector must get right on a repository it has never seen.
 *
 * <p>The two assertions that matter most are the ones that record real defects rather than
 * preferences: the Surefire acceptance selector must be a PATH pattern (§15.2 — the dotted form
 * matches nothing and the stage then silently runs zero tests and waves every candidate through),
 * and a multi-module reactor must enumerate its per-module report directories, because
 * {@code ExecTarget.listFiles} walks a directory and does not glob, so the root
 * {@code target/surefire-reports} of a reactor build finds nothing at all.
 */
class ToolchainDetectorTest {

    @TempDir
    Path root;

    // --- Maven ---------------------------------------------------------------------------------

    @Test
    void singleModuleMavenIsDetectedWithAWorkingSurefireSelector() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>a</artifactId></project>");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        assertThat(detection.recognised()).isTrue();
        assertThat(detection.toolchain()).isEqualTo("maven");
        assertThat(detection.proposed().compile()).allMatch(c -> c.contains("test-compile"));
        // The dotted package form (swarm.accept.*) matches NOTHING in Surefire — §15.2.
        assertThat(detection.proposed().acceptance()).allSatisfy(c -> {
            assertThat(c).contains("-Dtest=swarm/accept/**");
            assertThat(c).doesNotContain("swarm.accept");
        });
        assertThat(detection.proposed().existingReportDirs())
            .contains("target/surefire-reports");
        assertThat(detection.acceptanceTestDir()).isEqualTo("src/test/java/swarm/accept");
    }

    @Test
    void aMultiModuleReactorEnumeratesEveryModulesReportDirectory() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
            <project><modules>
              <module>core</module>
              <module>web</module>
            </modules></project>""");
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve("core/pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("web"));
        Files.writeString(root.resolve("web/pom.xml"), "<project/>");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        // Without these the stage reads results from the root only, finds none, and reports zero
        // tests — which is indistinguishable from a green run.
        assertThat(detection.proposed().existingReportDirs())
            .contains("core/target/surefire-reports", "web/target/surefire-reports");
        assertThat(detection.warnings())
            .anySatisfy(w -> assertThat(w).contains("multi-module"));
    }

    @Test
    void nestedAggregatorModulesAreFoundToo() throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            "<project><modules><module>group</module></modules></project>");
        Files.createDirectories(root.resolve("group/leaf"));
        Files.writeString(root.resolve("group/pom.xml"),
            "<project><modules><module>leaf</module></modules></project>");
        Files.writeString(root.resolve("group/leaf/pom.xml"), "<project/>");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        assertThat(detection.proposed().existingReportDirs())
            .contains("group/target/surefire-reports", "group/leaf/target/surefire-reports");
    }

    // --- Gradle --------------------------------------------------------------------------------

    @Test
    void gradleKeepsTheDottedSelectorBecauseGradleWantsOne() throws IOException {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(root.resolve("settings.gradle"), "include 'core'\ninclude 'app'");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        assertThat(detection.toolchain()).isEqualTo("gradle");
        assertThat(detection.proposed().acceptance()).allMatch(c -> c.contains("swarm.accept.*"));
        assertThat(detection.proposed().existingReportDirs())
            .contains("build/test-results", "core/build/test-results", "app/build/test-results");
    }

    // --- Node ----------------------------------------------------------------------------------

    @Test
    void nodeReadsTheScriptsAndTheLockfileRatherThanAssuming() throws IOException {
        Files.writeString(root.resolve("package.json"), """
            {"scripts": {"build": "tsc", "test": "vitest run", "lint": "eslint ."},
             "devDependencies": {"vitest": "^2.0.0"}}""");
        Files.writeString(root.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        assertThat(detection.toolchain()).isEqualTo("node");
        assertThat(detection.proposed().compile()).containsExactly("pnpm run build");
        assertThat(detection.proposed().existing()).containsExactly("pnpm run test");
        assertThat(detection.proposed().lint()).containsExactly("pnpm run lint");
        // Without JUnit XML the acceptance stage is INCONCLUSIVE and proves nothing.
        assertThat(detection.proposed().acceptance()).allMatch(c -> c.contains("--reporter=junit"));
    }

    @Test
    void nodeWithNoTestRunnerSaysSoInsteadOfProposingACommandThatProvesNothing()
            throws IOException {
        Files.writeString(root.resolve("package.json"), "{\"scripts\": {\"build\": \"tsc\"}}");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        assertThat(detection.proposed().acceptance()).isEmpty();
        assertThat(detection.warnings()).anySatisfy(w -> assertThat(w).contains("vitest or jest"));
        assertThat(detection.warnings()).anySatisfy(w -> assertThat(w).contains("no \"test\""));
    }

    // --- Cargo and Python ----------------------------------------------------------------------

    @Test
    void cargoUsesNextestBecausePlainCargoTestWritesNoReport() throws IOException {
        Files.writeString(root.resolve("Cargo.toml"), "[workspace]\nmembers = [\"a\"]");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        assertThat(detection.toolchain()).isEqualTo("cargo");
        assertThat(detection.proposed().existing()).allMatch(c -> c.contains("nextest"));
        assertThat(detection.proposed().compile()).allMatch(c -> c.contains("--workspace"));
        assertThat(detection.warnings()).anySatisfy(w -> assertThat(w).contains("cargo-nextest"));
    }

    @Test
    void pythonAsksForJunitXmlAndAdmitsItsCompileStageIsWeak() throws IOException {
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"x\"");
        Files.createDirectories(root.resolve("src"));

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        assertThat(detection.toolchain()).isEqualTo("python");
        assertThat(detection.proposed().compile()).containsExactly("python -m compileall -q src");
        assertThat(detection.proposed().existing()).allMatch(c -> c.contains("--junitxml"));
        assertThat(detection.warnings())
            .anySatisfy(w -> assertThat(w).contains("only byte-compiles"));
    }

    // --- the honest failures -------------------------------------------------------------------

    @Test
    void anUnrecognisedRootProposesNothingAndNamesTheSubprojectsItCanSee() throws IOException {
        Files.createDirectories(root.resolve("services/api"));
        Files.writeString(root.resolve("services/api/pom.xml"), "<project/>");
        Files.writeString(root.resolve("README.md"), "a monorepo");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);

        // Guessing a command here is worse than none: a guess that exits 0 for the wrong reason
        // certifies a candidate nobody checked.
        assertThat(detection.recognised()).isFalse();
        assertThat(detection.proposed()).isNull();
        assertThat(detection.subprojects()).contains("services/api");
    }

    @Test
    void aMissingDirectoryIsSaidPlainlyRatherThanThrown() {
        ToolchainDetector.Detection detection =
            ToolchainDetector.detect(root.resolve("nope"));

        assertThat(detection.recognised()).isFalse();
        assertThat(detection.evidence()).anySatisfy(e -> assertThat(e).contains("no such directory"));
    }

    // --- round trip ----------------------------------------------------------------------------

    @Test
    void whatIsRenderedIsWhatTheLoaderReadsBack() throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            "<project><modules><module>core</module></modules></project>");
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve("core/pom.xml"), "<project/>");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);
        String yaml = ToolchainDetector.render(detection);

        VerifySpec reloaded = VerifySpecLoader.parse(yaml);

        assertThat(reloaded.toolchain()).isEqualTo("maven");
        assertThat(reloaded.compile()).isEqualTo(detection.proposed().compile());
        assertThat(reloaded.acceptance()).isEqualTo(detection.proposed().acceptance());
        assertThat(reloaded.existing()).isEqualTo(detection.proposed().existing());
        assertThat(reloaded.existingReportDirs())
            .isEqualTo(detection.proposed().existingReportDirs());
        assertThat(reloaded.effectiveTimeoutSeconds())
            .isEqualTo(detection.proposed().effectiveTimeoutSeconds());
        // The comments are the point of hand-rendering it: this file is read by a person deciding
        // whether these are the right commands.
        assertThat(yaml).contains("# Detected from:");
        assertThat(yaml).contains("multi-module");
    }

    @Test
    void aWrittenContractIsLoadableFromTheTrustedRootExactlyAsTheSwarmWouldLoadIt()
            throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);
        Path spec = root.resolve(VerifySpecLoader.SPEC_PATH);
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, ToolchainDetector.render(detection));

        // The trusted root is the operator's checkout; the workspace is a worker's worktree that
        // has no contract of its own. This is the §13.1 boundary as the swarm actually asks it.
        Path worktree = Files.createTempDirectory("worktree");
        assertThat(VerifySpecLoader.loadTrusted(root, worktree)).isPresent();
    }
}
