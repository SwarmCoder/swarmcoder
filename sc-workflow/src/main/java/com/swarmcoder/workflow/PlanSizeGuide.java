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

import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.verify.BuildLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Says, as a proposal and never as an objection, which task of a plan delivers types in more
 * than one part of the build.
 *
 * <h2>The runs this is measured on</h2>
 *
 * <p>Live run 103 (section 76) ended with two tasks: one delivered the model type, the service
 * interface, the store's types, the commands, the queries and the implementation - ten files in
 * two modules - and one the screen. Runs 101 and 102 built the same layers of a smaller story
 * as three tasks (model, interface, server). Per line the winner delivered, the large task cost
 * about as much (45 output tokens a line against 37). What it cost was the candidate: one of its
 * two failed verification for one wrong import in one of ten files, and its 8,933 output tokens
 * went with it, where all six candidates of the three small tasks passed. The large task was
 * also the whole first wave, so nothing was built beside it.
 *
 * <p>That does not justify refusing a plan, and a size nobody can state as a fact is not a
 * check. What the build does state is where its parts are: a task whose types lie in two
 * production source roots is two tasks the compiler already keeps apart, with a dependency
 * between them that {@link TypeDependencyOrder} adds from the contracts. So the planner is
 * told where that line runs, with the types on each side, and may leave the plan as it is.
 *
 * <p>Read from each task's computed reservation ({@link ComputedReservation}) and the build's
 * source roots. No wording is read.
 */
final class PlanSizeGuide {

    private PlanSizeGuide() {
    }

    /** One proposal for each task whose computed files lie in more than one source root. */
    static List<String> proposals(TaskGraph plan, BuildLayout.Layout layout) {
        List<String> lines = new ArrayList<>();
        if (plan == null || plan.tasks() == null) {
            return lines;
        }
        List<String> roots = ComputedReservation.javaSourceRoots(layout);
        if (roots.size() < 2) {
            return lines;
        }
        for (Task task : plan.tasks()) {
            if (task == null || task.computedReservation() == null) {
                continue;
            }
            Map<String, List<String>> byRoot = new LinkedHashMap<>();
            for (String path : task.computedReservation()) {
                if (path == null || !path.endsWith(".java")) {
                    continue;
                }
                for (String root : roots) {
                    if (path.startsWith(root + "/")) {
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        byRoot.computeIfAbsent(root, r -> new ArrayList<>())
                            .add(name.substring(0, name.length() - ".java".length()));
                    }
                }
            }
            if (byRoot.size() < 2) {
                continue;
            }
            StringBuilder parts = new StringBuilder();
            byRoot.forEach((root, types) -> parts.append(parts.length() == 0 ? "" : "; ")
                .append(root).append(": ").append(String.join(", ", types)));
            lines.add("'" + task.title() + "' delivers types in " + byRoot.size()
                + " parts of the build (" + parts + "). One task for each part is the smaller "
                + "split: each part is then compiled, checked and, if it fails, repaired on "
                + "its own, and the order between them is added from the types they use. "
                + "This is a proposal, not an objection");
        }
        return lines;
    }
}
