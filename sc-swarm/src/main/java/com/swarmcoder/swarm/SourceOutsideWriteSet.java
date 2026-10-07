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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * A candidate that changed source files outside its task's write set does not pass.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 74, 2026-10-03. A repair worker of "add two methods to the service interface"
 * implemented the methods in the implementing class - a file of the next wave's task - and
 * created its own copies of two command classes two other tasks were writing. It passed
 * verification, was selected, and the delivery now holds dead duplicate classes.
 *
 * <h2>What the guard did before</h2>
 *
 * <p>A write outside the write set was recorded on the candidate and the worker told; the judge
 * was shown the list; selection used the count only to break a tie between candidates the judge
 * could not separate; and the integrators dropped the files that are not source (a stray note, a
 * script). A source file outside the write set was merged like any other.
 *
 * <h2>The rule now</h2>
 *
 * <p>Source changes outside the write set fail the candidate at verification, so it never counts
 * as a candidate that passed and is never selected. The write set is the one the task has NOW:
 * when the product widened it ({@link RepairCannotHelp}, {@link SiblingDefects}), the files it
 * was widened to are inside it. Files that are not source keep being dropped at integration, and
 * a build file is left to the judge as before - a task may need to declare a dependency.
 */
final class SourceOutsideWriteSet {

    private static final int MAX_NAMED = 8;

    private SourceOutsideWriteSet() {
    }

    /**
     * The source files this candidate's change touches outside the task's write set: recorded by
     * its toolbox as outside, still part of its change, not covered by the write set as it is
     * now, and neither a build file nor a non-source stray.
     */
    static List<String> paths(Task task, CandidateSolution candidate) {
        List<String> outside = new ArrayList<>();
        if (task == null || candidate == null || task.writeSet() == null
                || task.writeSet().isEmpty()) {
            return outside; // an empty write set is unrestricted
        }
        String diff = candidate.diffUnified() == null ? "" : candidate.diffUnified();
        for (String recorded : candidate.outOfWriteSetPaths()) {
            if (recorded == null || recorded.isBlank()) {
                continue;
            }
            String path = recorded.strip().replace('\\', '/');
            if (StrayFileCheck.isStray(path) || isBuildFile(path)
                    || RepairCannotHelp.covered(task.writeSet(), path)) {
                continue;
            }
            // Written and then put back as it was: not part of the change any more.
            if (!diff.isBlank() && !diff.contains(path)) {
                continue;
            }
            if (!outside.contains(path)) {
                outside.add(path);
            }
        }
        return outside;
    }

    /** The verdict sentence, or null when the candidate stayed inside its write set. */
    static String objection(Task task, CandidateSolution candidate) {
        List<String> outside = paths(task, candidate);
        if (outside.isEmpty()) {
            return null;
        }
        StringBuilder named = new StringBuilder(String.join(", ",
            outside.stream().limit(MAX_NAMED).toList()));
        if (outside.size() > MAX_NAMED) {
            named.append(" and ").append(outside.size() - MAX_NAMED).append(" more");
        }
        return "the candidate changed " + (outside.size() == 1 ? "a source file" : outside.size()
            + " source files") + " outside the task's write set " + task.writeSet() + ": " + named
            + ". Another task may own " + (outside.size() == 1 ? "that file" : "those files")
            + ", so a change there is never selected, whatever else it passes. Make the task's "
            + "change inside its write set and leave " + (outside.size() == 1 ? "that file"
            + " as it was" : "those files as they were") + "; if the task cannot be done without "
            + (outside.size() == 1 ? "it" : "them") + ", say so in your report - that is a fault "
            + "in the plan";
    }

    private static boolean isBuildFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return "pom.xml".equals(name) || "build.gradle".equals(name)
            || "build.gradle.kts".equals(name) || "settings.gradle".equals(name)
            || "settings.gradle.kts".equals(name) || "package.json".equals(name);
    }
}
