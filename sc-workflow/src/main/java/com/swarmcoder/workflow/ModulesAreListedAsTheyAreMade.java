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

import java.nio.file.Files;
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
 * An aggregating build file lists only the modules that exist when the task that writes it ends
 * (section 114). No model, and nothing read from a task's wording: from the build files the
 * plan's write sets name, the files already on disk, and the plan's order.
 *
 * <h2>Why</h2>
 *
 * <p>In a build of an empty project the first task was told to write the root {@code pom.xml}
 * listing three modules, two of which later tasks create. The verification contract compiles
 * from the root, and Maven stops on a listed module whose folder has no build file ("child
 * module does not exist") whatever the candidate writes. All four first-round candidates
 * failed for it, and the repair round cost 395,000 tokens.
 *
 * <h2>What is decided, and why it is put right rather than sent back</h2>
 *
 * <p>For every new module build file of the plan, the aggregating {@code pom.xml} of the nearest
 * folder above it that holds one (planned or on disk) is the file that must list it. When the
 * module is created by the task that writes the aggregator, or by a task that ends before it,
 * nothing is to do. Otherwise the plan's own facts decide the remedy completely, so it is applied
 * and said as a note, the way {@link TypeDependencyOrder} adds an order (section 76: a planner
 * told "fix this" for what the check can decide writes its whole plan again):
 *
 * <ul>
 *   <li>the module's task runs after the aggregator's task (an order is added when the plan had
 *       none), so the aggregator exists in its checkout;</li>
 *   <li>the aggregator is put in the module task's write set, so section 112's guard lets its
 *       change through, and its instructions say to add the module to it;</li>
 *   <li>the aggregator's task is told which modules it lists: those it creates or that tasks it
 *       waits for create, and that the others are added by their own tasks.</li>
 * </ul>
 *
 * <p>A module whose build file two tasks of one module hold (as {@link BuildFilesInTheJob} gives
 * it, on purpose) is treated as created by either: both are ordered and told. An objection is
 * left only where the structure cannot decide: a new aggregating build file that two tasks,
 * neither waiting for the other, both write. The planner is then told to give it to one.
 *
 * <p>Maven only. A Gradle build lists its modules in a settings file that no module task writes;
 * that is not checked here.
 */
final class ModulesAreListedAsTheyAreMade {

    private ModulesAreListedAsTheyAreMade() {
    }

    /**
     * @param corrections what was put right, one sentence each
     * @param violations  what only the planner can put right
     */
    record Outcome(List<String> corrections, List<String> violations) {
    }

    static Outcome apply(TaskGraph graph, Path repoRoot) {
        List<String> corrections = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        if (graph == null || graph.tasks() == null || graph.tasks().isEmpty()) {
            return new Outcome(corrections, violations);
        }
        Map<String, List<Task>> writers = new LinkedHashMap<>();
        for (Task task : graph.tasks()) {
            if (task == null || task.writeSet() == null) {
                continue;
            }
            for (String raw : task.writeSet()) {
                String path = clean(raw);
                if (path != null && BuildFilesInTheJob.isBuildFile(path)) {
                    writers.computeIfAbsent(path, key -> new ArrayList<>()).add(task);
                }
            }
        }
        if (writers.isEmpty()) {
            return new Outcome(corrections, violations);
        }
        Map<UUID, Set<UUID>> before = predecessors(graph);
        Set<String> plannedPoms = new TreeSet<>();
        for (String path : writers.keySet()) {
            if (name(path).equals("pom.xml")) {
                plannedPoms.add(path);
            }
        }
        // Each aggregator's modules, in the order of the plan's build files.
        Map<String, List<String>> modulesOf = new LinkedHashMap<>();
        for (String module : writers.keySet()) {
            if (exists(repoRoot, module)) {
                continue;
            }
            String aggregator = aggregatorAbove(folder(module), plannedPoms, repoRoot);
            if (aggregator != null) {
                modulesOf.computeIfAbsent(aggregator, key -> new ArrayList<>()).add(module);
            }
        }
        for (Map.Entry<String, List<String>> entry : modulesOf.entrySet()) {
            String aggregator = entry.getKey();
            boolean onDisk = exists(repoRoot, aggregator);
            Task makesAggregator = null;
            if (!onDisk) {
                List<Task> first = firstWriters(writers.get(aggregator), before);
                if (first.size() != 1) {
                    if (first.size() > 1) {
                        violations.add(twoCreators(aggregator, first));
                    }
                    continue;
                }
                makesAggregator = first.get(0);
            }
            List<String> listedAtTheStart = new ArrayList<>();
            Map<String, List<Task>> addedLater = new LinkedHashMap<>();
            for (String module : entry.getValue()) {
                // Two tasks of one module may both hold its build file (BuildFilesInTheJob);
                // whichever of them comes first creates it.
                List<Task> first = firstWriters(writers.get(module), before);
                boolean existsByThen = makesAggregator != null && !first.isEmpty();
                for (Task makesModule : first) {
                    existsByThen = existsByThen && (makesModule == makesAggregator
                        || reaches(before, makesModule.id(), makesAggregator.id()));
                }
                if (existsByThen
                        || (makesAggregator != null && first.contains(makesAggregator))) {
                    listedAtTheStart.add(moduleName(aggregator, module));
                    continue;
                }
                addedLater.put(module, first);
            }
            for (Map.Entry<String, List<Task>> later : addedLater.entrySet()) {
                for (Task makesModule : later.getValue()) {
                    before = addedLater(graph, before, aggregator, later.getKey(),
                        makesAggregator, makesModule, corrections);
                }
            }
            if (makesAggregator != null && !addedLater.isEmpty()) {
                List<String> others = new ArrayList<>();
                for (Map.Entry<String, List<Task>> later : addedLater.entrySet()) {
                    List<String> titles = new ArrayList<>();
                    for (Task task : later.getValue()) {
                        titles.add("'" + task.title() + "'");
                    }
                    others.add(moduleName(aggregator, later.getKey()) + " (task "
                        + String.join(" or ", titles) + ")");
                }
                say(makesAggregator, "In " + aggregator + " list only the modules that exist "
                    + "when this task ends: " + (listedAtTheStart.isEmpty() ? "none yet - "
                    + "write it with an empty <modules> or none" : String.join(", ",
                    listedAtTheStart)) + ". Do NOT list " + String.join(", ", others)
                    + ": each is added by the task that creates it, and a module listed before "
                    + "its folder has a build file fails the build from the root.");
                corrections.add("task '" + makesAggregator.title() + "' lists in " + aggregator
                    + " only the modules that exist when it ends ("
                    + (listedAtTheStart.isEmpty() ? "none yet" : String.join(", ",
                        listedAtTheStart)) + "); " + String.join(", ", others)
                    + " are added by the tasks that create them");
            }
        }
        return new Outcome(corrections, violations);
    }

    /**
     * Puts a module that is created after its aggregator's task right: the module's task runs
     * after that task, holds the aggregator, and is told to add itself to it.
     *
     * @return the plan's order as it is now
     */
    private static Map<UUID, Set<UUID>> addedLater(TaskGraph graph, Map<UUID, Set<UUID>> before,
                                                   String aggregator, String module,
                                                   Task makesAggregator, Task makesModule,
                                                   List<String> corrections) {
        Map<UUID, Set<UUID>> order = before;
        String name = moduleName(aggregator, module);
        if (makesAggregator != null
                && !reaches(order, makesAggregator.id(), makesModule.id())) {
            addEdge(graph, makesAggregator, makesModule);
            order = predecessors(graph);
            corrections.add("task '" + makesModule.title() + "' now runs after '"
                + makesAggregator.title() + "': it creates the module " + name + ", which "
                + aggregator + " lists, and that file is written by '"
                + makesAggregator.title() + "'");
        }
        if (!holds(makesModule, aggregator)) {
            Set<String> widened = new LinkedHashSet<>(makesModule.writeSet());
            widened.add(aggregator);
            makesModule.setWriteSet(widened);
            corrections.add("task '" + makesModule.title() + "' may now write " + aggregator
                + ", to add the module " + name + " it creates to that file's <modules>");
        }
        say(makesModule, "Add the module `" + name + "` to the <modules> of " + aggregator
            + " when you create " + module + " (that file is in your write set). Change "
            + "nothing else in it.");
        return order;
    }

    private static String twoCreators(String path, List<Task> first) {
        List<String> titles = new ArrayList<>();
        for (Task task : first) {
            titles.add("'" + task.title() + "'");
        }
        return path + " is created by " + String.join(" and ", titles) + ", and neither waits "
            + "for the other, so which of them writes it first - and what the aggregating build "
            + "file above it can list - is not known. Give the file to one of them, or make one "
            + "depend on the other.";
    }

    /** Appends a line to a task's instructions once; the check runs on every draft. */
    private static void say(Task task, String line) {
        String current = task.instructions() == null ? "" : task.instructions();
        if (!current.contains(line)) {
            task.setInstructions(current.isBlank() ? line : current + "\n\n" + line);
        }
    }

    private static boolean holds(Task task, String path) {
        for (String raw : task.writeSet() == null ? Set.<String>of() : task.writeSet()) {
            if (path.equals(clean(raw))) {
                return true;
            }
        }
        return false;
    }

    private static void addEdge(TaskGraph graph, Task from, Task to) {
        List<TaskEdge> edges = new ArrayList<>(graph.dependencies() == null ? List.of()
            : graph.dependencies());
        edges.add(new TaskEdge(from.id(), to.id()));
        graph.setDependencies(edges);
    }

    /** The writers of a file that no other writer of it comes before. */
    private static List<Task> firstWriters(List<Task> writers, Map<UUID, Set<UUID>> before) {
        List<Task> first = new ArrayList<>();
        for (Task task : writers) {
            boolean preceded = false;
            for (Task other : writers) {
                if (other != task && reaches(before, other.id(), task.id())) {
                    preceded = true;
                    break;
                }
            }
            if (!preceded && !first.contains(task)) {
                first.add(task);
            }
        }
        return first;
    }

    /** For each task, the tasks that must end before it, directly. */
    private static Map<UUID, Set<UUID>> predecessors(TaskGraph graph) {
        Map<UUID, Set<UUID>> before = new HashMap<>();
        for (TaskEdge edge : graph.dependencies() == null ? List.<TaskEdge>of()
                : graph.dependencies()) {
            if (edge != null && edge.from() != null && edge.to() != null) {
                before.computeIfAbsent(edge.to(), key -> new HashSet<>()).add(edge.from());
            }
        }
        return before;
    }

    /** True when {@code first} ends before {@code later} starts, by the plan's order. */
    private static boolean reaches(Map<UUID, Set<UUID>> before, UUID first, UUID later) {
        if (first == null || later == null || first.equals(later)) {
            return false;
        }
        Set<UUID> seen = new HashSet<>();
        Deque<UUID> next = new ArrayDeque<>();
        next.add(later);
        while (!next.isEmpty()) {
            for (UUID earlier : before.getOrDefault(next.poll(), Set.of())) {
                if (earlier.equals(first)) {
                    return true;
                }
                if (seen.add(earlier)) {
                    next.add(earlier);
                }
            }
        }
        return false;
    }

    /** The nearest folder strictly above {@code folder} with a pom.xml, planned or on disk. */
    private static String aggregatorAbove(String folder, Set<String> plannedPoms, Path repoRoot) {
        String current = folder;
        while (!current.isEmpty()) {
            int slash = current.lastIndexOf('/');
            current = slash < 0 ? "" : current.substring(0, slash);
            String pom = current.isEmpty() ? "pom.xml" : current + "/pom.xml";
            if (plannedPoms.contains(pom) || exists(repoRoot, pom)) {
                return pom;
            }
        }
        return null;
    }

    /** The module's folder as its aggregator's {@code <module>} names it. */
    private static String moduleName(String aggregator, String module) {
        String base = folder(aggregator);
        String dir = folder(module);
        return base.isEmpty() ? dir : dir.substring(base.length() + 1);
    }

    private static String folder(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static boolean exists(Path repoRoot, String path) {
        try {
            return repoRoot != null && Files.isRegularFile(repoRoot.resolve(path));
        } catch (RuntimeException badPath) {
            return false;
        }
    }

    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String path = raw.strip().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path.startsWith("/") ? null : path;
    }
}
