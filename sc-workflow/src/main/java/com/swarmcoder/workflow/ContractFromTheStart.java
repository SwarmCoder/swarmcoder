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
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.knowledge.DeclarableArtifacts;
import com.swarmcoder.knowledge.ReachableCode;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.verify.AcceptanceTestLocation;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.BuildLayout;
import com.swarmcoder.verify.ContractInUse;
import com.swarmcoder.verify.GeneratedContract;
import com.swarmcoder.verify.ServeFromTheCode;
import com.swarmcoder.verify.VerifySpecLoader;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A run is held to a verification contract from its first candidate, also when its project
 * starts as an empty folder (section 107).
 *
 * <p><b>When the contract is provided.</b> Right after the plan is accepted and before the
 * first acceptance test is written. That is the first moment at which the build system of a
 * project made from nothing is known without asking a model or reading prose: the plan's
 * tasks name the build files they create ({@code pom.xml}, {@code server/pom.xml}). Before
 * the plan there is only the design's text, and telling a build system from text would be a
 * match on words. Waiting for the first build file on the run's tree would be later than the
 * red check of the acceptance tests and later than the start of the first candidates, which
 * is what the run this was written after did for its whole length. A project whose folder
 * already holds a build file gets its contract from that instead; a contract that is already
 * there is never replaced.
 *
 * <p><b>Where the acceptance tests go in a project that does not exist yet.</b> The folder
 * has no build to read, so the place used to be the repository root. When the plan names the
 * build files of several modules, the root is an aggregator and compiles nothing: tests put
 * there are run by nobody and the acceptance stage selects none. The same build files that
 * decide the contract therefore decide the place: a module that compiles, per task the one
 * its own main sources are written to (section 109, {@link #acceptancePlaces}).
 */
final class ContractFromTheStart {

    private ContractFromTheStart() {}

    /**
     * @param contract the contract in use, as it is now in the project's folder
     * @param refusal  null when the run may go on; otherwise the question it stops with
     */
    record Gate(ContractInUse contract, String refusal) {}

    /** The build files the plan's tasks name in their write sets. */
    static List<String> plannedBuildFiles(TaskGraph graph) {
        List<String> paths = new ArrayList<>();
        if (graph != null && graph.tasks() != null) {
            for (Task task : graph.tasks()) {
                if (task.writeSet() != null) {
                    paths.addAll(task.writeSet());
                }
            }
        }
        return GeneratedContract.buildFilesAmong(paths);
    }

    /**
     * Provides the contract when the project has none, and says whether the run may go on:
     * a run whose tasks claim a check or have an acceptance test does not go on without one,
     * and a contract file that cannot be read stops it whatever is owed.
     */
    static Gate gate(Path repoPath, TaskGraph graph, ArtifactStore store) {
        ContractInUse contract = GeneratedContract.provide(repoPath, plannedBuildFiles(graph));
        int checks = 0;
        int tests = 0;
        if (graph != null && graph.tasks() != null) {
            for (Task task : graph.tasks()) {
                if (task.selectedCandidateId() != null) {
                    continue; // has its winner already
                }
                checks += store.describeClaimedChecks(task).size();
                tests += task.authoredTestPaths().size();
            }
        }
        return new Gate(contract, contract.refusal(checks, tests, "the work of this run"));
    }

    /**
     * What came of giving a generated contract its browser block (section 108).
     *
     * @param written  true when the block is now in the contract
     * @param said     for the run's log: how the application is started, or why it is not
     * @param question null unless the run must stop: what a person has to settle
     */
    record Served(boolean written, String said, String question) {}

    /**
     * Gives a contract SwarmCoder generated the browser block a story with a screen needs,
     * worked out from the code: the entry point from the object graph, its classpath from the
     * build (see {@link ServeFromTheCode}). Called when the journeys of a story are decided and
     * the contract cannot start the application. A contract a person wrote is never changed.
     *
     * @param graph the object graph of the project's own checkout; null when it could not be
     *              built
     */
    static Served browserFromTheCode(Path repoPath, ReachableCode.Graph graph,
                                     BuildLayout.Layout layout, BrowserOnlyCode.Survey survey) {
        if (!GeneratedContract.wantsABrowserBlock(repoPath)) {
            return new Served(false, "the contract was written by a person or already has a "
                + "browser block; it is not changed", null);
        }
        String toolchain = GeneratedContract.contractToolchain(repoPath);
        if (!"maven".equals(toolchain)) {
            return new Served(false, "the generated contract is for " + toolchain + ", whose "
                + "runtime classpath SwarmCoder cannot name without running the build", null);
        }
        if (graph == null || !graph.determined()) {
            return new Served(false, "", "The contract SwarmCoder generated for this project "
                + "has no `browser:` block, and the class that starts the application could not "
                + "be read from the code: " + (graph == null ? "its object graph could not be "
                    + "built" : graph.note()) + ". Write the `browser:` block of "
                + VerifySpecLoader.SPEC_PATH + " by hand.");
        }
        List<ServeFromTheCode.EntryPoint> mains = graph.mainTypes().stream()
            .map(main -> new ServeFromTheCode.EntryPoint(main.file(), main.type(),
                main.namedInABuildFile()))
            .toList();
        ServeFromTheCode.Outcome outcome = ServeFromTheCode.serve(repoPath, mains, layout,
            survey, GeneratedContract.entryPointNamed(repoPath),
            DeclarableArtifacts.defaultLocalRepository());
        if (outcome.question() != null) {
            return new Served(false, "", "The contract SwarmCoder generated for this project "
                + "has no `browser:` block, and how to start the application could not be "
                + "settled from the code. " + outcome.question());
        }
        if (!GeneratedContract.addBrowser(repoPath, outcome)) {
            return new Served(false, "", "The contract SwarmCoder generated for this project "
                + "has no `browser:` block, and the one worked out from the code ("
                + outcome.how() + ") could not be written to "
                + VerifySpecLoader.SPEC_PATH + ". Write it by hand: "
                + "serve: " + outcome.browser().serve());
        }
        return new Served(true, outcome.how() + ". Started with: "
            + outcome.browser().serve(), null);
    }

    /**
     * Where the acceptance tests go, per task (section 109).
     *
     * @param planDefault the place for a task that writes no main source of its own (a task
     *                    that only writes build files) or only code that runs in a browser;
     *                    also the place the plan as a whole is described by
     * @param perTask     every task of the plan, by id
     */
    record Places(AcceptanceTestLocation.Location planDefault,
                  Map<UUID, AcceptanceTestLocation.Location> perTask) {

        AcceptanceTestLocation.Location of(Task task) {
            AcceptanceTestLocation.Location own = task == null ? null : perTask.get(task.id());
            return own == null ? planDefault : own;
        }
    }

    /** The plan's default place; see {@link #acceptancePlaces}. */
    static AcceptanceTestLocation.Location acceptanceLocation(BuildLayout.Layout layout,
                                                              TaskGraph graph) {
        return acceptancePlaces(layout, graph, null).planDefault();
    }

    /**
     * Where the acceptance tests of each task of this plan go.
     *
     * <p><b>A project whose build can be read</b> keeps one place for every task: the module
     * whose classpath reaches the most other modules ({@link AcceptanceTestLocation#resolve}),
     * because there the modules' real dependencies are known.
     *
     * <p><b>A project whose build is still to be written</b> (section 107) has no dependencies to
     * read, only the plan. A task's test goes in the module its own main sources are written to:
     * that module's build file is the one that must declare everything the task's code, and so
     * its test, uses. A task writing the main sources of several modules takes the one whose
     * tasks come after the tasks of the most other modules. A task that writes no main source
     * (a task that only writes build files), or only code of a module that runs in a browser,
     * takes the plan's default: among the modules that do not run only in a browser, the one
     * whose tasks come after the most other modules' tasks; a tie goes to the module whose last
     * task comes latest in the plan. A build file a task writes does not make the task one of
     * that module's tasks: the task that writes every module's build file first would otherwise
     * come before everything, and every module would then seem to come after every other (the
     * live build of 2026-10-14 put a server test in the browser client so). The conventional
     * place at the repository root only when nothing says anything.
     *
     * @param repoPath the project's folder, to tell a module that runs only in a browser from
     *                 its build file when that file is already there; null when unknown
     */
    static Places acceptancePlaces(BuildLayout.Layout layout, TaskGraph graph, Path repoPath) {
        List<Task> tasks = graph == null || graph.tasks() == null ? List.of() : graph.tasks();
        if (layout != null && layout.determined()) {
            return everyTaskAt(AcceptanceTestLocation.resolve(layout), tasks);
        }
        List<String> files = plannedBuildFiles(graph);
        BuildLayout.Layout modules = GeneratedContract.plannedLayout(files, Map.of());
        if (modules == null) {
            return everyTaskAt(AcceptanceTestLocation.resolve(layout), tasks);
        }
        List<String> compiling = modules.compilingModules();
        Set<String> browserOnly = new TreeSet<>(
            BrowserOnlyCode.survey(repoPath, modules).browserOnlyDirs());
        Map<UUID, Set<String>> sources = sourceModules(tasks, compiling);
        Map<String, Set<String>> after = comesAfter(graph, sources);
        Map<UUID, Integer> position = planOrder(graph);
        Map<String, Integer> lastTask = new HashMap<>();
        for (Task task : tasks) {
            for (String module : sources.getOrDefault(task.id(), Set.of())) {
                lastTask.merge(module, position.getOrDefault(task.id(), -1), Math::max);
            }
        }
        List<String> eligible = compiling.stream().filter(m -> !browserOnly.contains(m)).toList();
        String fallback = latest(eligible.isEmpty() ? compiling : eligible, after, lastTask);
        int reach = after.getOrDefault(fallback, Set.of()).size();
        AcceptanceTestLocation.Location planDefault = place(fallback,
            compiling.size() == 1 || reach == compiling.size() - 1,
            "No build file of this project exists yet; the plan names " + compiling.size()
                + " compiling module" + (compiling.size() == 1 ? "" : "s") + ". A task's "
                + "acceptance tests go in the module its own main sources are written to. A task "
                + "that writes none goes in " + label(fallback) + ", whose tasks come after the "
                + "tasks of " + reach + " of the other " + (compiling.size() - 1) + " modules"
                + (browserOnly.isEmpty() ? "" : ", leaving out the modules that run only in a "
                    + "browser (" + String.join(", ", browserOnly) + ")")
                + ".");
        Map<UUID, AcceptanceTestLocation.Location> perTask = new LinkedHashMap<>();
        for (Task task : tasks) {
            List<String> own = sources.getOrDefault(task.id(), Set.of()).stream()
                .filter(m -> !browserOnly.contains(m)).toList();
            if (own.isEmpty()) {
                perTask.put(task.id(), planDefault);
                continue;
            }
            String module = latest(own, after, lastTask);
            perTask.put(task.id(), place(module, compiling.size() == 1,
                "Task '" + task.title() + "' writes the main sources of " + label(module)
                    + (own.size() == 1 ? "" : " (of " + own.size() + " modules, the one whose "
                        + "tasks come after the most others)")
                    + "; its acceptance tests go there, where that module's build file declares "
                    + "what its code uses."));
        }
        return new Places(planDefault, perTask);
    }

    private static Places everyTaskAt(AcceptanceTestLocation.Location location, List<Task> tasks) {
        Map<UUID, AcceptanceTestLocation.Location> perTask = new LinkedHashMap<>();
        for (Task task : tasks) {
            perTask.put(task.id(), location);
        }
        return new Places(location, perTask);
    }

    private static AcceptanceTestLocation.Location place(String module, boolean seesEvery,
                                                        String note) {
        String prefix = module.isEmpty() ? "" : module + "/";
        return new AcceptanceTestLocation.Location(module,
            prefix + AcceptanceTestLocation.PROTECTED_SUBDIR,
            prefix + AcceptanceTestLocation.WRITE_SUBDIR, seesEvery, note);
    }

    private static String label(String module) {
        return module.isEmpty() ? "the repository root" : module;
    }

    /**
     * Of {@code modules}, the one whose tasks come after the most other modules' tasks; a tie
     * goes to the one whose last task comes latest in the plan, then to the first named.
     */
    private static String latest(List<String> modules, Map<String, Set<String>> after,
                                 Map<String, Integer> lastTask) {
        String best = null;
        for (String module : modules) {
            if (best == null) {
                best = module;
                continue;
            }
            int reach = after.getOrDefault(module, Set.of()).size();
            int bestReach = after.getOrDefault(best, Set.of()).size();
            if (reach > bestReach || reach == bestReach
                    && lastTask.getOrDefault(module, -1) > lastTask.getOrDefault(best, -1)) {
                best = module;
            }
        }
        return best;
    }

    /**
     * Per task, the compiling modules whose main sources its write set holds: every entry that
     * is not a build file. A task that only writes build files has none.
     */
    static Map<UUID, Set<String>> sourceModules(List<Task> tasks, List<String> modules) {
        Map<UUID, Set<String>> of = new HashMap<>();
        for (Task task : tasks) {
            Set<String> written = new LinkedHashSet<>();
            if (task.writeSet() != null) {
                for (String raw : task.writeSet()) {
                    String path = BuildLayout.normalize(raw);
                    if (path == null || path.isEmpty()
                            || !GeneratedContract.buildFilesAmong(List.of(path)).isEmpty()) {
                        continue;
                    }
                    String module = moduleOf(path, modules);
                    if (module != null) {
                        written.add(module);
                    }
                }
            }
            of.put(task.id(), written);
        }
        return of;
    }

    /**
     * Each task's position in the plan: its depth in the dependency order (a task comes after
     * everything it depends on), then its place in the plan's list.
     */
    private static Map<UUID, Integer> planOrder(TaskGraph graph) {
        List<Task> tasks = graph.tasks();
        Map<UUID, Set<UUID>> before = earlierTasks(graph);
        Map<UUID, Integer> depth = new HashMap<>();
        for (int round = 0; round <= tasks.size(); round++) {
            boolean changed = false;
            for (Task task : tasks) {
                int d = 0;
                for (UUID earlier : before.getOrDefault(task.id(), Set.of())) {
                    d = Math.max(d, depth.getOrDefault(earlier, 0) + 1);
                }
                Integer was = depth.put(task.id(), d);
                changed |= was == null || was != d;
            }
            if (!changed) {
                break; // a cycle stops at the round limit; it is not a valid plan anyway
            }
        }
        Map<UUID, Integer> position = new HashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            position.put(tasks.get(i).id(),
                depth.getOrDefault(tasks.get(i).id(), 0) * (tasks.size() + 1) + i);
        }
        return position;
    }

    private static Map<UUID, Set<UUID>> earlierTasks(TaskGraph graph) {
        Map<UUID, Set<UUID>> before = new HashMap<>();
        if (graph.dependencies() != null) {
            for (TaskEdge edge : graph.dependencies()) {
                if (edge != null && edge.from() != null && edge.to() != null) {
                    before.computeIfAbsent(edge.to(), id -> new HashSet<>()).add(edge.from());
                }
            }
        }
        return before;
    }

    /**
     * Per module, the modules whose tasks its own tasks are planned to come after, directly
     * or through other tasks. A task counts for the modules whose main sources it writes.
     */
    static Map<String, Set<String>> comesAfter(TaskGraph graph, Map<UUID, Set<String>> modulesOf) {
        Map<UUID, Set<UUID>> before = earlierTasks(graph);
        Map<String, Set<String>> after = new LinkedHashMap<>();
        for (Task task : graph.tasks()) {
            Set<UUID> seen = new HashSet<>();
            Deque<UUID> queue = new ArrayDeque<>(before.getOrDefault(task.id(), Set.of()));
            while (!queue.isEmpty()) {
                UUID earlier = queue.poll();
                if (!seen.add(earlier)) {
                    continue;
                }
                queue.addAll(before.getOrDefault(earlier, Set.of()));
                for (String mine : modulesOf.getOrDefault(task.id(), Set.of())) {
                    for (String theirs : modulesOf.getOrDefault(earlier, Set.of())) {
                        if (!mine.equals(theirs)) {
                            after.computeIfAbsent(mine, m -> new LinkedHashSet<>()).add(theirs);
                        }
                    }
                }
            }
        }
        return after;
    }

    /** The module a path lies in: the longest module directory that holds it; null for none. */
    private static String moduleOf(String path, List<String> modules) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String best = null;
        for (String module : modules) {
            boolean holds = module.isEmpty() || path.equals(module)
                || path.startsWith(module + "/");
            if (holds && (best == null || module.length() > best.length())) {
                best = module;
            }
        }
        return best;
    }
}
