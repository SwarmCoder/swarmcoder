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

import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The story planner reads the project's standing rules, and is told a story says what the user
 * gets, never how it is built.
 *
 * <p>Harness runs 37, 38 and 39, 2026-09-25, Bookshelf demo: the technical document mandates
 * persistence through EclipseStore on the server, and the story planner — the one agent never shown
 * the rules — wrote "Add a persistence layer that saves the current books and their ratings to
 * localStorage…" three runs in a row. That sentence became the run's goal.
 */
class TheStoryPlannerIsToldTheProjectRulesTest {

    @TempDir
    Path storeDir;

    private static final String STORAGE_RULE = "Persistence uses EclipseStore through "
        + "zerozstack-store-eclipsestore. The server keeps the live Java objects in memory and "
        + "writes that object graph to disk.";

    @Test
    void theBriefingCarriesTheRulesInForceAndTheWhatNotHowInstruction() throws Exception {
        UUID project = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            put(store, rule(project, "storage-as-an-object-graph", STORAGE_RULE,
                GuidelineStatus.ACTIVE));
            put(store, rule(project, "retired-rule", "Use browser localStorage for everything.",
                GuidelineStatus.RETIRED));
            put(store, rule(UUID.randomUUID(), "another-projects-rule", "Use Spring Boot.",
                GuidelineStatus.ACTIVE));

            String brief = BacklogPlanning.brief(store, project);

            assertThat(brief).contains("HOW THIS PROJECT MUST BE BUILT");
            assertThat(brief).contains(STORAGE_RULE);
            assertThat(brief).contains(BacklogPlanning.STORIES_SAY_WHAT_NOT_HOW);
            assertThat(BacklogPlanning.STORIES_SAY_WHAT_NOT_HOW)
                .contains("A story says WHAT the user gets, never HOW it is built")
                .contains("NEVER name one a rule forbids or contradicts");
            assertThat(brief).as("a rule no longer in force is not shown")
                .doesNotContain("Use browser localStorage for everything");
            assertThat(brief).as("nor another project's").doesNotContain("Use Spring Boot");
            assertThat(brief.indexOf("COVERAGE"))
                .as("after what the plan must close, so the requirements still come first")
                .isLessThan(brief.indexOf("HOW THIS PROJECT MUST BE BUILT"));
        }
    }

    @Test
    void withNoRulesInForceTheBriefingIsExactlyWhatItWas() throws Exception {
        UUID project = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            String before = BacklogPlanning.brief(store, project);
            put(store, rule(project, "retired-rule", "Anything.", GuidelineStatus.RETIRED));

            assertThat(BacklogPlanning.rulesSection(store, project)).isEmpty();
            assertThat(BacklogPlanning.brief(store, project)).isEqualTo(before);
            assertThat(before).doesNotContain("HOW THIS PROJECT MUST BE BUILT")
                .doesNotContain("WHAT THESE RULES MEAN");
        }
    }

    private static LearnedGuideline rule(UUID project, String slug, String body,
                                         GuidelineStatus status) {
        return new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT, slug, body,
            new Provenance("stated", null, "bookshelf-tech-requirements.md"), 1.0, Instant.now(),
            0, status, project, null, 0);
    }

    private static void put(ArtifactStore store, LearnedGuideline rule) throws Exception {
        store.append(() -> {
            store.root().guidelines.put(rule.id(), rule);
            return null;
        }).get();
    }
}
