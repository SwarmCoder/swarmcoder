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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architect's reference material is sized by the ARCHITECT's own model, not by the workers'
 * (2026-09-25). In production the architect is a cloud role on the generic 32,768-token shape, so
 * it must see exactly the 9,000-character reference and 6,000-character research results it always
 * did; an architect whose role states a bigger room — the harness's, which runs on the workers'
 * DeepSeek endpoint — gets proportionally more.
 */
class TheArchitectsMaterialIsSizedByItsOwnRoomTest {

    /** A marker 20,000 characters into a file: past the old 6,000 cut, inside 30,720. */
    private static final String DEEP = "MARKERTWENTYTHOUSANDIN";

    private static final class SizedResearch implements ArchitectResearch {
        final AtomicInteger referenceAskedFor = new AtomicInteger();

        @Override
        public String reference(int maxChars) {
            referenceAskedFor.set(maxChars);
            return "widgetlib renders widget layouts.";
        }

        @Override
        public String lookupApi(String query) {
            return "nothing";
        }

        @Override
        public String searchCode(String query) {
            return "nothing";
        }

        @Override
        public String readFile(String address) {
            return "r".repeat(20_000) + DEEP + "s".repeat(20_000);
        }

        @Override
        public String listFolder(String address) {
            return "nothing";
        }
    }

    @Test
    void anArchitectOnTheGenericCloudShapeSeesTodaysFigures() throws Exception {
        Seen seen = designWith(null);

        assertThat(seen.referenceChars).isEqualTo(9_000);
        assertThat(seen.researchResult)
            .contains("…(truncated)")
            .doesNotContain(DEEP);
    }

    @Test
    void anArchitectWithTheDeepSeekRoomSeesProportionallyMore() throws Exception {
        Seen seen = designWith(ModelShapes.get("deepseek-v4-flash-ds4"));

        assertThat(seen.referenceChars).isEqualTo(46_080);
        assertThat(seen.researchResult)
            .as("one research result may be 30,720 characters in 262,144 tokens of room")
            .contains(DEEP);
    }

    private record Seen(int referenceChars, String researchResult) {}

    private static Seen designWith(com.swarmcoder.inference.ModelQuirks quirks) throws Exception {
        SizedResearch research = new SizedResearch();
        AtomicReference<String> afterTheRead = new AtomicReference<>("");
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            if (conversation.contains("RESULT for")) {
                afterTheRead.set(conversation);
                return "NOTES: widgets are rendered by WidgetRenderer.";
            }
            if (conversation.contains("research what you do not know")) {
                return "TOOL read_file widgetlib/WidgetRenderer.java";
            }
            return """
                {"decisions":[],"contracts":[],"risks":[],"missingRequirements":[]}
                """;
        })) {
            VllmClient client = quirks == null
                ? new VllmClient(llm.baseUrl(), null, "test-model", true)
                : new VllmClient(llm.baseUrl(), null, "test-model", quirks);
            ArchitectClient architect = new ArchitectClient(client, new CloudGate(10_000_000, null),
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), research);
            architect.design("Render the widget board", scope());
        }
        return new Seen(research.referenceAskedFor.get(), afterTheRead.get());
    }

    private static StoryScope scope() {
        var requirement = new BrdRequirement(UUID.randomUUID(), "R1", "Widget board",
            "The board renders its widgets", Priority.HIGH, RequirementStatus.ACTIVE, null);
        var criterion = new AcceptanceCriterion(UUID.randomUUID(), "the board renders its widgets",
            "BoardTest#renders");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        var brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "BRD",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());
        var story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Widget board", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        return StoryScope.resolve(brd, story);
    }
}
