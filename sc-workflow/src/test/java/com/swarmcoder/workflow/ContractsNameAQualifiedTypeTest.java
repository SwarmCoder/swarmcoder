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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A contract's {@link ApiContract#typeName()} reaches its task word for word and both the worker
 * gate ({@code com.swarmcoder.knowledge.ContractDelivery}) and the plan gate
 * ({@link TaskGraphValidator}) key delivery off it exactly as written. A simple name like
 * {@code Book} carries no package, so a worker who correctly writes {@code com.acme.shop.Book}
 * cannot be told apart from one who wrote nothing at all — this check objects to that at
 * DESIGN_REVIEW before PLAN ever sees it, the same way {@link ContractsNameRealTypes} objects to a
 * member type that does not exist.
 */
class ContractsNameAQualifiedTypeTest {

    @Test
    void aContractWithNoPackageIsObjectedTo() {
        DesignDocument design = design(contract("Book"));

        List<String> objections = ContractsNameAQualifiedType.objections(design);

        assertThat(objections).hasSize(1);
        assertThat(objections.get(0))
            .contains("the contract `Book` gives no package")
            .contains("fully-qualified name")
            .contains("com.acme.shop.Book");
    }

    @Test
    void aQualifiedContractIsNotObjectedTo() {
        DesignDocument design = design(contract("com.acme.shop.Book"));

        assertThat(ContractsNameAQualifiedType.objections(design)).isEmpty();
    }

    @Test
    void aContractThatNamesNoTypeAtAllIsNotObjectedTo() {
        // A different, already-handled gap (the architect is re-asked once, naming the checks) —
        // this check is only for a type that WAS named without a package, not a missing one.
        ApiContract blank = new ApiContract(UUID.randomUUID(), "Book", "a book", "", "", List.of());
        DesignDocument design = design(blank);

        assertThat(ContractsNameAQualifiedType.objections(design)).isEmpty();
    }

    @Test
    void oneObjectionPerUnqualifiedContractAndNoneForTheQualifiedOnes() {
        DesignDocument design = design(
            contract("Book"), contract("com.acme.shop.BookService"), contract("Rating"));

        List<String> objections = ContractsNameAQualifiedType.objections(design);

        assertThat(objections).hasSize(2);
        assertThat(objections.get(0)).contains("the contract `Book` gives no package");
        assertThat(objections.get(1)).contains("the contract `Rating` gives no package");
    }

    @Test
    void anEmptyDesignHasNoObjections() {
        assertThat(ContractsNameAQualifiedType.objections(null)).isEmpty();
        assertThat(ContractsNameAQualifiedType.objections(design())).isEmpty();
    }

    private static ApiContract contract(String typeName) {
        return new ApiContract(UUID.randomUUID(),
            typeName.substring(typeName.lastIndexOf('.') + 1), "a contract", "", typeName,
            List.of());
    }

    private static DesignDocument design(ApiContract... contracts) {
        return new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(), List.of(),
            List.of(contracts), List.of(), null, Instant.now());
    }
}
