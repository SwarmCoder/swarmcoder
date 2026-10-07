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

import java.util.ArrayList;
import java.util.List;

/**
 * DESIGN_REVIEW: a contract that names a type must name it with a package — not just a simple
 * name.
 *
 * <h2>Why this matters</h2>
 *
 * <p>{@link ApiContract#typeName()} reaches its task word for word
 * ({@code ArchitectClient.contractsNamed}), and both {@code com.swarmcoder.knowledge.ContractDelivery}
 * (the worker gate) and {@link TaskGraphValidator} (the plan gate) key delivery off it exactly as
 * written. A simple name like {@code Book} carries no package, so neither gate can tell a worker's
 * {@code com.acme.shop.Book} from anybody else's — a worker who wrote the type correctly would be
 * judged as never having delivered the contract at all, because "Book" matches every package and
 * proves none of them. That is not a smaller version of the problem
 * {@link ContractsNameRealTypes} already catches; that check only ever looks at qualified names
 * inside a contract's members and never at the contract's own {@code typeName}, so a bare simple
 * name there passed through unnoticed.
 *
 * <h2>Where this can come from</h2>
 *
 * <p>Added 2026-09-27, alongside the harness-run-46 fix to {@code ArchitectClient.resolveTypeName}:
 * that run's design named every contract's {@code "type"} as the KIND of the type ("class")
 * rather than its name, and the recovery added for it reads the real name back from the
 * contract's {@code name} field or from a kind-then-name pattern in its {@code signature}. That
 * recovery can legitimately land on a simple name with no package anywhere to combine it with
 * honestly — which would otherwise trade "every contract is called class" for "every contract is
 * unprovable", a smaller failure but the same shape. So this objects to it the same way an
 * unresolvable member type is objected to: through the design revision loop, before PLAN ever
 * sees it.
 *
 * <h2>What is judged, and what is left alone</h2>
 *
 * <p>Only {@link ApiContract#typeName()} itself, and only when {@link ApiContract#namesAType()}
 * is true. A contract that states no type at all is a different, already-handled gap (the
 * architect is re-asked once, naming the story's checks — see
 * {@code DesignRequiresTypedContractsForChecksTest}); this check is only for a type that WAS
 * named, without saying where it lives.
 */
final class ContractsNameAQualifiedType {

    private ContractsNameAQualifiedType() {}

    /** One objection per contract whose {@code typeName} has no package, in design order. */
    static List<String> objections(DesignDocument design) {
        List<String> objections = new ArrayList<>();
        if (design == null || design.contracts() == null) {
            return objections;
        }
        for (ApiContract contract : design.contracts()) {
            if (contract == null || !contract.namesAType()) {
                continue;
            }
            String type = contract.typeName().strip();
            if (!type.contains(".")) {
                objections.add("the contract `" + type + "` gives no package; give its "
                    + "fully-qualified name (e.g. com.acme.shop." + type + "), in the package "
                    + "the project rules/modules put it in.");
            }
        }
        return objections;
    }
}
