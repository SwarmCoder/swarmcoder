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
 * Whether the files a candidate wrote are inside something the project's build actually compiles.
 *
 * <p>This exists because a build command exiting 0 says nothing about the candidate. On
 * 2026-08-30 a run wrote six files into {@code src/main/java/...} at the root of a repository
 * whose root {@code pom.xml} is a {@code <packaging>pom</packaging>} aggregator with three
 * modules. The verification contract ran {@code mvn -o -q -B compile test-compile} from that
 * root, the three modules compiled cleanly, the six new files were invisible to every compiler
 * involved, and the stage reported success. Two tasks were selected and marked delivered on code
 * that is not in the application and never could be.
 *
 * <p><b>APPEND ONLY.</b> These are persisted with every {@link VerificationReport}; inserting a
 * constant in the middle re-labels every report already in the store.
 */
public enum BuildReachabilityStatus {

    /**
     * Nothing could be established, so nothing is claimed. Either no list of changed files was
     * available, or the repository's layout could not be read (a toolchain whose source roots are
     * configuration rather than convention, or build files that would not parse). A candidate is
     * NEVER failed on this: verification failing closed must not mean verification inventing a
     * failure it cannot substantiate.
     */
    UNDETERMINED,

    /** Every file this candidate added or changed sits somewhere the build compiles or packages. */
    REACHABLE,

    /**
     * At least one file this candidate added or changed sits where nothing in the build will ever
     * look at it. The candidate has not survived: the compile stage that passed did not compile
     * this code.
     */
    ORPHANED
}
