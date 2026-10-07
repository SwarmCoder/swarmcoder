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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduling over story dependencies: what may start now, what is waiting and why, and whether the
 * declared edges make sense at all.
 *
 * <p>The story-level twin of the task-level scheduler, sharing its one Kahn implementation
 * ({@link Waves}) rather than repeating it. What differs is only the finish line: a task is finished
 * when its winner is merged inside the run, a story is finished when it is ACCEPTED — because
 * acceptance is what puts its code on the branch the next story will branch from. So
 * {@link StoryState#DONE} is the whole definition of "delivered" here, and there is no second one.
 *
 * <p><b>Why waiting matters.</b> Nine stories planned from one document and started together on
 * 2026-08-28 each built its own version of a domain model the first story had not finished yet.
 * Neither half of the fix works alone: waiting without branching from the delivered state means the
 * later story still cannot see the earlier one's code, and branching from the delivered state
 * without waiting means there is nothing there to see.
 */
public final class StoryGraph {

    private StoryGraph() {}

    /** What is wrong with the declared edges. Empty means the graph can be scheduled. */
    @NotReachableFromStoreRoot("returned by validate() and read by the caller in the same call; "
        + "never assigned to a field")
    public record Verdict(List<String> violations) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    /**
     * Why one story cannot start.
     *
     * @param blockers the stories it is waiting for, in the order it declared them
     * @param reason   one sentence for the operator, naming each blocker and what state it is in
     */
    @NotReachableFromStoreRoot("returned by blockedBy() for the UI/console to read once and "
        + "discard; never assigned to a field")
    public record Blocked(List<Story> blockers, String reason) {
        public boolean any() {
            return !blockers.isEmpty();
        }
    }

    /**
     * Rejects a dependency graph that cannot be scheduled — the same three questions the task-graph
     * validator asks of a plan, for the same reason: a graph that is accepted and then cannot be run
     * produces a pipeline that looks busy and delivers nothing.
     *
     * <ul>
     *   <li>an edge to a story that does not exist in this project</li>
     *   <li>a story depending on itself</li>
     *   <li>a cycle — two or more stories each waiting for the other, which never starts</li>
     * </ul>
     *
     * <p>An edge to a CANCELLED story is a violation too. A cancelled story is a tombstone that will
     * never be delivered, so waiting for it is waiting for ever; the operator has to either revive it
     * or drop the edge.
     */
    public static Verdict validate(List<Story> stories) {
        List<String> violations = new ArrayList<>();
        Map<UUID, Story> byId = byId(stories);
        for (Story story : living(stories)) {
            Set<UUID> seen = new LinkedHashSet<>();
            for (UUID dependency : story.dependsOn()) {
                if (dependency == null) {
                    continue;
                }
                if (dependency.equals(story.id())) {
                    violations.add(label(story) + " waits for itself, so it could never start");
                    continue;
                }
                if (!seen.add(dependency)) {
                    continue;   // a repeated edge is harmless; say it once
                }
                Story target = byId.get(dependency);
                if (target == null) {
                    violations.add(label(story) + " waits for a story this project does not have");
                } else if (target.state() == StoryState.CANCELLED) {
                    violations.add(label(story) + " waits for " + label(target)
                        + ", which was dropped and will never be delivered");
                }
            }
        }
        if (Waves.hasCycle(living(stories), Story::id, edges(stories))) {
            violations.add("these stories wait for each other in a circle, so none of them could "
                + "ever start");
        }
        return new Verdict(List.copyOf(violations));
    }

    /**
     * The stories grouped into the order they can be built in: everything in the first group can
     * start now, everything in the second waits on the first, and so on. Cancelled stories are left
     * out — a tombstone schedules nothing.
     */
    public static List<List<Story>> waves(List<Story> stories) {
        return Waves.of(living(stories), Story::id, edges(stories)).waves();
    }

    /**
     * What {@code story} is waiting for, if anything.
     *
     * <p>A dependency counts as met only when the story it names has been ACCEPTED. Not "finished
     * building", not "back for a verdict" — accepted, because that is the moment its code reaches the
     * branch this story would branch from. A dependency naming a story that no longer exists is
     * ignored rather than treated as a blocker: validation reports it, and a story that can never be
     * started because of a typo somewhere else is the worse of the two failures.
     */
    public static Blocked blockedBy(Story story, List<Story> stories) {
        Map<UUID, Story> byId = byId(stories);
        List<Story> blockers = new ArrayList<>();
        Set<UUID> inferred = new LinkedHashSet<>(story.discoveredDependsOn());
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID dependency : story.dependsOn()) {
            if (dependency == null || dependency.equals(story.id()) || !seen.add(dependency)) {
                continue;
            }
            Story target = byId.get(dependency);
            // A dropped story is not a blocker, however wrong the edge is. It will never be
            // delivered, so waiting for it is waiting for ever; validation reports the edge and the
            // operator removes it, but nothing is frozen in the meantime. Same for an edge to a
            // story that is not there any more.
            if (target == null || target.state() == StoryState.DONE
                    || target.state() == StoryState.CANCELLED) {
                continue;
            }
            blockers.add(target);
        }
        return new Blocked(List.copyOf(blockers), reason(blockers, inferred));
    }

    /** True when nothing is in this story's way — every story it builds on has been accepted. */
    public static boolean isStartable(Story story, List<Story> stories) {
        return !blockedBy(story, stories).any();
    }

    /**
     * The story cards' waiting line, in the operator's words: which story is in the way, what that
     * story is called, and what is happening to it. No codes standing alone — "waiting for S1" tells
     * somebody with twenty projects nothing at all.
     */
    private static String reason(List<Story> blockers, Set<UUID> inferred) {
        if (blockers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Waiting for ");
        for (int i = 0; i < blockers.size(); i++) {
            Story blocker = blockers.get(i);
            if (i > 0) {
                sb.append(i == blockers.size() - 1 ? " and " : ", ");
            }
            sb.append(label(blocker)).append(" (").append(situation(blocker));
            if (inferred.contains(blocker.id())) {
                // Said out loud, because it is a weaker claim than the rest: nobody planned this
                // link, a failed build revealed it, and the operator is allowed to disagree.
                sb.append("; nobody planned this — a failed build showed it was needed");
            }
            sb.append(')');
        }
        sb.append(blockers.size() == 1
            ? " — this story builds on it, so it cannot be built until that one is accepted."
            : " — this story builds on them, so it cannot be built until those are accepted.");
        return sb.toString();
    }

    /** What is currently happening to a story that is in somebody's way, in plain words. */
    private static String situation(Story blocker) {
        StoryState state = blocker.state();
        if (state == null) {
            return "not started";
        }
        return switch (state) {
            case DRAFT -> "still only a suggestion, not agreed yet";
            case READY -> "agreed, not built yet";
            case RUNNING -> "being built now";
            case REVIEW -> "built, waiting for you to accept it";
            case BLOCKED -> "stopped — it needs you before anything after it can move";
            case CANCELLED -> "dropped";
            case DONE -> "accepted";
        };
    }

    private static String label(Story story) {
        String key = story.key() == null ? "a story" : story.key();
        String title = story.title();
        return title == null || title.isBlank() ? key : key + " \"" + title + "\"";
    }

    private static List<Waves.Edge> edges(List<Story> stories) {
        Map<UUID, Story> byId = byId(stories);
        List<Waves.Edge> edges = new ArrayList<>();
        for (Story story : living(stories)) {
            for (UUID dependency : story.dependsOn()) {
                Story target = dependency == null ? null : byId.get(dependency);
                if (target != null && target.state() != StoryState.CANCELLED
                        && !dependency.equals(story.id())) {
                    edges.add(new Waves.Edge(dependency, story.id()));
                }
            }
        }
        return edges;
    }

    private static List<Story> living(List<Story> stories) {
        List<Story> out = new ArrayList<>();
        for (Story story : stories == null ? List.<Story>of() : stories) {
            if (story != null && story.state() != StoryState.CANCELLED) {
                out.add(story);
            }
        }
        return out;
    }

    private static Map<UUID, Story> byId(List<Story> stories) {
        Map<UUID, Story> byId = new LinkedHashMap<>();
        for (Story story : stories == null ? List.<Story>of() : stories) {
            if (story != null && story.id() != null) {
                byId.put(story.id(), story);
            }
        }
        return byId;
    }
}
