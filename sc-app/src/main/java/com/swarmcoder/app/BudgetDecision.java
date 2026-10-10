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
package com.swarmcoder.app;

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Story;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * The {@code BUDGET_EXTENSION} decision raised when a cloud token limit is passed: it names the
 * project, the story and the run, says which limit was passed and how much input and output had
 * been used, and offers to extend that limit by the same amount again or to stop.
 *
 * <p>Answering "extend" is {@link CloudGate#extendForRun(UUID)} with the decision's {@code runId},
 * followed by the usual re-drive of the parked run ({@code WorkflowEngine.advance}).
 */
final class BudgetDecision {

    private BudgetDecision() {}

    /** Writes the decision for {@code breach}, once, to the store. */
    static void raise(ArtifactStore store, CloudGate.Breach breach) {
        String brief = brief(breach, nameOfProject(store, breach.projectId()),
            labelOfStory(store, breach.storyId()));
        store.append(() -> {
            UUID id = UUID.randomUUID();
            store.root().decisions.put(id, new Decision(id, breach.runId(),
                DecisionKind.BUDGET_EXTENSION, brief, DecisionState.PENDING, null, Instant.now()));
            return null;
        });
    }

    /** What an extension raised, as one sentence for whoever answered. */
    static String describe(CloudGate.Extension extension) {
        CloudGate.Cap cap = extension.limitNow();
        StringBuilder sb = new StringBuilder("The ")
            .append(extension.level().name().toLowerCase(Locale.ROOT)).append(" limit is now ");
        String sep = "";
        if (cap.total() > 0) {
            sb.append(String.format(Locale.ROOT, "%,d", cap.total()))
                .append(" input and output tokens together");
            sep = ", ";
        }
        if (cap.input() > 0) {
            sb.append(sep).append(String.format(Locale.ROOT, "%,d", cap.input()))
                .append(" input tokens");
            sep = ", ";
        }
        if (cap.output() > 0) {
            sb.append(sep).append(String.format(Locale.ROOT, "%,d", cap.output()))
                .append(" output tokens");
        }
        return sb.append('.').toString();
    }

    /** The question, in words an operator can answer without looking anything up. */
    static String brief(CloudGate.Breach breach, String project, String story) {
        String which = switch (breach.level()) {
            case RUN -> "this run's";
            case STORY -> "the story's (all its runs together)";
            case PROJECT -> "the project's (all its stories together)";
        };
        String counts = switch (breach.direction()) {
            case TOTAL -> "input and output tokens together";
            case INPUT -> "input tokens";
            case OUTPUT -> "output tokens";
        };
        return "A run stopped because it passed " + which + " limit on cloud " + counts + ".\n\n"
            + "- Project: " + project + "\n"
            + "- Story: " + story + "\n"
            + "- Run: " + breach.runId() + "\n"
            + "- Limit passed: " + String.format(Locale.ROOT, "%,d", breach.limit()) + " " + counts
            + " (" + breach.level().name().toLowerCase(Locale.ROOT) + " limit)\n"
            + "- Used so far: " + String.format(Locale.ROOT, "%,d", breach.used().input())
            + " input tokens and " + String.format(Locale.ROOT, "%,d", breach.used().output())
            + " output tokens\n\n"
            + "Answer \"extend\" to raise that limit by the same amount again and let the run "
            + "continue, or \"stop\" to leave the run stopped.";
    }

    private static String nameOfProject(ArtifactStore store, UUID projectId) {
        Project project = projectId == null ? null : store.getProject(projectId);
        return project == null || project.name() == null ? "(unknown)" : project.name();
    }

    private static String labelOfStory(ArtifactStore store, UUID storyId) {
        Story story = storyId == null ? null : store.getStory(storyId);
        if (story == null) {
            return "(not part of a story)";
        }
        return (story.key() == null ? "" : story.key() + " ")
            + (story.title() == null ? "" : story.title());
    }
}
