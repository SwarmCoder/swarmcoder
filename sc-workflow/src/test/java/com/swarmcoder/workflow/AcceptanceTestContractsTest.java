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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how a contract that accidentally names the acceptance test class itself is told apart
 * from a real contract — see {@link AcceptanceTestContracts} for the run this exists because of.
 */
class AcceptanceTestContractsTest {

    private static ApiContract contract(String typeName) {
        return new ApiContract(UUID.randomUUID(), typeName, "desc", null, typeName, List.of());
    }

    @Test
    void thePackageTheTestAuthorWritesIntoIsDerivedNotHardCoded() {
        // "swarm.accept" is where AcceptanceTestLocation.WRITE_SUBDIR actually points today — the
        // point of deriving it is that this test would still be right if that constant changed.
        assertThat(AcceptanceTestContracts.testAuthorPackage()).isEqualTo("swarm.accept");
    }

    @Test
    void aContractInTheTestAuthorsPackageIsTheAcceptanceTestClass() {
        ApiContract testClass = contract("swarm.accept.PersistenceTest");

        assertThat(AcceptanceTestContracts.isAcceptanceTestClass(testClass, List.of())).isTrue();
    }

    @Test
    void aNestedTypeUnderTheTestAuthorsPackageIsAlsoRecognised() {
        ApiContract nested = contract("swarm.accept.support.Helper");

        assertThat(AcceptanceTestContracts.isAcceptanceTestClass(nested, List.of())).isTrue();
    }

    @Test
    void aRealContractOutsideThePackageAndUnmentionedByAnyCheckIsNotTheTestClass() {
        ApiContract book = contract("com.acme.demo.bookshelf.Book");

        assertThat(AcceptanceTestContracts.isAcceptanceTestClass(book,
            List.of("swarm.accept.PersistenceTest#retainsDataAfterRestart"))).isFalse();
    }

    /**
     * The second signal: even a contract the architect placed outside the test package is the
     * test class when its exact name is what a check's own test reference names.
     */
    @Test
    void aContractNamedByAChecksTestReferenceIsTheAcceptanceTestClassEvenOutsideThePackage() {
        ApiContract misplaced = contract("com.acme.demo.bookshelf.PersistenceTest");

        assertThat(AcceptanceTestContracts.isAcceptanceTestClass(misplaced,
            List.of("com.acme.demo.bookshelf.PersistenceTest#retainsDataAfterRestart"))).isTrue();
    }

    @Test
    void aContractNamingNoTypeAtAllIsNeverTheTestClass() {
        ApiContract nameOnly = new ApiContract(UUID.randomUUID(), "CartApi", "desc", "POST /cart");

        assertThat(AcceptanceTestContracts.isAcceptanceTestClass(nameOnly, List.of())).isFalse();
    }

    @Test
    void withoutAcceptanceTestClassesKeepsTheRealContractAndReportsTheDroppedOne() {
        ApiContract book = contract("com.acme.demo.bookshelf.Book");
        ApiContract testClass = contract("swarm.accept.PersistenceTest");
        List<ApiContract> dropped = new ArrayList<>();

        List<ApiContract> kept = AcceptanceTestContracts.withoutAcceptanceTestClasses(
            List.of(book, testClass), List.of(), dropped::add);

        assertThat(kept).containsExactly(book);
        assertThat(dropped).containsExactly(testClass);
    }
}
