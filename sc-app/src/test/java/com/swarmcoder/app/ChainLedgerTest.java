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
package com.swarmcoder.app;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A link restored from a snapshot must never read as a link that held in this run (2026-09-25).
 *
 * <p>The resumed live walk puts links 1 to 9 on the ledger from the snapshot's manifest. What
 * matters is how they read — on the line printed as they are recorded, in the end-of-walk report,
 * and in the one-line verdict somebody reads instead of the log — because the whole value of the
 * chain is that a green line was measured in the run in front of the reader.
 */
class ChainLedgerTest {

    private static final List<String> PLAN = List.of("one", "two", "three", "four");

    @Test
    void aRestoredLinkIsPrintedAsSavedWithWhereItCameFromAndNeverAsHeld() {
        ChainLedger chain = new ChainLedger(PLAN);
        String printed = capture(() -> {
            chain.restored("one", "3 things measured", "snapshot X, saved yesterday");
            chain.restored("two", "4 things measured", "snapshot X, saved yesterday");
        });

        assertThat(printed).contains("[CHAIN saved] one — 3 things measured [NOT walked in this "
            + "run — restored from snapshot X, saved yesterday]");
        assertThat(printed).doesNotContain("[CHAIN ok");
        assertThat(chain.report()).contains(" 1. saved   one").contains(" 2. saved   two")
            .doesNotContain("held");
        assertThat(chain.heldSoFar()).as("a restored link is nothing a new snapshot may record")
            .isEmpty();
    }

    @Test
    void aWalkWithRestoredLinksIsNeverCalledWholeAndItsVerdictSaysWhy() {
        ChainLedger chain = new ChainLedger(PLAN);
        capture(() -> {
            chain.restored("one", "a", "snapshot X");
            chain.restored("two", "b", "snapshot X");
            chain.ok("three", "c");
            chain.ok("four", "d");
        });

        assertThat(chain.whole()).isFalse();
        assertThat(chain.verdict())
            .startsWith("CHAIN WHOLE FROM A SNAPSHOT")
            .contains("the last 2 of 4 links held in this run")
            .contains("the first 2 were restored from a snapshot and were NOT walked in it")
            .doesNotContain("all 4 links held");
        assertThat(chain.heldSoFar()).extracting(ChainLedger.Held::name)
            .containsExactly("three", "four");
    }

    @Test
    void anIncompleteResumedWalkCountsOnlyTheLinksItWalked() {
        ChainLedger chain = new ChainLedger(PLAN);
        capture(() -> {
            chain.restored("one", "a", "snapshot X");
            chain.restored("two", "b", "snapshot X");
            chain.ok("three", "c");
        });

        assertThat(chain.verdict()).isEqualTo("CHAIN INCOMPLETE — 1 of 4 links walked (and 2 "
            + "restored from a snapshot, not walked); never reached [four]");
    }

    @Test
    void aBreakAfterRestoredLinksIsStillABreak() {
        ChainLedger chain = new ChainLedger(PLAN);
        capture(() -> chain.restored("one", "a", "snapshot X"));
        assertThatThrownBy(() -> capture(() -> chain.require("two", false, "nothing happened")))
            .isInstanceOf(ChainLedger.Broken.class);
        assertThat(chain.verdict()).isEqualTo("CHAIN BROKE AT [two] — observed: nothing happened");
        assertThat(chain.report()).contains(" 1. saved   one").contains(" 2. BROKE   two")
            .contains(" 3. NOT REACHED  three");
    }

    @Test
    void aWalkWithNothingRestoredReadsExactlyAsItAlwaysDid() {
        ChainLedger chain = new ChainLedger(PLAN);
        capture(() -> PLAN.forEach(link -> chain.ok(link, "fine")));
        assertThat(chain.whole()).isTrue();
        assertThat(chain.verdict())
            .isEqualTo("CHAIN WHOLE — all 4 links held, document to merged commit");
        assertThat(chain.report()).contains(" 1. held    one");
    }

    private static String capture(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
