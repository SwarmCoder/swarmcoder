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
import com.swarmcoder.domain.AuthoredTest;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.Task;
import com.swarmcoder.verify.TestOwnership;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the red check does with a test whose task will not have the types it needs
 * ({@link TestOwnership}, section 83): the test and its checks are given to the task that will,
 * the way a journey is given to the task that writes its screen (section 70); where no task
 * will, the test goes back to its author with the fact, and then the run stops on it.
 *
 * <p>A task's tests and checks move together and all of them move. Which check one test file
 * proves is recorded as the check's handle only, and a task left with a check and no test for
 * it is the state the red check exists to refuse.
 *
 * <p>Pure functions; the workflow saves the tasks and writes the log.
 */
final class TestOwners {

    private TestOwners() {
    }

    /**
     * Gives every test file and every check of {@code from} to {@code to}, in place. The files
     * and the tests commit are not touched: only who claims them changes.
     *
     * @return the test files that moved
     */
    static List<String> give(Task from, Task to) {
        List<String> moved = List.copyOf(from.authoredTestPaths());
        List<String> paths = new ArrayList<>(to.authoredTestPaths());
        for (String path : moved) {
            if (!paths.contains(path)) {
                paths.add(path);
            }
        }
        to.setAuthoredTestPaths(List.copyOf(paths));
        from.setAuthoredTestPaths(List.of());

        Set<UUID> ids = new LinkedHashSet<>(to.criterionIds());
        ids.addAll(from.criterionIds());
        to.setCriterionIds(ids);
        from.setCriterionIds(new LinkedHashSet<>());

        List<AcceptanceCriterion> criteria =
            new ArrayList<>(to.criteria() == null ? List.of() : to.criteria());
        for (AcceptanceCriterion criterion : from.criteria() == null
                ? List.<AcceptanceCriterion>of() : from.criteria()) {
            if (!criteria.contains(criterion)) {
                criteria.add(criterion);
            }
        }
        to.setCriteria(criteria);
        from.setCriteria(new ArrayList<>());

        AuthoredTests given = from.authoredTests();
        if (given != null) {
            AuthoredTests held = to.authoredTests();
            if (held == null) {
                to.setAuthoredTests(given);
            } else {
                List<String> files = new ArrayList<>(held.files());
                given.files().stream().filter(file -> !files.contains(file)).forEach(files::add);
                held.setFiles(files);
                List<AuthoredTest> tests = new ArrayList<>(held.tests());
                given.tests().stream().filter(test -> !tests.contains(test)).forEach(tests::add);
                held.setTests(tests);
                held.setChecksOffered(held.checksOffered() + given.checksOffered());
                List<String> problems = new ArrayList<>(held.problems());
                problems.addAll(given.problems());
                held.setProblems(problems);
            }
            // The first owner was handed its checks and is done with them: it claims none now.
            AuthoredTests none = AuthoredTests.started(0, given.startedAt());
            none.setWrittenAt(given.writtenAt() == null ? Instant.now() : given.writtenAt());
            from.setAuthoredTests(none);
        }
        return moved;
    }

    /** The note for the log and the run's carried warnings, once the tests have been given. */
    static String movedNote(TestOwnership.Finding finding, List<String> moved) {
        return "the acceptance test(s) " + moved + " and the check(s) they prove were given to "
            + "task '" + finding.target().title() + "' and taken from task '"
            + finding.owner().title() + "', where the plan had put them. " + finding.fact()
            + " '" + finding.target().title() + "' is the last task to write one of those types "
            + "and has all of them when it starts. Only the claim moved: the files and the "
            + "plan's order are as they were, and '" + finding.owner().title() + "' is now "
            + "built with no acceptance test of its own.";
    }

    /** What the test's author is told, once, when no task can be given the test. */
    static String reask(TestOwnership.Finding finding) {
        StringBuilder has = new StringBuilder();
        for (Map.Entry<String, List<Task>> need : finding.needs().entrySet()) {
            if (!finding.outside().containsKey(need.getKey())) {
                has.append(has.isEmpty() ? "" : ", ").append('`').append(need.getKey()).append('`');
            }
        }
        return "Your test cannot be compiled when its task is built, and no worker of that task "
            + "can change that. " + finding.fact() + " " + finding.whyNoMove()
            + "\n\nA test may use only what the checkout already holds, what its own task "
            + "writes and what the tasks that task waits for write"
            + (has.isEmpty() ? "" : " (of what this test uses, that is " + has + ")")
            + ". Prove this task's check with those and nothing else."
            + "\n\nReply with the same JSON object, with the corrected file(s).";
    }

    /** What the run stops on when the test could neither be given to a task nor corrected. */
    static String park(TestOwnership.Finding finding, String note) {
        return finding.fact() + " " + finding.whyNoMove()
            + (note == null || note.isBlank() ? "" : "\n\n" + note)
            + "\n\nNo worker was started on '" + finding.owner().title() + "': every candidate "
            + "would fail on a test none of them may change, and so would a repair round."
            + "\n\nPut the check on a task that has those types when it starts, or make one of "
            + "the tasks wait for the other in the plan, then resume the run.";
    }
}
