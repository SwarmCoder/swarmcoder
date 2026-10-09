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
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A contract for a type declared inside another type belongs to the file, and so to the task, of
 * the type it is declared in.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 103 (section 76). A design had a class of write commands with three nested classes
 * and a class of queries with one, each nested class a contract of its own - the design check
 * asks for that, because a nested class written as a member is not a declaration it can check.
 * Two things then went wrong in the plan, neither of them a decision:
 *
 * <ul>
 *   <li>the planner listed the outer type in a task and not the nested ones. Six of thirteen
 *       plan drafts came back with "no task delivers the contract" four times over;</li>
 *   <li>the file computed for a nested type was a file of its own in a folder named after the
 *       outer type, which is no place a compiler looks: four files nobody would write were
 *       reserved, and named to the planner as new files the application could not reach.</li>
 * </ul>
 *
 * <p>Read from the names alone: a type name whose qualifier is itself a type of the design or
 * of the project is nested in it. No wording is read and no spelling is judged.
 */
final class NestedContracts {

    private NestedContracts() {
    }

    /**
     * The outermost type {@code typeName} is declared in; {@code typeName} itself when the name
     * before its last dot is not a type.
     */
    static String outermost(String typeName, Predicate<String> isType) {
        String name = typeName == null ? "" : typeName.strip();
        while (true) {
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                return name;
            }
            String outer = name.substring(0, dot);
            if (!isType.test(outer)) {
                return name;
            }
            name = outer;
        }
    }

    /** The type names the design's contracts and the plan's tasks give. */
    static Set<String> typeNames(TaskGraph graph, DesignDocument design) {
        Set<String> names = new HashSet<>();
        if (design != null && design.contracts() != null) {
            for (ApiContract contract : design.contracts()) {
                if (contract != null && contract.namesAType()) {
                    names.add(contract.typeName().strip());
                }
            }
        }
        if (graph != null && graph.tasks() != null) {
            for (Task task : graph.tasks()) {
                for (ApiContract contract : task == null ? List.<ApiContract>of()
                        : task.deliveredContracts()) {
                    if (contract != null && contract.namesAType()) {
                        names.add(contract.typeName().strip());
                    }
                }
            }
        }
        return names;
    }

    /**
     * Gives every contract of the design that no task delivers, and that is nested in a type
     * exactly one task delivers, to that task. In place.
     *
     * @return one line for each contract given, for the log and for the planner's note
     */
    static List<String> assign(TaskGraph graph, DesignDocument design) {
        List<String> lines = new ArrayList<>();
        if (graph == null || graph.tasks() == null || design == null
                || design.contracts() == null) {
            return lines;
        }
        Set<String> known = typeNames(graph, design);
        for (ApiContract contract : design.contracts()) {
            if (contract == null || !contract.namesAType()) {
                continue;
            }
            String name = contract.typeName().strip();
            String outer = outermost(name, known::contains);
            if (outer.equals(name) || !deliverers(graph, name).isEmpty()) {
                continue;
            }
            List<Task> writers = deliverers(graph, outer);
            if (writers.size() != 1) {
                continue; // nobody writes the outer type, or two do: the plan's own objection
            }
            Task writer = writers.get(0);
            List<ApiContract> delivered = new ArrayList<>(writer.deliveredContracts());
            delivered.add(contract);
            writer.setDeliveredContracts(delivered);
            lines.add("'" + writer.title() + "' also delivers " + name + ": it is declared "
                + "inside " + outer + ", which that task writes, and one file is one task's");
        }
        return lines;
    }

    private static List<Task> deliverers(TaskGraph graph, String typeName) {
        List<Task> tasks = new ArrayList<>();
        for (Task task : graph.tasks()) {
            if (task == null) {
                continue;
            }
            for (ApiContract claimed : task.deliveredContracts()) {
                if (claimed != null && claimed.namesAType()
                        && typeName.equalsIgnoreCase(claimed.typeName().strip())) {
                    tasks.add(task);
                    break;
                }
            }
        }
        return tasks;
    }
}
