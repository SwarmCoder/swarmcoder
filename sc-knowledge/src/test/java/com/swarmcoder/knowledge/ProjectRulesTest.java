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

import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project's rules are store objects with one identity — their wording — and nothing else
 * (author decision 2026-09-02).
 *
 * <p>These pin the behaviour that used to be spread over nine tests of the file mechanism: a
 * stated rule is in force at once; the same wording is the same rule, however often it is stated
 * and whatever it was called; a document stated again replaces its previous statement without
 * duplicating what it still says; a machine proposal waits for a person and never carries a
 * check; one project never sees another's rules; and nothing touches a file.
 */
class ProjectRulesTest {

    private static final String DOC = "bookshelf-tech-requirements.md";

    @TempDir
    Path dir;

    @Test
    void aStatedRuleIsInForceAtOnceAndNamesItsDocument() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());

            assertThat(rules.stateRule("Storage is an object graph",
                "Persistence is EclipseStore. Every changed level of nesting needs its own save "
                    + "call.", DOC)).isEmpty();

            List<LearnedGuideline> active = rules.activeRules();
            assertThat(active).hasSize(1);
            LearnedGuideline rule = active.get(0);
            assertThat(rule.status()).isEqualTo(GuidelineStatus.ACTIVE);
            assertThat(rule.title()).isEqualTo("Storage is an object graph");
            assertThat(rule.slug()).isEqualTo("storage-is-an-object-graph");
            assertThat(rule.provenance().source()).isEqualTo("stated");
            assertThat(rule.provenance().document()).isEqualTo(DOC);
            assertThat(rule.confidence()).isEqualTo(1.0);
            assertThat(rules.renderActive(12_000))
                .contains("HOW THIS PROJECT MUST BE BUILT")
                .contains("Storage is an object graph")
                .contains("EclipseStore");
            assertThat(Files.list(dir).map(p -> p.getFileName().toString()))
                .describedAs("the store directory is the only thing on disk — no rule file")
                .containsExactly("store");
        }
    }

    /**
     * A rule keeps the document's own sentence(s) it was drawn from, verbatim, alongside its
     * (possibly paraphrased) body — so a rule is never ONLY a paraphrase, and
     * {@code RulesVersusManifest} always has the document's own wording to fall back on even when
     * a rule's body dropped the artifact's name.
     */
    @Test
    void aStatedRuleKeepsTheDocumentsOwnSentenceItWasDrawnFrom() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());

            assertThat(rules.stateRule("Storage is an object graph",
                "Persistence via EclipseStore object graph.", DOC,
                "Persistence uses EclipseStore through `zerozstack-store-eclipsestore`."))
                .isEmpty();

            LearnedGuideline rule = rules.activeRules().get(0);
            assertThat(rule.provenance().excerpt())
                .isEqualTo("Persistence uses EclipseStore through `zerozstack-store-eclipsestore`.");
            // The paraphrase is still what the agents are briefed with — the excerpt is provenance,
            // not a second rendering of the rule.
            assertThat(rules.renderActive(12_000)).contains("Persistence via EclipseStore");
        }
    }

    /** A rule with no excerpt to quote (a hand-written one, or an analyst reply that gave none). */
    @Test
    void aStatedRuleWithNoExcerptToQuoteRecordsNone() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());

            assertThat(rules.stateRule("No Spring", "There is no Spring in this project.", DOC))
                .isEmpty();

            assertThat(rules.activeRules().get(0).provenance().excerpt()).isNull();
        }
    }

    /**
     * The same wording is the same rule. Stated twice it exists once; stated again after being
     * retired it comes back under the same id rather than as a second copy.
     */
    @Test
    void theSameWordingIsTheSameRuleWhateverItIsCalled() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.stateRule("No Spring", "There is no Spring in this project.", DOC);
            rules.stateRule("Forbidden frameworks", "There is no Spring in this project.", DOC);
            rules.stateRule("No Spring", "  there is NO spring in this project  ", DOC);

            assertThat(rules.all()).describedAs("one rule, not three").hasSize(1);
            UUID id = rules.all().get(0).id();
            assertThat(rules.all().get(0).title())
                .describedAs("the latest statement names it")
                .isEqualTo("No Spring");

            rules.setStatus(id, GuidelineStatus.RETIRED);
            assertThat(rules.activeRules()).isEmpty();
            rules.stateRule("No Spring", "There is no Spring in this project.", DOC);
            assertThat(rules.activeRules()).extracting(LearnedGuideline::id).containsExactly(id);
            assertThat(rules.all().get(0).revision())
                .describedAs("stated three times, retired once, stated again: five revisions of "
                    + "one rule, never a second rule")
                .isEqualTo(5);
        }
    }

    /**
     * A document stated again replaces what it said before: what it still says survives under
     * its old id, what it dropped is retired and can be switched back on, and a rule from a
     * different document is not touched. This is the 44-rules-for-eleven defect, closed at the
     * object rather than at a filename.
     */
    @Test
    void aRestatedDocumentReplacesItsRulesWithoutDuplicatingWhatItStillSays() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.stateRule("The stack is fixed", "ZeroZ Stack on Java 21 with Maven.", DOC);
            rules.stateRule("Server jar stays unshaded", "Never shade the server jar.", DOC);
            rules.stateRule("Single user", "One user; no accounts and no sharing.", "scope.md");
            UUID stack = rules.activeRules().stream()
                .filter(r -> r.slug().equals("the-stack-is-fixed")).findFirst().orElseThrow().id();

            // Second reading: the stack rule is worded the same, the jar rule is worded
            // differently, and a new rule appears.
            assertThat(rules.supersedeRulesFrom(DOC)).isEqualTo(2);
            rules.stateRule("Fixed stack", "ZeroZ Stack on Java 21 with Maven.", DOC);
            rules.stateRule("Keep jars unshaded",
                "The server jar is not a fat jar; module jars stay separate.", DOC);
            rules.stateRule("Offline build", "Build offline from the repository root.", DOC);

            List<LearnedGuideline> active = rules.activeRules();
            assertThat(active).extracting(LearnedGuideline::slug).containsExactlyInAnyOrder(
                "the-stack-is-fixed", "keep-jars-unshaded", "offline-build", "single-user");
            assertThat(active).extracting(LearnedGuideline::id)
                .describedAs("the unchanged rule kept its id")
                .contains(stack);
            assertThat(rules.all()).hasSize(5);
            assertThat(rules.all().stream()
                .filter(r -> r.status() == GuidelineStatus.RETIRED)
                .map(LearnedGuideline::slug))
                .describedAs("the old wording of the jar rule is history, one click from back")
                .containsExactly("server-jar-stays-unshaded");

            assertThat(rules.supersedeRulesFrom(null)).isZero();
            assertThat(rules.supersedeRulesFrom("  ")).isZero();
        }
    }

    /**
     * A machine proposal is a suggestion: it enters no prompt until a person switches it on, it
     * is refused when the project already says the same thing, and a check on it is never run.
     */
    @Test
    void aMachineProposalWaitsForAPersonAndNeverCarriesACheck() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            LearnedGuideline proposed = rules.propose("use-jackson",
                "Use Jackson for JSON, not Gson, in this repository.", false);
            assertThat(proposed).isNotNull();
            assertThat(proposed.status()).isEqualTo(GuidelineStatus.PROPOSED);
            assertThat(proposed.provenance().source()).isEqualTo("extraction");
            assertThat(proposed.confidence()).isEqualTo(0.5);
            assertThat(rules.renderActive(12_000)).isNull();
            assertThat(rules.propose("jackson-not-gson",
                "use jackson for json, not gson, in this repository", false))
                .describedAs("the same lesson in the same words is not written twice")
                .isNull();

            assertThat(rules.setCheck(proposed.id(), "true", 0))
                .describedAs("a model-proposed rule may not carry a command")
                .startsWith("error:");
            assertThat(rules.setStatus(proposed.id(), GuidelineStatus.ACTIVE)).isEmpty();
            assertThat(rules.renderActive(12_000)).contains("Jackson");
            assertThat(rules.activeChecks()).isEmpty();

            LearnedGuideline auto = rules.propose("tabs", "Indent with tabs, never spaces.", true);
            assertThat(auto.status()).describedAs("autoPromote").isEqualTo(GuidelineStatus.ACTIVE);
        }
    }

    @Test
    void aCheckBelongsToARulePersonDecidedAndReachesVerification() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.stateRule("No Java records", "Never declare a Java record.", null);
            LearnedGuideline rule = rules.activeRules().get(0);
            assertThat(rule.provenance().source())
                .describedAs("no document means written by hand")
                .isEqualTo("human");

            assertThat(rules.setCheck(rule.id(), "! grep -rq 'public record' src/main/java", 45))
                .isEmpty();
            List<GuidelineCheck> checks = rules.activeChecks();
            assertThat(checks).hasSize(1);
            assertThat(checks.get(0).slug()).isEqualTo("no-java-records");
            assertThat(checks.get(0).command()).isEqualTo("! grep -rq 'public record' src/main/java");
            assertThat(checks.get(0).effectiveTimeoutSeconds()).isEqualTo(45);
            assertThat(rules.renderActive(12_000))
                .describedAs("the agent is told a command will decide the matter")
                .contains("This rule is CHECKED");

            assertThat(rules.setCheck(rule.id(), "a\nb", 0)).startsWith("error:");
            assertThat(rules.setCheck(rule.id(), "", 0)).isEmpty();
            assertThat(rules.activeChecks()).isEmpty();

            rules.setStatus(rule.id(), GuidelineStatus.RETIRED);
            assertThat(rules.setCheck(rule.id(), "true", 0)).isEmpty();
            assertThat(rules.activeChecks())
                .describedAs("a retired rule's check does not run")
                .isEmpty();
        }
    }

    @Test
    void oneProjectNeverSeesAnothersRules() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules a = new ProjectRules(store, UUID.randomUUID());
            ProjectRules b = new ProjectRules(store, UUID.randomUUID());
            a.stateRule("No records", "Never use a Java record.", DOC);
            b.stateRule("Tabs", "Indent with tabs.", DOC);

            assertThat(a.renderActive(12_000)).contains("record").doesNotContain("tabs");
            assertThat(b.renderActive(12_000)).contains("tabs").doesNotContain("record");
            assertThat(b.supersedeRulesFrom(DOC))
                .describedAs("B restating the document touches only B's rules")
                .isEqualTo(1);
            assertThat(a.activeRules()).hasSize(1);

            UUID aRule = a.activeRules().get(0).id();
            assertThat(b.setStatus(aRule, GuidelineStatus.RETIRED)).startsWith("error:");
            assertThat(b.setCheck(aRule, "true", 0)).startsWith("error:");
            assertThat(a.setStatus(UUID.randomUUID(), GuidelineStatus.RETIRED)).startsWith("error:");
            assertThat(a.stateRule("", "   ", DOC)).startsWith("error:");
        }
    }

    /** The prefix must be byte-identical across assemblies, whatever order the map yields. */
    @Test
    void theRenderingIsDeterministic() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            for (int i = 0; i < 20; i++) {
                rules.stateRule("Rule " + i, "Rule number " + i + " says something distinct.", DOC);
            }
            String first = rules.renderActive(12_000);
            for (int i = 0; i < 5; i++) {
                assertThat(rules.renderActive(12_000)).isEqualTo(first);
            }
            assertThat(rules.activeRules()).extracting(LearnedGuideline::slug).isSorted();
        }
    }
}
