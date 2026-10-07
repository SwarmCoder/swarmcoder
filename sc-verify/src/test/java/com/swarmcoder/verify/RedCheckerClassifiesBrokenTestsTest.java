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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedChecker#check(ExecTarget, VerifySpec, List)}: a compile-failure red state is only
 * healthy when every missing symbol is something the plan's own tasks will deliver — see harness
 * run 26, quoted on {@code com.swarmcoder.workflow.BrokenAcceptanceTest}.
 */
class RedCheckerClassifiesBrokenTestsTest {

    private static VerifySpec spec() {
        return new VerifySpec("gradle", null, List.of("gradlew acceptanceTest"), null, null,
            null, null, 60, null);
    }

    private static Task task(String title, Set<String> writeSet, List<ApiContract> contracts) {
        Task t = new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(), List.of(),
            "bookshelf-demo-server/src/test/java/swarm", null, null,
            new com.swarmcoder.domain.SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
        t.setDeliveredContracts(contracts);
        return t;
    }

    @Test
    void aMissingContractTypeIsRedNotBroken() {
        FakeExecTarget target = new FakeExecTarget().scriptOutput("gradlew acceptanceTest", 1,
            "RatingTest.java:5: error: cannot find symbol\n"
            + "  symbol:   class Rating\n"
            + "  location: package com.demo.shared");
        Task server = task("Implement the server", Set.of("src/main/java/com/demo/server"),
            List.of(new ApiContract(UUID.randomUUID(), "Rating", "", "",
                "com.demo.shared.Rating", List.of())));

        RedChecker.RedCheckResult result = new RedChecker().check(target, spec(), List.of(server));

        assertThat(result.red()).isTrue();
        assertThat(result.broken()).as("a contract type is a healthy TDD miss, not broken").isFalse();
        assertThat(result.brokenTypes()).isEmpty();
    }

    @Test
    void aClientOnlyPackageOutsideEveryWriteSetIsBroken() {
        // The run-26 shape: an acceptance test in the server module imports a package that exists
        // only in the client module — no task here writes it and no contract names a type in it.
        FakeExecTarget target = new FakeExecTarget().scriptOutput("gradlew acceptanceTest", 1,
            "UiPresentationTest.java:5: error: package com.zeroz4j.ui does not exist");
        Task server = task("Implement the server", Set.of("src/main/java/com/demo/server"),
            List.of());

        RedChecker.RedCheckResult result = new RedChecker().check(target, spec(), List.of(server));

        assertThat(result.red()).as("the compiler failure is still a compile-fail red state").isTrue();
        assertThat(result.broken()).as("nobody delivers or can reach com.zeroz4j.ui").isTrue();
        assertThat(result.brokenTypes()).containsExactly("com.zeroz4j.ui");
    }

    @Test
    void aTypoedSymbolNotInAnyContractIsBroken() {
        FakeExecTarget target = new FakeExecTarget().scriptOutput("gradlew acceptanceTest", 1,
            "BookTest.java:5: error: cannot find symbol\n"
            + "  symbol:   class Bookk\n"
            + "  location: package com.demo.shared");
        Task server = task("Implement the server", Set.of("src/main/java/com/demo/server"),
            List.of(new ApiContract(UUID.randomUUID(), "Book", "", "",
                "com.demo.shared.Book", List.of())));

        RedChecker.RedCheckResult result = new RedChecker().check(target, spec(), List.of(server));

        assertThat(result.broken()).as("Bookk is a typo, not the delivered Book contract").isTrue();
        assertThat(result.brokenTypes()).containsExactly("com.demo.shared.Bookk");
    }

    @Test
    void thePlainTwoArgumentCheckNeverClassifies() {
        FakeExecTarget target = new FakeExecTarget().scriptOutput("gradlew acceptanceTest", 1,
            "UiPresentationTest.java:5: error: package com.zeroz4j.ui does not exist");

        RedChecker.RedCheckResult result = new RedChecker().check(target, spec());

        assertThat(result.red()).isTrue();
        assertThat(result.broken()).as("no plan was given, so nothing is called broken").isFalse();
    }
}
