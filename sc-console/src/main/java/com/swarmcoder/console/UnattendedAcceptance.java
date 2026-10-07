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

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;

import java.util.UUID;

/**
 * The single question the unattended pilot is allowed to answer for the operator: is this story so
 * plainly finished that a person looking at it would have pressed Accept without thinking?
 *
 * <p><b>Why this is narrow on purpose.</b> The rule it works under says definition of done is human,
 * and the reason behind that rule is still true: "the tests pass" and "this is what I asked for" are
 * not the same claim. Nothing here decides the second one. What it does is notice the one case where
 * the machine has already proved the first claim completely and honestly, and where a human verdict
 * would therefore be a rubber stamp — and it refuses everything else, which goes on waiting until
 * morning exactly as it does today.
 *
 * <p><b>What "completely and honestly" already means here.</b> A story only reaches REVIEW when every
 * requirement-check it promised came back PASSED, and PASSED needs a positive match against a test id
 * the runner actually reported as having run and passed. A check whose test did not run is UNKNOWN,
 * never PASSED, and a story with a single UNKNOWN or FAILED check is sent to BLOCKED instead. So the
 * evidence bar is not being lowered here; it is being read.
 *
 * <p><b>The exceptions this refuses.</b>
 *
 * <ul>
 *   <li><b>A story that claims no requirement-check.</b> The freeform escape hatch reaches REVIEW on
 *       a vacuous truth — every check passed because there were none. Nothing was proved, so there is
 *       nothing to rubber-stamp, and this is precisely the story a person has to look at.</li>
 *   <li><b>A story whose run stopped to ask something.</b> An unanswered question is the definition of
 *       needing a person.</li>
 *   <li><b>A story whose run did not finish.</b> A run still going, or one that ended without ever
 *       being planned, has not produced the evidence this is reading.</li>
 *   <li><b>A story that never recorded the code it delivered.</b> Nothing to put on the delivery
 *       branch means the next story would find nothing there.</li>
 * </ul>
 */
final class UnattendedAcceptance {

    private UnattendedAcceptance() {}

    /**
     * @param acceptable true when the machine may accept this story on its own
     * @param reason     why not, in the operator's words, when it may not — shown on the card in the
     *                   morning so nobody has to work out why it is still sitting there
     */
    record Verdict(boolean acceptable, String reason) {
        static final Verdict YES = new Verdict(true, null);
        static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }

    static Verdict judge(ArtifactStore store, Story story) {
        if (story == null || story.state() != StoryState.REVIEW) {
            return Verdict.no("it has not come back for a verdict yet");
        }
        if (story.kind() == StoryKind.ENABLER || story.criterionIds().isEmpty()) {
            return Verdict.no("this story promised no checks of its own, so nothing was proved "
                + "about it. It was built and it passed everything the project already had, but "
                + "whether it is what you wanted is a judgement, and it is yours to make.");
        }
        UUID runId = lastRunOf(story);
        if (runId == null) {
            return Verdict.no("no build was ever recorded against it");
        }
        Run run = store.root().runs.get(runId);
        if (run == null) {
            return Verdict.no("the build that produced it cannot be found any more");
        }
        if (run.state() != RunState.DELIVERED) {
            return Verdict.no("its build has not finished");
        }
        if (!run.wasEverPlanned()) {
            return Verdict.no("its build finished without ever planning any work, so it produced "
                + "no evidence to read");
        }
        if (story.integrationCommit() == null && story.deliveredCommit() == null) {
            return Verdict.no("no finished code was recorded against it, so there is nothing to "
                + "add to the project");
        }
        String question = openQuestion(store, runId);
        if (question != null) {
            return Verdict.no("its build stopped to ask you something: " + question);
        }
        return Verdict.YES;
    }

    /** The first unanswered question this run raised, or null. */
    private static String openQuestion(ArtifactStore store, UUID runId) {
        for (Decision decision : store.root().decisions.values()) {
            if (runId.equals(decision.runId()) && decision.state() == DecisionState.PENDING) {
                String brief = decision.briefMarkdown();
                if (brief == null || brief.isBlank()) {
                    return "(no details were recorded)";
                }
                return brief.length() > 200 ? brief.substring(0, 200).strip() + "…" : brief.strip();
            }
        }
        return null;
    }

    private static UUID lastRunOf(Story story) {
        return story.runIds().isEmpty() ? null : story.runIds().get(story.runIds().size() - 1);
    }
}
