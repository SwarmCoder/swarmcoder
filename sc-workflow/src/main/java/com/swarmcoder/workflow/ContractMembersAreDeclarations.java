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

import java.util.ArrayList;
import java.util.List;

/**
 * DESIGN_REVIEW and check_design: every member of a contract must be written as a Java
 * declaration — {@code @Annotation Type name;}, {@code String getCall()},
 * {@code void setCall(String)}, an enum constant, a record component — not as a sentence.
 *
 * <p>Harness run 71, 2026-10-02: the architect wrote a member as prose ("call field is annotated
 * @NotBlank (...) - this is the member this story adds"). Six correct candidates were each failed
 * on it, because the delivery check could not compare a sentence with a tree. The delivery check
 * now skips what it cannot read, but a skipped promise is an unchecked one, so the architect is
 * told here, before hand-in, to say it in the form that IS checked: an annotation is written in
 * front of the declaration it must be on.
 *
 * <p>Mechanical and library-agnostic: it reads only the design's own text.
 */
final class ContractMembersAreDeclarations {

    private ContractMembersAreDeclarations() {}

    /** One objection per contract member that is not a declaration, in design order. */
    static List<String> objections(DesignDocument design) {
        List<String> objections = new ArrayList<>();
        if (design == null || design.contracts() == null) {
            return objections;
        }
        for (ApiContract contract : design.contracts()) {
            if (contract == null || !contract.namesAType()) {
                continue;
            }
            for (String member : contract.members()) {
                if (member == null || member.isBlank() || ContractMember.isDeclaration(member)
                        || !ContractMember.typeAnnotations(member, contract.simpleTypeName()).isEmpty()) {
                    continue;
                }
                objections.add("the member `" + member.strip() + "` of the contract `"
                    + contract.typeName().strip() + "` is not a declaration, so nothing can check "
                    + "that it is delivered. Write each member as a Java declaration of its own, "
                    + "such as `" + ContractMember.EXAMPLE_FORM + "` for a field that must carry "
                    + "an annotation, `String getCall()` for a method, or a bare constant name "
                    + "for an enum constant, and put the explanation in the contract's "
                    + "description.");
            }
        }
        return objections;
    }
}
