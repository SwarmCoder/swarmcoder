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

import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.knowledge.ProjectTypes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Every fact check DESIGN_REVIEW makes on a design's contracts, in one place, so the stage, the
 * architect's own {@code check_design} tool and the offline replay of saved live runs
 * ({@code GateReplayTest}) all ask exactly the same questions.
 *
 * <p>Each check here objects only to something it has established from the checkout, the JDK or a
 * reference source; none of them is a model's opinion.
 */
final class DesignFactChecks {

    private DesignFactChecks() {}

    /**
     * The objections to send back to the architect, most fundamental first: a second type of a
     * name the project already has, a contract in a package no task can write, a member naming a
     * type that does not exist, a contract with no package, a member that is not a declaration.
     * An annotation a contract states on a type the project already has, which the real
     * declaration lacks, comes first ({@link ContractsMatchExistingTypes}, harness run 78).
     *
     * @param repo    the project's checkout; null reads as an empty project
     * @param library the reference sources; null reads as none
     * @param say     where each group of findings is logged
     */
    static List<String> objections(DesignDocument design, Path repo, LibraryTypes library,
                                   Consumer<String> say) {
        LibraryTypes lib = library == null ? LibraryTypes.NONE : library;
        Consumer<String> log = say == null ? line -> { } : say;
        ProjectTypes checkout = ProjectTypes.of(repo);
        List<String> undeliverable =
            ContractsAreDeliverable.check(design, checkout, lib).objections();
        if (!undeliverable.isEmpty()) {
            log.accept("DESIGN_REVIEW: " + undeliverable.size() + " contract(s) no task can deliver, "
                + "because their type is in a package this project cannot write: " + undeliverable);
        }
        List<String> objections = new ArrayList<>(ContractsNameRealTypes.objections(design,
            checkout, lib));
        objections.addAll(0, undeliverable);
        if (objections.size() > undeliverable.size()) {
            log.accept("DESIGN_REVIEW: " + (objections.size() - undeliverable.size())
                + " type(s) the contracts name do not exist: "
                + objections.subList(undeliverable.size(), objections.size()));
        }
        List<String> duplicates = ExistingProjectTypes.of(repo).duplicateObjections(design);
        if (!duplicates.isEmpty()) {
            log.accept("DESIGN_REVIEW: " + duplicates.size() + " contract(s) would create a second type "
                + "of a name this project already has, in another package: " + duplicates);
            objections.addAll(0, duplicates);
        }
        List<String> unqualified = ContractsNameAQualifiedType.objections(design);
        if (!unqualified.isEmpty()) {
            log.accept("DESIGN_REVIEW: " + unqualified.size() + " contract(s) name a type with no "
                + "package: " + unqualified);
            objections.addAll(unqualified);
        }
        List<String> existing = ContractsMatchExistingTypes.objections(design, repo);
        if (!existing.isEmpty()) {
            log.accept("DESIGN_REVIEW: " + existing.size() + " contract(s) state something the "
                + "existing type does not have: " + existing);
            objections.addAll(0, existing);
        }
        List<String> prose = ContractMembersAreDeclarations.objections(design);
        if (!prose.isEmpty()) {
            log.accept("DESIGN_REVIEW: " + prose.size() + " contract member(s) are not written as "
                + "declarations: " + prose);
            objections.addAll(prose);
        }
        return objections;
    }
}
