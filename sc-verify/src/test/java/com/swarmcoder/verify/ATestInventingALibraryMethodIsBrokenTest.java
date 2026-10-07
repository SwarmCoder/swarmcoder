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
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.AcceptanceCompileErrors.PlannedChanges;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 57 (2026-10-01): the acceptance test called {@code TestServer.builder().storeDir(Path)},
 * a method that does not exist on a class that lives in a jar. No task writes that class and no
 * contract names it, yet the red check confirmed it as a healthy red and a whole swarm was spent on
 * a test no candidate could make compile.
 */
class ATestInventingALibraryMethodIsBrokenTest {

    private static final String TEST_FILE =
        "hambook-server/src/test/java/swarm/accept/EditContactTest.java";

    private static final String OUTPUT = """
        [ERROR] COMPILATION ERROR :\s
        [ERROR] /C:/Users/dev/.swarmcoder/wt/redcheck-x/hambook-server/src/test/java/swarm/accept/EditContactTest.java:[24,55] cannot find symbol
          symbol:   method storeDir(java.nio.file.Path)
          location: class com.zeroz4j.server.test.TestServer.Builder
        """;

    private static Task logbookTask() {
        Task task = new Task(UUID.randomUUID(), 1, "Implement logbook service", "do it",
            Set.of("hambook-server/src/main/java/com/hambook/server/logbook/LogbookServiceImpl.java",
                "hambook-server/src/main/java/com/hambook/server/store/HamBookStore.java",
                "hambook-server/pom.xml"),
            Set.of(), List.of(), "hambook-server/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
        task.setDeliveredContracts(List.of(new ApiContract(UUID.randomUUID(), "LogbookService", "",
            "", "com.hambook.LogbookService", List.of("Qso update(Qso qso)"))));
        return task;
    }

    @Test
    void aMissingMethodOnALibraryClassNoTaskWritesIsBroken() {
        Reading reading = AcceptanceCompileErrors.classify(OUTPUT, List.of(TEST_FILE),
            List.of(logbookTask()), PlannedChanges.NONE, null);

        assertThat(reading.inTestFiles()).hasSize(1);
        assertThat(reading.isBroken()).isTrue();
        assertThat(reading.quoted()).contains("storeDir");
    }

    @Test
    void aTaskOwningAWholeSourceRootDoesNotMakeAJarTypeWritable() {
        // A directory entry that IS a source root "covers everything" for the write-set reading,
        // which is how the invented method on a jar class sailed through. The source of the owner
        // is not in the project, so it is beyond any task.
        Task ownsTheRoot = new Task(UUID.randomUUID(), 1, "Build screen", "do it",
            Set.of("hambook-server/src/main/java"), Set.of(), List.of(),
            "hambook-server/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);

        Reading withoutLocation = AcceptanceCompileErrors.classify(OUTPUT, List.of(TEST_FILE),
            List.of(ownsTheRoot), PlannedChanges.NONE, null);
        assertThat(withoutLocation.isBroken()).as("unknown where the type lives: fail open").isFalse();

        Reading jarType = AcceptanceCompileErrors.classify(OUTPUT, List.of(TEST_FILE),
            List.of(ownsTheRoot), PlannedChanges.NONE, null, top -> false);
        assertThat(jarType.isBroken()).isTrue();
        assertThat(jarType.quoted()).contains("library type").contains("storeDir");

        Reading projectType = AcceptanceCompileErrors.classify(OUTPUT, List.of(TEST_FILE),
            List.of(ownsTheRoot), PlannedChanges.NONE, null,
            top -> top.equals("com.zeroz4j.server.test.TestServer"));
        assertThat(projectType.isBroken()).as("the task may edit a type that is in the project")
            .isFalse();
    }

    @Test
    void theVerifiersWordingOfAMissingMemberIsReadBackAgainstTheTask() {
        String message = "cannot find symbol: method storeDir(java.nio.file.Path) in class "
            + "com.zeroz4j.server.test.TestServer.Builder";

        assertThat(AcceptanceCompileErrors.absenceNoTaskCanSupply(message, TEST_FILE, logbookTask()))
            .isTrue();
        // a task that writes the owner's file may add the method: the candidates' fault
        Task writesIt = new Task(UUID.randomUUID(), 1, "Extend", "do it",
            Set.of("src/main/java/com/zeroz4j/server/test/TestServer.java"), Set.of(), List.of(),
            "src/test/java/swarm", null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()),
            TaskState.READY);
        assertThat(AcceptanceCompileErrors.absenceNoTaskCanSupply(message, TEST_FILE, writesIt))
            .isFalse();
        // a missing type, an unrestricted task and a misuse are not judged here
        assertThat(AcceptanceCompileErrors.absenceNoTaskCanSupply(
            "refers to com.acme.Foo, which does not exist", TEST_FILE, logbookTask())).isFalse();
        assertThat(AcceptanceCompileErrors.absenceNoTaskCanSupply(message, TEST_FILE,
            new Task(UUID.randomUUID(), 1, "Any", "do it", Set.of(), Set.of(), List.of(),
                "src/test/java/swarm", null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()),
                TaskState.READY))).isFalse();
    }
}
