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
package com.swarmcoder.app;

import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.workflow.StoryScope;
import com.swarmcoder.workflow.TaskGraphValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * The predicate behind {@code EndToEndLoopTest}'s link 7 (L_TASKS: "the plan's tasks reference
 * the agreed checks") — extracted so it can be exercised without running the live chain.
 *
 * <p>The link used to require every task to carry a check. That rejects the product's own
 * enabler shape: a task that delivers shared code (a data model, a service interface) for later
 * tasks to build on, and claims no check of its own — {@link TaskGraphValidator} accepts it,
 * {@code EmptyCheckIsNotAPassTest} documents that a task claiming no checks runs none, and
 * {@code WaveIntegrator} exists so a later task can build on an earlier winner. A run on
 * 2026-09-02 produced exactly that: "Implement Book and Rating data models" with no checks,
 * followed by two dependent tasks that did have them — and link 7 stopped the harness for a
 * reason that was not a defect.
 *
 * <p>What actually matters: no agreed check is left with nobody to write a test for it, and no
 * enabler task was planned for nothing. Both halves are {@link TaskGraphValidator}'s own rules
 * now — {@link TaskGraphValidator#checkCoverage} and {@link TaskGraphValidator#checkEnablersAreUsed}
 * — called rather than reimplemented, so this and the live PLAN-stage gate can never disagree
 * about what "claimed" or "an enabler nothing builds on" means. The second half used to be
 * reimplemented here alone (before 2026-09-03, run {@code ede2068b}: a four-task plan with
 * exactly that dead-enabler shape was accepted at PLAN and only this test helper caught it, after
 * a wave had already been spent on it); it is a live PLAN-stage rule now, not just a harness one.
 */
final class PlanTaskLinkageCheck {

    record Verdict(boolean ok, String detail) {}

    private PlanTaskLinkageCheck() {}

    static Verdict evaluate(TaskGraph graph, StoryScope scope) {
        return evaluate(graph, scope, BrowserOnlyCode.Survey.NONE);
    }

    /**
     * The same predicate, told which modules run only in a browser — exactly what the live
     * PLAN-stage validator is told. Harness run 50, 2026-09-30: the product accepted a plan whose
     * browser UI task claims no check (such a task is proved by compiling, never by a test), the
     * story was delivered, and this link still failed it because it built its validator without
     * the survey.
     */
    static Verdict evaluate(TaskGraph graph, StoryScope scope, BrowserOnlyCode.Survey browserOnly) {
        TaskGraphValidator validator = new TaskGraphValidator(browserOnly);
        List<String> problems = new ArrayList<>(validator.checkCoverage(graph, scope));
        problems.addAll(validator.checkEnablersAreUsed(graph));

        if (problems.isEmpty()) {
            return new Verdict(true, graph.tasks().size() + " task(s): every agreed check is "
                + "claimed by a task, and every task with no check of its own is depended on by "
                + "a task that has one");
        }
        return new Verdict(false, String.join("; ", problems));
    }
}
