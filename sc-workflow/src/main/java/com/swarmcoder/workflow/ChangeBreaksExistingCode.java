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
import com.swarmcoder.knowledge.ContractDelivery;
import com.swarmcoder.knowledge.ContractMember;
import com.swarmcoder.knowledge.JavaSourceFacts;
import com.swarmcoder.knowledge.ProjectTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A task whose change to an EXISTING type stops other existing files compiling must be allowed to
 * write those files.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 74, 2026-10-03. The plan put "add two methods to an existing service interface" in
 * wave 1 and "implement them in the existing implementing class" in wave 2. A new abstract member
 * on an interface breaks every class that already implements it, and verification compiles the
 * whole build, so no candidate confined to the interface's file could ever pass: 150 minutes of
 * first candidates and two repair rounds went into a task the plan had made impossible.
 *
 * <h2>What is checked</h2>
 *
 * <p>For each task and each contract it delivers on a type the start tree already declares:
 *
 * <ul>
 *   <li>an <b>interface</b> gaining a method the contract does not write as {@code default},
 *       {@code static} or {@code private}, or an <b>abstract class</b> gaining a method the
 *       contract writes as {@code abstract}: every existing concrete class under it (through
 *       sub-interfaces and abstract classes too) that does not already declare a method of that
 *       name stops compiling;</li>
 *   <li>a <b>record</b> gaining a component: its constructor gains a parameter, so every existing
 *       file that writes {@code new TheRecord(} stops compiling.</li>
 * </ul>
 *
 * <p>Each such file must be covered by the task's write set. Where it is not, the objection names
 * the files and the way out: widen this task's write set, or merge it with the task that owns the
 * file.
 *
 * <p>Not checked, because a contract does not say it: a member that is removed or whose signature
 * is changed, and a constructor parameter added to an ordinary class. Read from source text with
 * the project's own type index, no compiler; a tree or type that cannot be read establishes
 * nothing and raises no objection. Project-agnostic: every name comes from the checkout.
 */
final class ChangeBreaksExistingCode {

    /** How many broken files one objection names in full. */
    private static final int MAX_FILES_NAMED = 12;

    private static final Pattern NON_ABSTRACT_IN_INTERFACE =
        Pattern.compile("(^|\\s)(default|static|private)\\s");
    private static final Pattern ABSTRACT = Pattern.compile("(^|\\s)abstract\\s");

    private ChangeBreaksExistingCode() {
    }

    /**
     * One objection per task and changed type, or none.
     *
     * @param repoRoot the start tree; null (a greenfield run) checks nothing
     */
    static List<String> objections(TaskGraph graph, Path repoRoot) {
        if (graph == null || graph.tasks() == null || repoRoot == null
                || !Files.isDirectory(repoRoot)) {
            return List.of();
        }
        boolean anyContract = graph.tasks().stream()
            .anyMatch(t -> t != null && !t.deliveredContracts().isEmpty());
        if (!anyContract) {
            return List.of();
        }
        ProjectTypes types;
        try {
            types = ProjectTypes.of(repoRoot);
        } catch (RuntimeException e) {
            return List.of();
        }
        Map<String, List<String>> subtypes = directSubtypes(types);
        List<String> objections = new ArrayList<>();
        for (Task task : graph.tasks()) {
            if (task == null || task.writeSet() == null || task.writeSet().isEmpty()) {
                continue; // an empty write set is unrestricted
            }
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract == null || !contract.namesAType()) {
                    continue;
                }
                String fullName = contract.typeName().strip();
                if (!types.declares(fullName) || !types.bodyWasRead(fullName)) {
                    continue;
                }
                List<String> added = addedMembers(types, contract);
                if (added.isEmpty()) {
                    continue;
                }
                String kind = types.kindOf(fullName);
                Map<String, String> broken = new LinkedHashMap<>(); // path -> why
                List<String> breaking = new ArrayList<>();
                if ("record".equals(kind)) {
                    List<String> components = added.stream()
                        .filter(m -> ContractMember.parse(m, simple(fullName)).map(p -> !p.method()).orElse(false))
                        .toList();
                    if (!components.isEmpty()) {
                        breaking.addAll(components);
                        for (Path caller : constructorCallers(types, fullName)) {
                            broken.put(relative(repoRoot, caller), "constructs it");
                        }
                    }
                } else {
                    boolean isInterface = "interface".equals(kind);
                    boolean isAbstractClass = "class".equals(kind)
                        && declaredAbstract(types.fileOf(fullName), simple(fullName));
                    if (!isInterface && !isAbstractClass) {
                        continue;
                    }
                    List<ContractMember> abstractMethods = new ArrayList<>();
                    for (String member : added) {
                        Optional<ContractMember> parsed = ContractMember.parse(member);
                        if (parsed.isEmpty() || !parsed.get().method()
                                || parsed.get().name().equals(simple(fullName))) {
                            continue;
                        }
                        boolean isAbstract = isInterface
                            ? !NON_ABSTRACT_IN_INTERFACE.matcher(member).find()
                            : ABSTRACT.matcher(member).find();
                        if (isAbstract) {
                            abstractMethods.add(parsed.get());
                            breaking.add(member.strip());
                        }
                    }
                    if (abstractMethods.isEmpty()) {
                        continue;
                    }
                    for (String implementor : concreteImplementors(types, subtypes, fullName)) {
                        Set<String> has = types.membersOf(implementor).stream()
                            .filter(JavaSourceFacts.Declared::method)
                            .map(JavaSourceFacts.Declared::name).collect(Collectors.toSet());
                        boolean lacksOne = abstractMethods.stream()
                            .anyMatch(m -> !has.contains(m.name()));
                        Path file = types.fileOf(implementor);
                        if (lacksOne && file != null) {
                            broken.put(relative(repoRoot, file),
                                (isInterface ? "implements it" : "extends it"));
                        }
                    }
                }
                List<String> outside = broken.keySet().stream()
                    .filter(path -> !covered(task.writeSet(), path)).toList();
                if (outside.isEmpty()) {
                    continue;
                }
                objections.add(message(graph, task, fullName, kind, breaking, outside, broken));
            }
        }
        return objections;
    }

    private static String message(TaskGraph graph, Task task, String fullName, String kind,
                                  List<String> breaking, List<String> outside,
                                  Map<String, String> broken) {
        boolean record = "record".equals(kind);
        StringBuilder sb = new StringBuilder("task '").append(task.title()).append("' adds ")
            .append(String.join(", ", breaking)).append(" to the existing ").append(kind)
            .append(' ').append(fullName).append(", and that stops ")
            .append(outside.size() == 1 ? "an existing file" : outside.size() + " existing files")
            .append(" compiling that the task may not write: ");
        sb.append(outside.stream().limit(MAX_FILES_NAMED)
            .map(path -> path + " (" + broken.get(path) + ")")
            .collect(Collectors.joining(", ")));
        if (outside.size() > MAX_FILES_NAMED) {
            sb.append(", and ").append(outside.size() - MAX_FILES_NAMED).append(" more");
        }
        sb.append(". ").append(record
            ? "A new record component is a new constructor parameter, so every file that "
                + "constructs the record must change with it"
            : "A new abstract method breaks every class that already implements the type until "
                + "that class has the method");
        sb.append(", and a candidate is verified by compiling the whole build, so this task can "
            + "never pass as planned. ");
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (String path : outside) {
            for (Task other : graph.tasks()) {
                if (other != null && other != task && other.writeSet() != null
                        && covered(other.writeSet(), path)) {
                    owners.computeIfAbsent(other.title(), k -> new ArrayList<>()).add(path);
                }
            }
        }
        if (owners.isEmpty()) {
            sb.append("Add ").append(outside.size() == 1 ? "that file" : "those files")
                .append(" to this task's writeSet and say in its instructions that it changes ")
                .append(outside.size() == 1 ? "it" : "them").append(" too.");
        } else {
            sb.append("Merge this task with ").append(owners.keySet().stream()
                    .map(t -> "'" + t + "'").collect(Collectors.joining(" and ")))
                .append(" into one task that writes the type and ")
                .append(outside.size() == 1 ? "that file" : "those files")
                .append(" together; or keep them apart, add ")
                .append(outside.size() == 1 ? "the file" : "the files")
                .append(" to this task's writeSet as well, and keep the edge that makes the "
                    + "other task run after this one.");
        }
        return sb.toString();
    }

    /** The members the contract names that the existing type does not have. */
    private static List<String> addedMembers(ProjectTypes types, ApiContract contract) {
        List<ContractDelivery.Shortfall> shortfalls;
        try {
            shortfalls = ContractDelivery.shortfalls(types, List.of(contract));
        } catch (RuntimeException e) {
            return List.of();
        }
        List<String> added = new ArrayList<>();
        for (ContractDelivery.Shortfall shortfall : shortfalls) {
            if (!shortfall.missingType()) {
                added.addAll(shortfall.missingMembers());
            }
        }
        return added;
    }

    /** Full name of a type the tree declares → the types that name it in their header. */
    private static Map<String, List<String>> directSubtypes(ProjectTypes types) {
        Map<String, List<String>> subtypes = new LinkedHashMap<>();
        for (String fullName : types.fullNames().stream().sorted().toList()) {
            for (ProjectTypes.Supertype supertype : types.supertypesOf(fullName)) {
                if (supertype.ours() && supertype.fullName() != null) {
                    subtypes.computeIfAbsent(supertype.fullName(), k -> new ArrayList<>())
                        .add(fullName);
                }
            }
        }
        return subtypes;
    }

    /** Every concrete class under {@code root}, through sub-interfaces and abstract classes. */
    private static List<String> concreteImplementors(ProjectTypes types,
                                                     Map<String, List<String>> subtypes,
                                                     String root) {
        List<String> concrete = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(subtypes.getOrDefault(root, List.of()));
        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            String kind = types.kindOf(type);
            boolean passesItOn = "interface".equals(kind)
                || ("class".equals(kind) && declaredAbstract(types.fileOf(type), simple(type)));
            if (passesItOn) {
                queue.addAll(subtypes.getOrDefault(type, List.of()));
            } else if ("class".equals(kind) || "enum".equals(kind) || "record".equals(kind)) {
                concrete.add(type);
            }
        }
        return concrete;
    }

    /** The files, other than the record's own, that write {@code new Simple(}. */
    private static Set<Path> constructorCallers(ProjectTypes types, String fullName) {
        Pattern construction = Pattern.compile(
            "\\bnew\\s+(?:[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*)*" + Pattern.quote(simple(fullName))
                + "\\s*(?:<[^>(]*>)?\\s*\\(");
        Path own = types.fileOf(fullName);
        Set<Path> callers = new LinkedHashSet<>();
        Set<Path> read = new HashSet<>();
        for (String other : types.fullNames().stream().sorted().toList()) {
            Path file = types.fileOf(other);
            if (file == null || file.equals(own) || !read.add(file)) {
                continue;
            }
            if (construction.matcher(read(file)).find()) {
                callers.add(file);
            }
        }
        return callers;
    }

    private static boolean declaredAbstract(Path file, String simpleName) {
        if (file == null) {
            return false;
        }
        return Pattern.compile("\\babstract\\s+(?:[a-z-]+\\s+)*class\\s+" + Pattern.quote(simpleName)
            + "\\b").matcher(read(file)).find();
    }

    /** Whether a write set covers a path: the path itself, or a directory above it. */
    static boolean covered(Set<String> writeSet, String path) {
        String wanted = normalize(path);
        for (String entry : writeSet) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String e = normalize(entry);
            if (wanted.equals(e) || wanted.startsWith(e + "/")) {
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

    private static String relative(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
            .toString().replace('\\', '/');
    }

    private static String simple(String fullName) {
        return fullName.substring(fullName.lastIndexOf('.') + 1);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
