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
package com.swarmcoder.app;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registering a repository: what {@link TargetRepository} writes, what it refuses to write, and
 * what it reports either way.
 *
 * <p>Against the small demo repository rather than jsoup, because these are questions about
 * {@link TargetRepository}'s own behaviour and a 30,000-line third-party tree would only make them
 * slower to answer. {@link BrownfieldLoopTest} is where it meets a real target.
 */
@RunsWhen(Need.MAVEN)
@ModelCodeOnThisPc
class TargetRepositoryTest {

    @TempDir
    Path work;

    @Test
    void registeringARepositoryDetectsProbesWritesAndCommitsAContractThatLoadsBack()
            throws Exception {
        Path repo = DemoRepo.create(work.resolve("target"));
        Files.deleteIfExists(repo.resolve(VerifySpecLoader.SPEC_PATH));

        TargetRepository.Registration registration = TargetRepository.register(repo, 600, true);
        System.out.println("[TARGET] " + registration.describe());

        assertThat(registration.detection().recognised()).isTrue();
        assertThat(registration.detection().toolchain()).isEqualTo("maven");
        assertThat(registration.probe().compiles())
            .as("the proposed compile command must really build this project:\n"
                + registration.probe().logTail())
            .isTrue();
        assertThat(registration.ready()).isTrue();
        assertThat(registration.contractWritten()).isTrue();
        assertThat(registration.contractCommit()).matches("[0-9a-f]{7,40}");

        // Committed, not merely written: a worker's worktree is cut from a commit, and a contract
        // that is only in the working tree is in no worktree at all.
        assertThat(Files.isRegularFile(repo.resolve(VerifySpecLoader.SPEC_PATH))).isTrue();
        String status = BookshelfFixture.git(repo, "status --porcelain "
            + VerifySpecLoader.SPEC_PATH).strip();
        assertThat(status)
            .as("the contract must be committed, not left as a working-tree change")
            .isEmpty();

        // Read back across the boundary the orchestrator actually asks across.
        Path notTheOperatorsTree = Files.createDirectories(work.resolve("pretend-worktree"));
        VerifySpec loaded =
            VerifySpecLoader.loadTrusted(repo, notTheOperatorsTree).orElseThrow();
        assertThat(loaded.compile()).isEqualTo(registration.spec().compile());
        assertThat(loaded.existing()).isNotEmpty();
    }

    /**
     * A correction replaces the detected existing-test command and writes its reason into the
     * contract's own comments — the harness standing in for the person at the console screen.
     */
    @Test
    void aCorrectedExistingCommandIsWhatGetsCommittedAndTheReasonIsCommittedWithIt()
            throws Exception {
        Path repo = DemoRepo.create(work.resolve("corrected"));
        Files.deleteIfExists(repo.resolve(VerifySpecLoader.SPEC_PATH));
        TargetRepository.Correction correction = new TargetRepository.Correction(
            java.util.List.of("mvn -B test \"-Dtest=!org/example/flaky/**\""),
            "those tests need a network the verifier does not have");

        TargetRepository.Registration registration =
            TargetRepository.register(repo, 600, true, correction);

        assertThat(registration.ready()).isTrue();
        assertThat(registration.spec().existing())
            .containsExactly("mvn -B test \"-Dtest=!org/example/flaky/**\"");
        assertThat(registration.verdict()).contains("network the verifier does not have");

        // The renderer wraps long warnings across commented lines, so the file is read the way a
        // person reads it — comment markers stripped, whitespace collapsed — rather than as one
        // string that happens to have newlines in the middle of the sentence.
        String written = Files.readString(repo.resolve(VerifySpecLoader.SPEC_PATH));
        String asAPersonReadsIt = written.replaceAll("(?m)^#\\s*-?\\s*", " ")
            .replaceAll("\\s+", " ");
        assertThat(asAPersonReadsIt)
            .as("the reason must survive into the file, because a gate narrowed for reasons "
                + "nobody can see is how a suite stops gating")
            .contains("network the verifier does not have")
            .contains("NOT the one that was detected");

        Path notTheOperatorsTree = Files.createDirectories(work.resolve("elsewhere"));
        VerifySpec loaded = VerifySpecLoader.loadTrusted(repo, notTheOperatorsTree).orElseThrow();
        assertThat(loaded.existing())
            .as("the corrected command has to survive the render/parse round trip, or the run "
                + "would use something nobody reviewed")
            .containsExactly("mvn -B test \"-Dtest=!org/example/flaky/**\"");
    }

    @Test
    void aCorrectionWithNothingInItLeavesTheDetectedContractExactlyAsProposed() throws Exception {
        Path repo = DemoRepo.create(work.resolve("uncorrected"));
        Files.deleteIfExists(repo.resolve(VerifySpecLoader.SPEC_PATH));

        TargetRepository.Registration plain = TargetRepository.register(repo, 600, true);
        java.util.List<String> asDetected = plain.spec().existing();

        Path second = DemoRepo.create(work.resolve("uncorrected-2"));
        Files.deleteIfExists(second.resolve(VerifySpecLoader.SPEC_PATH));
        TargetRepository.Registration empty = TargetRepository.register(second, 600, true,
            new TargetRepository.Correction(java.util.List.of(), "nothing to change"));

        assertThat(empty.spec().existing()).isEqualTo(asDetected);
        assertThat(Files.readString(second.resolve(VerifySpecLoader.SPEC_PATH)))
            .doesNotContain("NOT the one that was detected");
    }

    @Test
    void aRepositoryThatAlreadyHasAContractKeepsItUnlessTheCallerSaysOtherwise() throws Exception {
        Path repo = DemoRepo.create(work.resolve("keeps-its-own"));
        Path contract = repo.resolve(VerifySpecLoader.SPEC_PATH);
        Files.createDirectories(contract.getParent());
        Files.writeString(contract, "toolchain: maven\ncompile:\n  - \"echo corrected by hand\"\n");

        TargetRepository.Registration registration = TargetRepository.register(repo, 600, false);

        assertThat(registration.contractWritten()).isFalse();
        assertThat(registration.verdict()).contains("already has a verification contract");
        assertThat(Files.readString(contract))
            .as("a contract corrected by hand is the one that has been thought about")
            .contains("corrected by hand");
    }

    @Test
    void aFolderWithNoBuildFileGetsNoGuess() {
        Path empty = work.resolve("nothing-here");

        TargetRepository.Registration registration = TargetRepository.register(empty, 60, true);

        assertThat(registration.detection().recognised()).isFalse();
        assertThat(registration.contractWritten()).isFalse();
        assertThat(registration.ready()).isFalse();
        assertThat(registration.spec()).isNull();
        assertThat(registration.verdict()).contains("nothing was proposed rather than guessed");
    }

    @Test
    void theToolchainReportsTheJdkThisRunsOnAndTheBuildToolTheRepositoryAnswersWith()
            throws Exception {
        Path repo = DemoRepo.create(work.resolve("versions"));

        TargetRepository.Toolchain toolchain = TargetRepository.toolchain(repo, "maven");
        System.out.println("[TARGET] " + toolchain.describe());

        assertThat(toolchain.javaVersion()).isNotBlank();
        assertThat(toolchain.buildToolRan())
            .as("this test is gated on Maven being present, so mvn -v must answer")
            .isTrue();
        assertThat(toolchain.buildTool()).containsIgnoringCase("maven");
        assertThat(toolchain.javaAtLeast(11))
            .as("this build itself needs a modern JDK, so the check must agree")
            .isTrue();
    }

    @Test
    void aContractNamingNoExistingTestsRunsNothingAndSaysSoRatherThanReportingGreen() {
        VerifySpec noSuite = new VerifySpec("maven", java.util.List.of("mvn -B -q test-compile"),
            null, null, null, null, null, 0, null);

        TargetRepository.SuiteRun suite = TargetRepository.runSuite(work, noSuite, 60);

        assertThat(suite.ran()).isFalse();
        assertThat(suite.green())
            .as("nothing ran, so nothing may be reported green — that is the failure the whole "
                + "harness exists to catch")
            .isFalse();
        assertThat(suite.greenAndNotEmpty()).isFalse();
        assertThat(suite.executed()).isZero();
        assertThat(suite.describe()).contains("no existing-test command");
    }

    @Test
    void aSuiteThatExitsZeroButExecutedNothingIsNotTreatedAsGreen() {
        // The exact shape of Verdicts' oldest bug: a command that selects nothing exits 0 and reads
        // like a suite that ran and passed. greenAndNotEmpty is what separates them.
        TargetRepository.SuiteRun exitedZeroRanNothing = new TargetRepository.SuiteRun(
            true, true, java.util.List.of("mvn -B test"), 0, false,
            java.time.Duration.ofSeconds(3), null, "");

        assertThat(exitedZeroRanNothing.green()).isTrue();
        assertThat(exitedZeroRanNothing.executed()).isZero();
        assertThat(exitedZeroRanNothing.greenAndNotEmpty()).isFalse();
        assertThat(exitedZeroRanNothing.describe())
            .contains("no JUnit reports were found");
    }

    @Test
    void aGreenSuiteThatReallyRanTestsIsTreatedAsGreen() {
        com.swarmcoder.domain.TestResults results =
            new com.swarmcoder.domain.TestResults(1647, 0, 0, 3, java.util.List.of());
        TargetRepository.SuiteRun real = new TargetRepository.SuiteRun(
            true, true, java.util.List.of("mvn -B test"), 0, false,
            java.time.Duration.ofSeconds(180), results, "");

        assertThat(real.executed()).isEqualTo(1647);
        assertThat(real.greenAndNotEmpty()).isTrue();
        assertThat(real.describe()).contains("1647 tests executed").contains("180s");
    }
}
