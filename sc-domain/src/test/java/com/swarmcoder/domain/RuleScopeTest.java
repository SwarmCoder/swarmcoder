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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rule records the part of the project it applies to, and work on another part is not sent it
 * (owner's decision 2026-10-07, section 65). Run 89's shape: one server file written, the
 * browser client's rules sent with it.
 */
class RuleScopeTest {

    private static final UUID PROJECT = UUID.randomUUID();

    private static LearnedGuideline rule(String slug, String... appliesTo) {
        LearnedGuideline rule = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            slug, "the body of " + slug, new Provenance("stated", null), 1.0, Instant.now(), 0,
            GuidelineStatus.ACTIVE, PROJECT, null, 0);
        rule.setAppliesTo(List.of(appliesTo));
        return rule;
    }

    private static List<String> slugs(RuleScope.Selection selection) {
        return selection.sent().stream().map(LearnedGuideline::slug).toList();
    }

    @Test
    void workOnOnePartIsSentTheRulesOfTheWholeProjectAndOfThatPart() {
        List<LearnedGuideline> rules = List.of(rule("everywhere"), rule("client-only", "client"),
            rule("server-only", "server"), rule("store-only", "server/src/main/java/app/store"));

        RuleScope.Selection server = RuleScope.forPaths(rules,
            List.of("server/src/main/java/app/LogbookServiceImpl.java"));

        assertThat(slugs(server)).containsExactly("everywhere", "server-only");
        assertThat(server.inForce()).isEqualTo(4);
        assertThat(server.narrowed()).isTrue();
    }

    @Test
    void aWriteSetThatIsAFolderIsSentTheRulesOfEveryFolderInsideIt() {
        List<LearnedGuideline> rules = List.of(rule("client-only", "client"),
            rule("store-only", "server/src/main/java/app/store"));

        assertThat(slugs(RuleScope.forPaths(rules, List.of("server/**"))))
            .containsExactly("store-only");
        assertThat(slugs(RuleScope.forPaths(rules, List.of("server\\src\\main\\java\\app\\store\\"))))
            .containsExactly("store-only");
    }

    @Test
    void aFolderWhoseNameOnlyStartsTheSameIsAnotherFolder() {
        List<LearnedGuideline> rules = List.of(rule("client-only", "client"));

        assertThat(RuleScope.forPaths(rules, List.of("client-api/src/main/java/A.java")).sent())
            .isEmpty();
        assertThat(RuleScope.forPaths(rules, List.of("client/src/main/java/A.java")).sent())
            .hasSize(1);
    }

    @Test
    void workWithNoWriteSetOrOnTheWholeRepositoryIsSentEveryRule() {
        List<LearnedGuideline> rules = List.of(rule("everywhere"), rule("client-only", "client"));

        assertThat(RuleScope.forPaths(rules, Set.of()).sent()).hasSize(2);
        assertThat(RuleScope.forPaths(rules, null).sent()).hasSize(2);
        assertThat(RuleScope.forPaths(rules, List.of(".")).sent()).hasSize(2);
        assertThat(RuleScope.forPaths(rules, List.of("/")).narrowed()).isFalse();
    }

    /** A rule nobody is told is worse than a rule everybody is told. */
    @Test
    void aRuleWhoseFolderTheProjectNoLongerHasGoesToEveryone() {
        List<LearnedGuideline> rules = List.of(rule("client-only", "client"),
            rule("renamed-away", "web"));

        RuleScope.Selection server = RuleScope.forPaths(rules, List.of("server/A.java"),
            folder -> folder.equals("client") || folder.equals("server"));

        assertThat(slugs(server)).containsExactly("renamed-away");
    }

    @Test
    void aScopeIsKeptOnlyWhenEveryEntryIsAPartOfTheProject() {
        Set<String> parts = Set.of("client", "server", "server/src/main/java/app/store");

        assertThat(RuleScope.checked(List.of("server/", "./client", "server"), parts::contains))
            .as("normalized, without repeats, sorted").containsExactly("client", "server");
        assertThat(RuleScope.checked(List.of("client", "frontend"), parts::contains))
            .as("one unknown entry: the whole project, not the entries that happened to be right")
            .isEmpty();
        assertThat(RuleScope.checked(List.of("../elsewhere"), parts::contains)).isEmpty();
        assertThat(RuleScope.checked(List.of("client"), null))
            .as("no tree to check against: nothing is kept").isEmpty();
        assertThat(RuleScope.checked(null, parts::contains)).isEmpty();
        assertThat(RuleScope.checked(List.of(" ", ""), parts::contains)).isEmpty();
    }

    /** Every place that rebuilds a rule through the constructor goes through this copy. */
    @Test
    void aRuleKeepsItsPartOfTheProjectWhenItIsRevised() {
        LearnedGuideline first = rule("client-only", "client");
        LearnedGuideline next = new LearnedGuideline(first.id(), 2, first.scope(), first.slug(),
            first.markdownBody(), first.provenance(), first.confidence(), first.lastUsed(), 0,
            GuidelineStatus.RETIRED, PROJECT, null, 0).carryingMeaningFrom(first);

        assertThat(next.appliesTo()).containsExactly("client");
        assertThat(rule("everywhere").appliesTo()).as("never null").isEmpty();
    }
}
