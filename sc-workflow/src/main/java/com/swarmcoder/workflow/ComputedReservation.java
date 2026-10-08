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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.knowledge.ProjectTypes;
import com.swarmcoder.verify.BuildLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The files a task starts with, derived with no model from what the task claims (owner's
 * decision, 2026-10-08; section 73).
 *
 * <h2>Why</h2>
 *
 * <p>Write sets were guessed by the planner model, which was told to include "every implementing
 * class and every caller" a change would break, and a guess one file short failed every
 * candidate (run 74; run 90 lost a whole build). What a task builds or changes is already said,
 * exactly, by the contracts it delivers, and where those types are is in the tree.
 *
 * <h2>What is computed</h2>
 *
 * <p>For each contract a task delivers that names a type:
 *
 * <ul>
 *   <li>a type the start tree declares: the file the tree has it in;</li>
 *   <li>a new type: {@code <source root>/<package as folders>/<Type>.java}. The source root is,
 *       in this order, the one a path of the planner's own write set for this task lies in; the
 *       one that already holds the contract's package or the nearest package above it; the only
 *       Java source root the build has. When none of these gives exactly one, nothing is
 *       computed for that contract and the log says so - the planner's paths stand;</li>
 *   <li>every existing file that stops compiling when the contract adds an abstract method to
 *       an existing interface or abstract class, or a component to an existing record
 *       ({@link ChangeBreaksExistingCode#brokenBy}).</li>
 * </ul>
 *
 * <p>A module's build file is added afterwards by {@link BuildFilesInTheJob#expandWriteSets},
 * as it already was.
 *
 * <h2>The planner's paths</h2>
 *
 * <p>Kept: the planner may add what a contract cannot say (a resource, a class no contract
 * names, a directory). Two things it wrote are corrected, each said on the log:
 *
 * <ul>
 *   <li>a file path with the name of an existing contract type, somewhere the tree does not
 *       have it and where no file is: removed. The tree says where the type is;</li>
 *   <li>a file path with the name of a new contract type whose folders are not the contract's
 *       package: replaced by the path the package gives, under the same source root. The
 *       contract is what the acceptance tests are written against.</li>
 * </ul>
 *
 * <p>Nothing is taken from another task here. Where the computation gives one file to two tasks
 * that nothing orders, the plan's own disjointness check says so to the planner, as it does for
 * any two tasks that name one file; two tasks one of which waits for the other may share it.
 *
 * <p>Not computed, because a contract does not say it: the callers of a member that is removed
 * or whose parameters change, and a constructor parameter added to an ordinary class. A worker
 * that meets one takes the file by rule when no other task holds it ({@code ReservationBook}).
 *
 * <p>Read from source text with the project's own type index, no compiler and no build.
 * Project-agnostic: every name comes from the plan, the design and the checkout.
 */
final class ComputedReservation {

    private ComputedReservation() {
    }

    /**
     * Computes every task's reservation and puts it in the task's write set, in place.
     *
     * @param repoRoot the start tree; null or absent computes new types only
     * @return what was done, one line a finding, for the log
     */
    static List<String> apply(TaskGraph graph, BuildLayout.Layout layout, Path repoRoot) {
        List<String> lines = new ArrayList<>();
        if (graph == null || graph.tasks() == null) {
            return lines;
        }
        boolean anyContract = graph.tasks().stream()
            .anyMatch(t -> t != null && !t.deliveredContracts().isEmpty());
        if (!anyContract) {
            return lines;
        }
        ProjectTypes types = null;
        if (repoRoot != null && Files.isDirectory(repoRoot)) {
            try {
                types = ProjectTypes.of(repoRoot);
            } catch (RuntimeException unreadable) {
                lines.add("the start tree's types could not be read (" + unreadable + "); only "
                    + "the files of new types are computed");
            }
        }
        Map<String, List<String>> subtypes = types == null ? Map.of()
            : ChangeBreaksExistingCode.subtypesIn(types);
        List<String> javaRoots = javaSourceRoots(layout);
        for (Task task : graph.tasks()) {
            if (task == null || task.deliveredContracts().isEmpty()) {
                continue;
            }
            Set<String> writeSet = new LinkedHashSet<>();
            for (String entry : task.writeSet() == null ? Set.<String>of() : task.writeSet()) {
                if (entry != null && !entry.isBlank()) {
                    writeSet.add(normalize(entry));
                }
            }
            boolean planned = !writeSet.isEmpty();
            Set<String> computed = new LinkedHashSet<>();
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract == null || !contract.namesAType()) {
                    continue;
                }
                String fullName = contract.typeName().strip();
                String fileName = contract.simpleTypeName() + ".java";
                Path existing = types != null && types.declares(fullName)
                    ? types.fileOf(fullName) : null;
                if (existing != null) {
                    String path = ChangeBreaksExistingCode.relative(repoRoot, existing);
                    computed.add(path);
                    for (String entry : List.copyOf(writeSet)) {
                        if (!entry.equals(path) && named(entry, fileName)
                                && !Files.exists(repoRoot.resolve(entry))) {
                            writeSet.remove(entry);
                            lines.add("'" + task.title() + "': " + entry + " was planned for "
                                + fullName + ", which the project already has in " + path
                                + "; the planned path is dropped");
                        }
                    }
                    ChangeBreaksExistingCode.Broken broken =
                        ChangeBreaksExistingCode.brokenBy(types, subtypes, repoRoot, contract);
                    if (broken != null) {
                        computed.addAll(broken.files().keySet());
                        lines.add("'" + task.title() + "': adding "
                            + String.join(", ", broken.breaking()) + " to " + fullName
                            + " stops " + broken.files().size() + " existing file(s) compiling, "
                            + "so they are reserved for this task too: "
                            + broken.files().keySet());
                    }
                    continue;
                }
                String packagePath = contract.packageName().replace('.', '/');
                String relative = (packagePath.isEmpty() ? "" : packagePath + "/") + fileName;
                String plannedFile = null;
                for (String entry : writeSet) {
                    if (named(entry, fileName)) {
                        plannedFile = entry;
                    }
                }
                if (plannedFile != null && (plannedFile.equals(relative)
                        || plannedFile.endsWith("/" + relative))) {
                    computed.add(plannedFile); // the planner said exactly where; it stands
                    continue;
                }
                String root = sourceRootFor(writeSet, javaRoots, types, repoRoot,
                    contract.packageName());
                if (root == null) {
                    lines.add("'" + task.title() + "': no file was computed for the new type "
                        + fullName + " - which module it belongs in cannot be told from the "
                        + "task's paths, the packages the project has, or the build");
                    continue;
                }
                String path = root.isEmpty() ? relative : root + "/" + relative;
                if (plannedFile != null) {
                    writeSet.remove(plannedFile);
                    lines.add("'" + task.title() + "': " + plannedFile + " was planned for "
                        + fullName + ", whose package puts it in " + path + "; the planned "
                        + "path is replaced");
                }
                computed.add(path);
            }
            List<String> added = new ArrayList<>();
            for (String path : computed) {
                if (!covers(writeSet, path)) {
                    writeSet.add(path);
                    added.add(path);
                } else if (writeSet.contains(path)) {
                    added.add(path);
                }
            }
            if (computed.isEmpty() && !planned) {
                continue; // nothing known: an empty write set stays unrestricted, as before
            }
            task.setWriteSet(writeSet);
            task.setComputedReservation(added.isEmpty() ? null : added);
        }
        return lines;
    }

    /**
     * The source root a new type of {@code packageName} belongs in, or null when it cannot be
     * told: exactly one answer is needed at each step, and the first step that gives one wins.
     */
    private static String sourceRootFor(Set<String> writeSet, List<String> javaRoots,
                                        ProjectTypes types, Path repoRoot, String packageName) {
        Set<String> byPlan = new LinkedHashSet<>();
        for (String entry : writeSet) {
            for (String root : javaRoots) {
                if (entry.equals(root) || entry.startsWith(root + "/")
                        || root.startsWith(entry + "/")) {
                    byPlan.add(root);
                }
            }
        }
        if (byPlan.size() == 1) {
            return byPlan.iterator().next();
        }
        if (types != null && repoRoot != null) {
            String wanted = packageName;
            while (wanted != null && !wanted.isEmpty()) {
                Set<String> byPackage = new LinkedHashSet<>();
                for (String fullName : types.fullNames()) {
                    int dot = fullName.lastIndexOf('.');
                    if (dot < 0 || !fullName.substring(0, dot).equals(wanted)) {
                        continue;
                    }
                    Path file = types.fileOf(fullName);
                    if (file == null) {
                        continue;
                    }
                    String path = ChangeBreaksExistingCode.relative(repoRoot, file);
                    for (String root : javaRoots) {
                        if (path.startsWith(root + "/")
                                && (byPlan.isEmpty() || byPlan.contains(root))) {
                            byPackage.add(root);
                        }
                    }
                }
                if (byPackage.size() == 1) {
                    return byPackage.iterator().next();
                }
                if (byPackage.size() > 1) {
                    return null; // the package lives in several modules: not ours to pick
                }
                int dot = wanted.lastIndexOf('.');
                wanted = dot < 0 ? "" : wanted.substring(0, dot);
            }
        }
        return javaRoots.size() == 1 ? javaRoots.get(0) : null;
    }

    /**
     * The build's source roots that hold production Java: the last folder is {@code java} and
     * no folder is {@code test}. Folder names of the build's own layout, not of any project.
     */
    static List<String> javaSourceRoots(BuildLayout.Layout layout) {
        List<String> roots = new ArrayList<>();
        if (layout == null || !layout.determined()) {
            return roots;
        }
        for (String raw : layout.sourceRoots()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String root = normalize(raw);
            List<String> segments = List.of(root.split("/"));
            if ("java".equals(segments.get(segments.size() - 1)) && !segments.contains("test")
                    && !roots.contains(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    private static boolean named(String entry, String fileName) {
        return entry.equals(fileName) || entry.endsWith("/" + fileName);
    }

    private static boolean covers(Set<String> writeSet, String path) {
        for (String entry : writeSet) {
            if (path.equals(entry) || path.startsWith(entry + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String path) {
        String p = path.strip().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
}
