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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.verify.Verdicts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 25, 02:54: two candidates of an enabler task each failed verification, and the log
 * read
 * <pre>
 * Candidate 9f6388a6... FAILED verification:
 * </pre>
 * with nothing after the colon. The candidates had a real, legitimate reason to fail — the task
 * promised three types, each with a {@code @DataModel} member, and neither candidate delivered the
 * annotation on any of them — but {@link SwarmEngineImpl#contractShortfall} built the sentence
 * wrong: for two or more missing contracts it prepended {@code "\n  - "} before {@link
 * com.swarmcoder.knowledge.ContractDelivery#describe}, which already opens every item after the
 * first with its own {@code "\n  - "} and leaves the first one bare. The extra newline landed in
 * front of the first item, so the reason's FIRST LINE — all a scrolling console log or a hover card
 * ever shows — was empty. Two prior runs never hit this because contractShortfall had only ever
 * been asked about a single missing contract before.
 *
 * <p>This class pins two things: the direct fix (a task with two or more missing contracts never
 * produces a reason whose first line is blank), and the backstop ({@link
 * SwarmEngineImpl#survivalReason}) that keeps ANY future path from going silent the same way, by
 * naming the bug and the report's own counts instead of nothing.
 */
class AVerdictIsNeverBlankTest {

    @TempDir
    Path tree;

    // --- the direct fix: contractShortfall's own formatting -----------------------------------

    @Test
    void twoMissingContractsNeverProduceABlankFirstLine() throws IOException {
        ApiContract book = new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf", "Book",
            "com.x.Book", List.of("int rating"));
        ApiContract rating = new ApiContract(UUID.randomUUID(), "Rating", "a book's rating", "Rating",
            "com.x.Rating", List.of("int stars"));
        Task enabler = task("Deliver Book and Rating", Set.of("src/main/java/com/x"));
        enabler.setDeliveredContracts(List.of(book, rating));

        Files.createDirectories(tree.resolve("src/main/java/com/x"));
        // Both types exist, and both are missing the member the contract promised.
        Files.writeString(tree.resolve("src/main/java/com/x/Book.java"),
            "package com.x;\npublic class Book { public String title; }\n");
        Files.writeString(tree.resolve("src/main/java/com/x/Rating.java"),
            "package com.x;\npublic class Rating { public String comment; }\n");

        String reason = SwarmEngineImpl.contractShortfall(enabler, tree);

        assertThat(reason).isNotNull();
        String firstLine = reason.lines().findFirst().orElse("");
        assertThat(firstLine)
            .as("the first line of a multi-shortfall reason must carry the substance, not a blank "
                + "line left over from a leading newline")
            .isNotBlank()
            .contains("the contract `com.x.Book` was delivered without");
        assertThat(reason).contains("the contract `com.x.Rating` was delivered without")
            .contains("cannot be corrected later");
    }

    @Test
    void oneMissingContractIsUnaffected() throws IOException {
        ApiContract book = new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf", "Book",
            "com.x.Book", List.of("int rating"));
        Task enabler = task("Deliver Book", Set.of("src/main/java/com/x"));
        enabler.setDeliveredContracts(List.of(book));

        Files.createDirectories(tree.resolve("src/main/java/com/x"));
        Files.writeString(tree.resolve("src/main/java/com/x/Book.java"),
            "package com.x;\npublic class Book { public String title; }\n");

        String reason = SwarmEngineImpl.contractShortfall(enabler, tree);

        assertThat(reason).isNotNull();
        assertThat(reason.lines().findFirst().orElse(""))
            .contains("the contract `com.x.Book` was delivered without");
    }

    // --- the backstop: survivalReason never lets a FAILED verdict carry nothing ----------------

    @Test
    void survivalReasonPassesThroughARealReasonUnchanged() {
        Verdicts.Verdict verdict = new Verdicts.Verdict(false, "the candidate does not compile");
        VerificationReport report = report(null);

        String reason = SwarmEngineImpl.survivalReason(verdict, report, List.of());

        assertThat(reason).isEqualTo("the candidate does not compile");
    }

    @Test
    void survivalReasonReturnsNullWhenTheCandidateSurvived() {
        Verdicts.Verdict verdict = new Verdicts.Verdict(true, null);

        assertThat(SwarmEngineImpl.survivalReason(verdict, report(null), List.of())).isNull();
    }

    @Test
    void survivalReasonFillsInWhenTheReasonIsNull() {
        Verdicts.Verdict verdict = new Verdicts.Verdict(false, null);
        TestResults acceptance = new TestResults(0, 0, 0, 0, List.of())
            .withStageOutcome(TestStageOutcome.EXECUTED);
        VerificationReport report = report(acceptance);

        String reason = SwarmEngineImpl.survivalReason(verdict, report, List.of("R1:C1"));

        assertThat(reason)
            .contains("verification failed without a recorded reason")
            .contains("this is a bug in SwarmCoder")
            .contains("compiles=true")
            .contains("acceptance=0p/0f/0e")
            .contains("claimedChecks=1");
    }

    @Test
    void survivalReasonFillsInWhenTheReasonIsBlank() {
        Verdicts.Verdict verdict = new Verdicts.Verdict(false, "   ");

        String reason = SwarmEngineImpl.survivalReason(verdict, report(null), List.of());

        assertThat(reason).contains("verification failed without a recorded reason");
    }

    // --- fixtures -------------------------------------------------------------------------------

    private static Task task(String title, Set<String> writeSet) {
        Task t = new Task(UUID.randomUUID(), 1, title, "Create it.", writeSet, Set.of(),
            List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.READY);
        t.setAuthoredTestPaths(List.of());
        return t;
    }

    private static VerificationReport report(TestResults acceptance) {
        return new VerificationReport(UUID.randomUUID(), true, true, acceptance, null, null, null,
            Duration.ZERO, "", null);
    }
}
