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

import com.swarmcoder.domain.Task;
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.verify.AcceptanceCompileErrors.DependencyNeed;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A task's acceptance test does not compile because the build file of its module does not
 * declare a library the test uses, and no task still to run may write that build file (section
 * 111). Who may add the declaration?
 *
 * <p><b>The rule: the task whose test it is.</b> That build file is added to the task's write set,
 * and only that file. The reasons:
 * <ul>
 *   <li>The task's test goes in the module of the task's own main sources (section 109), so that
 *       module's build file is the one that must declare everything the task's code and test use.
 *       In the live build that found this (2026-10-14) the server module's build file declared no
 *       dependency at all: the server code needed three libraries the file did not name either,
 *       so the server task's workers had to be able to write it whatever the test said.</li>
 *   <li>A repair of the task that wrote the build file would rebuild a wave that is already
 *       merged and verified, to add a line the next task is about to need anyway.</li>
 *   <li>The test author may write test sources only, and a test-scope-only write to a build file
 *       could not add what the main code needs.</li>
 * </ul>
 * Workers still cannot change an acceptance test or the verification contract: those are refused
 * by {@link PathPolicy} before any write set is read, and a path is granted here only when the
 * policy, given the grown write set, allows it.
 *
 * <p>Deterministic: no model, and nothing matched on words.
 */
final class UndeclaredDependencies {

    private UndeclaredDependencies() {}

    /**
     * The build files to add to {@code task}'s write set so its workers can declare what its test
     * needs: the build file of each undeclared need that is on the tree, is a build file, and that
     * the path policy lets the task write. Empty when nothing is to be granted - a task with no
     * write set already may write anything.
     *
     * @param tree           the tree the test did not compile on
     * @param protectedPaths the operator's locked modules
     */
    static List<String> grantable(Reading reading, Task task, Path tree,
                                  Collection<String> protectedPaths) {
        List<String> granted = new ArrayList<>();
        if (reading == null || task == null || task.writeSet() == null
                || task.writeSet().isEmpty()) {
            return granted;
        }
        for (DependencyNeed need : reading.undeclared()) {
            String buildFile = need.buildFile();
            if (buildFile == null || granted.contains(buildFile)
                    || task.writeSet().contains(buildFile)
                    || !BuildFilesInTheJob.isBuildFile(buildFile)
                    || tree != null && !Files.isRegularFile(tree.resolve(buildFile))) {
                continue;
            }
            if (!PathPolicy.check(buildFile, List.of(buildFile), task.acceptanceTestDir(),
                    protectedPaths).allowed()) {
                continue;
            }
            granted.add(buildFile);
        }
        return granted;
    }

    /** {@code task}'s write set with {@code granted} added; a new set, for the store to see. */
    static Set<String> grown(Task task, List<String> granted) {
        Set<String> grown = new LinkedHashSet<>(task.writeSet());
        grown.addAll(granted);
        return grown;
    }

    /** One sentence for the run's log and its carried warnings. */
    static String note(Task task, Reading reading, List<String> granted) {
        List<String> what = new ArrayList<>();
        for (DependencyNeed need : reading.undeclared()) {
            if (granted.contains(need.buildFile())) {
                what.add(need.packageName() + (need.artifact() == null ? ""
                    : " (library " + need.artifact() + ")"));
            }
        }
        return "Task '" + task.title() + "': its acceptance test uses " + String.join(", ", what)
            + ", which " + String.join(", ", granted) + " does not declare, and no task still to "
            + "run may write " + (granted.size() == 1 ? "that file" : "those files") + ". The "
            + "test's own lines are not at fault: a build declaration is missing. "
            + String.join(", ", granted) + (granted.size() == 1 ? " is" : " are") + " added to "
            + "what '" + task.title() + "' may write, so its workers can declare it; they still "
            + "may not change an acceptance test or the verification contract.";
    }
}
