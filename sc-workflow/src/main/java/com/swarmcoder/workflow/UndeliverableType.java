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
import com.swarmcoder.verify.TypeDeliverability;

import java.util.ArrayList;
import java.util.List;

/**
 * A wave's tests do not compile because a type is missing. Which of the two things is that?
 *
 * <ul>
 *   <li><b>The healthy one.</b> The type is what this wave is about to write. A test written
 *       before its code cannot compile, and that is the whole idea.</li>
 *   <li><b>The one that costs a run.</b> The type is not a contract anybody delivers and not
 *       inside the write set of any task that has yet to run. Nobody will ever create it, so this
 *       wave's swarm will fail, its repair wave will fail identically, and every candidate will be
 *       told it broke a tree it never touched.</li>
 * </ul>
 *
 * <p>The two look the same from every gate downstream — "does not compile" — which is why run 13
 * spent three waves reaching one and then blamed the workers. Here they are separable, because at
 * wave time the plan is known: who delivers what, and who is still allowed to write where.
 *
 * <p><b>It fails open, everywhere.</b> No missing types parsed, an unreadable write set, a task
 * that owns a whole source root — all of those mean the question could not be settled, and an
 * unsettled question dispatches the wave exactly as before. It stops a run only when it can say,
 * concretely, that a named type is in nobody's job.
 */
public final class UndeliverableType {

    private UndeliverableType() {}

    /**
     * The reason this wave must park, or null to dispatch it.
     *
     * @param task      the task whose tests would not compile
     * @param remaining every task that has not run yet — this wave and the waves after it. A task
     *                  in an EARLIER wave cannot help: it already ran, and what it delivered is
     *                  in the tree that just failed to compile.
     * @param missing   the fully-qualified types the compiler said do not exist
     */
    public static String park(Task task, List<Task> remaining, List<String> missing) {
        if (task == null || missing == null || missing.isEmpty()) {
            return null;
        }
        List<Task> stillToRun = remaining == null ? List.of() : remaining;
        // The same classifier RedChecker uses at TEST_AUTHORING (com.swarmcoder.verify.
        // TypeDeliverability) - one implementation of "who may still create this", asked here at
        // wave time and there before a single wave has run.
        List<String> undeliverable = TypeDeliverability.undeliverable(missing, stillToRun);
        if (undeliverable.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Task '").append(task.title()).append("': its tests need ");
        for (int i = 0; i < undeliverable.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('`').append(undeliverable.get(i)).append('`');
        }
        sb.append(", which no task delivers and '")
            .append(task.title()).append("' cannot create — the plan or the tests are wrong.\n\n")
            .append("This task may only write ").append(sorted(task.writeSet()))
            .append(", and no task that has yet to run promises ")
            .append(undeliverable.size() == 1 ? "that type" : "those types")
            .append(". The waves in front of this one have already been built and integrated, so "
                + "nothing further along will create ")
            .append(undeliverable.size() == 1 ? "it" : "them")
            .append(" either. Dispatching a swarm now would fail every candidate for a tree that "
                + "none of them broke, and the repair wave after it would fail in exactly the same "
                + "way.\n\nDecide which side is wrong: add the missing type to the design as a "
                + "contract, so a task is made to deliver it, or correct the acceptance tests to "
                + "use the types this plan actually builds. Then resume the run.");
        return sb.toString();
    }

    private static String sorted(java.util.Set<String> writeSet) {
        if (writeSet == null || writeSet.isEmpty()) {
            return "nothing at all";
        }
        List<String> paths = new ArrayList<>(writeSet);
        java.util.Collections.sort(paths);
        return String.join(", ", paths);
    }
}
