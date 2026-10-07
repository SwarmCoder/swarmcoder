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

/**
 * Whose fault a failed compile stage is.
 *
 * <p>This exists because "the candidate does not compile" was said four times in one run
 * (2026-09-02) about four candidates whose own files compiled perfectly. What failed was
 * {@code test-compile}, on acceptance tests committed to the repository by earlier runs, importing
 * classes that exist nowhere — files the candidates were forbidden from touching. The exit code
 * cannot tell those apart; the compiler's own output can, because it names the file every error
 * is in, and the candidate's diff says which files it wrote.
 *
 * <p><b>APPEND ONLY.</b> These are persisted with every {@link VerificationReport}; inserting a
 * constant in the middle re-labels every report already in the store.
 */
public enum CompileFailureCause {

    /**
     * The compile stage failed and nothing here can say whose fault it is: the output named no
     * source file (a dependency that would not resolve, a build tool that would not start, a
     * timeout), or it named one but which files the candidate changed was not known. The old
     * sentence stays, because nothing better is known.
     */
    UNATTRIBUTED,

    /**
     * An error in a file this candidate added or changed, in main code. This is the candidate's
     * fault and the candidate can fix it.
     */
    CANDIDATE,

    /**
     * Every error is in a file this candidate did not touch. The tree does not compile before the
     * candidate's change — most often an acceptance test in the protected directory, committed by
     * an earlier run, importing a class no task ever produced.
     */
    PRE_EXISTING,

    /**
     * Main code compiles; a test file this candidate changed does not. Kept apart from
     * {@link #CANDIDATE} because a test-compile failure is the one a worker can rarely act on: the
     * tests that judge it live in a directory it may not edit, so the only test it could have
     * broken is one it wrote itself.
     */
    TEST_TREE,

    /**
     * The build stopped before any compiler ran: it could not resolve a dependency, and the
     * sandbox has no network to fetch one with.
     *
     * <p>New on 2026-09-03, alongside the change that lets a worker declare a dependency in a
     * module's build file. That capability is what the operator needed — nobody can hand-hold a
     * swarm that cannot add a line to a pom — and its one new failure mode is a worker declaring
     * something the offline Maven repository does not hold. Kept apart from {@link #UNATTRIBUTED}
     * because it is the opposite of unattributed: the build names the exact coordinate, the
     * candidate's diff names the build file it wrote, and the judge can be told precisely what
     * went wrong and that a library from its brief's list would not have.
     */
    UNRESOLVABLE_DEPENDENCY,

    /**
     * The error is in a file the candidate did not touch, and the tree the candidate was cut from
     * was measured and had no error there: the candidate's change is what broke that file (live
     * run 74, 2026-10-03). Appended last: this enum is persisted.
     */
    CAUSED_BY_CHANGE
}
