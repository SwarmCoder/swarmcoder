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
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.knowledge.ContractMember;
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.knowledge.ProjectTypes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * DESIGN_REVIEW: every library type a contract names must exist. A contract whose member is
 * declared with a type nobody has — not this design, not the project, not the JDK, not the
 * library it claims to come from — is a promise no worker can keep, and it goes back to the
 * architect with the type named and, where the reference source has one, the real type nearest
 * to it.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness runs 44/45, 2026-09-27 (DeepSeek V4 Flash, the Bookshelf demo on ZeroZ Stack, story
 * "Add, edit, and remove books"). The contract for "Create BookListPage UI component in client
 * module" promised {@code public com.zeroz4j.ui.ListView<Book> bookList}, beside {@code
 * com.zeroz4j.ui.TextField} and {@code com.zeroz4j.ui.Button} fields. ZeroZ Stack has no {@code
 * ListView} at all — its dynamic-list component is {@code com.zeroz4j.ui.component.KeyedList} —
 * and its {@code TextField} and {@code Button} are in {@code com.zeroz4j.ui.component}. The help
 * desk told the worker exactly that when asked. It did not help: the task was told to deliver the
 * type "EXACTLY AS WRITTEN", {@link com.swarmcoder.knowledge.ContractDelivery} compares a member's
 * declared type, and a member of a type that does not exist cannot be delivered by anybody. The
 * page task burned its turns (one worker spent 290 seconds scanning jar files for {@code
 * ListView}), and because the only checked task had been made to depend on it, the story's proof
 * was blocked behind it.
 *
 * <h2>Why at DESIGN_REVIEW, and not at PLAN</h2>
 *
 * <p>The member text belongs to the design. The planner names contracts by name and {@code
 * ArchitectClient.contractsNamed} hands each task the design's contract exactly as the design
 * wrote it, so no planner attempt could correct {@code ListView}: objecting at PLAN would burn
 * three attempts on a defect none of them can reach — the same trap harness run 19 fell into with a
 * design that broke a rule, and the reason rule conflicts are caught here and not there. PLAN also
 * adds nothing this check needs: a type the plan will create in the project's own packages is
 * never judged (see below), so the plan's write sets could only ever clear names this check has
 * already left alone.
 *
 * <h2>What is judged, and what is left alone</h2>
 *
 * <p>Only fully-qualified names in a contract's members — a field's type, a method's return and
 * parameter types, generic arguments, annotations. A simple name ({@code Book}, {@code
 * List<Book>}) carries no package, so which type it means depends on imports the contract does not
 * have; judging it would be a guess. Each qualified name is then taken in order:
 *
 * <ol>
 *   <li><b>planned</b> — the type of some contract in this design: the plan will create it;</li>
 *   <li><b>in the project</b> — the checkout already declares it;</li>
 *   <li><b>the JDK</b> — a package of the JDK is answered by the JDK, and a name it does not have
 *       is an objection like any other;</li>
 *   <li><b>the project's own namespace</b> — a package in the part of the namespace the checkout
 *       already writes code into (same rule as {@link LibraryTypes#sameNamespace}) is left
 *       alone: the plan may yet create the type through a write set, and nothing here can know;</li>
 *   <li><b>a library with its source checked out</b> — a package a reference checkout covers
 *       ({@link LibraryTypes#covers}) is judged against it: a type it does not declare is an
 *       objection;</li>
 *   <li>anything else — a library with no source checked out, say {@code org.teavm.jso} — is
 *       left alone. There is no dependency-jar index in this codebase, and "we have no source
 *       for it" must never become "it does not exist".</li>
 * </ol>
 *
 * <p>The objection is fed back through the same revision loop as a rule conflict, with the same
 * cap ({@code GreenfieldWorkflow.MAX_DESIGN_RULE_REVISIONS}) and the same park when it is spent: a
 * contract no worker can deliver cannot be planned around either.
 */
final class ContractsNameRealTypes {

    /** How many real types an objection offers in place of one that does not exist. */
    static final int MAX_SUGGESTIONS = 3;

    /**
     * A qualified type name: lower-case package segments, then one or more capitalised type
     * segments. Not preceded by a word character or a dot, so it starts at the package's first
     * segment.
     */
    private static final Pattern QUALIFIED = Pattern.compile(
        "(?<![\\w.$])((?:[a-z_][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$]*(?:\\.[A-Z][A-Za-z0-9_$]*)*)");

    /**
     * One type a contract names that exists nowhere.
     *
     * @param contract    the contract that names it
     * @param typeName    the qualified name as written
     * @param member      the member it was written in
     * @param source      where the name was looked for and not found — a reference checkout's
     *                    label, or "the JDK"
     * @param suggestions the real types with the nearest names; may be empty
     */
    record Unknown(ApiContract contract, String typeName, String member, String source,
                   List<LibraryTypes.Suggestion> suggestions) {

        /** The objection the architect reads, ending with what to do about it. */
        String objection() {
            String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
            String pkg = LibraryTypes.packageOf(typeName);
            StringBuilder sb = new StringBuilder("the contract ")
                .append(contract.typeName().strip()).append(" names ").append(typeName)
                .append(" (in `").append(member.strip()).append("`), and that type does not ")
                .append("exist: nothing in this design creates it, this project does not have ")
                .append("it, and ");
            boolean exact = !suggestions.isEmpty()
                && suggestions.get(0).fullName().endsWith("." + simple);
            if ("the JDK".equals(source)) {
                sb.append("the JDK has no ").append(simple).append(" in ").append(pkg).append('.');
            } else {
                sb.append("the ").append(source).append(" reference source, where ")
                    .append(pkg).append(" comes from, has no ").append(simple).append(" in ")
                    .append(pkg).append('.');
            }
            sb.append(" No worker can deliver a member whose type does not exist.");
            if (exact) {
                sb.append(" Its ").append(simple).append(" is ")
                    .append(suggestions.get(0).fullName())
                    .append(" — write the member with that name.");
                return sb.toString();
            }
            if (!suggestions.isEmpty()) {
                sb.append(" The real types with the nearest names are ")
                    .append(suggestions.stream().map(s -> s.fullName()
                            + (s.summary().isBlank() ? "" : " (" + s.summary() + ")"))
                        .collect(Collectors.joining("; ")))
                    .append('.');
            }
            sb.append(" Change the member to a type that exists, or drop the member if the ")
                .append("design does not need it.");
            return sb.toString();
        }
    }

    private ContractsNameRealTypes() {}

    /**
     * Every library type {@code design}'s contracts name that exists nowhere, one entry per
     * contract and type.
     *
     * @param checkout the project as it stands on disk; {@link ProjectTypes#of} of null is empty
     * @param library  the reference checkouts; {@link LibraryTypes#NONE} judges only the JDK
     */
    static List<Unknown> unknown(DesignDocument design, ProjectTypes checkout,
                                 LibraryTypes library) {
        List<Unknown> found = new ArrayList<>();
        if (design == null || design.contracts() == null) {
            return found;
        }
        LibraryTypes lib = library == null ? LibraryTypes.NONE : library;
        Set<String> planned = new HashSet<>();
        for (ApiContract contract : design.contracts()) {
            if (contract != null && contract.namesAType()) {
                planned.add(contract.typeName().strip());
            }
        }
        Set<String> projectPackages = checkout == null ? Set.of() : checkout.packages();
        for (ApiContract contract : design.contracts()) {
            if (contract == null || !contract.namesAType()) {
                continue;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String member : contract.members()) {
                if (member == null || member.isBlank()) {
                    continue;
                }
                // only the declaration is searched: a remark after it is for the reader
                Matcher m = QUALIFIED.matcher(ContractMember.declarationPart(member));
                while (m.find()) {
                    String name = m.group(1).replace('$', '.');
                    if (!seen.add(name)) {
                        continue;
                    }
                    String source = missingFrom(name, planned, checkout, projectPackages, lib);
                    if (source != null && isAMemberOfARealType(name, planned, checkout,
                            projectPackages, lib)) {
                        continue; // java.time.Duration.ZERO: a constant of a type that exists
                    }
                    if (source != null) {
                        List<LibraryTypes.Suggestion> suggestions = "the JDK".equals(source)
                            ? List.of() : lib.nearest(name, MAX_SUGGESTIONS);
                        found.add(new Unknown(contract, name, member, source, suggestions));
                    }
                }
            }
        }
        return found;
    }

    /** The objections for {@link #unknown}, in the same order. */
    static List<String> objections(DesignDocument design, ProjectTypes checkout,
                                   LibraryTypes library) {
        return unknown(design, checkout, library).stream().map(Unknown::objection).toList();
    }

    /**
     * Where {@code name} should have been and is not — a reference checkout's label or "the
     * JDK" — or null when it exists or is not this check's to judge.
     */
    private static String missingFrom(String name, Set<String> planned, ProjectTypes checkout,
                                      Set<String> projectPackages, LibraryTypes library) {
        if (isPlanned(name, planned)) {
            return null;
        }
        if (checkout != null && (checkout.declares(name) || checkout.declares(flattened(name)))) {
            return null;
        }
        String pkg = LibraryTypes.packageOf(name);
        if (pkg.isEmpty() || pkg.equals(name)) {
            return null;
        }
        if (LibraryTypes.isJdkPackage(pkg)) {
            return LibraryTypes.jdkDeclares(name) ? null : "the JDK";
        }
        for (String own : projectPackages) {
            if (LibraryTypes.sameNamespace(pkg, own)) {
                return null; // the project's own namespace: the plan may still create it
            }
        }
        String root = library.coveringRoot(pkg);
        if (root == null) {
            return null; // no source for this library anywhere: not ours to judge
        }
        return library.declares(name) ? null : root;
    }

    /** A planned type, or a type nested in one ({@code com.x.Book.Kind}). */
    /**
     * Whether a dotted name that is no type is a static member of one that exists:
     * {@code java.time.Duration.ZERO}, {@code java.lang.annotation.RetentionPolicy.RUNTIME}. The
     * pattern cannot tell a nested type from a constant, so before the name is called a type that
     * does not exist, its outer type is looked at. Where the outer type exists and its members
     * cannot be listed (a library type), a name written like a constant is taken to be one.
     */
    private static boolean isAMemberOfARealType(String name, Set<String> planned,
                                                ProjectTypes checkout, Set<String> projectPackages,
                                                LibraryTypes library) {
        String pkg = LibraryTypes.packageOf(name);
        String[] types = name.substring(pkg.isEmpty() ? 0 : pkg.length() + 1).split("\\.");
        if (pkg.isEmpty() || types.length < 2) {
            return false;
        }
        String last = types[types.length - 1];
        String outer = name.substring(0, name.length() - last.length() - 1);
        if (missingFrom(outer, planned, checkout, projectPackages, library) != null) {
            return false; // the outer type does not exist either: the objection stands
        }
        boolean constantLike = last.equals(last.toUpperCase(java.util.Locale.ROOT));
        if (LibraryTypes.isJdkPackage(pkg)) {
            return jdkHasField(outer, last);
        }
        if (checkout != null && checkout.declares(outer)) {
            return checkout.membersOf(outer).stream().anyMatch(m -> m.name().equals(last))
                || !checkout.supertypesOf(outer).isEmpty();
        }
        return constantLike || library.exposedMembers(outer).stream()
            .anyMatch(m -> m.name().equals(last));
    }

    private static boolean jdkHasField(String outer, String field) {
        String binary = outer;
        for (int tries = 0; tries < 4; tries++) {
            try {
                Class<?> type = Class.forName(binary, false, ClassLoader.getPlatformClassLoader());
                for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field declared : c.getDeclaredFields()) {
                        if (declared.getName().equals(field)) {
                            return true;
                        }
                    }
                }
                for (java.lang.reflect.Field inherited : type.getFields()) {
                    if (inherited.getName().equals(field)) {
                        return true;
                    }
                }
                return false;
            } catch (ClassNotFoundException | LinkageError e) {
                int dot = binary.lastIndexOf('.');
                if (dot < 0) {
                    return false;
                }
                binary = binary.substring(0, dot) + '$' + binary.substring(dot + 1);
            }
        }
        return false;
    }

    private static boolean isPlanned(String name, Set<String> planned) {
        if (planned.contains(name)) {
            return true;
        }
        for (String type : planned) {
            if (name.startsWith(type + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String flattened(String name) {
        String pkg = LibraryTypes.packageOf(name);
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }
}
