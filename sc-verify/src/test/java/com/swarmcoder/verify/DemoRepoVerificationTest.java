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
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of a real repository with a checked-in {@code .swarmcoder/verify.yaml}
 * — the full pipeline against a real toolchain, opt-in because it runs Maven:
 * {@code mvn test -pl sc-verify -Dswarmcoder.demo.repo=<absolute path to dev/demo-repo>}.
 */
class DemoRepoVerificationTest {

    @Test
    @RunsWhen(Need.DEMO_REPO)
    void demoRepoPassesFullVerification() {
        Path repo = Path.of(System.getProperty("swarmcoder.demo.repo"));

        Optional<VerifySpec> spec = VerifySpecLoader.load(repo);
        assertThat(spec).as(".swarmcoder/verify.yaml must exist in " + repo).isPresent();

        VerificationReport report = new CommandPipelineVerifier()
            .verify(new LocalProcessExecTarget(repo), null, spec.get());

        assertThat(report.compiles()).as("compile stage; log:\n" + report.logTail()).isTrue();
        assertThat(report.existing().passed()).as("existing tests should run and pass").isPositive();
        assertThat(report.existing().failed()).isZero();
        assertThat(Verdicts.survived(report)).isTrue();
    }
}
