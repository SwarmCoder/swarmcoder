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
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;

import java.util.List;

/**
 * Why a task was blocked, said once at the top of its question (section 83).
 *
 * <p>The question of a blocked task listed, for each worker, its state, {@code compiles=false}
 * and pass counts, and nothing else. Whoever had to answer it - a person, or a supervising model
 * shown about 1,300 characters - was told that eight workers failed and not on what. The reason
 * is what verification already recorded for the first candidate that has one: the file and line
 * the compile failed at with the compiler's message, or the first acceptance test that failed
 * with its message. It goes before the list of workers, and it is short.
 *
 * <p>Pure functions over what verification recorded.
 */
final class BlockedReason {

    private BlockedReason() {
    }

    /** The longest a compiler's or a test's message is quoted at. */
    static final int MESSAGE_LIMIT = 300;

    /**
     * The reason, as one short paragraph ending in a blank line, or "" when verification
     * recorded none or {@code headline} already says where the failure is.
     *
     * @param evidence the task's candidates, in the order the question lists them
     * @param headline what the question starts with
     */
    static String of(Task task, List<CandidateSolution> evidence, String headline) {
        if (evidence == null) {
            return "";
        }
        String said = headline == null ? "" : headline;
        for (CandidateSolution candidate : evidence) {
            if (candidate == null || candidate.verification() == null
                    || candidate.verification().compiles()) {
                continue;
            }
            CompileFailure failure = candidate.verification().compileFailure();
            if (failure == null || failure.file() == null) {
                continue;
            }
            String where = failure.file().replace('\\', '/')
                + (failure.line() > 0 ? ":" + failure.line() : "");
            if (said.contains(where)) {
                return "";
            }
            return "Why: it does not compile - " + where + " " + shortened(failure.message())
                + (ownTest(task, failure.file())
                    ? "\nThat file is this task's own protected acceptance test; no worker may "
                        + "change it."
                    : "")
                + "\n\n";
        }
        for (CandidateSolution candidate : evidence) {
            TestResults acceptance = candidate == null || candidate.verification() == null
                ? null : candidate.verification().acceptance();
            if (acceptance == null || acceptance.failures() == null) {
                continue;
            }
            for (TestFailure failure : acceptance.failures()) {
                if (failure == null || failure.testId() == null) {
                    continue;
                }
                if (said.contains(failure.testId())) {
                    return "";
                }
                return "Why: acceptance test " + failure.testId() + " fails - "
                    + shortened(failure.message()) + "\n\n";
            }
        }
        return "";
    }

    /** One line of at most {@link #MESSAGE_LIMIT} characters. */
    static String shortened(String message) {
        String line = message == null ? "" : message.strip().replaceAll("\\s+", " ");
        if (line.isEmpty()) {
            return "(no message)";
        }
        return line.length() <= MESSAGE_LIMIT ? line : line.substring(0, MESSAGE_LIMIT) + "...";
    }

    private static boolean ownTest(Task task, String file) {
        if (task == null || file == null) {
            return false;
        }
        String wanted = file.replace('\\', '/');
        for (String path : task.authoredTestPaths()) {
            String own = path.replace('\\', '/');
            if (own.equals(wanted) || own.endsWith("/" + wanted) || wanted.endsWith("/" + own)) {
                return true;
            }
        }
        return false;
    }
}
