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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TopologicalWavesTest {

    private static Task task(String title) {
        return new Task(UUID.randomUUID(), 1, title, "do " + title, Set.of(), Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    @Test
    void independentTasksShareAWaveDependentsFollow() {
        Task a = task("a");
        Task b = task("b");
        Task c = task("c"); // depends on a
        Task d = task("d"); // depends on c
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(a, b, c, d),
            List.of(new TaskEdge(a.id(), c.id()), new TaskEdge(c.id(), d.id())));

        List<List<Task>> waves = SwarmEngineImpl.topologicalWaves(graph);

        assertThat(waves).hasSize(3);
        assertThat(waves.get(0)).extracting(Task::title).containsExactlyInAnyOrder("a", "b");
        assertThat(waves.get(1)).extracting(Task::title).containsExactly("c");
        assertThat(waves.get(2)).extracting(Task::title).containsExactly("d");
    }

    @Test
    void edgelessGraphIsOneWave() {
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(task("x"), task("y")), List.of());

        assertThat(SwarmEngineImpl.topologicalWaves(graph)).hasSize(1);
    }
}
