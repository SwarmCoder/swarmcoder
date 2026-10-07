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

import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.RuleScope;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rule records which part of the project it applies to, checked against the project's tree,
 * and a worker is sent the rules for the part its task may write (owner's decision 2026-10-07,
 * section 65). What the judge and verification read is not narrowed.
 */
class ARuleIsRecordedForAPartOfTheProjectTest {

    private static final String DOC = "tech.md";

    /** Run 89's shape: a browser client, a server and what they share. */
    private static ProjectParts parts() {
        return ProjectParts.ofFiles(List.of(
            "pom.xml",
            "client/pom.xml",
            "client/src/main/java/app/client/LogbookScreen.java",
            "server/pom.xml",
            "server/src/main/java/app/server/LogbookServiceImpl.java",
            "server/src/main/java/app/server/store/LogStore.java",
            "shared/src/main/java/app/shared/LogbookService.java"));
    }

    @TempDir
    Path dir;

    @Test
    void theModulesAndFoldersOfAProjectComeFromItsFiles() {
        ProjectParts parts = parts();

        assertThat(parts.modules()).containsExactly("client", "server", "shared");
        assertThat(parts.holds("server/src/main/java/app/server/store")).isTrue();
        assertThat(parts.holds("server")).isTrue();
        assertThat(parts.holds("frontend")).isFalse();
        assertThat(parts.holds("")).as("the root is the whole project, not a part").isFalse();
        assertThat(ProjectParts.of(null, null).modules()).isEmpty();
    }

    @Test
    void aWorkerIsSentTheRulesForItsPartAndTheJudgeAndTheChecksStillReadEveryRule()
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.setParts(ARuleIsRecordedForAPartOfTheProjectTest::parts);
            assertThat(rules.modules()).containsExactly("client", "server", "shared");

            rules.stateRule("No frameworks", "Do not use Spring.", DOC, null, null, true, null);
            rules.stateRule("Screens are descriptors", "A screen is drawn from a descriptor.",
                DOC, null, null, false, List.of("client/"));
            rules.stateRule("Stores save each level", "Every changed level needs its own save.",
                DOC, null, null, true, List.of("server"));
            LearnedGuideline client = rules.activeRules().stream()
                .filter(r -> r.title().equals("Screens are descriptors")).findFirst().orElseThrow();
            assertThat(client.appliesTo()).containsExactly("client");
            assertThat(rules.setCheck(client.id(), "mvn -o -q verify", 0)).isEmpty();

            RuleScope.Briefing server = rules.briefingFor(
                List.of("server/src/main/java/app/server/LogbookServiceImpl.java"), 12_000);

            assertThat(server.sent()).isEqualTo(2);
            assertThat(server.inForce()).isEqualTo(3);
            assertThat(server.text()).contains("No frameworks").contains("Stores save each level")
                .doesNotContain("Screens are descriptors");
            assertThat(server.text().length())
                .as("fewer rules is a smaller opening").isLessThan(rules.renderActive(12_000).length());

            assertThat(rules.activeRules()).as("the judge reads every rule").hasSize(3);
            assertThat(rules.activeChecks()).as("every rule's check still runs, whatever the "
                + "task wrote").extracting(c -> c.command()).containsExactly("mvn -o -q verify");
            assertThat(rules.renderActive(12_000)).as("the planning roles read every rule")
                .contains("Screens are descriptors");
            assertThat(rules.briefingFor(List.of(), 12_000).sent())
                .as("a task with no write set may be anywhere").isEqualTo(3);
        }
    }

    @Test
    void aPartTheTreeDoesNotHoldRecordsTheRuleForTheWholeProject() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.setParts(ARuleIsRecordedForAPartOfTheProjectTest::parts);

            assertThat(rules.stateRule("Browser code", "No JavaScript.", DOC, null, null, false,
                List.of("client", "frontend"))).as("recorded, not refused").isEmpty();

            assertThat(rules.activeRules().get(0).appliesTo()).isEmpty();
            assertThat(rules.briefingFor(List.of("server/A.java"), 12_000).sent()).isEqualTo(1);
        }
    }

    @Test
    void withNoTreeConnectedEveryRuleIsForTheWholeProjectAndEveryWorkerIsSentEveryRule()
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());

            rules.stateRule("Screens", "A screen is drawn from a descriptor.", DOC, null, null,
                false, List.of("client"));

            assertThat(rules.activeRules().get(0).appliesTo()).isEmpty();
            assertThat(rules.modules()).isEmpty();
            RuleScope.Briefing briefing = rules.briefingFor(List.of("server/A.java"), 12_000);
            assertThat(briefing.sent()).isEqualTo(1);
            assertThat(briefing.text()).isEqualTo(rules.renderActive(12_000));
        }
    }

    /** A document stated again says where its rules apply NOW; and retiring keeps the part. */
    @Test
    void aRuleStatedAgainTakesThePartOfTheNewStatement() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            rules.setParts(ARuleIsRecordedForAPartOfTheProjectTest::parts);
            rules.stateRule("Screens", "A screen is drawn from a descriptor.", DOC, null, null,
                false, List.of("client"));
            UUID id = rules.activeRules().get(0).id();

            assertThat(rules.setStatus(id, GuidelineStatus.RETIRED)).isEmpty();
            assertThat(rules.all().get(0).appliesTo()).containsExactly("client");

            rules.stateRule("Screens", "A screen is drawn from a descriptor.", DOC, null, null,
                false, List.of("client", "shared"));
            assertThat(rules.activeRules()).hasSize(1);
            assertThat(rules.activeRules().get(0).id()).isEqualTo(id);
            assertThat(rules.activeRules().get(0).appliesTo()).containsExactly("client", "shared");
        }
    }

    /**
     * The store reads a scoped rule back, and a rule written before the field existed (its
     * part never set) is a rule of the whole project.
     */
    @Test
    void aRulesPartSurvivesTheStoreAndARuleWithoutOneIsForEveryone() throws Exception {
        UUID project = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, project);
            rules.setParts(ARuleIsRecordedForAPartOfTheProjectTest::parts);
            rules.stateRule("Screens", "A screen is drawn from a descriptor.", DOC, null, null,
                false, List.of("client"));
            rules.put(new LearnedGuideline(old, 1, GuidelineScope.PROJECT, "an-old-rule",
                "Recorded before rules had a part.", new Provenance("stated", null), 1.0,
                Instant.now(), 0, GuidelineStatus.ACTIVE, project, null, 0));
        }
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ProjectRules rules = new ProjectRules(store, project);
            rules.setParts(ARuleIsRecordedForAPartOfTheProjectTest::parts);

            assertThat(rules.activeRules()).hasSize(2);
            assertThat(rules.activeRules().stream().filter(r -> r.id().equals(old)).findFirst()
                .orElseThrow().appliesTo()).isEmpty();
            RuleScope.Briefing server = rules.briefingFor(List.of("server/A.java"), 12_000);
            assertThat(server.sent()).isEqualTo(1);
            assertThat(server.text()).contains("Recorded before rules had a part")
                .doesNotContain("A screen is drawn");
            assertThat(rules.briefingFor(List.of("client/src/main/java/A.java"), 12_000).sent())
                .isEqualTo(2);
        }
    }
}
