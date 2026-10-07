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
package com.swarmcoder.console.ui;

import com.swarmcoder.console.api.RunGraphDto;
import com.zeroz4j.signals.ValueSignal;

import java.util.HashMap;
import java.util.Map;

/** Run-graph signals: one snapshot signal per run, fed by the run-graph push topic. */
final class GraphStore {

    private static final Map<String, ValueSignal<RunGraphDto>> graphs = new HashMap<>();

    private GraphStore() {}

    static ValueSignal<RunGraphDto> graph(String runId) {
        return graphs.computeIfAbsent(runId, id -> new ValueSignal<>(null));
    }

    /** run-graph push: full snapshots, seq-ordered — drop stale frames. */
    static void onGraph(RunGraphDto snapshot) {
        ValueSignal<RunGraphDto> signal = graph(snapshot.getRunId());
        RunGraphDto current = signal.get();
        if (current == null || current.getSeq() <= snapshot.getSeq()) {
            signal.set(snapshot);
        }
    }
}
