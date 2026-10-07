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

import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.BrowserStageOutcome;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two halves of the gate, and the line between them.
 *
 * <p>"The application failed to start" must kill the candidate — that is the whole reason the
 * browser stage exists, and until now nothing in verification had ever started an application at
 * all. "The harness could not try" must kill nobody, because it is not evidence about the candidate:
 * a machine with no headless browser, an execution target with no background-process support, a
 * fixed port somebody else already holds. A hard gate that cannot be attempted would park every run,
 * which is exactly the failure this project can least afford.
 *
 * <p>Neither test needs a browser: both decide before any page is opened.
 */
class AppThatDoesNotStartFailsTest {

    @TempDir
    Path workspace;

    private static final VerifySpec.BrowserSpec SPEC = new VerifySpec.BrowserSpec(
        // A command that exits at once instead of serving: exactly what a candidate whose
        // application cannot boot looks like from here.
        "java -version", "http://localhost:{PORT}/", 5,
        List.of(new VerifySpec.PageCheckSpec("/", true, List.of("#app-root"), false)), 0);

    @Test
    void anApplicationThatNeverServesAnythingDoesNotSurvive() {
        StringBuilder log = new StringBuilder();

        BrowserCheckResults results = new BrowserVerifier(BlobSink.NONE)
            .run(new LocalProcessExecTarget(workspace), SPEC, log);

        assertThat(results.stageOutcome()).isEqualTo(BrowserStageOutcome.EXECUTED);
        assertThat(results.couldNotTry()).isFalse();
        assertThat(results.checks()).hasSize(1);
        assertThat(results.checks().get(0).loaded()).isFalse();
        assertThat(log.toString()).contains("never became ready");

        VerificationReport report = reportWith(results);
        Verdicts.Verdict verdict = Verdicts.assess(report, List.of());
        assertThat(verdict.survived())
            .as("an application that does not start has delivered nothing")
            .isFalse();
        assertThat(verdict.reason()).contains("did not serve /").contains("does not run");
    }

    @Test
    void aHarnessThatCouldNotTryFailsNobody() {
        StringBuilder log = new StringBuilder();
        // FakeExecTarget cannot start a background process at all. A sandbox target reaches the
        // same verdict by a different route: it CAN start the service, but the container publishes
        // no ports, so this process could never connect to it — which BrowserVerifier asks about
        // up front through ExecTarget.hostCannotReachServices().
        BrowserCheckResults results = new BrowserVerifier(BlobSink.NONE)
            .run(new FakeExecTarget(), SPEC, log);

        assertThat(results.stageOutcome()).isEqualTo(BrowserStageOutcome.COULD_NOT_TRY);
        assertThat(results.couldNotTryReason()).contains("does not run services");
        assertThat(log.toString()).contains("NOT RUN").contains("does not fail it");

        VerificationReport report = reportWith(results);
        Verdicts.Verdict verdict = Verdicts.assess(report, List.of());
        assertThat(verdict.survived())
            .as("an absent instrument is not evidence, so it must not fail a candidate")
            .isTrue();
    }

    /**
     * The regression this stops is one the sandbox path only became capable of once it could start
     * background services at all.
     *
     * <p>Before, a sandbox target refused to start anything, so the stage recorded "could not try"
     * and nobody was hurt. Now it starts the application for real — and the readiness probe still
     * runs in the orchestrator's process, which a {@code network: none} container publishes no port
     * for. Left alone, that produces the precise evidence that means "this candidate's application
     * does not run", from a cause that is entirely the harness's. The candidate would die for it.
     * So the stage asks the target first, and declines.
     */
    @Test
    void aTargetThisProcessCannotReachFailsNobody() {
        StringBuilder log = new StringBuilder();

        BrowserCheckResults results = new BrowserVerifier(BlobSink.NONE)
            .run(new UnreachableFromHere(new LocalProcessExecTarget(workspace)), SPEC, log);

        assertThat(results.stageOutcome()).isEqualTo(BrowserStageOutcome.COULD_NOT_TRY);
        assertThat(results.couldNotTryReason()).contains("publishes no ports");
        assertThat(log.toString())
            .as("and it must decline BEFORE launching the application")
            .contains("NOT RUN")
            .doesNotContain("never became ready");

        VerificationReport report = reportWith(results);
        assertThat(Verdicts.assess(report, List.of()).survived())
            .as("the harness's own blindness is not evidence about the candidate")
            .isTrue();
    }

    /** A perfectly working target whose services this process happens not to be able to open. */
    private record UnreachableFromHere(ExecTarget delegate) implements ExecTarget {

        @Override
        public Optional<String> hostCannotReachServices() {
            return Optional.of("this container publishes no ports");
        }

        @Override
        public ExecResult exec(String command, int timeoutSeconds) throws IOException {
            return delegate.exec(command, timeoutSeconds);
        }

        @Override
        public String readFile(String relativePath, int maxBytes) throws IOException {
            return delegate.readFile(relativePath, maxBytes);
        }

        @Override
        public List<String> listFiles(String relativeDir, String suffix) throws IOException {
            return delegate.listFiles(relativeDir, suffix);
        }

        @Override
        public void deleteDir(String relativePath) throws IOException {
            delegate.deleteDir(relativePath);
        }

        @Override
        public ServiceHandle startService(String command) {
            throw new AssertionError("the stage must decline before it launches anything");
        }
    }

    @Test
    void aFixedPortSomebodyElseHoldsIsNotEvidenceEither() throws Exception {
        try (java.net.ServerSocket taken = new java.net.ServerSocket(0)) {
            VerifySpec.BrowserSpec fixed = new VerifySpec.BrowserSpec(
                "java -version", "http://localhost:{PORT}/", 5,
                List.of(new VerifySpec.PageCheckSpec("/", true, List.of("body"), false)),
                taken.getLocalPort());

            StringBuilder log = new StringBuilder();
            BrowserCheckResults results = new BrowserVerifier(BlobSink.NONE)
                .run(new LocalProcessExecTarget(workspace), fixed, log);

            assertThat(results.stageOutcome()).isEqualTo(BrowserStageOutcome.COULD_NOT_TRY);
            assertThat(results.couldNotTryReason()).contains("already in use");
            assertThat(Verdicts.assess(reportWith(results), List.of()).survived()).isTrue();
        }
    }

    /** A report that is clean everywhere except the browser stage, so only that decides. */
    private static VerificationReport reportWith(BrowserCheckResults browser) {
        return new VerificationReport(
            java.util.UUID.randomUUID(), true, true,
            new com.swarmcoder.domain.TestResults(1, 0, 0, 0, List.of()),
            new com.swarmcoder.domain.TestResults(1, 0, 0, 0, List.of()),
            null, browser, java.time.Duration.ofSeconds(1), "", null);
    }
}
