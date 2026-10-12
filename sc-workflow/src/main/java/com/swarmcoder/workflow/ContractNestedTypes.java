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
import com.swarmcoder.knowledge.ContractDelivery;
import com.swarmcoder.knowledge.ContractMember;
import com.swarmcoder.knowledge.ProjectTypes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DESIGN_REVIEW and check_design: a contract must not list a constructor of another type. A
 * constructor is named for its type, so {@code public Search()} in the contract of
 * {@code BookQueries} can never be a member of {@code BookQueries}: it says that the members
 * around it belong to a type {@code Search}.
 *
 * <p>Live supervised build, 2026-10-12: the design wrote the members of the nested class
 * {@code BookQueries.Search} as members of {@code BookQueries}. The contract reached the workers
 * word for word, eight candidates wrote the nested class, compiled and passed every acceptance
 * test, and all eight were failed for members the outer type "was delivered without".
 *
 * <p>Where the project's own tree and the design decide, the product puts it right and says so
 * in a note (as {@link ContractAnnotationPlaces} does for an annotation): the contract is split
 * into the outer type's and the nested type's, the latter named the way a nested type's contract
 * is named everywhere else, {@code pkg.Outer.Nested} ({@link NestedContracts}). Where they do not
 * decide, the contract goes back to the architect with the constructor named. Nothing here is a
 * model's opinion and no wording is read.
 *
 * <p>What decides that the constructor's type is declared inside the contract's type, any one
 * of: the start tree declares a nested type of that name there; the design has a contract of
 * that nested name; or the type on the start tree already declares nested types and no type of
 * the project or the design has that name (its siblings of this kind are nested, and the name is
 * new).
 *
 * <p>What decides where each other member goes: a member the outer type already declares stays
 * with it; a member the nested type already declares, or its contract already lists, goes with
 * it; and when the outer type on the start tree declares nested types and no field or method of
 * its own, every remaining member goes with the one nested type the contract names. A member
 * none of these place is not guessed at.
 */
final class ContractNestedTypes {

    private ContractNestedTypes() {}

    /**
     * @param contracts  the design's contracts, each nested type with a contract of its own
     * @param notes      what was put right, one sentence each; empty when nothing was
     * @param objections what only the architect can put right
     */
    record Outcome(List<ApiContract> contracts, List<String> notes, List<String> objections) {

        boolean changed() {
            return !notes.isEmpty();
        }

        /** {@code design} with the corrected contracts; the same object when nothing changed. */
        DesignDocument applyTo(DesignDocument design) {
            if (design == null || !changed()) {
                return design;
            }
            DesignDocument corrected = new DesignDocument(design.id(), design.revision(),
                design.goal(), design.requirements(), design.decisions(), contracts,
                design.risks(), design.review(), design.createdAt());
            corrected.setFindings(design.findings().isEmpty() ? null : design.findings());
            corrected.setBrdRequirementIds(design.brdRequirementIds().isEmpty() ? null
                : design.brdRequirementIds());
            return corrected;
        }
    }

    /** One contract that lists constructors of other types, and where its members go. */
    private record Split(List<String> staying, Map<String, List<String>> moved,
                         Map<String, String> because) {}

    /**
     * @param startTree the project as it is before the design is built; null reads as empty
     */
    static Outcome check(DesignDocument design, ProjectTypes startTree) {
        List<String> notes = new ArrayList<>();
        List<String> objections = new ArrayList<>();
        if (design == null || design.contracts() == null) {
            return new Outcome(List.of(), notes, objections);
        }
        ProjectTypes tree = startTree == null ? ProjectTypes.of(null) : startTree;
        List<ApiContract> written = design.contracts();
        Map<String, ApiContract> byTypeName = new LinkedHashMap<>();
        for (ApiContract contract : written) {
            if (contract != null && contract.namesAType()) {
                byTypeName.putIfAbsent(normalized(contract.typeName()), contract);
            }
        }
        Map<ApiContract, Split> splits = new java.util.IdentityHashMap<>();
        // nested type's full name -> what is added to (or becomes) its contract
        Map<String, List<String>> added = new LinkedHashMap<>();
        for (ApiContract contract : written) {
            if (contract == null || !contract.namesAType()) {
                continue;
            }
            Split split = split(contract, tree, byTypeName, objections);
            if (split != null) {
                splits.put(contract, split);
                split.moved().forEach((nested, members) ->
                    added.computeIfAbsent(nested, n -> new ArrayList<>()).addAll(members));
            }
        }
        if (splits.isEmpty()) {
            return new Outcome(List.copyOf(written), notes, List.copyOf(objections));
        }
        List<ApiContract> contracts = new ArrayList<>();
        for (ApiContract contract : written) {
            String name = contract != null && contract.namesAType()
                ? normalized(contract.typeName()) : null;
            ApiContract kept = contract;
            Split split = contract == null ? null : splits.get(contract);
            if (split != null) {
                kept = withMembers(contract, merged(split.staying(),
                    name != null && added.containsKey(name) ? added.get(name) : List.of()));
            } else if (name != null && added.containsKey(name)
                    && byTypeName.get(name) == contract) {
                kept = withMembers(contract, merged(contract.members(), added.get(name)));
            }
            contracts.add(kept);
            if (split == null) {
                continue;
            }
            for (String nested : split.moved().keySet()) {
                ApiContract existing = byTypeName.get(nested);
                ApiContract nestedContract = existing != null
                    ? withMembers(existing, merged(existing.members(), added.get(nested)))
                    : new ApiContract(
                        UUID.nameUUIDFromBytes((contract.id() + "/" + nested)
                            .getBytes(StandardCharsets.UTF_8)),
                        contract.simpleTypeName() + "." + simpleOf(nested),
                        "Declared inside " + contract.typeName().strip() + ".", "",
                        contract.typeName().strip() + "." + simpleOf(nested),
                        merged(List.of(), added.get(nested)));
                if (existing == null) {
                    contracts.add(nestedContract);
                }
                notes.add("the contract `" + contract.typeName().strip() + "` listed "
                    + quoted(constructorsOf(split.moved().get(nested), contract.simpleTypeName()))
                    + ". A constructor is named for its type, so that is a constructor of a "
                    + "type `" + simpleOf(nested) + "` and cannot be a member of `"
                    + contract.simpleTypeName() + "`. " + split.because().get(nested)
                    + ", so `" + simpleOf(nested) + "` is declared inside `"
                    + contract.simpleTypeName() + "` and has a contract of its own: `"
                    + nestedContract.describe() + "`. The contract of `"
                    + contract.simpleTypeName() + "` is now `" + kept.describe() + "`.");
            }
        }
        return new Outcome(List.copyOf(contracts), List.copyOf(notes), List.copyOf(objections));
    }

    /**
     * Where the members of {@code contract} go, or null when it lists no constructor of another
     * type or the facts do not decide (then an objection has been added).
     */
    private static Split split(ApiContract contract, ProjectTypes tree,
                               Map<String, ApiContract> byTypeName, List<String> objections) {
        String simple = contract.simpleTypeName();
        String full = normalized(contract.typeName());
        Map<String, List<String>> constructors = new LinkedHashMap<>();
        for (String member : contract.members()) {
            ContractMember.constructorOfAnotherType(member, simple).ifPresent(name ->
                constructors.computeIfAbsent(name, n -> new ArrayList<>()).add(member));
        }
        if (constructors.isEmpty()) {
            return null;
        }
        boolean onTheTree = tree.declares(full);
        List<String> siblings = onTheTree ? tree.nestedTypesOf(full) : List.of();
        boolean onlyNestedTypes = !siblings.isEmpty() && tree.bodyWasRead(full)
            && tree.membersOf(full).stream()
                .allMatch(m -> m.method() && m.name().equals(simple));
        Map<String, String> because = new LinkedHashMap<>();
        for (String name : constructors.keySet()) {
            String nested = full + "." + name;
            if (tree.declares(nested)) {
                because.put(nested, "`" + simple + "` already declares the nested type `" + name
                    + "`");
            } else if (byTypeName.containsKey(nested)) {
                because.put(nested, "The design has the contract `"
                    + byTypeName.get(nested).typeName().strip() + "`");
            } else if (!siblings.isEmpty() && !tree.hasSimpleName(name)
                    && byTypeName.keySet().stream().noneMatch(n -> simpleOf(n).equals(name))) {
                because.put(nested, "`" + simple + "` already declares the nested "
                    + (siblings.size() == 1 ? "type " : "types ") + quoted(siblings)
                    + ", and no type of the project or the design is named `" + name + "`");
            } else {
                objections.add(objection(contract, constructors.get(name), name,
                    "Nothing in the project or the design says where `" + name
                        + "` is declared."));
                return null;
            }
        }
        List<String> staying = new ArrayList<>();
        Map<String, List<String>> moved = new LinkedHashMap<>();
        because.keySet().forEach(nested -> moved.put(nested, new ArrayList<>()));
        List<String> undecided = new ArrayList<>();
        for (String member : contract.members()) {
            String constructorOf = ContractMember.constructorOfAnotherType(member, simple)
                .orElse(null);
            if (constructorOf != null) {
                moved.get(full + "." + constructorOf).add(member);
                continue;
            }
            var parsed = member == null ? java.util.Optional.<ContractMember>empty()
                : ContractMember.parse(member, simple);
            if (parsed.isEmpty() || (parsed.get().method() && parsed.get().type().isEmpty()
                    && parsed.get().name().equals(simple))) {
                staying.add(member); // the type's own annotation or constructor, or no declaration
                continue;
            }
            if (onTheTree && ContractDelivery.declaredOnTheType(tree, full, member)) {
                staying.add(member);
                continue;
            }
            List<String> homes = new ArrayList<>();
            for (String nested : moved.keySet()) {
                ApiContract stated = byTypeName.get(nested);
                if (ContractDelivery.declaredOnTheType(tree, nested, member)
                        || (stated != null && stated.members().stream()
                            .anyMatch(m -> sameDeclaration(m, member)))) {
                    homes.add(nested);
                }
            }
            if (homes.isEmpty() && onlyNestedTypes && moved.size() == 1) {
                homes.add(moved.keySet().iterator().next());
            }
            if (homes.size() == 1) {
                moved.get(homes.get(0)).add(member);
            } else {
                undecided.add(member);
            }
        }
        if (!undecided.isEmpty()) {
            String name = constructors.keySet().iterator().next();
            objections.add(objection(contract, constructors.get(name), name,
                "It cannot be told from the project which of the other members belong to `"
                    + String.join("`, `", constructors.keySet()) + "` and which to `" + simple
                    + "`: " + quoted(undecided) + "."));
            return null;
        }
        return new Split(staying, moved, because);
    }

    private static String objection(ApiContract contract, List<String> constructors, String name,
                                    String why) {
        String simple = contract.simpleTypeName();
        return "the contract `" + contract.typeName().strip() + "` lists "
            + quoted(constructors.stream().map(String::strip).toList()) + ". A constructor is "
            + "named for its type, so that is a constructor of a type `" + name + "` and cannot "
            + "be a member of `" + simple + "`; no task can deliver it there. " + why
            + " If `" + name + "` is declared inside `" + simple + "`, give it a contract of its "
            + "own with the type name `" + contract.typeName().strip() + "." + name + "` and "
            + "move its constructors and members there. If it is a type of its own, give it a "
            + "contract under its own name. A constructor of `" + simple + "` is written `"
            + simple + "(...)`.";
    }

    private static List<String> constructorsOf(List<String> members, String outerSimple) {
        return members.stream()
            .filter(m -> ContractMember.constructorOfAnotherType(m, outerSimple).isPresent())
            .map(String::strip).toList();
    }

    private static String quoted(List<String> texts) {
        return "`" + String.join("`, `", texts) + "`";
    }

    private static ApiContract withMembers(ApiContract contract, List<String> members) {
        return new ApiContract(contract.id(), contract.name(), contract.description(),
            contract.signatureSketch(), contract.typeName(), members);
    }

    /** {@code first} and then each of {@code more} it does not already state. */
    private static List<String> merged(List<String> first, List<String> more) {
        List<String> all = new ArrayList<>(first);
        for (String member : more) {
            if (all.stream().noneMatch(m -> sameDeclaration(m, member))) {
                all.add(member);
            }
        }
        return all;
    }

    /** The same text once what a model wraps around a declaration is taken off. */
    private static boolean sameDeclaration(String a, String b) {
        return a != null && b != null && bare(a).equals(bare(b));
    }

    private static String bare(String member) {
        String text = ContractMember.declarationPart(member);
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text;
    }

    private static String normalized(String typeName) {
        return typeName.replace('$', '.').strip();
    }

    private static String simpleOf(String fullName) {
        return fullName.substring(fullName.lastIndexOf('.') + 1);
    }
}
