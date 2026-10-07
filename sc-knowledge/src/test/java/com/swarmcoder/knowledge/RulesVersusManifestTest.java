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

import com.swarmcoder.domain.LibraryDoc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check behind run {@code ede2068b}: four workers spent 15-22 turns each rediscovering, by
 * hand, that the plan told them to use EclipseStore while the server's pom declared no such
 * dependency — none of them could fix it, since workers may only write under {@code src/main/java}.
 * This is the comparison that should have parked the run at PLAN instead, in milliseconds, before
 * any worker was dispatched.
 */
class RulesVersusManifestTest {

    private static final List<String> POMS =
        List.of("bookshelf-demo-server/pom.xml");

    /** The real rule, verbatim from {@code dev/bookshelf-tech-requirements.md}. */
    private static final String ECLIPSESTORE_RULE =
        "- Storage is an object graph, not a database\n"
        + "  Persistence uses **EclipseStore** through `zerozstack-store-eclipsestore`. The "
        + "server keeps the live Java objects in memory and writes that object graph to disk.\n";

    // ------------------------------------------------------------ the build agrees: no finding

    @Test
    void aRuleNamingAnArtifactTheBuildDeclaresIsNoFinding() {
        List<LibraryDoc> declared = List.of(
            new LibraryDoc("com.zeroz4j:zerozstack-store-eclipsestore", "0.8.0-SNAPSHOT", ""));

        List<RulesVersusManifest.Finding> findings =
            RulesVersusManifest.check(ECLIPSESTORE_RULE, declared, POMS);

        assertThat(findings).isEmpty();
    }

    /** A {@code ${property}} version on the declared dependency still counts as declared. */
    @Test
    void aPropertyVersionOnTheDeclaredDependencyStillCountsAsDeclared() {
        List<LibraryDoc> declared = List.of(
            new LibraryDoc("com.zeroz4j:zerozstack-store-eclipsestore", "${zeroz4j.version}", ""));

        List<RulesVersusManifest.Finding> findings =
            RulesVersusManifest.check(ECLIPSESTORE_RULE, declared, POMS);

        assertThat(findings).isEmpty();
    }

    // ---------------------------------------------------------- the build disagrees: one finding

    @Test
    void aRuleNamingAnArtifactTheBuildDoesNotDeclareIsOneFindingWithTheExactWording() {
        // The demo server's real pom before the fix: helidon and apt, no eclipsestore.
        List<LibraryDoc> declared = List.of(
            new LibraryDoc("com.zeroz4j:zerozstack-server-helidon", "0.8.0-SNAPSHOT", ""),
            new LibraryDoc("com.zeroz4j:zerozstack-apt", "0.8.0-SNAPSHOT", ""));

        List<RulesVersusManifest.Finding> findings =
            RulesVersusManifest.check(ECLIPSESTORE_RULE, declared, POMS);

        assertThat(findings).hasSize(1);
        RulesVersusManifest.Finding finding = findings.get(0);
        assertThat(finding.artifact()).isEqualTo("zerozstack-store-eclipsestore");
        assertThat(finding.ruleExcerpt())
            .as("the rule's own wording, not a paraphrase")
            .isEqualTo("- Storage is an object graph, not a database\n"
                + "  Persistence uses **EclipseStore** through `zerozstack-store-eclipsestore`");
        assertThat(finding.inspectedPoms()).isEqualTo(POMS);
    }

    /** A Maven coordinate (group:artifact), not just a backticked bare id, is recognised. */
    @Test
    void aFullMavenCoordinateNamesTheSameMissingArtifact() {
        String rule = "- Uses com.zeroz4j:zerozstack-store-eclipsestore for persistence.\n";

        List<RulesVersusManifest.Finding> findings =
            RulesVersusManifest.check(rule, List.of(), POMS);

        assertThat(findings).extracting(RulesVersusManifest.Finding::artifact)
            .containsExactly("zerozstack-store-eclipsestore");
    }

    // --------------------------------------------------------------------------- precision

    /**
     * Ordinary hyphenated prose is not an artifact name. Each of these phrases matches the bare
     * artifact-id shape the spec cares about, and none of them sits in backticks or right after a
     * word that introduces a dependency — so none should be flagged.
     */
    @Test
    void hyphenatedProseThatIsNotAnArtifactNameIsNoFinding() {
        String rule = "- Keep it simple\n"
            + "  This must be an end-to-end, read-only build for a single-user setup, with "
            + "nothing left half-finished.\n";

        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(rule, List.of(), POMS);

        assertThat(findings).isEmpty();
    }

    /** No rules at all — nothing to compare, nothing found. */
    @Test
    void blankRuleTextIsNoFinding() {
        assertThat(RulesVersusManifest.check("", List.of(), POMS)).isEmpty();
        assertThat(RulesVersusManifest.check(null, List.of(), POMS)).isEmpty();
    }

    /** A rule mentioning nothing artifact-shaped at all is left alone. */
    @Test
    void ruleWithNoArtifactMentionIsNoFinding() {
        String rule = "- Keep the module jars separate on a plain classpath.\n";

        assertThat(RulesVersusManifest.check(rule, List.of(), POMS)).isEmpty();
    }

    /** Two rules naming two different missing artifacts produce two separate findings. */
    @Test
    void twoRulesEachNamingAMissingArtifactProduceTwoFindings() {
        String rules = "- Storage\n"
            + "  Persistence uses EclipseStore through `zerozstack-store-eclipsestore`.\n"
            + "- Transport\n"
            + "  The browser talks to the server via `zerozstack-transport-ws`.\n";

        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(rules, List.of(), POMS);

        assertThat(findings).extracting(RulesVersusManifest.Finding::artifact)
            .containsExactlyInAnyOrder("zerozstack-store-eclipsestore", "zerozstack-transport-ws");
    }

    // ------------------------------------------------ the document, not only the analyst's rewording

    private static final String DOC_NAME = "bookshelf-tech-requirements.md";

    /**
     * The analyst's own statement of the rule (author decision 2026-08-31: a stated rule is the
     * ANALYST's wording) — and the analyst dropped the one thing this whole class exists to catch:
     * the artifact's name. "EclipseStore" carries no hyphen and no backticks, so the extractor's
     * precision rules correctly find nothing here — the document is the only place the artifact was
     * actually named.
     */
    private static final String RULE_WITH_THE_ARTIFACT_PARAPHRASED_AWAY =
        "- Storage is an object graph, not a database\n"
        + "  Persistence via EclipseStore object graph. The server keeps the live Java objects in "
        + "memory and writes that object graph to disk.\n";

    /** The document the rule above was drawn from — the real wording, backticks and all. */
    private static final String DOC_TEXT =
        "Persistence uses **EclipseStore** through `zerozstack-store-eclipsestore`. The server "
        + "keeps the live Java objects in memory and writes that object graph to disk.";

    @Test
    void aRuleThatParaphrasedAwayTheArtifactIsCaughtByScanningTheDocumentItCameFrom() {
        List<RulesVersusManifest.NamedDocument> documents =
            List.of(new RulesVersusManifest.NamedDocument(DOC_NAME, DOC_TEXT));

        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(
            RULE_WITH_THE_ARTIFACT_PARAPHRASED_AWAY, documents, List.of(), POMS);

        assertThat(findings).hasSize(1);
        RulesVersusManifest.Finding finding = findings.get(0);
        assertThat(finding.artifact()).isEqualTo("zerozstack-store-eclipsestore");
        assertThat(finding.source())
            .describedAs("named the document, not attributed to \"the rules\"")
            .isEqualTo(DOC_NAME);
        assertThat(finding.sourceLabel())
            .isEqualTo("the technical document \"" + DOC_NAME + "\"");
        assertThat(finding.ruleExcerpt())
            .as("the document's own sentence, not the rule's paraphrase of it")
            .contains("Persistence uses **EclipseStore** through `zerozstack-store-eclipsestore`");
    }

    /** The same paraphrased rule and the same document — but now the pom agrees, so nothing fires. */
    @Test
    void theSameDocumentIsNoFindingOnceThePomDeclaresTheArtifact() {
        List<RulesVersusManifest.NamedDocument> documents =
            List.of(new RulesVersusManifest.NamedDocument(DOC_NAME, DOC_TEXT));
        List<LibraryDoc> declared = List.of(
            new LibraryDoc("com.zeroz4j:zerozstack-store-eclipsestore", "0.8.0-SNAPSHOT", ""));

        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(
            RULE_WITH_THE_ARTIFACT_PARAPHRASED_AWAY, documents, declared, POMS);

        assertThat(findings).isEmpty();
    }

    /** A document that only ever uses hyphenated prose is left alone, exactly like a rule is. */
    @Test
    void aDocumentNamingOnlyHyphenatedProseIsNoFinding() {
        List<RulesVersusManifest.NamedDocument> documents = List.of(new RulesVersusManifest.NamedDocument(
            DOC_NAME, "This must be an end-to-end, read-only build for a single-user setup, "
                + "with nothing left half-finished."));

        List<RulesVersusManifest.Finding> findings =
            RulesVersusManifest.check("", documents, List.of(), POMS);

        assertThat(findings).isEmpty();
    }

    /** No documents at all behaves exactly as the three-argument overload always has. */
    @Test
    void noDocumentsIsTheSameAsTheThreeArgumentOverload() {
        List<RulesVersusManifest.Finding> withEmptyDocuments =
            RulesVersusManifest.check(ECLIPSESTORE_RULE, List.of(), List.of(), POMS);
        List<RulesVersusManifest.Finding> threeArg =
            RulesVersusManifest.check(ECLIPSESTORE_RULE, List.of(), POMS);

        assertThat(withEmptyDocuments).extracting(RulesVersusManifest.Finding::artifact)
            .isEqualTo(threeArg.stream().map(RulesVersusManifest.Finding::artifact).toList());
        assertThat(threeArg).allMatch(f -> f.source() == null);
    }

    // -------------------------------------------------- the build's own modules, not a dependency

    /** The demo's real three modules — {@code dev/bookshelf-tech-requirements.md} names all three
     * this way, and its root aggregator's artifactId is {@code bookshelf-demo}. */
    private static final RulesVersusManifest.OwnBuild BOOKSHELF_DEMO_BUILD =
        new RulesVersusManifest.OwnBuild(
            Set.of("bookshelf-demo", "bookshelf-demo-shared", "bookshelf-demo-client",
                "bookshelf-demo-server"),
            Set.of("com.swarmcoder.demo.bookshelf"),
            Set.of());

    /** The technical document's own sentence naming one of the build's own modules — this is what
     * run {@code ede2068b} actually parked on: not the EclipseStore sentence, but the analyst
     * having kept this one too now that documents are scanned in full. */
    @Test
    void aDocumentSentenceNamingTheBuildsOwnModuleIsNoFinding() {
        List<RulesVersusManifest.NamedDocument> documents = List.of(new RulesVersusManifest.NamedDocument(
            DOC_NAME, "- `bookshelf-demo-server` -- everything the server runs."));

        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check("", documents,
            List.of(), POMS, BOOKSHELF_DEMO_BUILD);

        assertThat(findings).isEmpty();
    }

    /** The real missing dependency alongside it is still caught — the fix narrows the extractor,
     * it does not blunt it. */
    @Test
    void aGenuinelyUndeclaredArtifactIsStillFoundAlongsideTheBuildsOwnModules() {
        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(ECLIPSESTORE_RULE,
            List.of(), List.of(), POMS, BOOKSHELF_DEMO_BUILD);

        assertThat(findings).extracting(RulesVersusManifest.Finding::artifact)
            .containsExactly("zerozstack-store-eclipsestore");
    }

    /** A backticked path into the repository, not a bare artifact name, is never a finding. */
    @Test
    void aBacktickedRepositoryPathIsNoFinding() {
        String rule = "- Layout\n"
            + "  The server's sources live under `bookshelf-demo-server/src/main/java`.\n";

        List<RulesVersusManifest.Finding> findings =
            RulesVersusManifest.check(rule, List.of(), POMS, BOOKSHELF_DEMO_BUILD);

        assertThat(findings).isEmpty();
    }

    /** The root aggregator itself — not a compiling module, never a dependency either. */
    @Test
    void theRootAggregatorsOwnArtifactIdIsNoFinding() {
        String rule = "- Build order\n"
            + "  Compile `bookshelf-demo` from the repository root so the three modules build in "
            + "order.\n";

        List<RulesVersusManifest.Finding> findings =
            RulesVersusManifest.check(rule, List.of(), POMS, BOOKSHELF_DEMO_BUILD);

        assertThat(findings).isEmpty();
    }
}
