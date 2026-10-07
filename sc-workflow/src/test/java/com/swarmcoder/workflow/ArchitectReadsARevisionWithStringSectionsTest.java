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
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Requirement;
import com.swarmcoder.domain.Severity;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fix for harness runs 60 and 61 (2026-10-01): the architect's revision was discarded
 * twice per run as "dropped 1 requirement(s) and all contracts, every section absent", though the
 * raw reply carried them all. The reply wrote requirements, decisions and risks as plain strings
 * (the way the design summary in the prompt shows them: {@code "R1 [HIGH] text"}), which did not
 * bind to the wire objects; the whole object failed, the lenient reader retried from the next
 * brace and bound an inner contract object as the design, and every section then looked absent.
 */
class ArchitectReadsARevisionWithStringSectionsTest {

    private static ArchitectClient architect(ScriptedLlm llm) {
        return new ArchitectClient(
            new VllmClient(llm.baseUrl(), null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));
    }

    private static DesignDocument original() {
        return new DesignDocument(UUID.randomUUID(), 1, "Edit a contact",
            new ArrayList<>(List.of(new Requirement(UUID.randomUUID(),
                "The operator can open an existing contact and save it.", Priority.HIGH))),
            new ArrayList<>(),
            new ArrayList<>(List.of(new ApiContract(UUID.randomUUID(), "Qso", "a contact", "",
                "com.hambook.Qso", List.of("String getCall()")))),
            new ArrayList<>(), null, Instant.now());
    }

    /** The shape of the real reply: fenced, string sections, contracts with types, trailing comma. */
    private static final String REPLY = """
        ```json
        {
          "requirements": [
            "R1 [HIGH] The operator can open an existing contact, change any field, and save; the row updates."
          ],
          "decisions": [
            "Add `void updateQso(Qso qso)` to `LogbookService`. \u2014 Satisfies R3:C1 by letting the operator save.",
            "Client code calls `getLogbook()` after a successful `updateQso`."
          ],
          "contracts": [
            {
              "name": "Qso",
              "description": "One logged contact.",
              "signature": "@DataModel class com.hambook.Qso",
              "type": "com.hambook.Qso",
              "members": [
                "QslStatus getQsl()",
                "void setQsl(QslStatus qsl)",
              ]
            },
            {
              "name": "QslStatus",
              "description": "QSL status container.",
              "signature": "@DataModel class com.hambook.QslStatus",
              "type": "com.hambook.QslStatus",
              "members": ["QslState getState()"]
            }
          ],
          "risks": [
            "[LOW] Concurrent edits could cause last-write-wins.",
            "[CRITICAL] Improper DbCommand enlistment could silently lose data on restart."
          ]
        }
        ```
        """;

    @Test
    void aRevisionWithStringSectionsAndATrailingCommaIsAcceptedOnTheFirstReply() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            calls.incrementAndGet();
            return REPLY;
        })) {
            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original(), List.of("name the types"));

            assertThat(attempt.discarded()).as(String.valueOf(attempt.discardReason())).isFalse();
            assertThat(calls.get()).isEqualTo(1);
            DesignDocument revised = attempt.design();
            assertThat(revised.requirements()).hasSize(1);
            assertThat(revised.requirements().get(0).text()).startsWith("The operator can open");
            assertThat(revised.requirements().get(0).priority()).isEqualTo(Priority.HIGH);
            assertThat(revised.decisions()).hasSize(2);
            assertThat(revised.decisions().get(0).decision()).startsWith("Add `void updateQso");
            assertThat(revised.decisions().get(0).rationale()).startsWith("Satisfies R3:C1");
            assertThat(revised.contracts()).extracting(ApiContract::typeName)
                .containsExactly("com.hambook.Qso", "com.hambook.QslStatus");
            assertThat(revised.contracts().get(0).members()).hasSize(2);
            assertThat(revised.risks()).hasSize(2);
            assertThat(revised.risks().get(1).severity()).isEqualTo(Severity.CRITICAL);
        }
    }

    @Test
    void aSectionExplicitlyEmptiedIsStillADrop() throws Exception {
        String reply = """
            {"requirements": [], "decisions": [], "contracts": [], "risks": []}
            """;
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            calls.incrementAndGet();
            return reply;
        })) {
            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original(), List.of("name the types"));

            assertThat(attempt.discarded()).isTrue();
            assertThat(calls.get()).isEqualTo(1);
        }
    }
}
