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

import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.knowledge.ProjectTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A check the existing code already satisfies is recorded as already proved, not parked on
 * (owner decision after the audit of 2026-10-02).
 *
 * <p>At TEST_AUTHORING a task's acceptance tests are run on the tree the run starts from and must
 * fail there. When they pass, the run used to park: "the tests are not red". For a story built on
 * earlier stories that is often simply true and good - the code delivered before already does
 * what the check asks. Parking then stops a person over a fact, and asks them to rewrite a test
 * that is doing its job.
 *
 * <p><b>The guard.</b> A green test on an untouched tree is also exactly what a test that
 * measures nothing looks like. So "already proved" is accepted only on evidence that the test
 * executed assertions against the project's own code - {@link #assess}; anything less keeps the
 * behaviour there was (the run stops and the test goes back to be written again).
 *
 * <p><b>What follows.</b> The task is no longer obliged to turn those tests green. A task that
 * then has nothing left to prove and that nothing else builds on is dropped, by the same rule
 * {@link UnusedEnablers} drops a task under - {@link #droppable}.
 */
final class AlreadySatisfied {

    private AlreadySatisfied() {}

    /**
     * @param accepted true when the green run is accepted as proof
     * @param refusal  why it is not; "" when it is
     * @param tests    the passing tests that are the evidence ({@code class#method})
     */
    record Evidence(boolean accepted, String refusal, List<String> tests) {

        static Evidence refused(String why) {
            return new Evidence(false, why, List.of());
        }
    }

    /** A call that checks something: assertEquals(, assertThat(, fail(, verify(, expect(... */
    private static final Pattern ASSERTION = Pattern.compile(
        "\\b(?:assert\\w*|fail|verify\\w*|expect\\w*|should\\w*|then\\w*)\\s*\\(");

    private static final Pattern IMPORT = Pattern.compile(
        "(?m)^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;");

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /**
     * Whether a green run of {@code task}'s acceptance tests on the start tree is proof that the
     * existing code satisfies its checks. Every one of these must hold:
     *
     * <ul>
     *   <li>the stage executed tests, at least one passed and none failed;</li>
     *   <li>every test file the task claims was among what ran and passed - a file the runner
     *       never executed has proved nothing;</li>
     *   <li>every such file makes at least one assertion - an empty test passes on any tree;</li>
     *   <li>every such file names a type the project itself declares outside its test trees - a
     *       test that touches no project code measures no project code;</li>
     *   <li>{@link SelfImplementedContract} finds no contract the test stands in for - a test
     *       that supplies the behaviour it measures passes with no delivered code at all.</li>
     * </ul>
     *
     * @param tree the tree the tests ran on (the start tree plus the task's own tests)
     */
    static Evidence assess(Path tree, DesignDocument design, Task task, List<Task> planTasks,
                           TestResults results) {
        if (task == null || results == null || tree == null) {
            return Evidence.refused("there is no tree or no result to read the evidence from");
        }
        if (results.stageOutcome() != TestStageOutcome.EXECUTED || results.passed() <= 0
                || results.failed() > 0 || results.errored() > 0) {
            return Evidence.refused("the acceptance stage did not run the tests to a clean pass");
        }
        List<String> paths = task.authoredTestPaths();
        if (paths == null || paths.isEmpty()) {
            return Evidence.refused("the task claims no test file");
        }
        if (results.passedIds().isEmpty()) {
            return Evidence.refused("the runner did not report which tests passed");
        }
        ProjectTypes types = ProjectTypes.of(tree);
        Set<String> evidence = new LinkedHashSet<>();
        for (String path : paths) {
            Path file = tree.resolve(path);
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException | RuntimeException e) {
                return Evidence.refused("the test file " + path + " could not be read");
            }
            String simpleName = simpleNameOf(path);
            List<String> ran = results.passedIds().stream()
                .filter(id -> classOf(id).equals(simpleName)).toList();
            if (ran.isEmpty()) {
                return Evidence.refused("no test of " + path + " is among the tests reported "
                    + "as passed, so nothing shows it was executed");
            }
            String code = SelfImplementedContract.strip(source);
            if (!ASSERTION.matcher(code).find()) {
                return Evidence.refused(path + " makes no assertion, so it passes on any tree");
            }
            if (!namesProjectCode(code, types, tree, task, paths)) {
                return Evidence.refused(path + " names no type the project declares outside "
                    + "its tests, so it does not measure the project's code");
            }
            evidence.addAll(ran);
        }
        SelfImplementedContract.Check standIn =
            SelfImplementedContract.check(tree, design, paths, planTasks);
        if (!standIn.ok()) {
            return Evidence.refused("the test supplies its own implementation of "
                + standIn.contractTypes() + ", so it passes without the project's code");
        }
        return new Evidence(true, "", List.copyOf(evidence));
    }

    private static boolean namesProjectCode(String code, ProjectTypes types, Path tree, Task task,
                                            List<String> testPaths) {
        Matcher pkg = PACKAGE.matcher(code);
        String ownPackage = pkg.find() ? pkg.group(1) : "";
        Matcher imports = IMPORT.matcher(code);
        while (imports.find()) {
            String name = imports.group(2);
            boolean isStatic = imports.group(1) != null;
            boolean wildcard = imports.group(3) != null;
            List<String> candidates = new ArrayList<>();
            if (wildcard && !isStatic) {
                // import a.b.*; - any project type of that package the test names
                for (String full : types.fullNames()) {
                    if (full.startsWith(name + ".") && full.indexOf('.', name.length() + 1) < 0
                            && usesSimpleName(code, full.substring(name.length() + 1))) {
                        candidates.add(full);
                    }
                }
            } else {
                candidates.add(name);
                if (isStatic && name.contains(".")) {
                    candidates.add(name.substring(0, name.lastIndexOf('.')));
                }
            }
            for (String candidate : candidates) {
                if (isProductionType(candidate, types, tree, task, testPaths)) {
                    return true;
                }
            }
        }
        // A type of the test's own package needs no import.
        if (!ownPackage.isEmpty()) {
            for (String full : types.fullNames()) {
                if (full.startsWith(ownPackage + ".")
                        && full.indexOf('.', ownPackage.length() + 1) < 0
                        && usesSimpleName(code, full.substring(ownPackage.length() + 1))
                        && isProductionType(full, types, tree, task, testPaths)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean usesSimpleName(String code, String simpleName) {
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(code).find();
    }

    /** Declared by the project, and not in a test tree or among the task's own test files. */
    private static boolean isProductionType(String fullName, ProjectTypes types, Path tree,
                                            Task task, List<String> testPaths) {
        Path file = types.fileOf(fullName);
        if (file == null) {
            return false;
        }
        String relative;
        try {
            relative = tree.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (testPaths.contains(relative)) {
            return false;
        }
        String testDir = task.acceptanceTestDir() == null ? ""
            : task.acceptanceTestDir().replace('\\', '/');
        if (!testDir.isEmpty() && relative.startsWith(testDir)) {
            return false;
        }
        for (String segment : relative.split("/")) {
            if (segment.equals("test") || segment.equals("tests") || segment.equals("it")) {
                return false;
            }
        }
        return true;
    }

    private static String simpleNameOf(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** The simple class name of a reported test id ({@code a.b.FooTest$Nested#method}). */
    private static String classOf(String testId) {
        String type = testId.contains("#") ? testId.substring(0, testId.indexOf('#')) : testId;
        type = type.substring(type.lastIndexOf('.') + 1);
        return type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
    }

    /**
     * Which of the tasks whose checks are already satisfied may be dropped: those nothing that is
     * kept builds on. A task is kept when a kept task depends on it, or uses a type it writes
     * (read as {@link TypeDependencyOrder} reads it). The caller only offers a task whose
     * contracts are all present in the start tree already.
     *
     * @param nothingLeftToProve ids of tasks whose every check was accepted as already satisfied
     *                           and whose delivered contracts are already in the tree
     * @return the ids to drop; empty when none may be
     */
    static Set<UUID> droppable(TaskGraph graph, DesignDocument design,
                               Collection<UUID> nothingLeftToProve) {
        Set<UUID> drop = new LinkedHashSet<>();
        if (graph == null || graph.tasks() == null || nothingLeftToProve == null) {
            return drop;
        }
        for (Task task : graph.tasks()) {
            if (nothingLeftToProve.contains(task.id())) {
                drop.add(task.id());
            }
        }
        if (drop.isEmpty()) {
            return drop;
        }
        List<TypeDependencyOrder.Use> uses =
            TypeDependencyOrder.uses(graph.tasks(), design, new ArrayList<>());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TaskEdge edge : graph.dependencies() == null
                    ? List.<TaskEdge>of() : graph.dependencies()) {
                // "to" may not start until "from" has finished: a kept task builds on "from".
                if (edge != null && drop.contains(edge.from()) && !drop.contains(edge.to())) {
                    changed |= drop.remove(edge.from());
                }
            }
            for (TypeDependencyOrder.Use use : uses) {
                if (drop.contains(use.writer().id()) && !drop.contains(use.user().id())) {
                    changed |= drop.remove(use.writer().id());
                }
            }
        }
        return drop;
    }
}
