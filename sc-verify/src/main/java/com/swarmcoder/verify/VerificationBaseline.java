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
package com.swarmcoder.verify;

import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What was already wrong with the tree a run started from, so that it is not held against the
 * work built on it (owner decision after the audit of 2026-10-02).
 *
 * <p>A test of the project's own that fails before anybody has changed anything fails for every
 * candidate, and until now it failed every candidate: the existing-tests stage counted it as the
 * candidate's regression. The existing-tests stage is run once per run on the untouched tree
 * (see {@link BaseTreeChecks}); a candidate then fails that stage only for a test that passes
 * there and fails on the candidate.
 *
 * @param failingExistingTests ids ({@code class#method}) of the tests that fail on the start tree
 */
public record VerificationBaseline(Set<String> failingExistingTests, StartCompile startCompile) {

    /**
     * What the compile stage did on the tree the candidates are cut from, measured before any of
     * them ran (live run 74, 2026-10-03). It is what tells "the tree did not compile before this
     * candidate" from "this candidate's change broke a file it did not touch": an error in a file
     * that had none on the start tree is caused by the change, whichever file reports it.
     *
     * @param established  false when the start tree's compile was not measured; nothing is then
     *                     known and nothing is concluded from it
     * @param compiles     the whole compile stage passed
     * @param mainCompiles main code compiled (true when everything did, or when only the test
     *                     compile failed)
     * @param errorFiles   the files the compiler reported an error in on the start tree
     * @param files        the source files the start tree held, repo-relative; null when they
     *                     were not listed. A file that is not among them was not there to be
     *                     compiled (section 83)
     */
    public record StartCompile(boolean established, boolean compiles, boolean mainCompiles,
                               Set<String> errorFiles, Set<String> files) {

        public static final StartCompile UNKNOWN = new StartCompile(false, false, false, Set.of());

        public StartCompile {
            errorFiles = errorFiles == null ? Set.of() : Set.copyOf(errorFiles);
            files = files == null ? null : Set.copyOf(files);
        }

        /** A measurement whose tree was not listed: every file is taken to have been there. */
        public StartCompile(boolean established, boolean compiles, boolean mainCompiles,
                            Set<String> errorFiles) {
            this(established, compiles, mainCompiles, errorFiles, null);
        }

        /** The same measurement with the source files the measured tree held. */
        public StartCompile withFiles(Set<String> held) {
            return new StartCompile(established, compiles, mainCompiles, errorFiles, held);
        }

        /**
         * The same measurement with the source files found under {@code root}, the tree that
         * was measured. Unchanged when the tree cannot be read: nothing is then known about
         * which files it held.
         */
        public StartCompile withFilesOf(java.nio.file.Path root) {
            if (root == null || !java.nio.file.Files.isDirectory(root)) {
                return this;
            }
            Set<String> held = new LinkedHashSet<>();
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path file : walk.filter(java.nio.file.Files::isRegularFile)
                        .toList()) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (!rel.startsWith(".git/") && !rel.contains("/target/")
                            && !rel.startsWith("target/") && !rel.contains("/build/")
                            && !rel.startsWith("build/") && !rel.contains("node_modules/")) {
                        held.add(rel);
                    }
                }
            } catch (java.io.IOException | RuntimeException unreadable) {
                return this;
            }
            return withFiles(held);
        }

        /**
         * False only when the start tree's files were listed and this one is not among them:
         * the file was added afterwards - by the candidate, or put over its work for
         * verification, as a task's acceptance test is.
         */
        public boolean existed(String file) {
            if (files == null) {
                return true;
            }
            if (file == null) {
                return false;
            }
            String wanted = file.replace('\\', '/');
            for (String known : files) {
                if (known.equals(wanted) || known.endsWith("/" + wanted)
                        || wanted.endsWith("/" + known)) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the start tree already had an error in this file. */
        public boolean hadErrorIn(String file) {
            if (file == null) {
                return false;
            }
            String wanted = file.replace('\\', '/');
            for (String known : errorFiles) {
                if (known.equals(wanted) || known.endsWith("/" + wanted)
                        || wanted.endsWith("/" + known)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether an error now reported in {@code file} can only come from the change: the file
         * was compiled on the start tree and had no error there. A test file counts only when
         * the whole start tree compiled, and a main file only when main code did - a compiler
         * that stopped earlier never looked at the rest. A file the start tree did not hold was
         * not compiled there, so nothing is known about it and it is never called clean
         * (section 83: a task's acceptance test, put over a candidate for verification, was
         * called "compiled on the tree this candidate started from" when it had never been on
         * it).
         */
        public boolean wasCleanOnTheStartTree(String file, boolean testFile) {
            if (!established || hadErrorIn(file) || !existed(file)) {
                return false;
            }
            return compiles || (mainCompiles && !testFile);
        }
    }

    public VerificationBaseline(Set<String> failingExistingTests) {
        this(failingExistingTests, StartCompile.UNKNOWN);
    }


    public static final VerificationBaseline NONE = new VerificationBaseline(Set.of());

    public VerificationBaseline {
        failingExistingTests = failingExistingTests == null
            ? Set.of() : Set.copyOf(failingExistingTests);
        startCompile = startCompile == null ? StartCompile.UNKNOWN : startCompile;
    }

    /** The same baseline with what the start tree's compile did. */
    public VerificationBaseline withStartCompile(StartCompile measured) {
        return new VerificationBaseline(failingExistingTests, measured);
    }

    public static VerificationBaseline of(Collection<String> failingExistingTests) {
        if (failingExistingTests == null || failingExistingTests.isEmpty()) {
            return NONE;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : failingExistingTests) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? NONE : new VerificationBaseline(ids);
    }

    public boolean isEmpty() {
        return failingExistingTests.isEmpty();
    }

    /**
     * The existing-tests result of a candidate with the failures the start tree already had
     * taken out. A failure the start tree did not have stays, with its count; a stage that could
     * not produce a result at all is returned as it is.
     *
     * @param fullLog told which failures were set aside, so the verification log says why a
     *                stage with failing tests in its output did not fail the candidate; may be null
     */
    public TestResults discount(TestResults existing, StringBuilder fullLog) {
        if (isEmpty() || existing == null || existing.failures() == null
                || existing.failures().isEmpty()) {
            return existing;
        }
        List<TestFailure> kept = new ArrayList<>();
        List<String> setAside = new ArrayList<>();
        for (TestFailure failure : existing.failures()) {
            if (failure != null && failure.testId() != null
                    && failingExistingTests.contains(failure.testId())) {
                setAside.add(failure.testId());
            } else {
                kept.add(failure);
            }
        }
        if (setAside.isEmpty()) {
            return existing;
        }
        int removed = setAside.size();
        int failed = Math.max(0, existing.failed() - removed);
        int fromErrored = removed - (existing.failed() - failed);
        int errored = Math.max(0, existing.errored() - fromErrored);
        if (fullLog != null) {
            fullLog.append("[existing] ").append(removed).append(" failing test(s) already fail "
                + "on the tree this run started from and are not held against this candidate: ")
                .append(String.join(", ", setAside)).append('\n');
        }
        TestResults discounted = new TestResults(existing.passed(), failed, errored,
            existing.skipped(), kept, existing.getPassedIds(), existing.getSkippedIds(),
            existing.idsTruncated());
        discounted.setStageOutcome(existing.getStageOutcome());
        return discounted;
    }
}
