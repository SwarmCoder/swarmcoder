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
package com.swarmcoder.verify;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.AcceptanceCompileErrors.PlannedChanges;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The red check calls an acceptance test broken only for a compile error no task of the plan could
 * cure. These are errors a task WILL cure, which the reading of the compiler's words took for
 * broken tests (audit of 2026-10-02, branch fix/audit-the-gates): a type that is not written yet
 * used through a static call, and a member missing from a type the build generates.
 */
class ACompileErrorATaskWillCureIsNotABrokenTestTest {

    private static final String TEST_FILE = "app/src/test/java/swarm/accept/LogbookTest.java";

    /** Nothing in the project has hand-written source for a type the build generates. */
    private static final Predicate<String> ONLY_QSO_IS_HAND_WRITTEN = "com.acme.Qso"::equals;

    @Test
    void aStaticCallOnATypeNotWrittenYetIsTheSameMissingType() {
        // javac reports the import, then every `Qso_Rules.validate(...)` as a missing VARIABLE
        String output = """
            [ERROR] /workspace/app/src/test/java/swarm/accept/LogbookTest.java:[5,16] cannot find symbol
            [ERROR]   symbol:   class Qso_Rules
            [ERROR]   location: package com.acme
            [ERROR] /workspace/app/src/test/java/swarm/accept/LogbookTest.java:[21,9] cannot find symbol
            [ERROR]   symbol:   variable Qso_Rules
            [ERROR]   location: class swarm.accept.LogbookTest
            """;
        Task task = task(Set.of("app/src/main/java/com/acme/Qso.java"),
            contract("com.acme.Qso_Rules", "List<String> validate(Qso qso)"));

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE), List.of(task),
            PlannedChanges.NONE, null, ONLY_QSO_IS_HAND_WRITTEN);

        assertThat(reading.inTestFiles()).hasSize(2);
        assertThat(reading.isBroken()).as(reading.quoted()).isFalse();
    }

    @Test
    void aStaticCallOnATypeNobodyDeliversIsStillBroken() {
        String output = """
            [ERROR] /workspace/app/src/test/java/swarm/accept/LogbookTest.java:[21,9] cannot find symbol
            [ERROR]   symbol:   variable QsoValidator
            [ERROR]   location: class swarm.accept.LogbookTest
            """;
        Task task = task(Set.of("app/src/main/java/com/acme/Qso.java"),
            contract("com.acme.Qso", "String getCall()"));

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE), List.of(task),
            PlannedChanges.NONE, null, ONLY_QSO_IS_HAND_WRITTEN);

        assertThat(reading.isBroken()).isTrue();
    }

    @Test
    void aMemberMissingFromAGeneratedTypeATaskDeliversIsNotALibrarysMember() {
        // the generated class exists already (the build made it) but not yet with this method
        String output = """
            [ERROR] /workspace/app/src/test/java/swarm/accept/LogbookTest.java:[22,18] cannot find symbol
            [ERROR]   symbol:   method call()
            [ERROR]   location: class com.acme.Qso_Rules
            """;
        Task task = task(Set.of("app/src/main/java/com/acme/Qso.java"),
            contract("com.acme.Qso_Rules", "FieldRule<String> call()"));

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE), List.of(task),
            PlannedChanges.NONE, null, ONLY_QSO_IS_HAND_WRITTEN);

        assertThat(reading.isBroken()).as(reading.quoted()).isFalse();
    }

    @Test
    void aMemberMissingFromATypeGeneratedBesideAFileATaskWritesIsNotEstablishedAsBroken() {
        // no contract for the generated type, but a task writes the file beside it, in its package
        String output = """
            [ERROR] /workspace/app/src/test/java/swarm/accept/LogbookTest.java:[22,18] cannot find symbol
            [ERROR]   symbol:   method call()
            [ERROR]   location: class com.acme.Qso_Rules
            """;
        Task task = task(Set.of("app/src/main/java/com/acme/Qso.java"),
            contract("com.acme.Qso", "@NotBlank String call;"));

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE), List.of(task),
            PlannedChanges.NONE, null, ONLY_QSO_IS_HAND_WRITTEN);

        assertThat(reading.isBroken()).as(reading.quoted()).isFalse();
    }

    @Test
    void aMemberMissingFromALibraryTypeIsStillBroken() {
        String output = """
            [ERROR] /workspace/app/src/test/java/swarm/accept/LogbookTest.java:[22,18] cannot find symbol
            [ERROR]   symbol:   method connectAs(java.lang.String)
            [ERROR]   location: class com.library.server.test.TestServer
            """;
        Task task = task(Set.of("app/src/main/java/com/acme/Qso.java"),
            contract("com.acme.Qso", "String getCall()"));

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE), List.of(task),
            PlannedChanges.NONE, null, ONLY_QSO_IS_HAND_WRITTEN);

        assertThat(reading.isBroken()).isTrue();
        assertThat(reading.quoted()).contains("is a library type");
    }

    private static Task task(Set<String> writeSet, ApiContract... delivers) {
        Task task = new Task(UUID.randomUUID(), 1, "t", "", new HashSet<>(writeSet), new HashSet<>(),
            new ArrayList<>(), null, null, null, null, TaskState.PENDING);
        task.setDeliveredContracts(List.of(delivers));
        return task;
    }

    private static ApiContract contract(String type, String... members) {
        return new ApiContract(UUID.randomUUID(), type, "", "", type, List.of(members));
    }
}
