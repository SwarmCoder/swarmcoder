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
 * The REFINES hierarchy: resolution, tolerance of shapes that pre-date the rule, and the write gate.
 *
 * <p>The cycle cases are the reason this test exists at all. Nothing prevented a REFINES cycle before
 * {@link RequirementTree}, so a real store may hold one, and every consumer of the hierarchy walks it
 * — a resolution that looped would not produce a wrong tree, it would hang the Console on load.
 */
class RequirementTreeTest {

    // --- resolution ------------------------------------------------------------------------------

    @Test
    void resolvesACleanHierarchyWithDepthsAndDescendants() {
        Fixture f = new Fixture();
        UUID r1 = f.req("R1");
        UUID r2 = f.req("R2");
        UUID r3 = f.req("R3");
        UUID r4 = f.req("R4");
        f.refines(r2, r1);   // R2 is part of R1
        f.refines(r3, r2);
        f.refines(r4, r1);

        RequirementTree tree = RequirementTree.of(f.brd());

        assertThat(tree.isClean()).isTrue();
        assertThat(tree.roots()).containsExactly(r1);
        assertThat(tree.childrenOf(r1)).containsExactly(r2, r4);
        assertThat(tree.childrenOf(r2)).containsExactly(r3);
        assertThat(tree.parentOf(r3)).isEqualTo(r2);
        assertThat(tree.parentOf(r1)).isNull();
        assertThat(tree.depthOf(r1)).isZero();
        assertThat(tree.depthOf(r2)).isEqualTo(1);
        assertThat(tree.depthOf(r3)).isEqualTo(2);
        assertThat(tree.descendantsOf(r1)).containsExactly(r2, r3, r4);
        assertThat(tree.descendantsOf(r3)).isEmpty();
    }

    @Test
    void ordersChildrenTheWayAnOperatorReadsHandles() {
        // R2 before R10. A plain string sort puts "R10" first, which looks arbitrary to anyone with
        // more than nine requirements — and the chosen parent of a violating node depends on this
        // order, so it has to be the order a human would predict.
        Fixture f = new Fixture();
        UUID parent = f.req("R1");
        UUID r10 = f.req("R10");
        UUID r2 = f.req("R2");
        UUID r9 = f.req("R9");
        f.refines(r10, parent);
        f.refines(r2, parent);
        f.refines(r9, parent);

        assertThat(RequirementTree.of(f.brd()).childrenOf(parent)).containsExactly(r2, r9, r10);
    }

    @Test
    void ignoresEdgesThatPointAtNothing() {
        Fixture f = new Fixture();
        UUID r1 = f.req("R1");
        f.edge(r1, UUID.randomUUID(), RequirementRelation.REFINES); // target was deleted
        f.edge(UUID.randomUUID(), r1, RequirementRelation.REFINES); // source was deleted

        RequirementTree tree = RequirementTree.of(f.brd());
        assertThat(tree.roots()).containsExactly(r1);
        assertThat(tree.parentOf(r1)).isNull();
        assertThat(tree.isClean()).isTrue();
    }

    @Test
    void onlyRefinesEdgesNest() {
        Fixture f = new Fixture();
        UUID r1 = f.req("R1");
        UUID r2 = f.req("R2");
        f.edge(r2, r1, RequirementRelation.DEPENDS_ON);
        f.edge(r2, r1, RequirementRelation.DERIVED_FROM);
        f.edge(r2, r1, RequirementRelation.CONFLICTS_WITH);

        RequirementTree tree = RequirementTree.of(f.brd());
        assertThat(tree.roots()).containsExactly(r1, r2);
        assertThat(tree.parentOf(r2)).isNull();
    }

    // --- tolerance of shapes that pre-date the rule -----------------------------------------------

    @Test
    void aRequirementWithTwoParentsLandsUnderTheLowestHandleAndReportsTheOther() {
        Fixture f = new Fixture();
        UUID r2 = f.req("R2");
        UUID r5 = f.req("R5");
        UUID r7 = f.req("R7");
        f.refines(r7, r5);   // added first, but R5 is the higher handle
        f.refines(r7, r2);

        RequirementTree tree = RequirementTree.of(f.brd());

        assertThat(tree.parentOf(r7)).isEqualTo(r2);
        assertThat(tree.extraParentsOf(r7)).containsExactly(r5);
        assertThat(tree.childrenOf(r2)).containsExactly(r7);
        assertThat(tree.childrenOf(r5)).isEmpty();
        assertThat(tree.isClean()).isFalse();
        assertThat(tree.withViolations()).containsExactly(r7);
    }

    @Test
    void aTwoNodeCycleTerminatesAndBecomesARoot() {
        Fixture f = new Fixture();
        UUID r1 = f.req("R1");
        UUID r2 = f.req("R2");
        f.refines(r1, r2);
        f.refines(r2, r1);

        RequirementTree tree = RequirementTree.of(f.brd());

        assertThat(tree.inCycle()).isNotEmpty();
        assertThat(tree.roots()).isNotEmpty();
        assertThat(tree.isClean()).isFalse();
        // Whichever edge was cut, every node still has a finite depth and appears exactly once.
        assertThat(tree.depthOf(r1)).isLessThan(2);
        assertThat(tree.depthOf(r2)).isLessThan(2);
    }

    @Test
    void aLongCycleTerminatesAndEveryNodeIsStillReachable() {
        // R1 → R2 → R3 → R4 → R1. A visited-set-free walk loops here for ever.
        Fixture f = new Fixture();
        UUID r1 = f.req("R1");
        UUID r2 = f.req("R2");
        UUID r3 = f.req("R3");
        UUID r4 = f.req("R4");
        f.refines(r1, r2);
        f.refines(r2, r3);
        f.refines(r3, r4);
        f.refines(r4, r1);

        RequirementTree tree = RequirementTree.of(f.brd());

        assertThat(tree.roots()).hasSize(1);
        assertThat(tree.inCycle()).hasSize(1);
        UUID root = tree.roots().get(0);
        // The cut turns the ring into a chain, so the other three hang off the root.
        assertThat(tree.descendantsOf(root)).hasSize(3);
        assertThat(tree.depthOf(root)).isZero();
    }

    @Test
    void aSelfRefiningRequirementIsNotAParentOfItself() {
        Fixture f = new Fixture();
        UUID r1 = f.req("R1");
        f.edge(r1, r1, RequirementRelation.REFINES);

        RequirementTree tree = RequirementTree.of(f.brd());
        assertThat(tree.parentOf(r1)).isNull();
        assertThat(tree.roots()).containsExactly(r1);
        assertThat(tree.descendantsOf(r1)).isEmpty();
    }

    @Test
    void anEmptyOrNullBrdResolvesToNothing() {
        assertThat(RequirementTree.of(null).roots()).isEmpty();
        assertThat(RequirementTree.of(new Brd()).roots()).isEmpty();
        assertThat(RequirementTree.of(new Brd()).isClean()).isTrue();
    }

    // --- the write gate --------------------------------------------------------------------------

    @Test
    void refusesASecondParentButAcceptsTheSameOneAgain() {
        Fixture f = new Fixture();
        UUID r2 = f.req("R2");
        UUID r5 = f.req("R5");
        UUID r7 = f.req("R7");
        f.refines(r7, r2);

        assertThat(RequirementTree.rejectionFor(f.brd(), r7, r5, RequirementRelation.REFINES))
            .describedAs("a requirement belongs in one place")
            // The alternative it offers has to be named the way the picker on screen names it, so
            // it reads RequirementRelation.label() rather than spelling anything out here. It used
            // to say "depends on" and "derived from" - the Java constants read aloud, and not what
            // the control says (UX v3 §4, rule 6; §25.5).
            .contains("R7").contains("already part of R2")
            .contains(RequirementRelation.DEPENDS_ON.label())
            .contains(RequirementRelation.DERIVED_FROM.label())
            .doesNotContain("depends_on").doesNotContain("derived_from")
            .doesNotContain("refines");

        // Re-adding the link it already has is idempotent, not a violation: saveEdge is specified to
        // replace an existing edge, and refusing here would make a repeated click look like an error.
        assertThat(RequirementTree.rejectionFor(f.brd(), r7, r2, RequirementRelation.REFINES))
            .isNull();
    }

    @Test
    void refusesAnEdgeThatWouldCloseALoop() {
        Fixture f = new Fixture();
        UUID r1 = f.req("R1");
        UUID r2 = f.req("R2");
        UUID r3 = f.req("R3");
        f.refines(r2, r1);
        f.refines(r3, r2);

        // R1 is the top; making it part of its own grandchild would close the ring.
        assertThat(RequirementTree.rejectionFor(f.brd(), r1, r3, RequirementRelation.REFINES))
            .describedAs("a loop is refused before it can be stored, not repaired after")
            .contains("loop");
    }

    @Test
    void theSingleParentRuleDoesNotConstrainTheOtherRelations() {
        Fixture f = new Fixture();
        UUID r1 = f.req("R1");
        UUID r2 = f.req("R2");
        UUID r3 = f.req("R3");
        f.refines(r3, r1);

        // R3 already has a parent; it may still depend on and derive from anything.
        assertThat(RequirementTree.rejectionFor(f.brd(), r3, r2, RequirementRelation.DEPENDS_ON))
            .isNull();
        assertThat(RequirementTree.rejectionFor(f.brd(), r3, r2, RequirementRelation.DERIVED_FROM))
            .isNull();
        assertThat(RequirementTree.rejectionFor(f.brd(), r3, r2, RequirementRelation.CONFLICTS_WITH))
            .isNull();
    }

    @Test
    void onlyANonFunctionalRequirementMayGate() {
        // This rule existed on the agent's path and NOT on the operator's, so a hand-drawn edge could
        // create a gate no story could satisfy. Both paths call this now.
        Fixture f = new Fixture();
        UUID functional = f.req("R1");
        UUID nfr = f.req("R12", RequirementKind.NON_FUNCTIONAL);
        UUID target = f.req("R2");

        assertThat(RequirementTree.rejectionFor(f.brd(), functional, target, RequirementRelation.GATES))
            .contains("R1").contains("functional");
        assertThat(RequirementTree.rejectionFor(f.brd(), nfr, target, RequirementRelation.GATES))
            .isNull();
    }

    @Test
    void handlesAreComparedNumericallyWhereTheyEndInDigits() {
        assertThat(RequirementTree.compareHandles("R2", "R10")).isNegative();
        assertThat(RequirementTree.compareHandles("R10", "R2")).isPositive();
        assertThat(RequirementTree.compareHandles("R7", "R7")).isZero();
        // No digits, or a prefix difference: fall back to the plain comparison.
        assertThat(RequirementTree.compareHandles("A", "B")).isNegative();
        assertThat(RequirementTree.compareHandles("NFR1", "R1")).isNegative();
        assertThat(RequirementTree.compareHandles(null, "R1")).isNegative();
    }

    // --- fixture ---------------------------------------------------------------------------------

    /** Builds a Brd by handle, so a test reads as the shape it is describing. */
    private static final class Fixture {
        private final List<BrdRequirement> reqs = new ArrayList<>();
        private final List<BrdEdge> edges = new ArrayList<>();

        UUID req(String handle) {
            return req(handle, RequirementKind.FUNCTIONAL);
        }

        UUID req(String handle, RequirementKind kind) {
            BrdRequirement r = new BrdRequirement(UUID.randomUUID(), handle, handle, handle,
                Priority.MEDIUM, RequirementStatus.ACTIVE, null);
            r.setKind(kind);
            reqs.add(r);
            return r.id();
        }

        /** {@code child} is part of {@code parent}. */
        void refines(UUID child, UUID parent) {
            edge(child, parent, RequirementRelation.REFINES);
        }

        void edge(UUID from, UUID to, RequirementRelation relation) {
            edges.add(new BrdEdge(from, to, relation));
        }

        Brd brd() {
            return new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "test",
                new ArrayList<>(reqs), new ArrayList<>(edges), null, null);
        }
    }
}
