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
package com.swarmcoder.console;

import com.swarmcoder.console.api.RequirementPageDto;
import com.swarmcoder.console.api.RequirementQuery;
import com.swarmcoder.console.api.RequirementRowDto;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.NfrCategory;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementKind;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paged requirements query: tree order, filtering, and the counts that keep a filtered view honest.
 *
 * <p>The load-bearing assertions here are about what a filter is NOT allowed to do. A filter that
 * dropped a match's ancestors would break the hierarchy on screen (UX v3 rule 1), one that counted those
 * ancestors as results would overstate the answer, and one that reported nothing about what it hid would
 * make "no matches" indistinguishable from "empty project".
 */
class RequirementRowsTest {

    // --- shape and order --------------------------------------------------------------------------

    @Test
    void returnsTheWholeTreeInParentBeforeChildOrder() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1", "Checkout");
        BrdRequirement r2 = f.add("R2", "Guests can check out");
        BrdRequirement r3 = f.add("R3", "Empty cart is rejected");
        BrdRequirement r4 = f.add("R4", "Saved cards");
        f.refines(r2, r1);
        f.refines(r3, r2);
        f.refines(r4, r1);

        RequirementPageDto page = RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 0, 100);

        assertThat(page.getRows()).extracting(RequirementRowDto::getHandle)
            .describedAs("depth-first: a parent is always emitted before its parts")
            .containsExactly("R1", "R2", "R3", "R4");
        assertThat(page.getRows()).extracting(RequirementRowDto::getDepth)
            .containsExactly(0, 1, 2, 1);
        assertThat(page.getMatches()).isEqualTo(4);
        assertThat(page.getContextParents()).isZero();
        assertThat(page.getExcluded()).isZero();
        assertThat(page.getTotal()).isEqualTo(4);
        assertThat(page.moreFollow()).isFalse();
    }

    @Test
    void carriesOwnAndSubtreeCountsSeparately() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1", "Checkout");   // coarse, no checks of its own
        BrdRequirement r2 = f.add("R2", "Guests");
        f.accepted(r2, CriterionState.PASSING);
        f.accepted(r2, CriterionState.FAILING);
        f.refines(r2, r1);

        RequirementPageDto page = RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 0, 100);
        RequirementRowDto parent = page.getRows().get(0);

        assertThat(parent.getOwn().gating())
            .describedAs("nothing verifies the coarse requirement itself")
            .isZero();
        assertThat(parent.getSubtree().gating()).isEqualTo(2);
        assertThat(parent.getSubtree().getPassing()).isEqualTo(1);
        assertThat(parent.getDescendants()).isEqualTo(1);
        assertThat(parent.hasChildren()).isTrue();
    }

    @Test
    void pagesAStableSequence() {
        Fixture f = new Fixture();
        for (int i = 1; i <= 10; i++) {
            f.add("R" + i, "requirement " + i);
        }

        RequirementPageDto first = RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 0, 4);
        RequirementPageDto second = RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 4, 4);
        RequirementPageDto last = RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 8, 4);

        assertThat(first.getRows()).extracting(RequirementRowDto::getHandle)
            .containsExactly("R1", "R2", "R3", "R4");
        assertThat(first.moreFollow()).isTrue();
        assertThat(second.getRows()).extracting(RequirementRowDto::getHandle)
            .containsExactly("R5", "R6", "R7", "R8");
        assertThat(last.getRows()).extracting(RequirementRowDto::getHandle)
            .containsExactly("R9", "R10");
        assertThat(last.moreFollow()).isFalse();
        // Every window reports the same totals — they describe the result, not the window.
        assertThat(first.getMatches()).isEqualTo(10);
        assertThat(last.getMatches()).isEqualTo(10);
    }

    @Test
    void clampsAnUnboundedRequest() {
        Fixture f = new Fixture();
        f.add("R1", "one");
        assertThat(RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 0, 0).getRows())
            .describedAs("max 0 means 'the default window', not 'no rows'")
            .hasSize(1);
        assertThat(RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 0, 999_999)
            .getRows()).hasSize(1);
    }

    // --- filtering must not orphan a match ---------------------------------------------------------

    @Test
    void aMatchDeepInTheTreeArrivesWithItsAncestorsAsContext() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1", "Checkout");
        BrdRequirement r2 = f.add("R2", "Guests can check out");
        BrdRequirement r3 = f.add("R3", "Order confirmation is emailed");
        BrdRequirement r4 = f.add("R4", "Unrelated");
        f.refines(r2, r1);
        f.refines(r3, r2);

        RequirementQuery q = new RequirementQuery();
        q.setSearch("emailed");
        RequirementPageDto page = RequirementRows.page(f.brd(), List.of(), q, 0, 100);

        assertThat(page.getRows()).extracting(RequirementRowDto::getHandle)
            .describedAs("the match, plus the chain that shows where it lives")
            .containsExactly("R1", "R2", "R3");
        assertThat(page.getRows()).extracting(RequirementRowDto::getContextOnly)
            .describedAs("only R3 matched; R1 and R2 are shape")
            .containsExactly(1, 1, 0);
        assertThat(page.getMatches())
            .describedAs("ancestors shown for context are NOT results")
            .isEqualTo(1);
        assertThat(page.getContextParents()).isEqualTo(2);
        assertThat(page.getExcluded())
            .describedAs("R4 is neither a match nor context, and the operator is told so")
            .isEqualTo(1);
        assertThat(page.getTotal()).isEqualTo(4);
    }

    @Test
    void aFilterThatMatchesNothingIsDistinguishableFromAnEmptyProject() {
        Fixture f = new Fixture();
        f.add("R1", "Checkout");
        f.add("R2", "Login");

        RequirementQuery q = new RequirementQuery();
        q.setSearch("nothing here matches this");
        RequirementPageDto page = RequirementRows.page(f.brd(), List.of(), q, 0, 100);

        assertThat(page.getRows()).isEmpty();
        assertThat(page.getMatches()).isZero();
        assertThat(page.getExcluded())
            .describedAs("two requirements exist and are being hidden — say so")
            .isEqualTo(2);
        assertThat(page.getTotal()).isEqualTo(2);
    }

    @Test
    void searchCoversHandleTitleStatementAndCheckText() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1", "Checkout");
        r1.setText("The basket becomes an order");
        BrdRequirement r2 = f.add("R2", "Delivery");
        AcceptanceCriterion c = f.accepted(r2, CriterionState.UNVERIFIED);
        c.setText("a courier is notified");

        assertThat(handles(f, search("checkout"))).containsExactly("R1");
        assertThat(handles(f, search("CHECKOUT")))
            .describedAs("case-insensitive")
            .containsExactly("R1");
        assertThat(handles(f, search("basket")))
            .describedAs("the statement text is searched")
            .containsExactly("R1");
        assertThat(handles(f, search("courier")))
            .describedAs("a check's own wording is searched - someone looking for 'courier' is as "
                + "likely to be thinking of the check as of the requirement")
            .containsExactly("R2");
        assertThat(handles(f, search("R2"))).containsExactly("R2");
    }

    // --- the typed filters ------------------------------------------------------------------------

    @Test
    void filtersByStatusKindAndNfrCategory() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1", "Checkout");
        r1.setStatus(RequirementStatus.DRAFT);
        BrdRequirement r2 = f.add("R2", "Login");
        f.accepted(r2, CriterionState.PASSING);          // evidence makes this IMPLEMENTED
        BrdRequirement r12 = f.add("R12", "Latency");
        r12.setKind(RequirementKind.NON_FUNCTIONAL);
        r12.setNfrCategory(NfrCategory.PERFORMANCE);

        RequirementQuery byStatus = new RequirementQuery();
        byStatus.setStatusCsv("DRAFT");
        assertThat(handles(f, byStatus)).containsExactly("R1");

        RequirementQuery implemented = new RequirementQuery();
        implemented.setStatusCsv("IMPLEMENTED");
        assertThat(handles(f, implemented))
            .describedAs("status is what the EVIDENCE supports, not the stored field")
            .containsExactly("R2");

        RequirementQuery byKind = new RequirementQuery();
        byKind.setKindCsv("NON_FUNCTIONAL");
        assertThat(handles(f, byKind)).containsExactly("R12");

        RequirementQuery byCategory = new RequirementQuery();
        byCategory.setNfrCategoryCsv("PERFORMANCE");
        assertThat(handles(f, byCategory)).containsExactly("R12");

        RequirementQuery either = new RequirementQuery();
        either.setStatusCsv("DRAFT,IMPLEMENTED");
        assertThat(handles(f, either))
            .describedAs("a CSV filter is a set of alternatives")
            .containsExactly("R1", "R2");
    }

    @Test
    void filtersByStaleEvidence() {
        Fixture f = new Fixture();
        BrdRequirement fresh = f.add("R1", "Fresh");
        f.accepted(fresh, CriterionState.PASSING);
        BrdRequirement staled = f.add("R2", "Reworded");
        AcceptanceCriterion c = f.accepted(staled, CriterionState.PASSING);
        c.setVerifiedAgainstContentRevision(1);
        staled.setContentRevision(2);

        RequirementQuery q = new RequirementQuery();
        q.setOnlyStale(1);
        assertThat(handles(f, q)).containsExactly("R2");
    }

    @Test
    void filtersByMalformedShape() {
        Fixture f = new Fixture();
        BrdRequirement r2 = f.add("R2", "Checkout");
        BrdRequirement r5 = f.add("R5", "Payments");
        BrdRequirement r7 = f.add("R7", "Tokens");
        f.refines(r7, r5);
        f.refines(r7, r2);

        RequirementQuery q = new RequirementQuery();
        q.setOnlyShapeWarnings(1);
        RequirementPageDto page = RequirementRows.page(f.brd(), List.of(), q, 0, 100);

        assertThat(page.getMatches()).isEqualTo(1);
        RequirementRowDto row = page.getRows().stream()
            .filter(x -> "R7".equals(x.getHandle())).findFirst().orElseThrow();
        assertThat(row.getShapeWarning())
            .describedAs("the row carries the same sentence the canvas shows")
            .contains("belongs in one place").contains("R2").contains("R5");
        assertThat(row.getShapeWarning())
            .describedAs("and never the internal relation name")
            .doesNotContain("REFINES");
    }

    // --- the backlog half -------------------------------------------------------------------------

    @Test
    void reportsWhichStoriesClaimAChecksAndWhatIsUnclaimed() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1", "Checkout");
        AcceptanceCriterion claimed = f.accepted(r1, CriterionState.PASSING);
        AcceptanceCriterion unclaimed = f.accepted(r1, CriterionState.UNVERIFIED);
        f.proposed(r1); // advisory: nobody should have claimed it, so it is not "unclaimed"

        Story s3 = story("S3", claimed.id());
        RequirementPageDto page =
            RequirementRows.page(f.brd(), List.of(s3), new RequirementQuery(), 0, 100);
        RequirementRowDto row = page.getRows().get(0);

        assertThat(row.getStoryKeysCsv()).isEqualTo("S3");
        assertThat(row.getUnclaimedChecks())
            .describedAs("one accepted check has no story; the proposed one does not count")
            .isEqualTo(1);
        assertThat(unclaimed.id()).isNotNull();

        RequirementQuery onlyUnclaimed = new RequirementQuery();
        onlyUnclaimed.setOnlyUnclaimed(1);
        assertThat(RequirementRows.page(f.brd(), List.of(s3), onlyUnclaimed, 0, 100).getMatches())
            .isEqualTo(1);

        // …and once a story claims it too, the requirement drops out of the unclaimed filter.
        Story s4 = story("S4", unclaimed.id());
        assertThat(RequirementRows.page(f.brd(), List.of(s3, s4), onlyUnclaimed, 0, 100).getMatches())
            .isZero();
        RequirementRowDto both = RequirementRows
            .page(f.brd(), List.of(s3, s4), new RequirementQuery(), 0, 100).getRows().get(0);
        assertThat(both.getStoryKeysCsv()).isEqualTo("S3,S4");
    }

    @Test
    void filtersByClaimingStory() {
        Fixture f = new Fixture();
        BrdRequirement r1 = f.add("R1", "Checkout");
        AcceptanceCriterion a = f.accepted(r1, CriterionState.PASSING);
        BrdRequirement r2 = f.add("R2", "Login");
        AcceptanceCriterion b = f.accepted(r2, CriterionState.PASSING);

        List<Story> stories = List.of(story("S3", a.id()), story("S9", b.id()));
        RequirementQuery q = new RequirementQuery();
        q.setClaimedByStory("S9");

        RequirementPageDto page = RequirementRows.page(f.brd(), stories, q, 0, 100);
        assertThat(page.getMatches()).isEqualTo(1);
        assertThat(page.getRows()).extracting(RequirementRowDto::getHandle).containsExactly("R2");
    }

    // --- relations, phrased for a reader ----------------------------------------------------------

    @Test
    void aGateReadsCorrectlyFromBothEnds() {
        // The assertion that matters most in this file. ONE edge, two sentences: the NFR constrains,
        // the requirement is constrained by. Render the same phrase on both rows and the screen claims
        // an ordinary requirement imposes a quality constraint on a non-functional one — backwards, and
        // invisible to anyone who has not gone looking.
        Fixture f = new Fixture();
        BrdRequirement checkout = f.add("R2", "Checkout");
        BrdRequirement latency = f.add("R12", "p95 under 200ms");
        latency.setKind(RequirementKind.NON_FUNCTIONAL);
        latency.setNfrCategory(NfrCategory.PERFORMANCE);
        f.edge(latency, checkout, RequirementRelation.GATES);

        RequirementPageDto page = RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 0, 100);

        assertThat(rowFor(page, "R12").getRelationsCsv())
            .describedAs("the NFR is the one doing the constraining")
            .isEqualTo("constrains R2");
        assertThat(rowFor(page, "R2").getRelationsCsv())
            .describedAs("and the requirement is on the receiving end")
            .isEqualTo("constrained by R12");
    }

    @Test
    void everyOtherRelationIsPhrasedFromTheRightEndToo() {
        Fixture f = new Fixture();
        BrdRequirement a = f.add("R1", "Sign-in");
        BrdRequirement b = f.add("R2", "Session store");
        BrdRequirement c = f.add("R3", "Guest mode");
        BrdRequirement d = f.add("R4", "Business rule");
        f.edge(a, b, RequirementRelation.DEPENDS_ON);
        f.edge(a, c, RequirementRelation.CONFLICTS_WITH);
        f.edge(a, d, RequirementRelation.DERIVED_FROM);

        RequirementPageDto page = RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 0, 100);

        assertThat(rowFor(page, "R1").getRelationsCsv())
            // "drawn from", not "from": the words are RequirementRelation's now, so the row, the
            // legend, the picker and the line of history all say the same thing (25.5).
            .isEqualTo("waits for R2;conflicts with R3;drawn from R4");
        assertThat(rowFor(page, "R2").getRelationsCsv())
            .describedAs("the other end of a dependency is not another dependency")
            .isEqualTo("needed by R1");
        assertThat(rowFor(page, "R3").getRelationsCsv())
            .describedAs("a conflict is symmetric, so it genuinely reads the same both ways")
            .isEqualTo("conflicts with R1");
        assertThat(rowFor(page, "R4").getRelationsCsv()).isEqualTo("led to R1");
    }

    @Test
    void theHierarchyIsNotRestatedAsABadge() {
        // The row is already indented under its parent. A "part of R1" badge beside it would state one
        // fact twice, and two statements of one fact are how they drift apart.
        Fixture f = new Fixture();
        BrdRequirement parent = f.add("R1", "Checkout");
        BrdRequirement child = f.add("R2", "Guest checkout");
        f.refines(child, parent);

        RequirementPageDto page = RequirementRows.page(f.brd(), List.of(), new RequirementQuery(), 0, 100);
        assertThat(rowFor(page, "R2").getRelationsCsv()).isEmpty();
        assertThat(rowFor(page, "R1").getRelationsCsv()).isEmpty();
        assertThat(rowFor(page, "R2").getDepth())
            .describedAs("…because the indent is what says it")
            .isEqualTo(1);
    }

    @Test
    void noRelationPhraseNamesAnInternalConstant() {
        Fixture f = new Fixture();
        BrdRequirement a = f.add("R1", "One");
        BrdRequirement b = f.add("R2", "Two");
        BrdRequirement nfr = f.add("R3", "Fast");
        nfr.setKind(RequirementKind.NON_FUNCTIONAL);
        f.edge(a, b, RequirementRelation.DEPENDS_ON);
        f.edge(a, b, RequirementRelation.CONFLICTS_WITH);
        f.edge(a, b, RequirementRelation.DERIVED_FROM);
        f.edge(nfr, a, RequirementRelation.GATES);
        f.refines(b, a);

        StringBuilder everyPhrase = new StringBuilder();
        for (RequirementRowDto row : RequirementRows
                .page(f.brd(), List.of(), new RequirementQuery(), 0, 100).getRows()) {
            everyPhrase.append(row.getRelationsCsv()).append(' ');
        }
        assertThat(everyPhrase.toString())
            .describedAs("relation constants are internal; a row speaks the operator's language")
            .doesNotContain("DEPENDS_ON").doesNotContain("CONFLICTS_WITH")
            .doesNotContain("DERIVED_FROM").doesNotContain("GATES").doesNotContain("REFINES");
        assertThat(everyPhrase.toString()).contains("waits for").contains("constrains");
    }

    @Test
    void anEmptyOrNullBrdIsAnEmptyPage() {
        assertThat(RequirementRows.page(null, List.of(), new RequirementQuery(), 0, 10).getRows())
            .isEmpty();
        assertThat(RequirementRows.page(new Brd(), List.of(), null, 0, 10).getTotal()).isZero();
    }

    @Test
    void aNullQueryIsTreatedAsUnfiltered() {
        Fixture f = new Fixture();
        f.add("R1", "Checkout");
        assertThat(RequirementRows.page(f.brd(), null, null, 0, 10).getMatches()).isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static RequirementQuery search(String text) {
        RequirementQuery q = new RequirementQuery();
        q.setSearch(text);
        return q;
    }

    /** The handles that actually MATCHED, ignoring rows present only as context. */
    private static List<String> handles(Fixture f, RequirementQuery q) {
        List<String> out = new ArrayList<>();
        for (RequirementRowDto row : RequirementRows.page(f.brd(), List.of(), q, 0, 100).getRows()) {
            if (!row.isContextOnly()) {
                out.add(row.getHandle());
            }
        }
        return out;
    }

    private static RequirementRowDto rowFor(RequirementPageDto page, String handle) {
        return page.getRows().stream().filter(r -> handle.equals(r.getHandle()))
            .findFirst().orElseThrow(() -> new AssertionError("no row for " + handle));
    }

    private static Story story(String key, UUID... criterionIds) {
        Story s = new Story();
        s.setId(UUID.randomUUID());
        s.setKey(key);
        s.setState(StoryState.READY);
        s.setCriterionIds(new ArrayList<>(List.of(criterionIds)));
        return s;
    }

    private static final class Fixture {
        private final List<BrdRequirement> reqs = new ArrayList<>();
        private final List<BrdEdge> edges = new ArrayList<>();

        BrdRequirement add(String handle, String title) {
            BrdRequirement r = new BrdRequirement(UUID.randomUUID(), handle, title, title,
                Priority.MEDIUM, RequirementStatus.ACTIVE, null);
            r.setCriteria(new ArrayList<>());
            reqs.add(r);
            return r;
        }

        AcceptanceCriterion accepted(BrdRequirement r, CriterionState state) {
            AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), "check", "T#m");
            c.setStatus(CriterionStatus.ACCEPTED);
            c.setVerification(state);
            r.criteria().add(c);
            return c;
        }

        AcceptanceCriterion proposed(BrdRequirement r) {
            AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), "suggested", null);
            c.setStatus(CriterionStatus.PROPOSED);
            c.setVerification(CriterionState.UNVERIFIED);
            r.criteria().add(c);
            return c;
        }

        void refines(BrdRequirement child, BrdRequirement parent) {
            edges.add(new BrdEdge(child.id(), parent.id(), RequirementRelation.REFINES));
        }

        /** A typed edge FROM {@code from} TO {@code to} — direction matters for the phrasing tests. */
        void edge(BrdRequirement from, BrdRequirement to, RequirementRelation relation) {
            edges.add(new BrdEdge(from.id(), to.id(), relation));
        }

        Brd brd() {
            return new Brd(UUID.randomUUID(), UUID.randomUUID(), 7, "test",
                new ArrayList<>(reqs), new ArrayList<>(edges), null, null);
        }
    }
}
