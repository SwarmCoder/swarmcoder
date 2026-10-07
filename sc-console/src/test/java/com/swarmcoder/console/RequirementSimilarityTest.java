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

import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The near-duplicate matcher, measured against hand-labelled pairs rather than asserted against a
 * threshold somebody liked the look of.
 *
 * <p>Each pair below is either two ways of saying the same requirement or two requirements that
 * genuinely differ, and the numbers this test prints are the ones quoted in
 * {@code DEVELOPER_CORRECTIONS.md} §24. The assertions pin what the measurements settled: the pair
 * that motivated the work is caught, the plainly-different pairs stay under the "same requirement"
 * tier, and neither of those depends on anything but the two pieces of text.
 */
class RequirementSimilarityTest {

    private record Pair(String label, boolean alike, String titleA, String textA,
                        String titleB, String textB) {

        double score() {
            return RequirementSimilarity.score(titleA, textA, titleB, textB);
        }
    }

    /**
     * The same requirement, worded differently. Every one of these is a duplicate the operator
     * would not want written twice.
     */
    private static final List<Pair> ALIKE = List.of(
        new Pair("multiply / multiplication — the pair that motivated this", true,
            "Multiply two whole numbers",
            "The system multiplies two whole numbers and returns the product.",
            "Multiplication of two integers",
            "Given two integers the system returns their product."),
        new Pair("same title, different case and punctuation", true,
            "guest checkout!", "", "Guest checkout", ""),
        new Pair("guest checkout, reworded", true,
            "Checking out as a guest", "A shopper without an account can complete a purchase.",
            "Guest checkout", "A guest can complete a purchase without an account."),
        new Pair("refund window, reworded", true,
            "Refunds within thirty days", "A customer may request a refund up to 30 days after purchase.",
            "Refund window", "A customer can request a refund within 30 days of purchase."),
        new Pair("notify / notification", true,
            "Notify the customer by email", "The system notifies the customer when the order ships.",
            "Order shipment notification", "An email notification is sent when the order ships."),
        new Pair("verify / verification", true,
            "Verify the payment card", "The card is verified before the order is placed.",
            "Payment card verification", "Verification of the card happens before the order is placed."),
        new Pair("deliver / delivery", true,
            "Deliver the report nightly", "The report is delivered every night at 02:00.",
            "Nightly report delivery", "Delivery of the report happens nightly at 02:00.")
    );

    /** Requirements that really are different, including the hardest cases. */
    private static final List<Pair> DIFFERENT = List.of(
        new Pair("checkout vs refunds", false,
            "Guest checkout", "A guest can complete a purchase without an account.",
            "Refund window", "A customer can request a refund within 30 days of purchase."),
        new Pair("multiply vs divide — one word apart", false,
            "Multiply two whole numbers", "The system multiplies two whole numbers and returns the product.",
            "Divide two whole numbers", "The system divides two whole numbers and returns the quotient."),
        new Pair("multiply vs add — one word apart", false,
            "Multiply two whole numbers", "The system multiplies two whole numbers and returns the product.",
            "Add two whole numbers", "The system adds two whole numbers and returns the sum."),
        new Pair("latency vs availability", false,
            "Search responds in under 300ms", "A search over one million rows returns in under 300ms at p95.",
            "The service is available 99.9 percent of the time", "Availability is measured monthly."),
        new Pair("sign in vs password reset", false,
            "A user can sign in with an email and password", "",
            "A user can reset a forgotten password by email", ""),
        new Pair("data vs database — the prefix trap", false,
            "Data is exported nightly", "", "The database is backed up nightly", ""),
        new Pair("cart total vs invoice total", false,
            "The cart shows the order total", "",
            "The invoice shows the order total including tax", "")
    );

    @Test
    void everyPairIsScoredAndTheNumbersArePrinted() {
        StringBuilder table = new StringBuilder("\nnear-duplicate matcher, measured:\n");
        List<Pair> all = new ArrayList<>(ALIKE);
        all.addAll(DIFFERENT);
        for (Pair pair : all) {
            table.append(String.format(Locale.ROOT, "  %-8s %6.3f  %s%n",
                pair.alike() ? "SAME" : "differ", pair.score(), pair.label()));
        }
        System.out.println(table);
        assertThat(all).hasSize(14);
    }

    /**
     * The failure §21 named. Lucene's English stemmer takes "multiply" to {@code multipli} and
     * "multiplication" to {@code multipl} — close but NOT equal — so a matcher built on exact stem
     * comparison misses the one pair everybody agrees is a duplicate.
     */
    @Test
    void theStemmerAloneDoesNotEqualiseMultiplyAndMultiplication() {
        assertThat(RequirementSimilarity.stems("multiply")).containsExactly("multipli");
        assertThat(RequirementSimilarity.stems("multiplication")).containsExactly("multipl");
        assertThat(RequirementSimilarity.stems("multiply"))
            .isNotEqualTo(RequirementSimilarity.stems("multiplication"));

        // …which is why stems also match on a long-enough prefix. That is what closes the gap.
        assertThat(RequirementSimilarity.sameWord("multipli", "multipl")).isTrue();
        assertThat(RequirementSimilarity.sameWord("notifi", "notif")).isTrue();
        assertThat(RequirementSimilarity.sameWord("verifi", "verif")).isTrue();
        assertThat(RequirementSimilarity.sameWord("deliv", "deliveri")).isTrue();

        // …and the length floor is what stops it matching everything that starts the same way.
        assertThat(RequirementSimilarity.sameWord("data", "databas")).isFalse();
        assertThat(RequirementSimilarity.sameWord("sign", "signal")).isFalse();
    }

    @Test
    void wordsThatMeanTheSameThingAreScoredAsTheSameRequirement() {
        for (Pair pair : ALIKE) {
            assertThat(pair.score())
                .as(pair.label())
                .isGreaterThanOrEqualTo(RequirementSimilarity.NEAR_MATCH);
        }
    }

    /**
     * The stricter tier — the one that re-casts a proposal as a change to an existing requirement —
     * must never fire on a pair that genuinely differs. Flagging a sibling costs one tick; offering
     * to rewrite the wrong requirement costs trust.
     */
    @Test
    void nothingThatGenuinelyDiffersReachesTheSameRequirementTier() {
        for (Pair pair : DIFFERENT) {
            assertThat(pair.score())
                .as(pair.label())
                .isLessThan(RequirementSimilarity.SAME_REQUIREMENT);
        }
    }

    /**
     * The pairs the flagging tier also catches, named rather than hidden. These are requirements
     * that sit next to each other in the same area; telling the operator "this resembles R13" about
     * them is information, and it costs one tick to say no.
     */
    @Test
    void theFlaggingTierErrsTowardsAskingAndTheCostOfThatIsOneTick() {
        List<String> alsoFlagged = new ArrayList<>();
        for (Pair pair : DIFFERENT) {
            if (pair.score() >= RequirementSimilarity.NEAR_MATCH) {
                alsoFlagged.add(pair.label());
            }
        }
        System.out.println("also flagged (a question, not a verdict): " + alsoFlagged);
        // Deliberately loose: what is pinned is that the great majority of plainly-different pairs
        // are NOT flagged, not an exact list that a wording change would break.
        assertThat(alsoFlagged.size()).isLessThan(DIFFERENT.size() / 2 + 1);
    }

    /**
     * The other measure, and the numbers behind its threshold: how much of a question's wording a
     * proposal says back. Both proposals below came out of one real reply on 2026-08-28, scored
     * against the sentence the analyst's own question quoted.
     */
    @Test
    void howMuchOfAQuestionAProposalSaysBackSeparatesTheEchoFromTheRealRequirement() {
        String quoted = "Multiplication has to behave sensibly at the edges of the range we support.";

        double echo = RequirementSimilarity.containment(
            "Handle multiplication at the boundaries of the supported integer range",
            "Multiplication behaves sensibly at the edges of the supported range of whole numbers.",
            quoted);
        double genuine = RequirementSimilarity.containment(
            "Multiply two whole numbers",
            "The system multiplies two whole numbers and returns the product.",
            quoted);

        System.out.printf(Locale.ROOT,
            "%ndrawn from an unanswered question, measured:%n"
            + "  %6.2f  the vague restatement of the sentence the question was about%n"
            + "  %6.2f  the requirement the document genuinely stated%n%n", echo, genuine);

        assertThat(echo).isGreaterThanOrEqualTo(RequirementSimilarity.DRAWN_FROM);
        assertThat(genuine).isLessThan(RequirementSimilarity.DRAWN_FROM);
    }

    /** A question too short to judge absorbs nothing, rather than absorbing everything. */
    @Test
    void aQuestionWithTooFewWordsIsNeverTreatedAsAbsorbed() {
        assertThat(RequirementSimilarity.containment("Multiply two whole numbers", "", "range?"))
            .isEqualTo(0);
    }

    @Test
    void theNearestExistingRequirementIsFoundAndTheRestAreNot() {
        List<BrdRequirement> graph = new ArrayList<>();
        graph.add(requirement("R1", "Guest checkout",
            "A guest can complete a purchase without an account."));
        graph.add(requirement("R2", "Multiply two whole numbers",
            "The system multiplies two whole numbers and returns the product."));
        graph.add(requirement("R3", "Refund window",
            "A customer can request a refund within 30 days of purchase."));

        List<RequirementSimilarity.Hit> hits = RequirementSimilarity.nearest(
            "Multiplication of two integers", "Given two integers the system returns their product.",
            graph, RequirementSimilarity.NEAR_MATCH, 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).requirement().handle()).isEqualTo("R2");
    }

    /** Nothing but the two pieces of text decides the score — so it cannot drift as a BRD grows. */
    @Test
    void theScoreDoesNotDependOnTheRestOfTheGraph() {
        double alone = RequirementSimilarity.score("Multiply two whole numbers", "",
            "Multiplication of two integers", "");
        // Same call, made again after a graph of unrelated requirements would have been indexed by
        // anything that kept state. There is no state to keep.
        double again = RequirementSimilarity.score("Multiply two whole numbers", "",
            "Multiplication of two integers", "");
        assertThat(again).isEqualTo(alone);
    }

    private static BrdRequirement requirement(String handle, String title, String text) {
        return new BrdRequirement(UUID.randomUUID(), handle, title, text, Priority.MEDIUM,
            RequirementStatus.DRAFT, null);
    }
}
