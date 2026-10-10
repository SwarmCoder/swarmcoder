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

import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.Task;
import com.swarmcoder.verify.JourneyFile;

import java.util.ArrayList;
import java.util.List;

/**
 * A story that cannot be proven on an empty application (live run 102, section 80).
 *
 * <p>A journey starts on the entry page of an application with no data and must create what it
 * expects to see (sections 69 and 75). A story that only displays a kind of thing, planned
 * before the story that lets a person add one, has no such journey: its author can neither
 * create a record nor honestly expect the list. Run 102 found that out at final integration,
 * where the journey and the screen were each called right.
 *
 * <p>Nothing here decides that from wording. The test author says it in a field of its own
 * ({@link JourneyFile#NOTHING_CREATES}), when the tests are written and before any worker
 * starts. What is added here are facts of the backlog: which other stories are not delivered
 * yet, and which of them wait for this one.
 */
final class NothingCreatesIt {

    private NothingCreatesIt() {}

    /**
     * What stands of an author's statement once every task's tests are written: the statement,
     * unless a task of the same story claims a journey. That journey is then the story's and
     * is made in the browser as every journey is; null.
     */
    static String standing(String stated, List<Task> tasks) {
        if (stated == null || stated.isBlank()) {
            return null;
        }
        boolean claimed = tasks != null
            && tasks.stream().anyMatch(task -> !task.journeyPaths().isEmpty());
        return claimed ? null : stated.strip();
    }

    /** A story that is not delivered yet, and whether it comes after the stopped one today. */
    record Candidate(Story story, boolean comesAfter) {
    }

    /**
     * The project's other stories whose work is not in the application yet: not this one, not
     * dropped, not accepted. One of them is what would create the thing, if any story does.
     */
    static List<Candidate> candidates(Story stopped, List<Story> stories) {
        List<Candidate> out = new ArrayList<>();
        if (stopped == null || stories == null) {
            return out;
        }
        for (Story other : stories) {
            if (other == null || other.id() == null || other.id().equals(stopped.id())
                    || other.state() == StoryState.DONE
                    || other.state() == StoryState.CANCELLED) {
                continue;
            }
            out.add(new Candidate(other, other.dependsOn().contains(stopped.id())));
        }
        return out;
    }

    /**
     * What the person or the supervisor is asked. Its first sentences say the whole finding,
     * because a question may be shown shortened.
     *
     * @param title      the story's key and title, or the task's title when the run has no story
     * @param what       the author's words for what the story shows, passed on as written
     * @param candidates {@link #candidates}
     */
    static String brief(String title, String what, List<Candidate> candidates) {
        StringBuilder text = new StringBuilder("This story cannot be proven on an empty "
            + "application: it shows ").append(quoted(what)).append(" and nothing on screen lets "
            + "a person create that. Merge it with, or order it after, the story that creates "
            + "it. Nothing was built.\n\n")
            .append(title == null || title.isBlank() ? "The story" : title.strip())
            .append(" is proven in a real browser, starting on the entry page of the "
                + "application with no data, and a journey must create what it expects to see. "
                + "Its test author states, in a field of its own, that neither the application "
                + "as it stands nor this story gives a person a way to create what the story "
                + "shows. So no journey for it can pass on a correct screen.\n\n");
        if (candidates == null || candidates.isEmpty()) {
            text.append("No other story of this project is waiting to be built, so no story "
                + "creates it: the requirements have no way to add one, or it was dropped.");
        } else {
            text.append("Stories of this project that are not delivered yet:\n");
            for (Candidate one : candidates) {
                Story story = one.story();
                text.append("- ").append(story.key() == null ? "" : story.key() + " ")
                    .append(quoted(story.title()));
                if (one.comesAfter()) {
                    text.append(" - comes AFTER this story today, so it cannot be built first");
                }
                text.append('\n');
            }
        }
        return text.append("\nWhat can be done:\n"
            + "- MERGE: drop this story and the one that creates what it shows, then plan "
            + "again. The planner is shown this finding and plans their checks as one story "
            + "that can be proven from an empty application.\n"
            + "- ORDER: when the creating story does not need this one, make this story come "
            + "after it, and build that one first.\n"
            + "- When the author is mistaken and a screen does let a person create it, answer "
            + "retry: the tests and the journey are written again.").toString();
    }

    /** The line of the run's log. */
    static String logLine(String taskTitle, String what) {
        return "TEST_AUTHORING MISMATCH: the test author of task '" + taskTitle + "' states "
            + "that the story cannot be proven on an empty application - it shows "
            + quoted(what) + " and nothing on screen lets a person create that (`"
            + JourneyFile.NOTHING_CREATES + "`)";
    }

    private static String quoted(String text) {
        return "\"" + (text == null ? "" : text.strip().replaceAll("\\s+", " ")) + "\"";
    }
}
