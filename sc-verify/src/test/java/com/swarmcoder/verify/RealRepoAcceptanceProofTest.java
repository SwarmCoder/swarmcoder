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

import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the acceptance stage against a REAL repository on disk, and counts the tests it RAN:
 * {@code mvn test -pl sc-verify -Dtest=RealRepoAcceptanceProofTest -Dswarmcoder.demo.repo=<path>}.
 *
 * <p>Opt-in for the same reason {@link DemoRepoVerificationTest} is: {@code dev/bookshelf-demo} is
 * a separate git repository and is gitignored in this checkout.
 *
 * <p>It writes one acceptance test into the repository at the resolved location and deletes it
 * again, because the whole question is whether the build executes a test written there. Exit
 * status cannot answer it: the defect being fixed is a command that exits 0 having run nothing.
 */
class RealRepoAcceptanceProofTest {

    @Test
    @RunsWhen(Need.DEMO_REPO)
    void aTestWrittenWhereTheDetectorSaysIsExecutedByTheContractsAcceptanceCommand() throws Exception {
        Path repo = Path.of(System.getProperty("swarmcoder.demo.repo"));
        Optional<VerifySpec> spec = VerifySpecLoader.load(repo);
        assertThat(spec).as(".swarmcoder/verify.yaml must exist in " + repo).isPresent();
        String toolchain = spec.get().toolchain();

        BuildLayout.Layout layout = BuildLayout.read(repo, toolchain);
        AcceptanceTestLocation.Location location = AcceptanceTestLocation.resolve(layout);

        // Whatever repository this is pointed at, the acceptance tests must land in a directory
        // the build compiles — never beside an aggregator pom that compiles nothing.
        assertThat(layout.compilingModules())
            .as("the chosen module must be one the build actually compiles: " + location.note())
            .contains(location.module());
        assertThat(layout.sourceRoots())
            .anySatisfy(root -> assertThat(location.writeDir()).startsWith(root));

        Path file = repo.resolve(location.writeDir()).resolve("SwarmCoderProofTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package swarm.accept;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            class SwarmCoderProofTest {
                @Test
                void theAcceptanceStageCanActuallyRunATestWrittenHere() {
                    assertEquals(2, 1 + 1);
                }
            }
            """);
        try {
            VerificationReport report = new CommandPipelineVerifier()
                .verify(new LocalProcessExecTarget(repo), null, spec.get());

            assertThat(report.compiles()).as("compile stage; log:\n" + report.logTail()).isTrue();
            assertThat(report.acceptance().passed())
                .as("the acceptance command must EXECUTE the test, not merely exit 0. Log:\n"
                    + report.logTail())
                .isEqualTo(1);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
