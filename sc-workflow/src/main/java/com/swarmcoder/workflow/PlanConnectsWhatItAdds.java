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
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.knowledge.ReachableCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A plan must be able to connect what it adds to the application that is there.
 *
 * <h2>The runs this exists because of</h2>
 *
 * <p>Seven stories of one application, accepted one after another until 2026-10-05: every plan put
 * the story's screens into new files and gave no task an existing file of the browser client to
 * change. The workers did exactly that. The screens compiled, their tests passed, and no page of
 * the application ever opened them. The fault was in the plan, where it costs one objection; it
 * was found by the owner opening the application, after seven runs.
 *
 * <h2>The rule</h2>
 *
 * <p>Read from the object graph of the tree the run starts from ({@link ReachableCode}), with no
 * model: a plan that writes a new production source file, while no task may change anything the
 * application already reaches from which that file could be used, is sent back with the files
 * named and the places the application is entered today. A new type its task says carries one of
 * the project's own discovery annotations is found by the framework and is not counted. Nor is a
 * new file that another new file of the plan uses, when that one is connected or found by the
 * framework ({@link #usedBy}, live run 103). A tree
 * whose graph cannot be built, or that has no entry point, is not judged.
 *
 * <p>{@code -Dswarmcoder.verify.unreachableAddedCode=off} switches it off, with the checks of the
 * delivered code.
 */
final class PlanConnectsWhatItAdds {

    private PlanConnectsWhatItAdds() {
    }

    /**
     * The objection, or null.
     *
     * @param startTree the graph of the tree the run starts from; null when there is none
     * @param repoPath  that tree, to tell a new file from one that is there
     */
    static String objection(TaskGraph plan, ReachableCode.Graph startTree, Path repoPath) {
        return objection(plan, startTree, repoPath, null);
    }

    /**
     * @param design the design the plan decomposes; null reads the tasks alone. What "its task
     *               says" about a new type used to be the planner's how-to prose. Since section
     *               73 the planner writes none: that a type carries a discovery annotation is
     *               said by the contract or by a finding the architect kept about it, so those
     *               findings are read here with the contracts they are about.
     */
    static String objection(TaskGraph plan, ReachableCode.Graph startTree, Path repoPath,
                            com.swarmcoder.domain.DesignDocument design) {
        if (!ReachableCode.enabled() || plan == null || plan.tasks() == null || startTree == null
                || repoPath == null) {
            return null;
        }
        List<ReachableCode.PlannedTask> tasks = new ArrayList<>();
        for (Task task : plan.tasks()) {
            StringBuilder told = new StringBuilder(task.instructions() == null ? ""
                : task.instructions());
            for (ApiContract contract : task.deliveredContracts()) {
                told.append('\n').append(contract.description() == null ? ""
                    : contract.description());
                told.append('\n').append(contract.signatureSketch() == null ? ""
                    : contract.signatureSketch());
                if (contract.members() != null) {
                    contract.members().forEach(member -> told.append('\n').append(member));
                }
                if (design != null) {
                    for (com.swarmcoder.domain.DesignFinding finding : design.findings()) {
                        if (finding != null && finding.isAbout(contract)) {
                            told.append('\n').append(finding.note() == null ? ""
                                : finding.note());
                            told.append('\n').append(finding.snippet() == null ? ""
                                : finding.snippet());
                        }
                    }
                }
            }
            tasks.add(new ReachableCode.PlannedTask(task.title(), task.writeSet(),
                told.toString()));
        }
        return ReachableCode.planObjection(startTree, tasks, path -> {
            try {
                return Files.isRegularFile(repoPath.resolve(path));
            } catch (RuntimeException unreadable) {
                return true; // a path that cannot be looked at is not called new
            }
        }, usedBy(plan, design));
    }

    /**
     * Which file of the plan is used by which: the file of a type a task delivers, to the files
     * of the types whose contracts name it in their Java, and to the files of the types of a
     * task whose read set names it. Read from the plan's contracts and paths; no wording.
     * See {@link ReachableCode#planObjection(ReachableCode.Graph, List,
     * java.util.function.Predicate, Map)} for the run this is for.
     */
    static Map<String, Set<String>> usedBy(TaskGraph plan,
                                           com.swarmcoder.domain.DesignDocument design) {
        Set<String> known = NestedContracts.typeNames(plan, design);
        // each delivered type: its file in its task's write set, and the contract
        Map<ApiContract, String> fileOf = new LinkedHashMap<>();
        Map<Task, Set<String>> filesOf = new LinkedHashMap<>();
        for (Task task : plan.tasks()) {
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract == null || !contract.namesAType()) {
                    continue;
                }
                String outer = NestedContracts.outermost(contract.typeName().strip(),
                    known::contains);
                String wanted = "/" + outer.replace('.', '/') + ".java";
                for (String entry : task.writeSet() == null ? Set.<String>of() : task.writeSet()) {
                    String path = entry == null ? "" : entry.strip().replace((char) 92, '/');
                    if (("/" + path).endsWith(wanted)) {
                        fileOf.put(contract, path);
                        filesOf.computeIfAbsent(task, t -> new LinkedHashSet<>()).add(path);
                    }
                }
            }
        }
        Map<String, Set<String>> usedBy = new LinkedHashMap<>();
        for (Map.Entry<ApiContract, String> used : fileOf.entrySet()) {
            String simple = used.getKey().simpleTypeName();
            for (Map.Entry<ApiContract, String> user : fileOf.entrySet()) {
                if (!user.getValue().equals(used.getValue())
                        && TypeDependencyOrder.namesInItsJava(user.getKey(), simple)) {
                    usedBy.computeIfAbsent(used.getValue(), k -> new LinkedHashSet<>())
                        .add(user.getValue());
                }
            }
            for (Task task : plan.tasks()) {
                for (String read : task.readSet() == null ? Set.<String>of() : task.readSet()) {
                    String path = read == null ? "" : read.strip().replace((char) 92, '/');
                    if (path.equals(used.getValue())) {
                        for (String file : filesOf.getOrDefault(task, Set.of())) {
                            if (!file.equals(used.getValue())) {
                                usedBy.computeIfAbsent(used.getValue(),
                                    k -> new LinkedHashSet<>()).add(file);
                            }
                        }
                    }
                }
            }
        }
        return usedBy;
    }
}
