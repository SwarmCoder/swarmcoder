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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.Task;
import com.swarmcoder.inference.VllmClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judge is told to check the diff against EVERY house rule. It must therefore be shown the
 * rules that decide this diff, and told the names of any it could not be shown.
 *
 * <p><b>Where this test comes from.</b> The rule set below is the shape of the operator's real one
 * on 2026-09-01: 70 rule files, 44 of them ACTIVE and 21,660 characters of them, against a judge
 * window of 3,000. The rules reached this class as ONE already-concatenated string, so the only
 * available cut was the first 3,000 characters — about a tenth — and which tenth was decided by
 * the order the rules happened to be rendered in. The rest were replaced by the words
 * "[house rules truncated]", which tell the judge neither what was dropped nor that anything
 * dropped still applied.
 *
 * <p>Every test here fails against that behaviour.
 */
class JudgeSeesTheRulesThatMatterTest {

    private static final String OK = "{\"score\": 0.8, \"rationale\": \"fine\"}";

    /** The diff under judgement: it is about storage, and it saves a nested object. */
    private static final String STORAGE_DIFF = """
        --- a/BookStorage.java
        +++ b/BookStorage.java
        +class BookStorage {
        +    void addBook(Shelf shelf, Book book) {
        +        shelf.books.add(book);
        +        storage.store(shelf.books);
        +    }
        +}
        """;

    /**
     * The rule that decides this diff — a person stated it, and it is about the very thing the
     * diff does. Deliberately given a slug that sorts to the very bottom, because that is exactly
     * the rule the old first-n-characters cut threw away.
     */
    private static LearnedGuideline theRuleThatDecidesThisDiff() {
        return rule("zz-eclipsestore-nested-save", "Save every changed nesting level",
            "Persistence is an EclipseStore object graph. A store call does not cascade into "
                + "objects that are already persisted, so every nesting level you changed needs "
                + "its own explicit store call.",
            "stated", 1.0);
    }

    /**
     * The defect itself, in the shape it really had. Forty rules that a person also stated, so
     * provenance and confidence separate nothing and the ONLY thing left to sort on is the slug —
     * which is exactly the situation on the operator's project, where all 44 active rules came
     * from one document. The rule that decides this diff sorts last, so the old cut at the first
     * n characters threw away the only rule that mattered and kept forty that did not.
     */
    @Test
    void aRuleAboutThisDiffIsShownEvenWhenItSortsLastAmongEqualRules() throws Exception {
        List<LearnedGuideline> rules = new ArrayList<>(statedNoise(40));
        rules.add(theRuleThatDecidesThisDiff());

        String brief = briefFor(rules);

        assertThat(brief)
            .as("the one rule that decides this diff must be in front of the judge")
            .contains("Save every changed nesting level")
            .contains("does not cascade");
        assertThat(brief.length())
            .as("and it must not have got there by printing all 40 of the others too")
            .isLessThan(rules.size() * 400);
    }

    @Test
    void everyRuleThatDidNotFitIsNamedAndSaidToStillApply() throws Exception {
        List<LearnedGuideline> rules = new ArrayList<>();
        rules.add(theRuleThatDecidesThisDiff());
        rules.addAll(machineNoise(40));

        String brief = briefFor(rules);

        assertThat(brief)
            .as("a rule the judge cannot read is still in force, and it must know that")
            .contains("ALSO IN FORCE")
            .contains("left out for space")
            .doesNotContain("[house rules truncated]");
        // The names of what was dropped, not a count. A name is enough for the judge to notice
        // that a diff is walking into a rule whose wording it was never given.
        assertThat(brief).contains("Machine lesson 40");
    }

    @Test
    void aPersonsRuleOutranksAMachinesWhenBothCannotFit() throws Exception {
        List<LearnedGuideline> rules = new ArrayList<>(machineNoise(40));
        rules.add(rule("zz-no-spring", "There is no Spring in this project",
            "Spring, Spring Boot, JPA, Hibernate and SQL migrations are forbidden here.",
            "stated", 1.0));

        String brief = briefFor(rules);

        int person = brief.indexOf("There is no Spring in this project");
        int machine = brief.indexOf("Machine lesson 1\n");
        assertThat(person).as("the operator's own rule must be printed").isGreaterThanOrEqualTo(0);
        assertThat(person)
            .as("a rule a person stated comes before one a model proposed from a transcript")
            .isLessThan(machine < 0 ? Integer.MAX_VALUE : machine);
    }

    @Test
    void asmallRuleSetIsShownWholeWithNothingLeftOut() throws Exception {
        String brief = briefFor(List.of(theRuleThatDecidesThisDiff(),
            rule("no-spring", "No Spring", "There is no Spring here.", "stated", 1.0)));

        assertThat(brief).contains("Save every changed nesting level").contains("No Spring");
        assertThat(brief)
            .as("nothing was dropped, so nothing must claim anything was")
            .doesNotContain("ALSO IN FORCE");
    }

    /**
     * The fallback for a caller that only has the rendered text. It is the old behaviour and it
     * stays — but a bare "[house rules truncated]" tells the judge nothing, and it is what let the
     * same prompt say "check the diff against every rule" over a tenth of them.
     */
    @Test
    void thePlainTextFallbackSaysTheListIsIncompleteRatherThanJustTruncated() {
        String rendered = "RULE\n".repeat(4_000);

        String section = new JudgeClient(null, null, rendered).ruleSection(STORAGE_DIFF);

        assertThat(section)
            .doesNotContain("[house rules truncated]")
            .contains("MORE RULES ARE IN FORCE")
            .contains("Treat the list above as incomplete");
    }

    /** The whole judge call, end to end, so the assertion is on what the model really received. */
    private static String briefFor(List<LearnedGuideline> rules) throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(OK))) {
            new JudgeClient(new VllmClient(fake.baseUrl(), "", "fake-judge", true), null,
                null, rules).judge(candidate(), task());
            assertThat(fake.requests).hasSize(1);
            return fake.requests.get(0);
        }
    }

    /** Forty rules a model proposed after reading a transcript, about nothing in this diff. */
    private static List<LearnedGuideline> machineNoise(int count) {
        return noise(count, "aa-machine-%03d", "Machine lesson ", "extraction", 0.5);
    }

    /**
     * Forty rules a PERSON stated, about nothing in this diff. The operator's real set is all of
     * this kind, which is why provenance alone cannot decide and relevance has to.
     */
    private static List<LearnedGuideline> statedNoise(int count) {
        return noise(count, "aa-stated-%03d", "Stated rule ", "stated", 1.0);
    }

    private static List<LearnedGuideline> noise(int count, String slugFormat, String titlePrefix,
                                                String source, double confidence) {
        List<LearnedGuideline> noise = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            noise.add(rule(String.format(slugFormat, n), titlePrefix + n,
                "Lesson number " + n + " about tooling: prefer the documented command over "
                    + "guesswork when unpacking archives, inspecting jars or searching wide "
                    + "directory trees, because the wide search times out in this environment.",
                source, confidence));
        }
        return noise;
    }

    private static LearnedGuideline rule(String slug, String title, String body, String source,
                                         double confidence) {
        LearnedGuideline rule = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            slug, body, new Provenance(source, null, "bookshelf-tech-requirements.md"), confidence,
            Instant.now(), 0, GuidelineStatus.ACTIVE);
        rule.setTitle(title);
        return rule;
    }

    private static Task task() {
        Task t = new Task();
        t.setId(UUID.randomUUID());
        t.setTitle("Add a book to a shelf");
        t.setInstructions("Store the book on the shelf.");
        return t;
    }

    private static CandidateSolution candidate() {
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0, "swarm/w0",
            null, STORAGE_DIFF, null, new ClusterId("hash", 1), null,
            CandidateState.SURVIVED, null);
    }
}
