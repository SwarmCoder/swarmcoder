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
package com.swarmcoder.app;

import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which acceptance-test files are THIS run's, and which the project already had - for the chain
 * links that used to assume the acceptance-test folder starts empty (live run 88, 2026-10-05).
 *
 * <p>Since the harness keeps earlier stories' acceptance tests in the clone (DEVELOPER_CORRECTIONS
 * section 59), the base commit holds test files of its own. Run 88 delivered its story and the
 * chain broke at "acceptance tests land where the build compiles them": the kept
 * {@code LogbookTest.java} was counted as one of the run's tests commit's files and as a file
 * "the checkout carries, which would outlive the run".
 *
 * <p>The rule, from the run's pinned base commit and nothing else: a file is the run's when the
 * base commit does not hold it, or holds it with other content. A file the base commit holds
 * unchanged is the project's and is no finding anywhere. No git here - the callers read the
 * commits, this decides.
 */
final class AcceptanceFilesLink {

    private AcceptanceFilesLink() {}

    /**
     * @param authored       the run's files under the directory the build compiles tests in
     * @param strays         the run's files anywhere else: nothing compiles them
     * @param kept           files of the tests commit the base commit already held, unchanged
     * @param leftInCheckout test files in the checkout itself that the base commit does not
     *                       account for: written by the run outside its tests commit
     */
    record Verdict(boolean held, List<String> authored, List<String> strays, List<String> kept,
                   List<String> leftInCheckout) {}

    /**
     * @param writeDir             where the build compiles acceptance tests, repo-relative
     * @param atBase               the acceptance-test files the run's base commit holds
     * @param inTestsCommit        the acceptance-test files the run's tests commit holds
     * @param changedByTestsCommit every path that differs between the base and the tests commit
     * @param inCheckout           the acceptance-test files on disk in the checkout
     * @param changedInCheckout    every path whose content on disk differs from the base commit
     */
    static Verdict evaluate(String writeDir, Collection<String> atBase,
                            Collection<String> inTestsCommit,
                            Collection<String> changedByTestsCommit,
                            Collection<String> inCheckout,
                            Collection<String> changedInCheckout) {
        Set<String> base = normalised(atBase);
        Set<String> changedInCommit = normalised(changedByTestsCommit);
        Set<String> changedOnDisk = normalised(changedInCheckout);
        String home = normalise(writeDir) + "/";
        List<String> authored = new ArrayList<>();
        List<String> strays = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String path : normalised(inTestsCommit)) {
            if (base.contains(path) && !changedInCommit.contains(path)) {
                kept.add(path);
            } else if (path.startsWith(home)) {
                authored.add(path);
            } else {
                strays.add(path);
            }
        }
        List<String> left = new ArrayList<>();
        for (String path : normalised(inCheckout)) {
            if (!base.contains(path) || changedOnDisk.contains(path)) {
                left.add(path);
            }
        }
        return new Verdict(!authored.isEmpty() && strays.isEmpty() && left.isEmpty(),
            List.copyOf(authored), List.copyOf(strays), List.copyOf(kept), List.copyOf(left));
    }

    private static final Pattern TEST_MARKER = Pattern.compile(
        "^\\s*@(Test|ParameterizedTest|RepeatedTest)\\b", Pattern.MULTILINE);

    /** Test methods in one source, counted as the fixture counts the ones it keeps. */
    static int testMethodsIn(String source) {
        int n = 0;
        Matcher m = TEST_MARKER.matcher(source == null ? "" : source);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * Test methods the acceptance-test files of {@code commit} hold: what "earlier stories' tests"
     * comes to for a run pinned to that commit. Read from the commit, so a walk resumed from a
     * snapshot - which never made the fixture - counts the same as a full walk.
     */
    static int testMethodsAt(Path repo, String commit) throws Exception {
        int n = 0;
        for (String path : EndToEndLoopTest.filesInCommit(repo, commit)) {
            if (path.endsWith(".java") && path.contains("src/test/java/swarm/")) {
                n += testMethodsIn(BookshelfFixture.git(repo, "show " + commit + ":" + path));
            }
        }
        return n;
    }

    /**
     * How many of the tests one candidate's acceptance stage executed are the run's own: tests of
     * a class one of {@code authored} declares (live run 89, DEVELOPER_CORRECTIONS section 64).
     *
     * <p>A candidate is verified against the files its task claims and only those
     * ({@code AcceptanceOverlay.reduceTo} clears the earlier stories' files first), so the stage's
     * count never holds an earlier story's test. Link 10 used to ask for more executed tests than
     * the base commit holds; run 89's story had 3 tests, its base 5, all 3 ran and passed and the
     * link broke. Results that name no test at all are taken as they are.
     */
    static int ownTestsExecuted(TestResults results, Collection<String> authored) {
        if (results == null) {
            return 0;
        }
        List<String> ids = new ArrayList<>(results.passedIds());
        if (results.failures() != null) {
            for (TestFailure failure : results.failures()) {
                if (failure != null && failure.testId() != null) {
                    ids.add(failure.testId());
                }
            }
        }
        if (ids.isEmpty() || results.idsTruncated()) {
            return results.executed();
        }
        Set<String> files = normalised(authored);
        int own = 0;
        for (String id : ids) {
            int hash = id.indexOf('#');
            String type = hash < 0 ? id : id.substring(0, hash);
            int nested = type.indexOf('$');
            String file = (nested < 0 ? type : type.substring(0, nested)).replace('.', '/')
                + ".java";
            if (files.stream().anyMatch(f -> f.equals(file) || f.endsWith("/" + file))) {
                own++;
            }
        }
        return own;
    }

    private static Set<String> normalised(Collection<String> paths) {
        Set<String> out = new LinkedHashSet<>();
        if (paths != null) {
            paths.forEach(p -> out.add(normalise(p)));
        }
        return out;
    }

    private static String normalise(String path) {
        String p = path == null ? "" : path.replace('\\', '/').strip();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
