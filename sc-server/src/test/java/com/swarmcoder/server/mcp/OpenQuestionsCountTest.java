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
package com.swarmcoder.server.mcp;

import com.swarmcoder.console.api.Backlog;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backlog listing's count of questions from builds is the open ones of current builds, not
 * every question the project ever recorded.
 */
class OpenQuestionsCountTest {

    @Test
    void answeredWithdrawnAndEarlierBuildsQuestionsAreNotCounted() {
        UUID earlier = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        UUID sentBack = UUID.randomUUID();
        Story rebuilt = story("S1", StoryState.RUNNING, earlier, current);
        Story waiting = story("S2", StoryState.READY, sentBack);
        List<Decision> decisions = List.of(
            question(earlier, DecisionState.PENDING),
            question(current, DecisionState.RESOLVED),
            question(current, DecisionState.PENDING),
            question(sentBack, DecisionState.PENDING),
            question(null, DecisionState.PENDING));

        Backlog backlog = new Backlog(UUID.randomUUID(), List.of(), List.of(rebuilt, waiting),
            List.of(), decisions);

        assertThat(SupervisorMcpTools.openQuestions(backlog))
            .as("the current build's open question and the one that names no build")
            .isEqualTo(2);
    }

    private static Story story(String key, StoryState state, UUID... runs) {
        return new Story(UUID.randomUUID(), UUID.randomUUID(), key, StoryKind.DELIVERY, "A story",
            null, state, new ArrayList<>(), new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null,
            null, "agent", new ArrayList<>(List.of(runs)), null, null, null, null, Instant.now(),
            Instant.now());
    }

    private static Decision question(UUID runId, DecisionState state) {
        return new Decision(UUID.randomUUID(), runId, DecisionKind.BLOCKED_TASK, "a question",
            state, state == DecisionState.RESOLVED ? "answered" : null, Instant.now());
    }
}
