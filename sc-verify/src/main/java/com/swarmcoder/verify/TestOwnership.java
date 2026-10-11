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
import com.swarmcoder.domain.TaskEdge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the task that owns an acceptance test will have, when it is built, the types that test
 * needs (section 83).
 *
 * <h2>The build this exists because of</h2>
 *
 * <p>Live supervised build, 2026-10-12. A plan of four tasks. The first, which waits for nothing,
 * wrote three types of a shared module and owned a check whose test imported three types of the
 * server module. The second task, which runs after the first, wrote those. The order of the
 * tasks was right; the owner of the test was wrong. Every candidate of the first task wrote code
 * that compiled, verification put the task's protected test over it, and the test could not
 * compile until the second task had run. Four candidates and four repair workers failed
 * "compiles=false".
 *
 * <p>The red check had passed the test: {@link TypeDeliverability} asks whether ANY task of the
 * plan can supply a missing type, which is the right question for "is this test broken" and the
 * wrong one for "can its owner pass it".
 *
 * <h2>The rule</h2>
 *
 * <p>Every type a task's test needs and the tree does not hold must be written by that task or
 * by a task it waits for, directly or through others. When it is not, the test and its checks
 * belong to the task that is last to write one of the missing types, provided that task has
 * every one of them when it starts ({@link Finding#target}). When no task has, nothing is moved
 * and the fact is returned ({@link Finding#whyNoMove}).
 *
 * <h2>How a task is known to write a type</h2>
 *
 * <ol>
 *   <li>It names the type: a contract it delivers, or a file of its write set or its computed
 *       reservation ({@link TypeDeliverability#typeNamed}). When any task names a type, only the
 *       tasks that name it count.</li>
 *   <li>Otherwise, and only for a name the compiler reported missing: a package directory of its
 *       write set holds it ({@link TypeDeliverability#writesInPackageOf}). The owner and the
 *       tasks it waits for are asked the wider {@link TypeDeliverability#mayCreate} first, so a
 *       write set that cannot be read never moves a test.</li>
 * </ol>
 *
 * <p>Pure functions over the plan, the test's text and what the caller says the tree holds.
 * Nothing here reads a tree, runs a build or calls a model.
 */
public final class TestOwnership {

    private TestOwnership() {
    }

    private static final Pattern IMPORT = Pattern.compile(
        "(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(\\.\\*)?\\s*;");

    /**
     * @param owner     the task that owns the test
     * @param needs     every type the test needs that the tree does not hold and a task of the
     *                  plan writes, with the tasks that write it in plan order
     * @param outside   the entries of {@code needs} that neither the owner nor a task it waits
     *                  for writes
     * @param target    the task the test and its checks belong to; null when no task has every
     *                  needed type when it starts
     * @param whyNoMove when {@code target} is null, why, as a sentence
     */
    public record Finding(Task owner, Map<String, List<Task>> needs,
                          Map<String, List<Task>> outside, Task target, String whyNoMove) {

        /** What is wrong, in one sentence that names the types and the tasks that write them. */
        public String fact() {
            StringBuilder sb = new StringBuilder("Task '").append(owner.title())
                .append("' owns an acceptance test it cannot compile: the test needs ");
            int said = 0;
            for (Map.Entry<String, List<Task>> entry : outside.entrySet()) {
                if (said == 3) {
                    sb.append(" and ").append(outside.size() - said).append(" more");
                    break;
                }
                sb.append(said == 0 ? "" : ", ").append('`').append(entry.getKey())
                    .append("`, which task '").append(entry.getValue().get(0).title())
                    .append("' writes");
                said++;
            }
            return sb.append(". '").append(owner.title()).append("' does not write ")
                .append(outside.size() == 1 ? "it" : "them")
                .append(" and does not wait for a task that does.").toString();
        }
    }

    /**
     * The types the tasks of a plan name, each with the tasks that name it, in the order given.
     */
    public static Map<String, List<Task>> namedTypes(List<Task> planOrder) {
        Map<String, List<Task>> named = new LinkedHashMap<>();
        for (Task task : planOrder == null ? List.<Task>of() : planOrder) {
            if (task == null) {
                continue;
            }
            Set<String> types = new LinkedHashSet<>();
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract != null && contract.namesAType()) {
                    types.add(contract.typeName().strip());
                }
            }
            List<String> entries = new ArrayList<>();
            if (task.writeSet() != null) {
                entries.addAll(task.writeSet());
            }
            entries.addAll(task.computedReservation());
            for (String entry : entries) {
                String type = TypeDeliverability.typeNamed(entry);
                if (type != null && type.indexOf('.') > 0) {
                    types.add(type);
                }
            }
            for (String type : types) {
                named.computeIfAbsent(type, key -> new ArrayList<>()).add(task);
            }
        }
        return named;
    }

    /**
     * The types of {@code named} a test's source imports: by its own name, as the outer type of
     * a nested one or of a static member, or through an on-demand import of its package when the
     * source also uses its simple name.
     */
    public static Set<String> imported(String testSource, Collection<String> named) {
        Set<String> found = new LinkedHashSet<>();
        if (testSource == null || named == null || named.isEmpty()) {
            return found;
        }
        Matcher m = IMPORT.matcher(testSource);
        while (m.find()) {
            String name = m.group(1);
            String longest = null;
            for (String type : named) {
                if ((name.equals(type) || name.startsWith(type + "."))
                        && (longest == null || type.length() > longest.length())) {
                    longest = type;
                }
            }
            if (longest != null) {
                found.add(longest);
            }
            if (m.group(2) == null) {
                continue;
            }
            for (String type : named) {
                int dot = type.lastIndexOf('.');
                if (dot > 0 && type.substring(0, dot).equals(name)
                        && Pattern.compile("(?<![\\w$.])" + Pattern.quote(type.substring(dot + 1))
                            + "(?![\\w$])").matcher(testSource).find()) {
                    found.add(type);
                }
            }
        }
        return found;
    }

    /** The task and every task it waits for, directly or through others. */
    public static Set<UUID> ownAndWaitedFor(Task task, List<TaskEdge> edges) {
        Set<UUID> reach = new LinkedHashSet<>();
        if (task == null) {
            return reach;
        }
        reach.add(task.id());
        Deque<UUID> open = new ArrayDeque<>(List.of(task.id()));
        while (!open.isEmpty()) {
            UUID at = open.pop();
            for (TaskEdge edge : edges == null ? List.<TaskEdge>of() : edges) {
                if (edge != null && at.equals(edge.to()) && edge.from() != null
                        && reach.add(edge.from())) {
                    open.push(edge.from());
                }
            }
        }
        return reach;
    }

    /**
     * The finding for one task's tests, or null when the task will have everything they need -
     * and whenever that cannot be settled.
     *
     * @param owner           the task whose tests these are
     * @param planOrder       every task of the plan, in the order the plan runs them
     * @param edges           the plan's dependencies; {@code to} waits for {@code from}
     * @param testSources     the text of each of the owner's test files
     * @param compilerMissing the names the compiler reported missing when the tests were
     *                        compiled, fully qualified types or bare packages; null or empty when
     *                        they were not compiled
     * @param inTheTree       whether the tree the owner's candidates start from declares a type;
     *                        null when that is not known, and every named type then counts as
     *                        absent
     * @param mayReceive      whether a task may still be given a test: false for one that has
     *                        been built; null when every task may
     */
    public static Finding find(Task owner, List<Task> planOrder, List<TaskEdge> edges,
                               Collection<String> testSources, Collection<String> compilerMissing,
                               Predicate<String> inTheTree, Predicate<Task> mayReceive) {
        if (owner == null || planOrder == null || planOrder.size() < 2) {
            return null;
        }
        Map<String, List<Task>> named = namedTypes(planOrder);
        Set<UUID> own = ownAndWaitedFor(owner, edges);
        Map<String, List<Task>> needs = new LinkedHashMap<>();
        for (String source : testSources == null ? List.<String>of() : testSources) {
            for (String type : imported(source, named.keySet())) {
                if (inTheTree == null || !inTheTree.test(type)) {
                    needs.put(type, named.get(type));
                }
            }
        }
        for (String missing : compilerMissing == null ? List.<String>of() : compilerMissing) {
            if (missing == null || missing.isBlank()) {
                continue;
            }
            String name = missing.strip();
            if (named.containsKey(name)) {
                needs.put(name, named.get(name));
                continue;
            }
            if (needs.keySet().stream().anyMatch(type -> type.startsWith(name + "."))) {
                continue; // a package, and the types needed from it are already counted
            }
            boolean ownMayWrite = false;
            List<Task> others = new ArrayList<>();
            for (Task task : planOrder) {
                if (task == null) {
                    continue;
                }
                if (own.contains(task.id())) {
                    ownMayWrite = ownMayWrite || TypeDeliverability.mayCreate(task, name)
                        || namesATypeIn(named, task, name);
                } else if (TypeDeliverability.writesInPackageOf(task, name)
                        || namesATypeIn(named, task, name)) {
                    others.add(task);
                }
            }
            if (!ownMayWrite && !others.isEmpty()) {
                needs.put(name, others);
            }
        }
        Map<String, List<Task>> outside = new LinkedHashMap<>();
        for (Map.Entry<String, List<Task>> need : needs.entrySet()) {
            if (need.getValue().stream().noneMatch(task -> own.contains(task.id()))) {
                outside.put(need.getKey(), need.getValue());
            }
        }
        if (outside.isEmpty()) {
            return null;
        }
        // The task that is last to write one of the missing types: for each type the first
        // task that writes it, and of those the one the plan runs last.
        Task target = null;
        for (List<Task> writers : outside.values()) {
            Task first = writers.get(0);
            if (target == null || planOrder.indexOf(first) > planOrder.indexOf(target)) {
                target = first;
            }
        }
        if (mayReceive != null && !mayReceive.test(target)) {
            return new Finding(owner, needs, outside, null, "Task '" + target.title()
                + "', the last to write one of them, has already been built, so the test "
                + "cannot be given to it.");
        }
        Set<UUID> has = ownAndWaitedFor(target, edges);
        for (Map.Entry<String, List<Task>> need : needs.entrySet()) {
            if (need.getValue().stream().noneMatch(task -> has.contains(task.id()))) {
                return new Finding(owner, needs, outside, null, "No task of the plan has all of "
                    + "them when it starts: '" + target.title() + "', the last to write one, "
                    + "does not write `" + need.getKey() + "` and does not wait for '"
                    + need.getValue().get(0).title() + "', which does.");
            }
        }
        return new Finding(owner, needs, outside, target, null);
    }

    /** Whether {@code task} names a type inside the package {@code name}. */
    private static boolean namesATypeIn(Map<String, List<Task>> named, Task task, String name) {
        for (Map.Entry<String, List<Task>> entry : named.entrySet()) {
            int dot = entry.getKey().lastIndexOf('.');
            if (dot > 0 && entry.getKey().substring(0, dot).equals(name)
                    && entry.getValue().contains(task)) {
                return true;
            }
        }
        return false;
    }
}
