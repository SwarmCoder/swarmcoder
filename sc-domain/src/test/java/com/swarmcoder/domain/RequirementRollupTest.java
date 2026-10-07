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
package com.swarmcoder.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Check coverage, own and rolled up.
 *
 * <p>Three of these tests exist for specific traps rather than for coverage's sake: staleness is
 * per-requirement so a subtree must not be summed against one shared revision; a malformed hierarchy
 * must not let a child be counted under two parents; and the roll-up's "all passing" must be the same
 * condition {@link BrdRequirement#statusFromEvidence()} flips on, or a row can show a full tick beside
 * a badge that says ACTIVE.
 */
class RequirementRollupTest {

    // --- one requirement's own checks -------------------------------------------------------------

    @Test
    void countsEachStateAndSeparatesAdvisoryFromGating() {
        BrdRequirement r = req("R1");
        accepted(r, CriterionState.PASSING);
        accepted(r, CriterionState.PASSING);
        accepted(r, CriterionState.FAILING);
        accepted(r, CriterionState.UNVERIFIED);
        proposed(r);

        CheckCounts counts = CheckCounts.of(r);

        assertThat(counts.passing()).isEqualTo(2);
        assertThat(counts.failing()).isEqualTo(1);
        assertThat(counts.unverified()).isEqualTo(1);
        assertThat(counts.stale()).isZero();
        assertThat(counts.proposed()).isEqualTo(1);
        assertThat(counts.gating())
            .describedAs("a proposed check gates nothing, so it is not in the denominator")
            .isEqualTo(4);
        assertThat(counts.total()).isEqualTo(5);
        assertThat(counts.outstanding()).isEqualTo(2);
        assertThat(counts.allGatingPassing()).isFalse();
    }

    @Test
    void retiredChecksAreNotCountedAtAll() {
        // Retiring a check is a tidy-up. If it counted anywhere, tidying up would read as a
        // regression — the ratio would drop with nothing about the software having changed.
        BrdRequirement r = req("R1");
        accepted(r, CriterionState.PASSING);
        AcceptanceCriterion retired = accepted(r, CriterionState.FAILING);
        retired.setStatus(CriterionStatus.RETIRED);

        CheckCounts counts = CheckCounts.of(r);
        assertThat(counts.gating()).isEqualTo(1);
        assertThat(counts.total()).isEqualTo(1);
        assertThat(counts.failing()).isZero();
        assertThat(counts.allGatingPassing()).isTrue();
    }

    @Test
    void aPassRecordedAgainstOlderWordingIsStaleNotPassing() {
        BrdRequirement r = req("R1");
        AcceptanceCriterion c = accepted(r, CriterionState.PASSING);
        c.setVerifiedAgainstContentRevision(1);
        r.setContentRevision(2); // somebody reworded it after the check last passed

        CheckCounts counts = CheckCounts.of(r);
        assertThat(counts.passing()).isZero();
        assertThat(counts.stale()).isEqualTo(1);
        assertThat(counts.gating())
            .describedAs("stale stays in the denominator — a reword must not improve the ratio")
            .isEqualTo(1);
        assertThat(counts.allGatingPassing()).isFalse();
    }

    @Test
    void nothingToProveIsNotAllPassing() {
        assertThat(CheckCounts.EMPTY.allGatingPassing()).isFalse();
        assertThat(CheckCounts.of(req("R1")).allGatingPassing()).isFalse();
        // …and only-proposed is still nothing to prove.
        BrdRequirement r = req("R2");
        proposed(r);
        assertThat(CheckCounts.of(r).allGatingPassing()).isFalse();
    }

    @Test
    void theEvidenceBadgeAndTheRatioAreOneDerivation() {
        // statusFromEvidence() delegates to allGatingPassing(), so these two cannot drift.
        BrdRequirement r = req("R1");
        r.setStatus(RequirementStatus.ACTIVE);
        AcceptanceCriterion c = accepted(r, CriterionState.PASSING);
        assertThat(CheckCounts.of(r).allGatingPassing()).isTrue();
        assertThat(r.statusFromEvidence()).isEqualTo(RequirementStatus.IMPLEMENTED);

        c.setVerification(CriterionState.FAILING);
        assertThat(CheckCounts.of(r).allGatingPassing()).isFalse();
        assertThat(r.statusFromEvidence()).isEqualTo(RequirementStatus.ACTIVE);

        // A DRAFT is operator-owned and evidence never moves it, whatever the counts say.
        BrdRequirement draft = req("R2");
        draft.setStatus(RequirementStatus.DRAFT);
        accepted(draft, CriterionState.PASSING);
        assertThat(CheckCounts.of(draft).allGatingPassing()).isTrue();
        assertThat(draft.statusFromEvidence()).isEqualTo(RequirementStatus.DRAFT);
    }

    // --- rolling up ------------------------------------------------------------------------------

    @Test
    void aSubtreeSumsItselfAndEverythingBeneathIt() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1");                   // coarse, no checks of its own
        BrdRequirement r2 = f.add("R2");
        BrdRequirement r3 = f.add("R3");
        BrdRequirement r4 = f.add("R4");
        accepted(r2, CriterionState.PASSING);
        accepted(r3, CriterionState.PASSING);
        accepted(r3, CriterionState.FAILING);
        accepted(r4, CriterionState.UNVERIFIED);
        f.refines(r2, r1);
        f.refines(r3, r2);   // two levels deep
        f.refines(r4, r1);

        RequirementRollup rollup = RequirementRollup.of(f.brd());

        assertThat(rollup.own(r1.id()).total())
            .describedAs("a coarse requirement carries no checks of its own")
            .isZero();
        assertThat(rollup.subtree(r1.id()).gating()).isEqualTo(4);
        assertThat(rollup.subtree(r1.id()).passing()).isEqualTo(2);
        assertThat(rollup.subtree(r1.id()).failing()).isEqualTo(1);
        assertThat(rollup.subtree(r1.id()).unverified()).isEqualTo(1);
        assertThat(rollup.descendants(r1.id())).isEqualTo(3);

        // R2's subtree is R2 + R3; R4 is a sibling and must not appear in it.
        assertThat(rollup.subtree(r2.id()).gating()).isEqualTo(3);
        assertThat(rollup.descendants(r2.id())).isEqualTo(1);

        // A leaf's subtree is exactly its own.
        assertThat(rollup.subtree(r4.id())).isEqualTo(rollup.own(r4.id()));
        assertThat(rollup.descendants(r4.id())).isZero();
        assertThat(rollup.hasChildren(r4.id())).isFalse();
        assertThat(rollup.hasChildren(r1.id())).isTrue();
    }

    @Test
    void stalenessIsJudgedPerRequirementNotAgainstTheParents() {
        // The trap: rolling up with one shared contentRevision. R2's check passed against ITS current
        // wording and is green; R3's passed against older wording and is stale. A subtree computed
        // against R1's revision (0) would call both of them green.
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1");
        BrdRequirement r2 = f.add("R2");
        BrdRequirement r3 = f.add("R3");
        AcceptanceCriterion fresh = accepted(r2, CriterionState.PASSING);
        fresh.setVerifiedAgainstContentRevision(4);
        r2.setContentRevision(4);
        AcceptanceCriterion old = accepted(r3, CriterionState.PASSING);
        old.setVerifiedAgainstContentRevision(1);
        r3.setContentRevision(9);
        f.refines(r2, r1);
        f.refines(r3, r1);

        CheckCounts subtree = RequirementRollup.of(f.brd()).subtree(r1.id());

        assertThat(subtree.passing()).isEqualTo(1);
        assertThat(subtree.stale()).isEqualTo(1);
        assertThat(subtree.gating()).isEqualTo(2);
    }

    @Test
    void aChildClaimingTwoParentsIsCountedUnderExactlyOne() {
        // A malformed document must not inflate coverage. R7 claims both R2 and R5; the hierarchy puts
        // it under R2 only, so R5's subtree must not include it and no total may count it twice.
        Fixture f = new Fixture();
        BrdRequirement r2 = f.add("R2");
        BrdRequirement r5 = f.add("R5");
        BrdRequirement r7 = f.add("R7");
        accepted(r7, CriterionState.PASSING);
        f.refines(r7, r5);
        f.refines(r7, r2);

        RequirementRollup rollup = RequirementRollup.of(f.brd());

        assertThat(rollup.subtree(r2.id()).gating()).isEqualTo(1);
        assertThat(rollup.subtree(r5.id()).gating())
            .describedAs("the parent the hierarchy did not choose does not inherit the check")
            .isZero();
        assertThat(rollup.descendants(r2.id())).isEqualTo(1);
        assertThat(rollup.descendants(r5.id())).isZero();
    }

    @Test
    void aCycleRollsUpWithoutLoopingOrDoubleCounting() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1");
        BrdRequirement r2 = f.add("R2");
        BrdRequirement r3 = f.add("R3");
        accepted(r1, CriterionState.PASSING);
        accepted(r2, CriterionState.PASSING);
        accepted(r3, CriterionState.PASSING);
        f.refines(r1, r2);
        f.refines(r2, r3);
        f.refines(r3, r1);

        RequirementRollup rollup = RequirementRollup.of(f.brd());
        RequirementTree tree = rollup.tree();
        UUID root = tree.roots().get(0);

        // The cut turns the ring into a chain, so the root's subtree is all three checks — counted
        // once each, which is the only claim that matters here.
        assertThat(rollup.subtree(root).gating()).isEqualTo(3);
        assertThat(rollup.descendants(root)).isEqualTo(2);
    }

    @Test
    void reusesAnAlreadyResolvedHierarchy() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1");
        accepted(r1, CriterionState.PASSING);
        Brd brd = f.brd();
        RequirementTree tree = RequirementTree.of(brd);

        RequirementRollup rollup = RequirementRollup.of(brd, tree);
        assertThat(rollup.tree()).isSameAs(tree);
        assertThat(rollup.subtree(r1.id()).passing()).isEqualTo(1);
    }

    @Test
    void anEmptyOrNullBrdRollsUpToNothing() {
        assertThat(RequirementRollup.of(null).subtree(UUID.randomUUID())).isEqualTo(CheckCounts.EMPTY);
        assertThat(RequirementRollup.of(new Brd()).own(UUID.randomUUID())).isEqualTo(CheckCounts.EMPTY);
        assertThat(RequirementRollup.of(new Brd()).descendants(UUID.randomUUID())).isZero();
        assertThat(CheckCounts.EMPTY.plus(null)).isEqualTo(CheckCounts.EMPTY);
    }

    // --- fixture ---------------------------------------------------------------------------------

    private static BrdRequirement req(String handle) {
        BrdRequirement r = new BrdRequirement(UUID.randomUUID(), handle, handle, handle,
            Priority.MEDIUM, RequirementStatus.ACTIVE, null);
        r.setCriteria(new ArrayList<>());
        return r;
    }

    private static AcceptanceCriterion accepted(BrdRequirement r, CriterionState state) {
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), "check", "SomeTest#m");
        c.setStatus(CriterionStatus.ACCEPTED);
        c.setVerification(state);
        r.criteria().add(c);
        return c;
    }

    private static AcceptanceCriterion proposed(BrdRequirement r) {
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), "suggested", null);
        c.setStatus(CriterionStatus.PROPOSED);
        c.setVerification(CriterionState.UNVERIFIED);
        r.criteria().add(c);
        return c;
    }

    private static final class Fixture {
        private final List<BrdRequirement> reqs = new ArrayList<>();
        private final List<BrdEdge> edges = new ArrayList<>();

        BrdRequirement add(String handle) {
            BrdRequirement r = req(handle);
            reqs.add(r);
            return r;
        }

        /** {@code child} is part of {@code parent}. */
        void refines(BrdRequirement child, BrdRequirement parent) {
            edges.add(new BrdEdge(child.id(), parent.id(), RequirementRelation.REFINES));
        }

        Brd brd() {
            return new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "test",
                new ArrayList<>(reqs), new ArrayList<>(edges), null, null);
        }
    }
}
