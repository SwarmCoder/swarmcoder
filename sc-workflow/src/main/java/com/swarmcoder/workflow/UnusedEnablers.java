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
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * On the LAST plan attempt, drops a task that claims no check and that nothing claiming a check
 * builds on — instead of parking the run over it — when doing so is provably safe.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 40, 2026-09-26 (DeepSeek V4 Flash, the Bookshelf demo, story "Books and ratings
 * persist across restart", one check). The plan put the check on "Implement BookshelfStore with
 * EclipseStore persistence" — the acceptance test drives the store — and also contained
 * "Implement BookshelfServiceImpl delegating to BookshelfStore", which claimed no check and which
 * no other task depended on. {@link TaskGraphValidator#checkEnablersAreUsed} rejected it; attempt
 * 2 lost two contracts instead; attempt 3 brought the orphan back word for word; the run parked in
 * PLAN, and the operator's only option was to build again and hope.
 *
 * <h2>Why dropping is right, and not parking</h2>
 *
 * <p>The rule exists to stop work no check will ever prove. A task nothing checked depends on is
 * verified by nothing but "it compiled" — its candidates cannot be told apart, its judge has no
 * test to read, and its result is merged unproven. Dropping it achieves exactly what the rule is
 * for, and when every check of the story is already claimed by other tasks, the plan that remains
 * is still one that can deliver everything the story promises. Parking instead spends the whole
 * run to get, at best, the same plan without that task on a later try.
 *
 * <p><b>The cost, stated:</b> the architect may have meant the task for the app to work end to end
 * — in run 40 the service implementation is what the browser client would call. But the story's
 * checks are the definition of done that was agreed; a behaviour no check covers is a gap in the
 * story, and silently building unproven code does not close that gap, it hides it. So the drop is
 * logged in words an operator can act on: which task, why it was safe, and that a check saying so
 * is what would make the work part of the story.
 *
 * <h2>Why only on the last attempt</h2>
 *
 * <p>The objection now says how to fix it ({@link TaskGraphValidator#checkEnablersAreUsed(TaskGraph,
 * StoryScope)}), including moving a check onto the task — which may be the better plan, and is a
 * judgement only the planner can make. It gets two tries with that information. This is the
 * fallback when those are spent, never a shortcut that pre-empts them.
 *
 * <h2>When it does NOT drop</h2>
 *
 * <ul>
 *   <li><b>Anything else is wrong with the plan.</b> Every violation must be one of these
 *       objections; a plan with another fault parks as before, since dropping a task would not
 *       make it acceptable and would make the park brief lie about the plan.</li>
 *   <li><b>A check is unclaimed.</b> Then "the story's checks are all claimed without it" is false.
 *       (Coverage would also have objected, so this is belt and braces.)</li>
 *   <li><b>The task delivers a contract that names a type.</b> The acceptance tests are written
 *       against the design's contracts; dropping the task that delivers one would leave a test that
 *       cannot compile.</li>
 *   <li><b>A task that is kept uses a type the dropped task writes</b> — read the same way {@link
 *       TypeDependencyOrder} reads it (contracts' members and instructions' wording). Its code would
 *       then not compile.</li>
 * </ul>
 *
 * <p>The caller re-validates what this returns; the drop only stands if the smaller plan passes
 * every check.
 */
final class UnusedEnablers {

    /**
     * @param graph   the plan without the dropped tasks; null when nothing was dropped
     * @param dropped one log line per dropped task
     * @param refusal why nothing was dropped although there were unused enablers; null when there
     *                were none, or when the drop happened
     */
    record Outcome(TaskGraph graph, List<String> dropped, String refusal) {
        boolean droppedAny() {
            return graph != null;
        }
    }

    private UnusedEnablers() {}

    /**
     * @param validated the plan exactly as {@link TaskGraphValidator#validate} left it (it adds
     *                  edges in place), so the unused set read here is the one it objected to
     * @param verdict   that validation's verdict
     */
    static Outcome dropIfSafe(TaskGraph validated, TaskGraphValidator.Verdict verdict,
                              StoryScope scope, DesignDocument design,
                              TaskGraphValidator validator) {
        if (validated == null || validated.tasks() == null || scope == null || scope.isEmpty()) {
            return new Outcome(null, List.of(), null);
        }
        List<Task> unused = validator.unusedEnablers(validated, design);
        if (unused.isEmpty()) {
            return new Outcome(null, List.of(), null);
        }
        Set<String> enablerObjections =
            new HashSet<>(validator.checkEnablersAreUsed(validated, scope, design));
        List<String> others = verdict.violations().stream()
            .filter(v -> !enablerObjections.contains(v)).toList();
        if (!others.isEmpty()) {
            return new Outcome(null, List.of(), "not dropping the unused task(s) " + titles(unused)
                + ": the plan has other problems too, and dropping them would not make it "
                + "acceptable");
        }
        List<Task> checked = validated.tasks().stream()
            .filter(t -> t.criterionIds() != null && !t.criterionIds().isEmpty()).toList();
        if (!TaskGraphValidator.everyCheckClaimed(checked, scope)) {
            return new Outcome(null, List.of(), "not dropping the unused task(s) " + titles(unused)
                + ": a check of the story is not claimed by any other task");
        }
        Set<UUID> dropIds = new HashSet<>();
        for (Task task : unused) {
            dropIds.add(task.id());
        }
        for (Task task : unused) {
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract != null && contract.namesAType()) {
                    return new Outcome(null, List.of(), "not dropping '" + task.title() + "': it "
                        + "delivers the contract " + contract.typeName().strip() + ", which the "
                        + "acceptance tests may be written against");
                }
            }
        }
        for (TypeDependencyOrder.Use use
                : TypeDependencyOrder.uses(validated.tasks(), design, new ArrayList<>())) {
            if (dropIds.contains(use.writer().id()) && !dropIds.contains(use.user().id())) {
                return new Outcome(null, List.of(), "not dropping '" + use.writer().title()
                    + "': task '" + use.user().title() + "' uses " + use.typeName()
                    + ", which it writes");
            }
        }

        List<Task> kept = new ArrayList<>();
        for (Task task : validated.tasks()) {
            if (!dropIds.contains(task.id())) {
                kept.add(task);
            }
        }
        List<TaskEdge> keptEdges = new ArrayList<>();
        for (TaskEdge edge : validated.dependencies() == null
                ? List.<TaskEdge>of() : validated.dependencies()) {
            if (edge != null && !dropIds.contains(edge.from()) && !dropIds.contains(edge.to())) {
                keptEdges.add(edge);
            }
        }
        TaskGraph smaller = new TaskGraph(validated.id(), validated.revision(),
            validated.designId(), kept, keptEdges);
        String claimedBy = TaskGraphValidator.whoClaimsWhat(checked, scope);
        List<String> lines = new ArrayList<>();
        for (Task task : unused) {
            lines.add("dropped task '" + task.title() + "': it claims no check and no task that "
                + "claims one builds on it, and the planner did not settle that in its attempts. "
                + "Every check of the story is claimed without it (" + claimedBy + "), and no "
                + "remaining task uses a type it writes, so no check loses anything. If the story "
                + "needs that work — for the app to work end to end, say — the story is missing a "
                + "check that proves it: add one and build again");
        }
        return new Outcome(smaller, lines, null);
    }

    private static String titles(List<Task> tasks) {
        Set<String> names = new LinkedHashSet<>();
        for (Task task : tasks) {
            names.add("'" + task.title() + "'");
        }
        return String.join(", ", names);
    }
}
