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
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the reachability gate against a REAL repository on disk, so its layout reading is proved
 * against real build files rather than fixtures:
 * {@code mvn test -pl sc-verify -Dtest=RealRepoReachabilityTest -Dswarmcoder.demo.repo=<path>}.
 *
 * <p>Opt-in for the same reason {@link DemoRepoVerificationTest} is: the repository it wants is not
 * in this checkout ({@code dev/bookshelf-demo} is a separate git repository and is gitignored here).
 *
 * <p>What it asserts is deliberately layout-agnostic, because it does not know which repository it
 * has been pointed at: whatever that repository's real source roots are, a Java file dropped at a
 * root-level {@code src/main/java} that is not one of them must be rejected, and a Java file placed
 * inside one of them must be accepted. Against {@code dev/bookshelf-demo} that is precisely run
 * 9bd237ff's six files versus the same work inside {@code bookshelf-demo-shared}.
 */
class RealRepoReachabilityTest {

    @Test
    @RunsWhen(Need.DEMO_REPO)
    void aFileOutsideEveryRealSourceRootIsRejectedAndOneInsideIsNot() {
        Path repo = Path.of(System.getProperty("swarmcoder.demo.repo"));
        LocalProcessExecTarget target = new LocalProcessExecTarget(repo);

        Optional<VerifySpec> spec = VerifySpecLoader.load(repo);
        String toolchain = spec.map(VerifySpec::toolchain).orElse("maven");

        BuildLayout.Layout layout = BuildLayout.read(target, toolchain);
        assertThat(layout.determined())
            .as("the layout of " + repo + " must be readable: " + layout.note()).isTrue();

        String realRoot = layout.sourceRoots().stream()
            .filter(r -> r.endsWith("src/main/java"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Java source root found in " + repo));

        // Exactly what run 9bd237ff wrote: a conventional-looking source root at the repository
        // root, plus a web asset beside it.
        BuildReachability orphaned = BuildReachabilityCheck.check(target, toolchain,
            Set.of("src/main/java/com/zeroz4j/bookstore/domain/Book.java",
                "src/main/java/com/zeroz4j/bookstore/ui/AppShell.java",
                "src/main/webapp/bookstore.css"));

        if ("src/main/java".equals(realRoot)) {
            // A single-module repository: the root IS the source root, so there is nothing to prove
            // here and the fixture tests carry the case instead.
            assertThat(orphaned.status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
            return;
        }
        assertThat(orphaned.status())
            .as("root-level src/main/java is not a source root of " + repo + "; roots: "
                + layout.sourceRoots())
            .isEqualTo(BuildReachabilityStatus.ORPHANED);
        assertThat(orphaned.orphanFiles()).hasSize(3);
        assertThat(Verdicts.assess(reportWith(orphaned), List.of()).survived()).isFalse();

        BuildReachability reachable = BuildReachabilityCheck.check(target, toolchain,
            Set.of(realRoot + "/com/swarmcoder/demo/bookshelf/model/Book.java"));
        assertThat(reachable.status()).isEqualTo(BuildReachabilityStatus.REACHABLE);
        assertThat(Verdicts.assess(reportWith(reachable), List.of()).survived()).isTrue();
    }

    private static com.swarmcoder.domain.VerificationReport reportWith(BuildReachability reachability) {
        com.swarmcoder.domain.VerificationReport report =
            new com.swarmcoder.domain.VerificationReport(java.util.UUID.randomUUID(), true, true,
                null, null, null, null, java.time.Duration.ZERO, "", null);
        report.setBuildReachability(reachability);
        return report;
    }
}
