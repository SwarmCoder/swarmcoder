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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 78: a contract said a type that ALREADY existed carried an annotation it never had.
 * Every candidate failed as "not delivered", with "the task was told to create it".
 */
class AContractOnAnExistingTypeIsNotTheTasksToCreateTest {

    private static final String AT_START = "package com.x;\n@Vetoed\npublic class Root { }\n";

    @TempDir
    Path tree;

    private Task task() throws IOException {
        ApiContract root = new ApiContract(UUID.randomUUID(), "Root", "the root", "Root", "com.x.Root",
            List.of("@DataModel Root;"));
        Task t = new Task(UUID.randomUUID(), 1, "Do it", "Do it.", Set.of("src/main/java/com/x"),
            Set.of(), List.of(), "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.READY);
        t.setDeliveredContracts(List.of(root));
        Files.createDirectories(tree.resolve("src/main/java/com/x"));
        return t;
    }

    private void write(String text) throws IOException {
        Files.writeString(tree.resolve("src/main/java/com/x/Root.java"), text);
    }

    @Test
    void aTypeTheTaskLeftAsItWasIsNotHeldAgainstTheCandidate() throws IOException {
        Task t = task();
        write(AT_START);
        assertThat(SwarmEngineImpl.contractShortfall(t, tree,
            path -> path.equals("src/main/java/com/x/Root.java") ? AT_START : null)).isNull();
    }

    @Test
    void aTypeTheTaskChangedIsStillHeldAndSaidToExistAlready() throws IOException {
        Task t = task();
        write("package com.x;\n@Vetoed\npublic class Root { int x; }\n");
        String reason = SwarmEngineImpl.contractShortfall(t, tree,
            path -> path.equals("src/main/java/com/x/Root.java") ? AT_START : null);
        assertThat(reason).contains("does not carry @DataModel")
            .contains("already existed").doesNotContain("told to create");
    }

    @Test
    void withoutAStartCommitNothingIsExcused() throws IOException {
        Task t = task();
        write(AT_START);
        assertThat(SwarmEngineImpl.contractShortfall(t, tree)).contains("does not carry @DataModel");
    }
}
