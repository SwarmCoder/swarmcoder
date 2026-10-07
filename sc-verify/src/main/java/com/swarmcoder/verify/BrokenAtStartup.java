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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A freshly authored acceptance test that dies BEFORE any code under test runs — the red check's
 * third reading of "the test fails", next to a healthy red and a test that does not compile.
 *
 * <p><b>Why (live harness run 51, 2026-09-30).</b> Target: a ZeroZ Stack demo (Helidon MP server
 * module). The test author wrote {@code BookCrudTest} so that it booted the Helidon MP / CDI
 * container. On the pre-change tree that container died with a {@code NullPointerException} inside
 * {@code io.helidon.microprofile.security.SecurityCdiExtension} — class level, testId
 * {@code swarm.accept.BookCrudTest#} with a blank method name, no method ever started. The red
 * check counted it as one errored test and confirmed a healthy red. Both worker candidates then
 * compiled and failed identically, workers may not edit an acceptance test, and the run burned
 * about 90 minutes of model time on a test nothing in the plan could ever turn green.
 *
 * <p><b>The rule.</b> A failure is a startup failure when it is not an assertion (an assertion
 * means the test measured something and it fell short) and no frame of its trace belongs to one of
 * the project's own packages — nothing the plan will write ever ran, so no candidate can change
 * the outcome. A test class is broken at startup when either
 * <ul>
 *   <li>a failure is CLASS-LEVEL (no method name: the runner reported the class itself, which is
 *       how a failing {@code @BeforeAll}, a static initialiser or an extension's start-up shows
 *       up) and is a startup failure; or</li>
 *   <li>EVERY test of the class errored (none passed), every one is a startup failure, and none
 *       was thrown from the test class itself (its top-most frame is in framework code: an NPE in
 *       the test method on a stub's null is a healthy red).</li>
 * </ul>
 * Deliberately not tied to any framework: it reads only the stack's package names. A frame belongs
 * to the project when its package holds a source file under some {@code src/main/java} (or
 * {@code kotlin}) directory of the tree, or a package a still-to-run task's write set or contract
 * names. The trace the parser hands over is capped at a dozen frames, so a project frame buried
 * deeper than that reads as absent; the price is one re-ask of the test author, weighed against a
 * whole swarm on a test that cannot pass.
 *
 * <p>Pure text and paths, no process: like {@link AcceptanceCompileErrors}.
 */
public final class BrokenAtStartup {

    private BrokenAtStartup() {}

    /**
     * @param testClass   the fully-qualified test class that never got to run
     * @param reason      one sentence for a log line, a re-ask and a park brief
     * @param failureText the first offending failure as the runner reported it (id, message, trace)
     */
    public record Finding(String testClass, String reason, String failureText) {}

    /** {@code at fully.qualified.Class.method(File.java:41)} — the class is group 1. */
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+([\\w.$]+)\\.[\\w$<>]+\\(.*$");

    /** True when the runner reported the class itself rather than one of its methods. */
    private static boolean classLevel(String testId) {
        int hash = testId.indexOf('#');
        if (hash < 0) {
            return true; // the runner named no method at all
        }
        String cls = testId.substring(0, hash);
        String method = testId.substring(hash + 1).strip();
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        return method.isEmpty() || method.equals("initializationError") || method.equals(cls)
            || method.equals(simple) || method.equals("classMethod")
            || method.startsWith("@BeforeAll") || method.startsWith("beforeAll");
    }

    private static String classOf(String testId) {
        int hash = testId.indexOf('#');
        return hash < 0 ? testId : testId.substring(0, hash);
    }

    /**
     * The first test class that is broken at startup, or null when none is.
     *
     * @param projectPackages the packages of the project's own main code; see
     *                        {@link #projectPackages}
     */
    public static Finding find(TestResults results, Collection<String> projectPackages) {
        if (results == null || results.failures() == null || results.failures().isEmpty()) {
            return null;
        }
        Set<String> project = projectPackages == null ? Set.of() : new LinkedHashSet<>(projectPackages);
        List<String> classes = new ArrayList<>();
        for (TestFailure failure : results.failures()) {
            String cls = classOf(failure.testId() == null ? "" : failure.testId());
            if (!cls.isEmpty() && !classes.contains(cls)) {
                classes.add(cls);
            }
        }
        for (String cls : classes) {
            List<TestFailure> mine = new ArrayList<>();
            for (TestFailure failure : results.failures()) {
                if (failure.testId() != null && classOf(failure.testId()).equals(cls)) {
                    mine.add(failure);
                }
            }
            boolean anyPassed = results.passedIds().stream()
                .anyMatch(id -> id != null && classOf(id).equals(cls));
            // Every-method branch only: the exception must have been thrown OUTSIDE the test class
            // (top-most frame in framework code). A stub returning null and the test calling
            // .size() on it errors every method with frames only in the test class: a healthy red.
            boolean allStartup = mine.stream().allMatch(
                f -> startupFailure(f, project) && diedOutsideTestClass(f, cls));
            TestFailure classLevel = mine.stream()
                .filter(f -> classLevel(f.testId()) && startupFailure(f, project))
                .findFirst().orElse(null);
            TestFailure offending = classLevel != null ? classLevel
                : (!anyPassed && allStartup ? mine.get(0) : null);
            if (offending == null) {
                continue;
            }
            return new Finding(cls,
                (classLevel != null
                    ? "the test class " + cls + " errored at class level, before any of its test "
                        + "methods started"
                    : "every test in " + cls + " errored before any code under test ran")
                    + " (" + header(offending) + "), and no frame of the stack belongs to this "
                    + "project's own code — the failure is in the framework or container the test "
                    + "started, not in anything a task will write",
                (offending.testId() == null ? "" : offending.testId() + " — ")
                    + (offending.message() == null ? "" : offending.message())
                    + (offending.truncatedTrace() == null || offending.truncatedTrace().isBlank()
                        ? "" : "\n" + offending.truncatedTrace()));
        }
        return null;
    }

    /** Not an assertion, and no frame of the stack is in a package of the project's own. */
    private static boolean startupFailure(TestFailure failure, Set<String> project) {
        String trace = failure.truncatedTrace() == null ? "" : failure.truncatedTrace();
        if (AcceptanceFailureAttribution.isAssertionType(
                AcceptanceFailureAttribution.exceptionFullType(trace))) {
            return false;
        }
        for (String line : trace.split("\r?\n")) {
            Matcher m = FRAME.matcher(line);
            if (m.matches()) {
                String frameClass = m.group(1);
                int dot = frameClass.lastIndexOf('.');
                if (dot > 0 && project.contains(frameClass.substring(0, dot))) {
                    return false; // delivered-side code ran: the test measured something
                }
            }
        }
        // A framework that fails ABOUT one of the project's types - a container that cannot
        // satisfy a dependency on a service the plan has yet to write - has no project frame in
        // its trace, yet it is exactly what a candidate will change. Naming a project type in the
        // failure is enough to not call it a startup failure.
        String text = (failure.message() == null ? "" : failure.message()) + "\n" + trace;
        for (String pkg : project) {
            if (pkg.indexOf('.') > 0 && Pattern.compile("(?<![\\w.])" + Pattern.quote(pkg)
                    + "\\.[A-Z]\\w*").matcher(text).find()) {
                return false;
            }
        }
        return true;
    }

    /** True when the trace has a top-most frame and it is not in the test class (or a nested one). */
    private static boolean diedOutsideTestClass(TestFailure failure, String testClass) {
        String trace = failure.truncatedTrace() == null ? "" : failure.truncatedTrace();
        for (String line : trace.split("\\r?\\n")) {
            Matcher m = FRAME.matcher(line);
            if (m.matches()) {
                String frameClass = m.group(1);
                return !(frameClass.equals(testClass) || frameClass.startsWith(testClass + "$"));
            }
        }
        return false; // no frame to judge by: never read as broken
    }

    private static String header(TestFailure failure) {
        String type = AcceptanceFailureAttribution.exceptionFullType(
            failure.truncatedTrace() == null ? "" : failure.truncatedTrace());
        boolean hasType = type != null && !type.isBlank();
        String message = failure.message() == null ? "" : failure.message().strip();
        if (message.length() > 200) {
            message = message.substring(0, 200) + "...";
        }
        String text = (hasType ? type : "") + (message.isEmpty() ? "" : (hasType ? ": " : "") + message);
        return text.isEmpty() ? "no message" : text;
    }

    // ------------------------------------------------------------------ the project's packages

    /**
     * The packages of the project's own main code: every directory under a {@code src/main/java}
     * or {@code src/main/kotlin} that holds a source file, plus the packages of every write-set
     * file and design contract of {@code tasks} (code a task may still add).
     *
     * @param tree  the checkout the red check ran in; null reads only the tasks
     * @param tasks the tasks still to run; may be null
     */
    public static Set<String> projectPackages(Path tree, List<Task> tasks) {
        Set<String> packages = new LinkedHashSet<>();
        if (tree != null && Files.isDirectory(tree)) {
            try (Stream<Path> walk = Files.walk(tree, 16)) {
                walk.filter(p -> {
                    String n = p.getFileName() == null ? "" : p.getFileName().toString();
                    return n.endsWith(".java") || n.endsWith(".kt");
                }).forEach(p -> {
                    List<String> parts = new ArrayList<>();
                    tree.relativize(p).forEach(seg -> parts.add(seg.toString()));
                    // build output is skipped only above the source tree: below src/main/java
                    // the same words are package names (com.acme.build, com.acme.target)
                    int src = parts.indexOf("src");
                    List<String> above = src < 0 ? parts : parts.subList(0, src);
                    for (String skip : List.of(".git", "target", "build", "node_modules")) {
                        if (above.contains(skip)) {
                            return;
                        }
                    }
                    for (int i = 0; i + 2 < parts.size() - 1; i++) {
                        if (parts.get(i).equals("src") && parts.get(i + 1).equals("main")
                                && (parts.get(i + 2).equals("java") || parts.get(i + 2).equals("kotlin"))) {
                            packages.add(String.join(".", parts.subList(i + 3, parts.size() - 1)));
                            return;
                        }
                    }
                });
            } catch (IOException | RuntimeException e) {
                // an unreadable tree adds nothing; the tasks below still count
            }
        }
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null) {
                    continue;
                }
                if (task.writeSet() != null) {
                    for (String entry : task.writeSet()) {
                        String named = TypeDeliverability.typeNamed(entry);
                        if (named != null && named.contains(".")) {
                            packages.add(named.substring(0, named.lastIndexOf('.')));
                        }
                    }
                }
                for (ApiContract contract : task.deliveredContracts()) {
                    if (contract != null && !contract.packageName().isEmpty()) {
                        packages.add(contract.packageName());
                    }
                }
            }
        }
        packages.remove("");
        return packages;
    }
}
