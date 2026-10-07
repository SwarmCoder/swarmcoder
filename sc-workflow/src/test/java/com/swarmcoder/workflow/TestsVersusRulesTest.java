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
package com.swarmcoder.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An acceptance test is read against the project's standing rules, sent back to its author once
 * when it breaks one, and stops the run only for a HARD rule (live harness runs 56 and 58,
 * 2026-10-01; see {@link TestsVersusRules}).
 *
 * <p>There a rule said a service is never constructed by hand in a test, the test constructed it
 * by hand, the red-check passed, and two workers spent 80 minutes on a test no rule-keeping
 * candidate could pass. No paid model call anywhere: both roles are pointed at {@link ScriptedLlm}.
 */
class TestsVersusRulesTest {

    private static final String TEST_PATH = "src/test/java/swarm/accept/EditOrderTest.java";
    private static final String RULE = "Tests get a service from the test server";
    private static final String OFFENDING_LINE = "OrderService orders = new OrderServiceImpl();";

    private static final String BY_HAND = """
        package swarm.accept;
        import org.junit.jupiter.api.Test;
        class EditOrderTest {
            @Test void edits() {
                OrderService orders = new OrderServiceImpl();
                orders.edit("1", "two");
            }
        }
        """;
    private static final String FROM_THE_SERVER = """
        package swarm.accept;
        import org.junit.jupiter.api.Test;
        class EditOrderTest {
            @Test void edits() throws Exception {
                try (TestServer server = TestServer.builder().beans(OrderServiceImpl.class).start()) {
                    server.bean(OrderService.class).edit("1", "two");
                }
            }
        }
        """;

    private static final String OBJECTION = "line '" + OFFENDING_LINE + "' in " + TEST_PATH
        + " conflicts with rule '" + RULE + "': the test constructs the service by hand";

    @TempDir
    Path repo;

    @Test
    void aTestThatBreaksARuleGoesBackOnceWithTheRuleAndTheLineAndTheCorrectionIsAccepted()
            throws Exception {
        String rules = rules(true);
        write(BY_HAND);
        Script script = new Script(List.of(objecting(), approving()), FROM_THE_SERVER);
        try (ScriptedLlm llm = new ScriptedLlm(script::answer)) {
            TestAuthorClient author = author(llm);
            author.setStandingRules(() -> rules);
            List<String> log = new CopyOnWriteArrayList<>();

            TestsVersusRules.Outcome outcome = TestsVersusRules.hold(reviewer(llm), author, repo,
                task(), null, List.of(TEST_PATH), rules, null, log::add);

            assertThat(outcome.parks()).isFalse();
            assertThat(outcome.corrected()).isTrue();
            assertThat(script.reviews.get()).as("reviewed, and reviewed again after the repair")
                .isEqualTo(2);
            assertThat(script.repairs).as("sent back exactly once").hasSize(1);
            assertThat(script.repairs.get(0))
                .as("the author is shown the offending line, the rule in full, and the rules")
                .contains(OFFENDING_LINE)
                .contains("THE RULE, IN FULL — '" + RULE + "'")
                .contains("its store is injected")
                .contains("HOW THIS PROJECT MUST BE BUILT");
            assertThat(script.reviewPrompts.get(0))
                .as("the reviewer reads the test as written, under the rules")
                .contains(OFFENDING_LINE).contains(RULE);
            assertThat(Files.readString(repo.resolve(TEST_PATH))).contains("server.bean(");
            assertThat(log).anyMatch(line -> line.contains("no longer conflict"));
        }
    }

    @Test
    void aTestThatStillBreaksAHardRuleAfterItsOneCorrectionParksTheRun() throws Exception {
        String rules = rules(true);
        write(BY_HAND);
        Script script = new Script(List.of(objecting(), objecting()), BY_HAND);
        try (ScriptedLlm llm = new ScriptedLlm(script::answer)) {
            TestsVersusRules.Outcome outcome = TestsVersusRules.hold(reviewer(llm), author(llm),
                repo, task(), null, List.of(TEST_PATH), rules, null, line -> { });

            assertThat(script.repairs).as("one attempt, never a second").hasSize(1);
            assertThat(outcome.parks()).isTrue();
            assertThat(outcome.parkBrief())
                .contains("Implement OrderServiceImpl")
                .contains(OFFENDING_LINE)
                .contains(RULE)
                .contains("its store is injected")
                .contains("asked once to correct them")
                .contains("reword the rule or make it a preference");
            assertThat(outcome.hard())
                .as("the objections themselves, for a run nobody is watching to carry as warnings")
                .singleElement().asString().contains(RULE);
        }
    }

    @Test
    void aPreferenceNeverStopsTheRun() throws Exception {
        String rules = rules(false);
        write(BY_HAND);
        Script script = new Script(List.of(objecting(), objecting()), BY_HAND);
        try (ScriptedLlm llm = new ScriptedLlm(script::answer)) {
            List<String> log = new CopyOnWriteArrayList<>();

            TestsVersusRules.Outcome outcome = TestsVersusRules.hold(reviewer(llm), author(llm),
                repo, task(), null, List.of(TEST_PATH), rules, null, log::add);

            assertThat(script.repairs).as("still sent back once").hasSize(1);
            assertThat(outcome.parks()).isFalse();
            assertThat(outcome.carried()).containsExactly(OBJECTION);
            assertThat(log).anyMatch(line -> line.contains("go against a preference"));
        }
    }

    @Test
    void anObjectionNamingARuleTheProjectDoesNotHaveIsIgnored() throws Exception {
        write(BY_HAND);
        Script script = new Script(List.of(
            "{\"approved\":false,\"objections\":[\"line 'x' in " + TEST_PATH
                + " conflicts with rule 'Tests use Mockito': it does not\"]}"), BY_HAND);
        try (ScriptedLlm llm = new ScriptedLlm(script::answer)) {
            TestsVersusRules.Outcome outcome = TestsVersusRules.hold(reviewer(llm), author(llm),
                repo, task(), null, List.of(TEST_PATH), rules(true), null, line -> { });

            assertThat(outcome).isEqualTo(TestsVersusRules.Outcome.CLEAN);
            assertThat(script.repairs).isEmpty();
        }
    }

    @Test
    void aCorrectionWrittenForAnotherFaultIsReadAgainstTheRulesAndGetsNoFurtherAttempt()
            throws Exception {
        write(BY_HAND);
        Script script = new Script(List.of(objecting()), BY_HAND);
        try (ScriptedLlm llm = new ScriptedLlm(script::answer)) {
            String hard = TestsVersusRules.afterReauthoring(reviewer(llm), repo, task(),
                List.of(TEST_PATH), rules(true), line -> { });
            String preference = TestsVersusRules.afterReauthoring(reviewer(llm), repo, task(),
                List.of(TEST_PATH), rules(false), line -> { });

            assertThat(hard).contains(RULE).contains("it has had its one attempt");
            assertThat(preference).as("a preference never stops the work").isNull();
            assertThat(script.repairs).isEmpty();
        }
    }

    @Test
    void noRulesOrAReviewerThatAnswersNonsenseBlocksNothing() throws Exception {
        write(BY_HAND);
        Script script = new Script(List.of("I decline to produce JSON."), BY_HAND);
        try (ScriptedLlm llm = new ScriptedLlm(script::answer)) {
            assertThat(TestsVersusRules.hold(reviewer(llm), author(llm), repo, task(), null,
                List.of(TEST_PATH), "", null, line -> { })).isEqualTo(TestsVersusRules.Outcome.CLEAN);
            assertThat(script.reviews.get()).as("no rules: no model call").isZero();

            assertThat(TestsVersusRules.hold(reviewer(llm), author(llm), repo, task(), null,
                List.of(TEST_PATH), rules(true), null, line -> { }).parks()).isFalse();
            assertThat(script.repairs).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Answers the reviewer from a list (the last answer repeats) and the repair with one file. */
    private static final class Script {
        private final List<String> reviewAnswers;
        private final String repairSource;
        final AtomicInteger reviews = new AtomicInteger();
        final List<String> reviewPrompts = new CopyOnWriteArrayList<>();
        final List<String> repairs = new CopyOnWriteArrayList<>();

        Script(List<String> reviewAnswers, String repairSource) {
            this.reviewAnswers = reviewAnswers;
            this.repairSource = repairSource;
        }

        String answer(String conversation) {
            if (conversation.contains("checking ACCEPTANCE TESTS against")) {
                reviewPrompts.add(conversation);
                int n = reviews.getAndIncrement();
                return reviewAnswers.get(Math.min(n, reviewAnswers.size() - 1));
            }
            if (conversation.contains("break this project's standing rules")) {
                repairs.add(conversation);
                return reply(repairSource);
            }
            return "I decline to produce JSON.";
        }
    }

    private static String objecting() {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                "approved", false, "objections", List.of(OBJECTION)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String approving() {
        return "{\"approved\":true,\"objections\":[]}";
    }

    private static String rules(boolean hard) {
        LearnedGuideline rule = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            "rule", "A service is never constructed by hand in a test: its store is injected, so "
                + "a hand-made one has no database. Start the test server and ask it for the "
                + "service.", new Provenance("stated", null, "technical.md"), 1.0,
            Instant.now(), 0, GuidelineStatus.ACTIVE, UUID.randomUUID(), null, 0);
        rule.setTitle(RULE);
        rule.setPurpose("so the service under test has its database");
        rule.setHard(hard);
        return ConstraintBrief.render(List.of(rule));
    }

    private static DesignReviewerClient reviewer(ScriptedLlm llm) {
        return new DesignReviewerClient(new VllmClient(llm.baseUrl(), "", "scripted", true),
            new CloudGate(1_000_000, null));
    }

    private static TestAuthorClient author(ScriptedLlm llm) {
        return new TestAuthorClient(new VllmClient(llm.baseUrl(), "", "scripted", true),
            new CloudGate(1_000_000, null));
    }

    private void write(String source) throws Exception {
        Path file = repo.resolve(TEST_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static String reply(String source) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                "files", List.of(Map.of("path", TEST_PATH, "content", source)),
                "wrote", List.of()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Implement OrderServiceImpl", "edit an order",
            Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "an order can be edited",
                "swarm.accept.EditOrderTest")),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }
}
