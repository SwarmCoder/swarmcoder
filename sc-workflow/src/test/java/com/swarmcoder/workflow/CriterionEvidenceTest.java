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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.NfrCategory;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementKind;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a verification report into evidence about requirements, and deciding whether the quality
 * gates permit delivery.
 *
 * <p>The load-bearing case is the one about silence: a criterion whose test did not run is UNKNOWN,
 * never PASSED. Treating absence of evidence as success is exactly how a requirement ends up
 * claiming to be implemented because nobody checked.
 */
class CriterionEvidenceTest {

    @Test
    void aCriterionWhoseTestFailedIsFailedAndOneThatRanCleanIsPassed() {
        Fixture f = fixture(RequirementStatus.ACTIVE, "PerfSmokeTest#p95Under200ms");
        VerificationReport report = report(3, failure("com.acme.GuestCheckoutTest.emptyCart"));

        var outcomes = CriterionEvidence.outcomes(f.scope, report);

        assertThat(outcomes.get(f.functionalCriterion.id()))
            .isEqualTo(CriterionEvidence.Outcome.FAILED);
        assertThat(CriterionEvidence.allDelivered(f.scope, report)).isFalse();

        // The criterion's OWN test ran and passed.
        VerificationReport green = reportPassing("com.acme.GuestCheckoutTest#emptyCart");
        assertThat(CriterionEvidence.outcomes(f.scope, green).get(f.functionalCriterion.id()))
            .isEqualTo(CriterionEvidence.Outcome.PASSED);
        assertThat(CriterionEvidence.allDelivered(f.scope, green)).isTrue();

        // Three other tests ran green and the criterion's own test is not among them. This used to
        // read as PASSED — the suite ran, nothing matching it failed — which is how a requirement
        // reaches IMPLEMENTED on the strength of somebody else's test.
        VerificationReport greenButUnrelated = report(3);
        assertThat(CriterionEvidence.outcomes(f.scope, greenButUnrelated)
            .get(f.functionalCriterion.id()))
            .isEqualTo(CriterionEvidence.Outcome.UNKNOWN);
        assertThat(CriterionEvidence.allDelivered(f.scope, greenButUnrelated)).isFalse();
    }

    @Test
    void aCriterionWithNoResultsIsUnknownNotPassed() {
        Fixture f = fixture(RequirementStatus.ACTIVE, "PerfSmokeTest#p95Under200ms");

        // Suite never ran: zero passed, zero failed.
        VerificationReport nothing = report(0);
        assertThat(CriterionEvidence.outcomes(f.scope, nothing).get(f.functionalCriterion.id()))
            .isEqualTo(CriterionEvidence.Outcome.UNKNOWN);
        assertThat(CriterionEvidence.allDelivered(f.scope, nothing)).isFalse();

        // No report at all.
        assertThat(CriterionEvidence.outcomes(f.scope, null).get(f.functionalCriterion.id()))
            .isEqualTo(CriterionEvidence.Outcome.UNKNOWN);
    }

    @Test
    void anActiveGateWithAFailingFitnessTestBlocksIntegration() {
        Fixture f = fixture(RequirementStatus.ACTIVE, "PerfSmokeTest#p95Under200ms");
        VerificationReport report = report(5, failure("com.acme.PerfSmokeTest.p95Under200ms"));

        var verdict = CriterionEvidence.evaluateGates(f.scope, report);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.blocking()).anyMatch(b -> b.contains("R12"));
    }

    @Test
    void aDraftGateOnlyWarns() {
        Fixture f = fixture(RequirementStatus.DRAFT, "PerfSmokeTest#p95Under200ms");
        VerificationReport report = report(5, failure("com.acme.PerfSmokeTest.p95Under200ms"));

        var verdict = CriterionEvidence.evaluateGates(f.scope, report);

        // A half-written quality requirement must not be able to wedge every run.
        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.warnings()).anyMatch(w -> w.contains("advisory"));
    }

    @Test
    void anActiveGateWithNothingToMeasureItWarnsRatherThanBlockingForever() {
        Fixture f = fixture(RequirementStatus.ACTIVE, null);
        VerificationReport report = report(5);

        var verdict = CriterionEvidence.evaluateGates(f.scope, report);

        // Blocking would be permanent: there is no test that could ever satisfy it.
        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.warnings()).anyMatch(w -> w.contains("no measurable fitness criterion"));
    }

    @Test
    void aDeprecatedGateIsNotEvaluatedAtAll() {
        Fixture f = fixture(RequirementStatus.DEPRECATED, "PerfSmokeTest#p95Under200ms");
        VerificationReport report = report(5, failure("com.acme.PerfSmokeTest.p95Under200ms"));

        var verdict = CriterionEvidence.evaluateGates(f.scope, report);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.warnings()).isEmpty();
    }

    @Test
    void testReferencesMatchTheIdsTheRunnerActuallyEmits() {
        // The criterion and the harness are written by different authors, so the match has to
        // survive package prefixes, path form, and the #method vs .method spelling.
        Fixture hash = fixture(RequirementStatus.ACTIVE, "PerfSmokeTest#p95Under200ms");
        assertThat(CriterionEvidence.evaluateGates(hash.scope,
            report(5, failure("com.acme.perf.PerfSmokeTest.p95Under200ms"))).blocked()).isTrue();

        Fixture path = fixture(RequirementStatus.ACTIVE, "src/test/java/acme/PerfSmokeTest.java");
        assertThat(CriterionEvidence.evaluateGates(path.scope,
            report(5, failure("com.acme.PerfSmokeTest.p95Under200ms"))).blocked()).isTrue();

        // …and must NOT match an unrelated failure.
        assertThat(CriterionEvidence.evaluateGates(hash.scope,
            report(5, failure("com.acme.CartTest.total"))).blocked()).isFalse();
    }

    // --- fixtures ------------------------------------------------------------------------------

    // --- the freeform escape hatch: an empty slice that is not a failure ----------------------

    @Test
    void anAdHocStoryWithNoCriteriaIsDeliverable() {
        // The only way in on a project nobody has written requirements for. Having no criteria is
        // this story's definition, not a defect, so "every criterion passed" is vacuously true.
        // Answering false here recorded a run as ABORTED after it had designed, planned, watched
        // four acceptance tests go red, built two verified candidates and merged the winner.
        assertThat(CriterionEvidence.allDelivered(StoryScope.empty(adHoc()), null)).isTrue();
    }

    @Test
    void aStoryThatNamesRequirementsButHasNoCriteriaIsStillBlocked() {
        // It promised something specific and proved nothing. An empty slice HERE means its checks
        // were never agreed or have all been retired — a defect, not a shape of work.
        Story promised = adHoc();
        promised.setRequirementIds(new ArrayList<>(List.of(UUID.randomUUID())));
        assertThat(CriterionEvidence.allDelivered(StoryScope.empty(promised), null)).isFalse();
    }

    @Test
    void aBacklogStoryWithNoCriteriaIsStillBlocked() {
        // Same shape from the other direction: a story the operator planned off the backlog is
        // meant to carry checks, so an empty slice is a defect there too.
        Story planned = adHoc();
        planned.setOrigin(StoryOrigin.BACKLOG);
        assertThat(CriterionEvidence.allDelivered(StoryScope.empty(planned), null)).isFalse();
    }

    @Test
    void aRunWithNoStoryAtAllIsDeliverable() {
        assertThat(CriterionEvidence.allDelivered(StoryScope.empty(null), null)).isTrue();
    }

    /** The work item the chat mints for a freeform run: ENABLER, AD_HOC, no requirements. */
    private static Story adHoc() {
        return new Story(UUID.randomUUID(), UUID.randomUUID(), "S1", StoryKind.ENABLER,
            "Make the footer say the right year", null, StoryState.RUNNING, new ArrayList<>(),
            new ArrayList<>(), null, 0, StoryOrigin.AD_HOC, null, "started directly from chat",
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
    }

    private record Fixture(AcceptanceCriterion functionalCriterion, StoryScope scope) {}

    /** One functional criterion, plus an NFR gate whose status and fitness test vary. */
    private static Fixture fixture(RequirementStatus gateStatus, String fitnessTest) {
        BrdRequirement checkout = new BrdRequirement(UUID.randomUUID(), "R7", "Guest checkout",
            "A guest can pay", Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion c1 = new AcceptanceCriterion(UUID.randomUUID(),
            "empty cart is rejected", "GuestCheckoutTest#emptyCart");
        c1.setStatus(CriterionStatus.ACCEPTED);
        checkout.setCriteria(new ArrayList<>(List.of(c1)));

        BrdRequirement latency = new BrdRequirement(UUID.randomUUID(), "R12", "Latency",
            "p95 under 200ms", Priority.HIGH, gateStatus, null);
        latency.setKind(RequirementKind.NON_FUNCTIONAL);
        latency.setNfrCategory(NfrCategory.PERFORMANCE);
        List<AcceptanceCriterion> fitness = new ArrayList<>();
        if (fitnessTest != null) {
            AcceptanceCriterion f = new AcceptanceCriterion(UUID.randomUUID(),
                "p95 under 200ms at 100 rps", fitnessTest);
            f.setStatus(CriterionStatus.ACCEPTED);
            fitness.add(f);
        }
        latency.setCriteria(fitness);

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "BRD",
            new ArrayList<>(List.of(checkout, latency)),
            new ArrayList<>(List.of(
                new BrdEdge(latency.id(), checkout.id(), RequirementRelation.GATES))),
            Instant.now(), Instant.now());

        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Guest can pay", null, StoryState.RUNNING, new ArrayList<>(),
            new ArrayList<>(List.of(c1.id())), null, 0, StoryOrigin.BACKLOG, null, null, "human",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());

        return new Fixture(c1, StoryScope.resolve(brd, story));
    }

    private static TestFailure failure(String testId) {
        TestFailure f = new TestFailure();
        f.setTestId(testId);
        f.setMessage("boom");
        return f;
    }

    /**
     * A report of {@code passed} UNRELATED green tests, plus the given failures.
     *
     * <p>The green tests are given real ids, because a report has to carry them. A criterion may
     * only come out PASSED when a test matching it is among the ids that ran and passed; a bare
     * count says "some tests ran", which cannot answer "did MINE run?". A matcher that answered it
     * anyway is precisely the defect this fixture used to conceal — it let every one of these cases
     * be green without any of the criteria's own tests existing.
     */
    private static VerificationReport report(int passed, TestFailure... failures) {
        List<String> passedIds = new ArrayList<>();
        for (int i = 0; i < passed; i++) {
            passedIds.add("com.acme.UnrelatedTest#green" + i);
        }
        return reportOf(passedIds, failures);
    }

    /** A report in which exactly the named tests ran and passed. */
    private static VerificationReport reportPassing(String... passedIds) {
        return reportOf(List.of(passedIds));
    }

    private static VerificationReport reportOf(List<String> passedIds, TestFailure... failures) {
        TestResults acceptance = new TestResults();
        acceptance.setPassed(passedIds.size());
        acceptance.setFailed(failures.length);
        acceptance.setErrored(0);
        acceptance.setSkipped(0);
        acceptance.setPassedIds(new ArrayList<>(passedIds));
        acceptance.setFailures(new ArrayList<>(List.of(failures)));
        VerificationReport report = new VerificationReport();
        report.setId(UUID.randomUUID());
        report.setParses(true);
        report.setCompiles(true);
        report.setAcceptance(acceptance);
        return report;
    }
}
