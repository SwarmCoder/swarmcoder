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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 88 delivered its story and the chain broke on an earlier story's kept acceptance test
 * (DEVELOPER_CORRECTIONS section 60). The paths below are that run's.
 */
class AcceptanceFilesLinkTest {

    private static final String DIR = "hambook-server/src/test/java/swarm/accept";
    private static final String EARLIER = DIR + "/LogbookTest.java";
    private static final String OURS = DIR + "/UsabilityTest.java";

    @Test
    void anEarlierStorysTestInTheBaseCommitIsNeitherTheRunsNorALeftover() {
        AcceptanceFilesLink.Verdict verdict = AcceptanceFilesLink.evaluate(DIR,
            List.of(EARLIER), List.of(EARLIER, OURS), List.of(OURS), List.of(EARLIER), List.of());

        assertThat(verdict.held()).isTrue();
        assertThat(verdict.authored()).containsExactly(OURS);
        assertThat(verdict.kept()).containsExactly(EARLIER);
        assertThat(verdict.leftInCheckout()).isEmpty();
    }

    @Test
    void aTestFileTheRunWroteIntoTheCheckoutOutsideItsTestsCommitStillBreaksTheLink() {
        AcceptanceFilesLink.Verdict verdict = AcceptanceFilesLink.evaluate(DIR,
            List.of(EARLIER), List.of(EARLIER, OURS), List.of(OURS),
            List.of(EARLIER, DIR + "/StrayTest.java"), List.of());

        assertThat(verdict.held()).isFalse();
        assertThat(verdict.leftInCheckout()).containsExactly(DIR + "/StrayTest.java");
    }

    @Test
    void anEarlierTestChangedOnDiskIsALeftoverAndChangedInTheTestsCommitIsTheRuns() {
        AcceptanceFilesLink.Verdict onDisk = AcceptanceFilesLink.evaluate(DIR,
            List.of(EARLIER), List.of(EARLIER, OURS), List.of(OURS), List.of(EARLIER),
            List.of(EARLIER));
        assertThat(onDisk.held()).isFalse();
        assertThat(onDisk.leftInCheckout()).containsExactly(EARLIER);

        // The author added its methods to the earlier class: that file is this run's test.
        AcceptanceFilesLink.Verdict inCommit = AcceptanceFilesLink.evaluate(DIR,
            List.of(EARLIER), List.of(EARLIER), List.of(EARLIER), List.of(EARLIER), List.of());
        assertThat(inCommit.held()).isTrue();
        assertThat(inCommit.authored()).containsExactly(EARLIER);
        assertThat(inCommit.kept()).isEmpty();
    }

    @Test
    void aRunThatWroteNoTestIsNotCarriedByTheEarlierOnes() {
        AcceptanceFilesLink.Verdict verdict = AcceptanceFilesLink.evaluate(DIR,
            List.of(EARLIER), List.of(EARLIER), List.of(), List.of(EARLIER), List.of());

        assertThat(verdict.held()).isFalse();
        assertThat(verdict.authored()).isEmpty();
    }

    @Test
    void aTestTheRunCommittedWhereNothingCompilesItIsAStray() {
        String orphan = "src/test/java/swarm/accept/UsabilityTest.java";
        AcceptanceFilesLink.Verdict verdict = AcceptanceFilesLink.evaluate(DIR,
            List.of(EARLIER), List.of(EARLIER, OURS, orphan), List.of(OURS, orphan),
            List.of(EARLIER), List.of());

        assertThat(verdict.held()).isFalse();
        assertThat(verdict.strays()).containsExactly(orphan);
    }

    @Test
    void earlierTestsAreCountedByTheirTestMethods() {
        assertThat(AcceptanceFilesLink.testMethodsIn("""
            class LogbookTest {
                @Test
                void a() {}
                @ParameterizedTest
                void b(int n) {}
                void helper() {}
            }
            """)).isEqualTo(2);
    }

    /**
     * Live run 89: the story's 3 tests ran and passed in each candidate's acceptance stage, the
     * base commit held 5 tests of earlier stories, and link 10 asked for more than 5.
     */
    @Test
    void theRunsOwnExecutedTestsAreCountedWhateverTheBaseCommitHolds() {
        String ours = DIR + "/LogbookFilterTest.java";
        com.swarmcoder.domain.TestResults passed = new com.swarmcoder.domain.TestResults(3, 0, 0,
            0, List.of(), List.of("swarm.accept.LogbookFilterTest#filterAreaShowsOnlyFilterFields",
                "swarm.accept.LogbookFilterTest#nonFilterBoxesAreNotInputFields",
                "swarm.accept.LogbookFilterTest#existingActionsStillWork"), List.of(), false);
        assertThat(AcceptanceFilesLink.ownTestsExecuted(passed, List.of(ours))).isEqualTo(3);

        // Only an earlier story's tests ran: nothing of the run's was executed.
        com.swarmcoder.domain.TestResults earlier = new com.swarmcoder.domain.TestResults(2, 0, 0,
            0, List.of(), List.of("swarm.accept.LogbookTest#a", "swarm.accept.LogbookTest#b"),
            List.of(), false);
        assertThat(AcceptanceFilesLink.ownTestsExecuted(earlier, List.of(ours))).isZero();

        // Results that name no test are taken as counted; none at all is none.
        assertThat(AcceptanceFilesLink.ownTestsExecuted(
            new com.swarmcoder.domain.TestResults(3, 0, 0, 0, List.of()), List.of(ours)))
            .isEqualTo(3);
        assertThat(AcceptanceFilesLink.ownTestsExecuted(null, List.of(ours))).isZero();
    }
}
