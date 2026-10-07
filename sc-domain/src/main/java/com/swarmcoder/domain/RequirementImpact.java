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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What changing or retiring one requirement would disturb, in the words the operator reads.
 *
 * <p><b>Why this is one class and not two sentences.</b> The document-analysis wizard has warned
 * about this since 2026-07-27: tick a proposed edit in a list of twenty and it tells you, on that
 * row, "Editing R2 affects 2 agreed checks and 1 story." The hand-edit path — the one an operator
 * uses far more often — said nothing at all (§25.3). Writing a second sentence for it would be two
 * derivations of one fact, which is how they drift; this is the one, and both paths ask it.
 *
 * <p><b>Warn, do not block</b> (author decision 2026-07-27). Rewording an agreed requirement is a
 * legitimate act and the pool is always open. What the operator is not entitled to is to do it
 * unknowingly.
 *
 * <h2>What "sent back" means</h2>
 *
 * <p>A story is sent back when the requirement it is delivering changes underneath it: it stops
 * being work in progress and returns to the ready column, because what it was building is no longer
 * what the document says. Only a story that is actually in flight is sent back — building, back for
 * a verdict, or stopped. A story nobody has started yet has nothing to be sent back from, and
 * delivered work is never churned: its evidence goes stale, which is visible, and whether to build
 * it again is a decision rather than a side effect of typing.
 */
public final class RequirementImpact {

    private final String handle;
    private final int agreedChecks;
    private final int passingChecks;
    private final List<String> storyKeys;
    private final List<String> sentBackKeys;

    private RequirementImpact(String handle, int agreedChecks, int passingChecks,
                              List<String> storyKeys, List<String> sentBackKeys) {
        this.handle = handle;
        this.agreedChecks = agreedChecks;
        this.passingChecks = passingChecks;
        this.storyKeys = storyKeys;
        this.sentBackKeys = sentBackKeys;
    }

    /** Reads one requirement against the project's stories. Neither is modified. */
    public static RequirementImpact of(BrdRequirement requirement, List<Story> stories) {
        if (requirement == null) {
            return new RequirementImpact("", 0, 0, List.of(), List.of());
        }
        Set<UUID> criterionIds = new LinkedHashSet<>();
        int agreed = 0;
        int passing = 0;
        for (AcceptanceCriterion criterion : requirement.criteria()) {
            if (criterion == null || criterion.id() == null) {
                continue;
            }
            criterionIds.add(criterion.id());
            if (criterion.status() == CriterionStatus.ACCEPTED) {
                agreed++;
                if (criterion.effectiveState(requirement.contentRevision())
                        == CriterionState.PASSING) {
                    passing++;
                }
            }
        }
        List<String> claiming = new ArrayList<>();
        List<String> sentBack = new ArrayList<>();
        for (Story story : stories == null ? List.<Story>of() : stories) {
            if (story == null || story.criterionIds() == null
                    || story.state() == StoryState.CANCELLED) {
                continue;
            }
            boolean claims = false;
            for (UUID id : story.criterionIds()) {
                if (id != null && criterionIds.contains(id)) {
                    claims = true;
                    break;
                }
            }
            if (!claims) {
                continue;
            }
            String key = story.key() == null ? "?" : story.key();
            claiming.add(key);
            if (isInFlight(story.state())) {
                sentBack.add(key);
            }
        }
        String handle = requirement.handle() == null ? "This requirement" : requirement.handle();
        return new RequirementImpact(handle, agreed, passing,
            List.copyOf(claiming), List.copyOf(sentBack));
    }

    /** Building, back for a verdict, or stopped — the three states a story is sent back from. */
    private static boolean isInFlight(StoryState state) {
        return state == StoryState.RUNNING || state == StoryState.REVIEW
            || state == StoryState.BLOCKED;
    }

    /** True when nothing downstream would notice, so there is nothing worth warning about. */
    public boolean isEmpty() {
        return agreedChecks == 0 && storyKeys.isEmpty();
    }

    public int agreedChecks() {
        return agreedChecks;
    }

    /** Agreed checks that pass today and will read stale the moment the wording changes. */
    public int passingChecks() {
        return passingChecks;
    }

    /** Every story that has claimed one of this requirement's checks. */
    public List<String> storyKeys() {
        return storyKeys;
    }

    /** The stories that changing this requirement sends back to be built again. */
    public List<String> sentBackKeys() {
        return sentBackKeys;
    }

    /**
     * What rewording this requirement would cost, or "" when nothing downstream would notice.
     *
     * <p>Example: <i>"Editing R2 affects 2 agreed checks and 1 story. 1 test that passes today will
     * stop counting and has to be run again against the new wording. S1 will be sent back to be
     * built again."</i>
     */
    public String editSentence() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Editing ").append(handle).append(" affects ");
        appendScale(sb);
        if (passingChecks > 0) {
            sb.append(' ').append(passingChecks)
                .append(passingChecks == 1 ? " test that passes" : " tests that pass")
                .append(" today will stop counting and ")
                .append(passingChecks == 1 ? "has" : "have")
                .append(" to be run again against the new wording.");
        }
        appendSendBack(sb);
        return sb.toString().trim();
    }

    /**
     * What retiring this requirement would cost. Never "" — retiring always says what it does, and
     * says plainly that nothing is lost, because "delete" is the word the button used to use.
     */
    public String retireSentence() {
        StringBuilder sb = new StringBuilder("Retiring ").append(handle);
        if (isEmpty()) {
            sb.append(" takes it out of the list and out of every count.");
        } else {
            sb.append(" takes ");
            appendScale(sb);
        }
        sb.append(" Nothing is deleted: ").append(handle).append(" stays on the record with "
            + "everything that was built for it, and you can bring it back by setting it back to a "
            + "draft.");
        appendSendBack(sb);
        return sb.toString().trim();
    }

    /** "2 agreed checks and 1 story." — the size of what is downstream, ended with a full stop. */
    private void appendScale(StringBuilder sb) {
        sb.append(agreedChecks).append(agreedChecks == 1 ? " agreed check" : " agreed checks");
        if (!storyKeys.isEmpty()) {
            sb.append(" and ").append(storyKeys.size())
                .append(storyKeys.size() == 1 ? " story" : " stories");
        }
        sb.append('.');
    }

    private void appendSendBack(StringBuilder sb) {
        if (sentBackKeys.isEmpty()) {
            return;
        }
        sb.append(' ').append(String.join(" and ", sentBackKeys))
            .append(" will be sent back to be built again.");
    }
}
