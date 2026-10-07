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
package com.swarmcoder.testsupport;

import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Says out loud that a test did not run, and why.
 *
 * <p>This exists because of the thing the whole change is about. A skipped test is invisible: JUnit
 * records it, surefire counts it in a number nobody reads, and the build goes green. Thirteen test
 * classes in this repository were skipped in every build for weeks and read as coverage the whole
 * time. Coverage that is not run is not coverage, and the least a build can do is admit it.
 *
 * <p>So every skip goes through here and lands in two places:
 *
 * <ul>
 *   <li><b>The build output.</b> A banner on standard error, which surefire pipes straight to the
 *       Maven console, so it is in front of whoever ran the build.</li>
 *   <li><b>{@code target/tests-not-run.txt}</b> in the module, appended to. Every fork writes to
 *       it — the console module runs one test per JVM by necessity — so after a build that file
 *       is the complete list of what was not checked, in one place, readable afterwards and by
 *       anything automated.</li>
 * </ul>
 *
 * <p>{@code target/} is wiped by {@code mvn clean}, so the file cannot outlive the tree it
 * describes. Each line carries a wall-clock time so a stale line from an earlier run in the same
 * tree is recognisable as one.
 */
public final class NotRun {

    private static final Set<String> ALREADY_SAID =
        Collections.synchronizedSet(new LinkedHashSet<>());

    private NotRun() {
    }

    /**
     * Records and announces one test that will not run.
     *
     * @param test   how the test should be named to a person — the class, or the class and method
     * @param reason a full sentence saying what was missing and, where there is one, what to do
     */
    public static void announce(String test, String reason) {
        String line = test + " — " + reason;
        if (!ALREADY_SAID.add(line)) {
            return;
        }
        System.err.println();
        System.err.println("  !!  NOT RUN  " + test);
        System.err.println("  !!    " + reason);
        System.err.println();
        System.err.flush();
        append(line);
    }

    /**
     * Skips the test from inside its body, announced, when something it needs is missing part-way
     * through. Use it instead of {@code Assumptions.assumeTrue}, which skips in silence.
     *
     * @param satisfied whether the thing the test needs is there
     * @param test      how the test should be named to a person
     * @param reason    what is missing, as a full sentence
     */
    public static void needed(boolean satisfied, String test, String reason) {
        if (satisfied) {
            return;
        }
        announce(test, reason);
        throw new TestAbortedException(test + " — " + reason);
    }

    private static void append(String line) {
        try {
            Path target = Path.of("target");
            if (!Files.isDirectory(target)) {
                // Not a surefire fork with the module as its working directory. The banner on
                // standard error has already been printed, which is the part a person reads.
                return;
            }
            String stamp = LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
            Files.writeString(target.resolve("tests-not-run.txt"), stamp + "  " + line + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // Never fail a build over the bookkeeping about a skip.
            System.err.println("  !!    (could not write target/tests-not-run.txt: " + e + ")");
        }
    }
}
