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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 74: a repair worker implemented the new methods in a file of the next wave's task and
 * created its own copies of classes other tasks were writing; it was selected, and the delivery
 * holds dead duplicates. Source changed outside the write set now fails the candidate.
 */
class ACandidateThatChangedSourceOutsideItsWriteSetDoesNotPassTest {

    private static final String API = "shared/src/main/java/org/example/shop/OrderService.java";
    private static final String IMPL =
        "server/src/main/java/org/example/shop/server/OrderServiceImpl.java";
    private static final String COPY =
        "server/src/main/java/org/example/shop/server/CancelCommand.java";

    private static Task task(String... writeSet) {
        return new Task(UUID.randomUUID(), 1, "Extend OrderService", "do it", Set.of(writeSet),
            Set.of(), List.of(), null, null, null,
            new SwarmPolicy(2, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static CandidateSolution candidate(Task task, String diff, String... outside) {
        CandidateSolution candidate = new CandidateSolution(UUID.randomUUID(), task.id(), 110,
            "b", null, diff, null, null, null, CandidateState.SURVIVED, null);
        candidate.setOutOfWriteSetPaths(List.of(outside));
        return candidate;
    }

    private static String diffOf(String... paths) {
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                .append("--- a/").append(path).append("\n+++ b/").append(path).append("\n+x\n");
        }
        return sb.toString();
    }

    @Test
    void sourceOutsideTheWriteSetIsAnObjectionThatNamesTheFiles() {
        Task task = task(API);
        String objection = SourceOutsideWriteSet.objection(task,
            candidate(task, diffOf(API, IMPL, COPY), IMPL, COPY));

        assertThat(objection).contains("2 source files outside the task's write set")
            .contains(IMPL).contains(COPY).contains("never selected");
    }

    @Test
    void aWriteSetWidenedByTheProductCoversTheFile() {
        Task task = task(API);
        task.setWriteSet(Set.of(API, IMPL));
        task.setSiblingRepairPaths(List.of(IMPL));

        assertThat(SourceOutsideWriteSet.objection(task,
            candidate(task, diffOf(API, IMPL), IMPL))).isNull();
    }

    @Test
    void whatIsNotASourceChangeIsLeftAsItWas() {
        Task task = task(API);

        assertThat(SourceOutsideWriteSet.objection(task,
            candidate(task, diffOf(API, "notes.txt"), "notes.txt")))
            .as("a non-source stray is dropped at integration, as before").isNull();
        assertThat(SourceOutsideWriteSet.objection(task,
            candidate(task, diffOf(API, "server/pom.xml"), "server/pom.xml")))
            .as("a build file is left to the judge").isNull();
        assertThat(SourceOutsideWriteSet.objection(task, candidate(task, diffOf(API), IMPL)))
            .as("written and put back: not part of the change").isNull();
        assertThat(SourceOutsideWriteSet.objection(task, candidate(task, diffOf(API))))
            .isNull();
        Task open = task();
        assertThat(SourceOutsideWriteSet.objection(open, candidate(open, diffOf(IMPL), IMPL)))
            .as("an empty write set is unrestricted").isNull();
    }
}
