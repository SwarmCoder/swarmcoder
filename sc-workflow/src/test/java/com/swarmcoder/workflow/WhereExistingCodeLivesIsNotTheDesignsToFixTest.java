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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 74: two architect revisions (21 minutes) over a reviewer objection that two classes
 * written by an earlier story are in the wrong package. The three wordings below are the ones
 * that run's reviewer used, with other names.
 */
class WhereExistingCodeLivesIsNotTheDesignsToFixTest {

    @TempDir
    Path repo;

    private ExistingProjectTypes existing;

    @BeforeEach
    void anEarlierStoryWroteTheService() throws Exception {
        Path file = repo.resolve("server/src/main/java/org/example/shop/server/OrderServiceImpl.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package org.example.shop.server;

            public class OrderServiceImpl {
                public void place(String id) {
                }
            }
            """);
        existing = ExistingProjectTypes.of(repo);
    }

    @Test
    void anObjectionToWhereAnExistingTypeLivesIsCarriedWhateverItsWording() {
        List<String> asWorded = List.of(
            "contract 'OrderServiceImpl at org.example.shop.server' conflicts with rule 'Fixed "
                + "module and package layout': order logic must live in "
                + "org.example.shop.server.orders (OrderServiceImpl), not at the "
                + "org.example.shop.server root.",
            "decision 'OrderServiceImpl type org.example.shop.server.OrderServiceImpl' conflicts "
                + "with rule 'Fixed module and package layout': OrderServiceImpl must live in "
                + "org.example.shop.server.orders, but the design places it directly in "
                + "org.example.shop.server.",
            "decision 'org.example.shop.server.OrderServiceImpl' conflicts with rule 'Fixed "
                + "module and package layout': the design places OrderServiceImpl in "
                + "org.example.shop.server, but the rule mandates order logic in "
                + "org.example.shop.server.orders (OrderServiceImpl).");

        WhereExistingCodeLives.Split split = WhereExistingCodeLives.split(asWorded, existing);

        assertThat(split.carried()).containsExactlyElementsOf(asWorded);
        assertThat(split.forTheArchitect()).isEmpty();
    }

    @Test
    void aNewTypeInTheWrongPlaceStillGoesToTheArchitect() {
        String newType = "contract 'CancelCommand at org.example.shop.server' conflicts with rule "
            + "'Fixed module and package layout': write helpers must live in "
            + "org.example.shop.server.store, not at the org.example.shop.server root.";
        String mixed = "decision 'OrderServiceImpl and CancelCommand at org.example.shop.server' "
            + "conflicts with rule 'Fixed module and package layout': both must live in "
            + "org.example.shop.server.orders.";

        WhereExistingCodeLives.Split split =
            WhereExistingCodeLives.split(List.of(newType, mixed), existing);

        assertThat(split.carried()).isEmpty();
        assertThat(split.forTheArchitect()).containsExactly(newType, mixed);
    }

    @Test
    void anObjectionToAnExistingTypeForAnotherReasonStillGoesToTheArchitect() {
        String notPlacement = "decision 'org.example.shop.server.OrderServiceImpl keeps orders in "
            + "a map' conflicts with rule 'Persistence': orders must be stored through the "
            + "project's store, not held in memory.";
        String elsewhere = "decision 'OrderServiceImpl at org.example.shop.client' conflicts with "
            + "rule 'Fixed module and package layout': it must live in org.example.shop.server.";

        assertThat(WhereExistingCodeLives.isAbout(notPlacement, existing)).isFalse();
        assertThat(WhereExistingCodeLives.isAbout(elsewhere, existing))
            .as("the design puts it somewhere the tree does not have it: the design's to fix")
            .isFalse();
        assertThat(WhereExistingCodeLives.isAbout(notPlacement, ExistingProjectTypes.NONE))
            .isFalse();
    }
}
