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
import com.swarmcoder.knowledge.AnnotationTargets;
import com.swarmcoder.knowledge.ContractMember;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DESIGN_REVIEW and check_design: an annotation a contract writes in front of a member must be one
 * that can be written there. The annotation type's own {@code @Target} decides
 * ({@link AnnotationTargets}); nothing here is a model's opinion.
 *
 * <p>Live supervised build, 2026-10-11: the design wrote {@code @DataModel Book()}. The annotation
 * can only be written on a type. The contract reached the workers word for word, four candidates
 * annotated the class and all four were failed for a constructor that "does not carry" it.
 *
 * <p>Where the facts decide, the product puts it right and says so in a note (as the plan checks
 * do, section 76): an annotation that cannot be on the member and can be on a type becomes the
 * type's annotation, written the way a contract states one ({@code @Annotation TypeName}). An
 * annotation that can be on neither goes back to the architect. An annotation whose declaration
 * cannot be read is left exactly as written.
 */
final class ContractAnnotationPlaces {

    private ContractAnnotationPlaces() {}

    /**
     * @param contracts  the design's contracts, with every annotation where it can be
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

    static Outcome check(DesignDocument design, AnnotationTargets targets) {
        List<ApiContract> contracts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> objections = new ArrayList<>();
        if (design == null || design.contracts() == null) {
            return new Outcome(contracts, notes, objections);
        }
        AnnotationTargets known = targets == null ? AnnotationTargets.JDK_ONLY : targets;
        for (ApiContract contract : design.contracts()) {
            if (contract == null || !contract.namesAType()) {
                contracts.add(contract);
                continue;
            }
            String simple = contract.simpleTypeName();
            List<String> members = new ArrayList<>();
            List<String> forTheType = new ArrayList<>();
            for (String member : contract.members()) {
                Optional<ContractMember> parsed = member == null ? Optional.empty()
                    : ContractMember.parse(member, simple);
                if (parsed.isEmpty()) {
                    members.add(member); // the type's own annotation, or not a declaration
                    continue;
                }
                String kept = member;
                for (String annotation : ContractMember.ownAnnotations(member)) {
                    AnnotationTargets.Place place = known.placeOf(annotation, parsed.get(), simple);
                    if (place == AnnotationTargets.Place.ON_THE_TYPE) {
                        kept = ContractMember.withoutAnnotation(kept, annotation);
                        forTheType.add(annotation);
                        notes.add("the contract `" + contract.typeName().strip() + "` wrote @"
                            + annotation + " in front of the member `" + member.strip() + "`. @"
                            + annotation + " cannot be written there and can be written on a "
                            + "type (its own @Target says so), so it now stands as the type's "
                            + "annotation: `@" + annotation + " " + simple + "`.");
                    } else if (place == AnnotationTargets.Place.NOWHERE) {
                        objections.add("the contract `" + contract.typeName().strip() + "` writes @"
                            + annotation + " in front of the member `" + member.strip() + "`, but @"
                            + annotation + " may only be written on: "
                            + known.targetsOf(annotation).map(t -> t.stream().sorted().toList())
                                .orElse(List.of())
                            + " (its own @Target). It can be neither on that member nor on the "
                            + "type, so no task can deliver it. Take it off that member, or write "
                            + "it in front of a declaration it can be on.");
                    }
                }
                members.add(kept);
            }
            if (forTheType.isEmpty()) {
                contracts.add(contract);
                continue;
            }
            List<String> stated = new ArrayList<>();
            for (String member : members) {
                ContractMember.typeAnnotations(member, simple)
                    .forEach(a -> stated.add(ContractMember.simpleName(a)));
            }
            List<String> corrected = new ArrayList<>();
            for (String annotation : forTheType) {
                if (!stated.contains(ContractMember.simpleName(annotation))) {
                    stated.add(ContractMember.simpleName(annotation));
                    corrected.add("@" + annotation + " " + simple);
                }
            }
            corrected.addAll(members);
            contracts.add(new ApiContract(contract.id(), contract.name(), contract.description(),
                contract.signatureSketch(), contract.typeName(), corrected));
        }
        return new Outcome(List.copyOf(contracts), List.copyOf(notes), List.copyOf(objections));
    }
}
