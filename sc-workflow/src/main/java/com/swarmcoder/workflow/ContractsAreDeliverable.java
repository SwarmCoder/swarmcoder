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
import com.swarmcoder.knowledge.JavaSourceFacts;
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.knowledge.ProjectTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * DESIGN_REVIEW, as a fact and not a model's opinion: a contract is a type this story will CREATE,
 * so its own type must sit in a package the project can write. A contract whose type is in a
 * library's package (or the JDK's, or any package the project has no code near) can be delivered
 * by no task, whatever the planner does.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 67, 2026-10-02 (story "add a contact" on a project whose own code is under one
 * namespace and whose UI library is under another). The design that passed review carried four
 * contracts in the LIBRARY's packages: two types the library has never had, one that exists in a
 * different package of it, and one that exists exactly as named. The plan check then demanded a
 * task that delivers each ("no task delivers the contract …"), no task's write set can create a
 * class in a library's package, and the planner was rejected three times, parked, retried by the
 * operator, and parked again — about fifty minutes on a fault that was the design's.
 *
 * <p>{@link ContractsNameRealTypes} did not catch it because it reads the types a contract's
 * MEMBERS name, never the contract's own type — and it treats every contract's own type as
 * "planned", so the members that named these four were waved through as well.
 *
 * <h2>What is decided, per contract</h2>
 *
 * <ol>
 *   <li>the checkout already declares the type, or the type has no package, or its package is in
 *       the project's own namespace: left alone — the plan check and
 *       {@link ContractsNameAQualifiedType} own those;</li>
 *   <li>a JDK package, or a package a reference checkout covers, and the type <b>exists</b> there:
 *       it is an existing type, not a deliverable. It is taken out of the design's contracts
 *       ({@link Outcome#existing}) so no task is asked for it — unless the design gives it members
 *       the real type does not declare, which is an objection listing the real ones;</li>
 *   <li>such a package and the type does <b>not</b> exist: an objection naming the real type of
 *       the same simple name, or the nearest ones, or saying there is none;</li>
 *   <li>any other package, when the project has packages of its own to compare with: an objection
 *       — nothing can say what that package is, only that no task can write it.</li>
 * </ol>
 *
 * <p>Library-agnostic: every name in an objection is read from the design, the checkout or the
 * reference checkouts.
 */
final class ContractsAreDeliverable {

    /** How many real types an objection offers in place of one that does not exist. */
    static final int MAX_SUGGESTIONS = 3;

    /** How many of a real type's members an objection lists. */
    static final int MAX_MEMBERS_SHOWN = 12;

    /** How many member names of a type in a library jar an objection lists. */
    static final int MAX_JAR_MEMBERS_SHOWN = 40;

    /** How many of the project's own packages an objection names. */
    static final int MAX_PACKAGES_SHOWN = 4;

    /**
     * @param existing   contracts naming a type a library or the JDK already has, with nothing the
     *                   real type lacks: to be removed from the design's contracts
     * @param objections what the architect must correct; empty when there is nothing to
     */
    record Outcome(List<ApiContract> existing, List<String> objections) {

        boolean clean() {
            return existing.isEmpty() && objections.isEmpty();
        }

        /** {@code design}'s contracts without {@link #existing}. */
        List<ApiContract> remaining(DesignDocument design) {
            return design.contracts().stream().filter(c -> !existing.contains(c)).toList();
        }
    }

    private ContractsAreDeliverable() {}

    static Outcome check(DesignDocument design, ProjectTypes checkout, LibraryTypes library) {
        List<ApiContract> existing = new ArrayList<>();
        List<String> objections = new ArrayList<>();
        if (design == null || design.contracts() == null) {
            return new Outcome(existing, objections);
        }
        LibraryTypes lib = library == null ? LibraryTypes.NONE : library;
        Set<String> own = checkout == null ? Set.of() : checkout.packages();
        String ownShown = ownPackagesShown(own);
        for (ApiContract contract : design.contracts()) {
            if (contract == null || !contract.namesAType()
                    || AcceptanceTestContracts.isAcceptanceTestClass(contract, null)) {
                continue; // the acceptance test class is the test author's, in its own package
            }
            String name = contract.typeName().replace('$', '.').strip();
            String pkg = LibraryTypes.packageOf(name);
            if (pkg.isEmpty() || pkg.equals(name) || pkg.split("\\.").length < 2) {
                continue;
            }
            if (checkout != null && checkout.declares(name)) {
                continue;
            }
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (LibraryTypes.isJdkPackage(pkg)) {
                if (LibraryTypes.jdkDeclares(name)) {
                    existing.add(contract);
                } else {
                    objections.add(head(name) + pkg + " is a package of the JDK, where no task "
                        + "can create a type, and the JDK has no " + simple + " in " + pkg + "."
                        + defineYourOwn(ownShown));
                }
                continue;
            }
            if (inNamespaceOf(pkg, own, true)) {
                continue;
            }
            String root = lib.coveringRoot(pkg);
            if (root == null && inNamespaceOf(pkg, own, false)) {
                continue;
            }
            if (root == null) {
                // No reference checkout covers the package. A library the project compiles
                // against may still hold the type in a JAR, which the Java language server
                // reads (2026-10-04): then the type exists, and its real members are known.
                List<String> inJar = lib.jarMemberNames(name);
                if (!inJar.isEmpty()) {
                    List<String> missing = lib.membersNotInJar(contract);
                    if (missing.isEmpty()) {
                        existing.add(contract);
                    } else {
                        objections.add("the contract " + name + " names a type that already "
                            + "exists in a library jar this project compiles against. It is not "
                            + "something this story delivers, and no task can change it. The "
                            + "real " + simple + " has no " + String.join(", ", missing)
                            + ". Its real members are: " + inJar.stream()
                                .limit(MAX_JAR_MEMBERS_SHOWN).collect(Collectors.joining(", "))
                            + (inJar.size() > MAX_JAR_MEMBERS_SHOWN ? ", and "
                                + (inJar.size() - MAX_JAR_MEMBERS_SHOWN) + " more." : ".")
                            + " Remove this contract and use the real members; if the story "
                            + "needs what the real type lacks, put it on a type of this "
                            + "project's own.");
                    }
                    continue;
                }
                root = lib.looselyCoveringRoot(pkg);
            }
            if (root == null) {
                if (!ownShown.isEmpty()) {
                    objections.add(head(name) + pkg + " is not a package of this project (its "
                        + "own code is in " + ownShown + "), so no task can create a type there. "
                        + "If " + simple + " is a type of a library the project uses, it is not "
                        + "something this story delivers: remove the contract and use the "
                        + "library's type where the design needs it. If it is a type this story "
                        + "creates, give it a package of this project.");
                }
                continue;
            }
            if (lib.declares(name)) {
                List<String> missing = lib.membersNotOn(contract);
                if (missing.isEmpty()) {
                    existing.add(contract);
                    continue;
                }
                List<JavaSourceFacts.Exposed> real = lib.exposedMembers(name);
                StringBuilder sb = new StringBuilder("the contract ").append(name)
                    .append(" names a type that already exists in the ").append(root)
                    .append(" reference source. It is not something this story delivers, and no "
                        + "task can change it. The real ").append(simple).append(" has no ")
                    .append(String.join(", ", missing)).append('.');
                if (!real.isEmpty()) {
                    sb.append(" Its real members are: ").append(real.stream()
                        .limit(MAX_MEMBERS_SHOWN).map(JavaSourceFacts.Exposed::header)
                        .collect(Collectors.joining("; ")))
                        .append(real.size() > MAX_MEMBERS_SHOWN
                            ? "; and " + (real.size() - MAX_MEMBERS_SHOWN) + " more." : ".");
                }
                sb.append(" Remove this contract and use the real members; if the story needs "
                    + "what the real type lacks, put it on a type of this project's own.");
                objections.add(sb.toString());
                continue;
            }
            List<LibraryTypes.Suggestion> near = lib.nearest(name, MAX_SUGGESTIONS);
            StringBuilder sb = new StringBuilder(head(name)).append(pkg)
                .append(" belongs to the ").append(root).append(" reference source, a library "
                    + "this project uses, so no task can create a type there, and ").append(name)
                .append(" does not exist: the library has ");
            boolean exact = !near.isEmpty() && near.get(0).fullName().endsWith("." + simple);
            if (exact) {
                sb.append(near.get(0).fullName()).append(". Remove this contract and use ")
                    .append(near.get(0).fullName()).append(" wherever the design needs a ")
                    .append(simple).append('.');
            } else if (!near.isEmpty()) {
                sb.append("no ").append(simple).append(" anywhere. Its types with the nearest "
                    + "names are ").append(near.stream().map(s -> s.fullName()
                        + (s.summary().isBlank() ? "" : " (" + s.summary() + ")"))
                    .collect(Collectors.joining("; "))).append('.')
                    .append(" Remove this contract and use one of those, or")
                    .append(defineYourOwn(ownShown).replaceFirst("^ Either", ""));
            } else {
                sb.append("nothing of that name anywhere.").append(defineYourOwn(ownShown));
            }
            objections.add(sb.toString());
        }
        return new Outcome(List.copyOf(existing), List.copyOf(objections));
    }

    private static String head(String name) {
        return "the contract " + name + " cannot be delivered: ";
    }

    private static String defineYourOwn(String ownShown) {
        return " Either define the type in one of this project's own packages"
            + (ownShown.isEmpty() ? "" : " (" + ownShown + ")")
            + " and name the contract by that, or use a type that really exists and remove this "
            + "contract.";
    }

    /**
     * True when {@code pkg} is in the namespace of one of the project's own packages: by
     * {@link LibraryTypes#sameNamespace} when {@code strict}, by its first two segments otherwise
     * (a new sub-package of the project is the project's, unless a library covers it).
     */
    private static boolean inNamespaceOf(String pkg, Set<String> own, boolean strict) {
        String[] p = pkg.split("\\.");
        for (String known : own) {
            if (strict) {
                if (LibraryTypes.sameNamespace(pkg, known)) {
                    return true;
                }
                continue;
            }
            String[] k = known.split("\\.");
            if (k.length >= 2 && p.length >= 2 && k[0].equals(p[0]) && k[1].equals(p[1])) {
                return true;
            }
        }
        return false;
    }

    /** The shortest few of the project's packages of two segments or more, or "". */
    private static String ownPackagesShown(Set<String> own) {
        Set<String> roots = new TreeSet<>();
        for (String pkg : own) {
            String[] parts = pkg.split("\\.");
            if (parts.length >= 2) {
                roots.add(parts[0] + "." + parts[1]);
            }
        }
        return roots.stream().limit(MAX_PACKAGES_SHOWN).collect(Collectors.joining(", "));
    }
}
