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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An answer to a question about a rule is remembered for the project (harness runs 53 and 55,
 * 2026-10-01): a rule keeps its id when it is reworded or gains an exception, a restatement of the
 * same document does not resurrect the old wording, and a rule's purpose and strength are kept.
 */
class ARuleAnswerIsRememberedTest {

    private static final String DOC = "hambook-tech.md";
    private static final String ORIGINAL = "All user-visible text lives in resource bundles.";

    @TempDir
    Path dir;

    @Test
    void aStatedRuleKeepsItsPurposeAndStrength() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());

            assertThat(rules.stateRule("Text in bundles", ORIGINAL, DOC, null,
                "so the text can be translated later", false)).isEmpty();
            assertThat(rules.stateRule("Fixed stack", "The server MUST use ZeroZ Stack.", DOC,
                null, "one stack, so every module builds the same way", true)).isEmpty();

            LearnedGuideline text = byTitle(rules, "Text in bundles");
            assertThat(text.purpose()).isEqualTo("so the text can be translated later");
            assertThat(text.hard()).isFalse();
            assertThat(byTitle(rules, "Fixed stack").hard()).isTrue();
            assertThat(rules.renderActive(12_000))
                .contains("Why it exists: so the text can be translated later")
                .contains("A HARD rule");
        }
    }

    @Test
    void aRewordingKeepsTheIdAndSurvivesTheDocumentBeingAppliedAgain() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.stateRule("Text in bundles", ORIGINAL, DOC, null, "translation", true);
            LearnedGuideline before = byTitle(rules, "Text in bundles");

            String reworded = ORIGINAL + " Not in the browser client, which has no catalog yet.";
            assertThat(rules.reword(before.id(), reworded, "the operator's answer")).isEmpty();
            assertThat(rules.reword(before.id(), reworded, "again")).as("idempotent").isEmpty();

            LearnedGuideline after = byTitle(rules, "Text in bundles");
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.markdownBody()).isEqualTo(reworded);
            assertThat(after.statedWording()).isEqualTo(ORIGINAL);
            assertThat(after.purpose()).as("meaning carried across revisions").isEqualTo("translation");
            assertThat(after.hard()).isTrue();

            // The same document applied again, in its original words.
            rules.supersedeRulesFrom(DOC);
            rules.stateRule("Text in bundles", ORIGINAL, DOC, null, "translation", true);

            assertThat(rules.activeRules()).hasSize(1);
            LearnedGuideline restated = rules.activeRules().get(0);
            assertThat(restated.id()).isEqualTo(before.id());
            assertThat(restated.markdownBody())
                .as("the answer is remembered: the old wording does not come back")
                .isEqualTo(reworded);
        }
    }

    @Test
    void allowAddsOneExceptionAndKeepMakesTheRuleHard() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.stateRule("Text in bundles", ORIGINAL, DOC, null, null, false);
            UUID id = byTitle(rules, "Text in bundles").id();

            assertThat(rules.allowException(id, "allowed for 'Show the menu': no browser catalog"))
                .isEmpty();
            assertThat(rules.allowException(id, "allowed for 'Show the menu': no browser catalog"))
                .isEmpty();
            assertThat(byTitle(rules, "Text in bundles").markdownBody())
                .startsWith(ORIGINAL)
                .containsOnlyOnce("Exception: allowed for 'Show the menu'");

            assertThat(rules.keep(id)).isEmpty();
            assertThat(byTitle(rules, "Text in bundles").hard()).isTrue();
            assertThat(rules.reword(UUID.randomUUID(), "x", null)).startsWith("error:");
        }
    }

    private static LearnedGuideline byTitle(ProjectRules rules, String title) {
        return rules.activeRules().stream().filter(r -> title.equals(r.title())).findFirst()
            .orElseThrow();
    }
}
