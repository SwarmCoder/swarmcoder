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
package com.swarmcoder.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Kahn levelling: the one implementation of "which of these can run at the same time, and in what
 * order". Nodes with no unfinished prerequisite form wave 0, what they unblock forms wave 1, and so
 * on.
 *
 * <p>Extracted from {@code SwarmEngineImpl.topologicalWaves}, which now delegates here. It was the
 * only scheduler in the product and it worked on {@code Task}; scheduling STORIES needs exactly the
 * same levelling over a different node type, and the console module — which owns the backlog — is
 * not allowed to depend on the swarm module. Writing a second Kahn loop was the alternative, and
 * two schedulers that must agree and cannot be compared is how the two halves of a pipeline start
 * disagreeing about what is ready.
 *
 * <p>A cycle cannot hang this. Validation is supposed to reject one before it gets here, but if one
 * slips through, the nodes that are left are emitted as one final wave rather than looping for
 * ever — the caller is told through {@link Result#stalled()}.
 */
public final class Waves {

    private Waves() {}

    /**
     * @param waves   the levels, in order; every node appears exactly once
     * @param stalled true when a cycle was found and the remaining nodes were flushed into the last
     *                wave — the schedule is then a best effort, not a valid order
     */
    @NotReachableFromStoreRoot("returned by of()/hasCycle() and read by the caller in the same "
        + "call — the levelling is applied immediately, never assigned to a field")
    public record Result<T>(List<List<T>> waves, boolean stalled) {}

    /** One dependency edge: {@code to} may not start until {@code from} has finished. */
    @NotReachableFromStoreRoot("a caller-built input list passed into of()/hasCycle() for one "
        + "call; never assigned to a field")
    public record Edge(UUID from, UUID to) {}

    /**
     * Levels {@code nodes} by the edges between them. Edges naming a node that is not in the list
     * are ignored — validation is a separate question, and a scheduler that throws on unknown data
     * is a scheduler that stops the night.
     */
    public static <T> Result<T> of(List<T> nodes, Function<T, UUID> idOf, List<Edge> edges) {
        Map<UUID, T> byId = new LinkedHashMap<>();
        Map<UUID, Integer> inDegree = new LinkedHashMap<>();
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        for (T node : nodes == null ? List.<T>of() : nodes) {
            UUID id = idOf.apply(node);
            if (id == null || byId.containsKey(id)) {
                continue;
            }
            byId.put(id, node);
            inDegree.put(id, 0);
            adjacency.put(id, new ArrayList<>());
        }
        for (Edge edge : edges == null ? List.<Edge>of() : edges) {
            if (edge == null || !adjacency.containsKey(edge.from()) || !inDegree.containsKey(edge.to())) {
                continue;
            }
            adjacency.get(edge.from()).add(edge.to());
            inDegree.merge(edge.to(), 1, Integer::sum);
        }

        List<List<T>> waves = new ArrayList<>();
        Set<UUID> emitted = new HashSet<>();
        while (emitted.size() < byId.size()) {
            List<T> wave = new ArrayList<>();
            List<UUID> ids = new ArrayList<>();
            for (Map.Entry<UUID, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() == 0 && !emitted.contains(entry.getKey())) {
                    ids.add(entry.getKey());
                    wave.add(byId.get(entry.getKey()));
                }
            }
            if (wave.isEmpty()) {
                List<T> rest = new ArrayList<>();
                for (Map.Entry<UUID, T> entry : byId.entrySet()) {
                    if (!emitted.contains(entry.getKey())) {
                        rest.add(entry.getValue());
                    }
                }
                waves.add(rest);
                return new Result<>(List.copyOf(waves), true);
            }
            waves.add(wave);
            emitted.addAll(ids);
            for (UUID id : ids) {
                for (UUID next : adjacency.getOrDefault(id, List.of())) {
                    inDegree.merge(next, -1, Integer::sum);
                }
            }
        }
        return new Result<>(List.copyOf(waves), false);
    }

    /** True when the edges form a cycle among these nodes. */
    public static <T> boolean hasCycle(List<T> nodes, Function<T, UUID> idOf, List<Edge> edges) {
        return of(nodes, idOf, edges).stalled();
    }
}
