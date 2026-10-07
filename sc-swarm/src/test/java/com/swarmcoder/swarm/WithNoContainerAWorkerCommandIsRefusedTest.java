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

import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An engine with no container refuses to run a command a model chose. This class does not carry
 * {@code @ModelCodeOnThisPc}, so nothing here may reach a process on this PC.
 */
class WithNoContainerAWorkerCommandIsRefusedTest {

    @TempDir
    Path worktree;

    @Test
    void theToolboxRefusesTheCommandAndNothingRuns() {
        assertThat(HostExecution.allowedBy()).isEmpty();
        Task task = new Task(UUID.randomUUID(), 1, "Write a file", "write it",
            Set.of("src/main"), Set.of("src"), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 500000, 12),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of("minimal-diff")), TaskState.READY);
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task);

        String answer = toolbox.exec("echo ran > proof.txt");

        assertThat(answer).startsWith("error: Refused to run a worker's command on this PC");
        assertThat(Files.exists(worktree.resolve("proof.txt")))
            .as("the command never reached a shell").isFalse();
    }

    @Test
    void attachingWithNoSandboxIsARefusalNotThisPc() {
        SandboxAttach.Attachment attachment =
            SandboxAttach.attach(null, worktree.toString(), "", "Worker 0 (a task)");

        assertThat(attachment.refused()).isTrue();
        assertThat(attachment.localAllowed()).isFalse();
        assertThat(attachment.refusal()).contains("only inside a container");
    }
}
