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
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Requirement;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 17 (2026-09-04): a design DESIGN itself had just logged as carrying 1 requirement,
 * 2 contracts and 3 risks reached DESIGN_REVIEW as "The design section is empty" and then reached
 * PLAN, which failed three times in under two seconds — no network round trip could have happened
 * in that time — each attempt reporting only the canned "the architect answered unusably", with
 * nothing about what actually threw anywhere in the run's own log.
 *
 * <p>Two real gaps, both closed here:
 *
 * <ul>
 *   <li>{@code ArchitectClient#designSummary} null-guarded {@link DesignDocument#requirements()}
 *       before iterating it, but iterated {@link DesignDocument#decisions()},
 *       {@link DesignDocument#contracts()} and {@link DesignDocument#risks()} directly — so a
 *       design reaching it with any of those three unset (the bare no-arg constructor two Console
 *       test fixtures already use, or any future construction path) threw
 *       synchronously, before any model call, and rendered nothing for the reviewer whenever the
 *       throw was itself swallowed. Fixed by making the four {@code DesignDocument} list accessors
 *       null-safe, the same pattern {@link DesignDocument#brdRequirementIds()} already used.</li>
 *   <li>{@code ArchitectClient#plan}'s catch recorded {@code Exception#getMessage()} as the failure
 *       reason — null for a great many exceptions — so {@code planAttempt} had nothing to fall back
 *       on but its own canned text, and a genuinely-thrown attempt was indistinguishable from one
 *       where the model was reached and simply refused. Fixed by recording
 *       {@code Exception#toString()} (never null) instead, both inside {@code plan()}'s own catch
 *       and in a new catch inside {@code planAttempt} itself guarding the overridable call to
 *       {@code plan(...)} — the harness's {@code BudgetStampingArchitect} overrides exactly that
 *       method, and an override that throws must not be able to escape {@code planAttempt}
 *       uncaught.</li>
 * </ul>
 */
class PlanFailureNeverSilentTest {

    // ---- designSummary: the reproduction --------------------------------------------------

    /**
     * The reproduction: a design with real requirements and contracts, but whose decisions and
     * risks were never set (null fields, exactly what the bare constructor plus setters — or an
     * old store record — leaves behind). Before the fix this threw a NullPointerException with no
     * useful reason recorded anywhere the operator could see; now it renders every section it has
     * content for and simply omits the two that are empty.
     */
    @Test
    void designSummaryNeverThrowsWhenDecisionsOrRisksAreUnset() {
        Requirement requirement = new Requirement(UUID.randomUUID(), "Guest can pay", Priority.HIGH);
        ApiContract contract = new ApiContract(UUID.randomUUID(), "CartApi", "cart operations",
            "POST /cart", "com.acme.shop.Cart", List.of("int total"));
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "Guest can pay",
            List.of(requirement), null /* decisions never set */, List.of(contract),
            null /* risks never set */, null, Instant.now());

        String summary = ArchitectClient.designSummary(design);

        assertThat(summary)
            .contains("REQ R1").contains("Guest can pay")
            .contains("CONTRACT CartApi").contains("com.acme.shop.Cart").contains("int total")
            .doesNotContain("DECISION").doesNotContain("RISK");
    }

    /**
     * The exact null-fields case named by name in the incident: contracts is unset. On its own —
     * requirements also empty — the summary is blank, which is the legitimate "nothing to design
     * yet" case, not a bug; {@link DesignReviewerClient#designRenderedEmpty} treats the two
     * differently, exercised separately below.
     */
    @Test
    void designSummaryNeverThrowsWhenContractsIsUnset() {
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "Guest can pay",
            List.of(), List.of(), null /* contracts never set */, List.of(), null, Instant.now());

        assertThat(ArchitectClient.designSummary(design)).isEmpty();
    }

    /** A design built with the bare constructor plus setters — every list field starts null. */
    @Test
    void designSummaryNeverThrowsForABareConstructedDesign() {
        DesignDocument design = new DesignDocument();
        design.setGoal("Guest can pay");

        assertThat(ArchitectClient.designSummary(design)).isEmpty();
    }

    // ---- designSummary: a contract whose members were never set ---------------------------

    /**
     * {@link ApiContract#members()} is already null-safe, but only when nobody ever called
     * {@code setMembers(null)} through the setter that itself coalesces — the field can still be
     * null when a contract was built through the bare constructor and {@code setMembers} was never
     * called at all (Jackson would do exactly this for a JSON object missing the key). The type
     * still renders; there is simply nothing after "with".
     */
    @Test
    void designSummaryRendersTheTypeWithoutMembersWhenMembersWasNeverSet() {
        ApiContract contract = new ApiContract();
        contract.setName("Rating");
        contract.setTypeName("com.acme.shop.Rating");
        // setMembers(...) deliberately never called — the field stays null.

        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "Rate a book",
            List.of(), List.of(), List.of(contract), List.of(), null, Instant.now());

        String summary = ArchitectClient.designSummary(design);

        assertThat(summary)
            .contains("CONTRACT Rating")
            .contains("[type com.acme.shop.Rating]")
            .doesNotContain("with");
    }

    // ---- planAttempt: a plan failure always says what threw -------------------------------

    /**
     * The harness's own shape: a subclass overrides {@code plan(DesignDocument, String, StoryScope,
     * String)} — {@code BudgetStampingArchitect} in {@code EndToEndLoopTest} does exactly this,
     * stamping a tool-turn budget onto every planned task — and here the override throws a bare
     * {@code NullPointerException} with no message, the case that used to leave
     * {@code planAttempt} with nothing but "the architect answered unusably" and no way to tell an
     * override's crash apart from the model genuinely refusing.
     */
    @Test
    void planAttemptReportsWhatThrewEvenWhenAnOverrideThrowsWithNoMessage() {
        ArchitectClient architect = new ThrowingOverrideArchitect(
            new VllmClient("http://localhost:1", null, "test-model", true),
            new CloudGate(1_000_000, null));
        Fixture fixture = fixture();

        ArchitectClient.PlanAttempt result = architect.planAttempt(
            null, "Guest can pay", fixture.scope, "", null, List.of());

        assertThat(result.graph()).isNull();
        assertThat(result.failureReason())
            .as("the exception's own class must appear, not the canned fallback text")
            .contains("NullPointerException")
            .isNotEqualTo("the architect answered unusably");
    }

    private static final class ThrowingOverrideArchitect extends ArchitectClient {
        ThrowingOverrideArchitect(VllmClient client, CloudGate gate) {
            super(client, gate);
        }

        @Override
        public TaskGraph plan(DesignDocument design, String goal, StoryScope scope,
                              String repoLayoutBrief) {
            throw new NullPointerException(); // deliberately no message
        }
    }

    // ---- DesignReviewerClient: the reviewer never reviews an empty rendering --------------

    /**
     * The pure logic behind the skip, tested directly: a design with real content whose rendering
     * came back blank is a rendering bug, and must be told apart from a design that is genuinely
     * empty (nothing to say yet, which is not a bug and not worth a WARN).
     */
    @Test
    void designRenderedEmptyIsTrueOnlyWhenTheDesignHasContentButTheTextDoesNot() {
        Requirement requirement = new Requirement(UUID.randomUUID(), "Guest can pay", Priority.HIGH);
        DesignDocument withRequirement = new DesignDocument(UUID.randomUUID(), 1, "Guest can pay",
            List.of(requirement), List.of(), List.of(), List.of(), null, Instant.now());
        DesignDocument genuinelyEmpty = new DesignDocument(UUID.randomUUID(), 1, "Guest can pay",
            List.of(), List.of(), List.of(), List.of(), null, Instant.now());

        assertThat(DesignReviewerClient.designRenderedEmpty(withRequirement, ""))
            .as("real content, blank rendering — this is the bug the skip guards against")
            .isTrue();
        assertThat(DesignReviewerClient.designRenderedEmpty(withRequirement, "REQ R1 [HIGH] Guest can pay\n"))
            .as("real content that actually rendered — nothing to skip")
            .isFalse();
        assertThat(DesignReviewerClient.designRenderedEmpty(genuinelyEmpty, ""))
            .as("nothing to design yet is not a rendering bug")
            .isFalse();
    }

    /**
     * The reviewer call itself, end to end, through the OTHER way a rendering can fail: not a blank
     * string but an outright throw — a contracts list holding a null entry, which
     * {@code designSummary}'s {@code for (ApiContract c : design.contracts())} cannot render around
     * ({@code c.name()} on a null {@code c}). The {@code DesignReviewerClient} is built against a
     * dead endpoint (nothing listens on port 1); if the guard did not catch this before ever
     * building the model request, this test would fail on a connection error instead of asserting
     * anything about the objections — proving the skip genuinely happens before any call.
     */
    @Test
    void reviewSkipsTheModelCallWhenDesignSummaryThrows() {
        Requirement requirement = new Requirement(UUID.randomUUID(), "Guest can pay", Priority.HIGH);
        List<ApiContract> contractsWithANullEntry = new ArrayList<>();
        contractsWithANullEntry.add(null);
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "Guest can pay",
            List.of(requirement), List.of(), contractsWithANullEntry, List.of(), null, Instant.now());
        DesignReviewerClient reviewer = new DesignReviewerClient(
            new VllmClient("http://localhost:1", null, "test-model", true),
            new CloudGate(1_000_000, null));

        DesignReviewerClient.Review review = reviewer.review(design);

        assertThat(review.approved).isTrue();
        assertThat(review.objections).hasSize(1);
        assertThat(review.objections.get(0))
            .contains("design review skipped")
            .contains("failed to render")
            .doesNotContain("The design section is empty");
    }

    private record Fixture(BrdRequirement checkout, AcceptanceCriterion criterion, StoryScope scope) {}

    private static Fixture fixture() {
        BrdRequirement checkout = new BrdRequirement(UUID.randomUUID(), "R7", "Guest checkout",
            "A guest can complete a purchase", Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "empty cart is rejected", "GuestCheckoutTest#emptyCart");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        checkout.setCriteria(new ArrayList<>(List.of(criterion)));

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Business Requirements",
            new ArrayList<>(List.of(checkout)), new ArrayList<>(), Instant.now(), Instant.now());

        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Guest can pay", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());

        return new Fixture(checkout, criterion, StoryScope.resolve(brd, story));
    }
}
