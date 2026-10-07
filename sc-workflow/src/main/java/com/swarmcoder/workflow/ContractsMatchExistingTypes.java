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
import com.swarmcoder.knowledge.ProjectTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DESIGN_REVIEW, as a fact: a contract that names a type the project ALREADY has must not state
 * something the real declaration contradicts.
 *
 * <p>Harness run 78 (design made in run 77): the design listed {@code HamBookRoot} with
 * {@code @DataModel}; the real class carries {@code @Vetoed} and no {@code @DataModel}. Nothing
 * said so at design time. At verification every candidate failed on that one sentence, the repair
 * round ran its whole half hour, and the task blocked. A statement about existing code is checked
 * against that code here, with the same reading the delivery check uses (hand-written source,
 * generated source, compiled class), and an objection quotes the real declaration with its path
 * and line.
 *
 * <p><b>What it compares.</b> Annotations the contract puts on the type, and annotations it puts
 * on a member that already exists. Members the type lacks are not an objection: a design that
 * adds members to an existing type is the ordinary case. The contract syntax has no supertype
 * entry, so supertypes cannot be compared.
 *
 * <p><b>It cannot tell a new annotation from a false one.</b> A contract carries no mark saying
 * "this type is changed by the story" (an {@link ApiContract} is a name and its members, nothing
 * more), so a missing annotation is either a false claim or a change the story makes, and the
 * design has no way to say which. The objection says that plainly instead of guessing, and tells
 * the architect what to do in each case.
 */
final class ContractsMatchExistingTypes {

    private ContractsMatchExistingTypes() {}

    static List<String> objections(DesignDocument design, Path repo) {
        List<String> objections = new ArrayList<>();
        if (design == null || repo == null || design.contracts() == null) {
            return objections;
        }
        ProjectTypes checkout = ProjectTypes.of(repo);
        for (ApiContract contract : design.contracts()) {
            if (contract == null || !contract.namesAType()
                    || AcceptanceTestContracts.isAcceptanceTestClass(contract, null)) {
                continue;
            }
            List<ContractDelivery.Shortfall> shortfalls;
            try {
                shortfalls = ContractDelivery.shortfalls(repo, List.of(contract));
            } catch (RuntimeException unreadable) {
                continue; // an instrument that cannot read is not a verdict
            }
            for (ContractDelivery.Shortfall shortfall : shortfalls) {
                if (shortfall.missingType() || shortfall.unannotated().isEmpty()) {
                    continue;
                }
                objections.add(objection(contract, shortfall, checkout, repo));
            }
        }
        return objections;
    }

    private static String objection(ApiContract contract, ContractDelivery.Shortfall shortfall,
                                    ProjectTypes checkout, Path repo) {
        String name = contract.typeName().replace('$', '.').strip();
        Path file = checkout.fileOf(name);
        String where = file == null
            ? "in the project's generated or compiled output"
            : repo.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace(java.io.File.separatorChar, '/') + declarationLine(file, name);
        List<String> stated = new ArrayList<>();
        for (ContractDelivery.Unannotated u : shortfall.unannotated()) {
            String what = u.onType() ? "the type" : "the member `" + u.member() + "`";
            stated.add(what + " carries " + String.join(", ",
                u.annotations().stream().map(a -> "@" + a).toList()));
        }
        List<String> real = checkout.typeAnnotationsOf(name);
        return "the contract `" + name + "` states that " + String.join("; ", stated)
            + ", but `" + name + "` already exists in the project (" + where + ") and its real "
            + "declaration does not: its type annotations are "
            + (real.isEmpty() ? "none" : String.join(", ", real.stream().map(a -> "@" + a).toList()))
            + ". A contract carries no mark saying that the story CHANGES an existing type, so this "
            + "design cannot tell a false statement from an intended change, and a candidate is "
            + "checked against the statement. If you wrote it from memory, correct it to the real "
            + "declaration or leave the annotation out. If the story really has to add it, name "
            + "that change in the requirement or decision that needs it and keep it out of the "
            + "contract's members, so the contract describes only what the existing type has plus "
            + "members it gains.";
    }

    private static String declarationLine(Path file, String fullName) {
        String simple = fullName.substring(fullName.lastIndexOf('.') + 1);
        Pattern declaration = Pattern.compile(
            "\\b(class|interface|enum|record|@interface)\\s+" + Pattern.quote(simple) + "\\b");
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = declaration.matcher(lines.get(i));
                if (m.find() && !lines.get(i).strip().startsWith("*")
                        && !lines.get(i).strip().startsWith("//")) {
                    return ":" + (i + 1) + " `" + lines.get(i).strip() + "`";
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // no line to quote
        }
        return "";
    }
}
