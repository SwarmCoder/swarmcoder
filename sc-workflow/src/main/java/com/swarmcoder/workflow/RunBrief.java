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

import com.swarmcoder.domain.WorkflowKind;

/**
 * What the architect, the planner and (through the tasks they write) the test author are told about
 * the KIND of work, on top of the operator's goal.
 *
 * <p>This class is the whole difference between a bugfix run, a refactor run and a feature run.
 * They used to be three separate "workflow" classes; two of them did nothing at all. Everything
 * that makes a delivery real here — the design, the review, the plan, acceptance tests written
 * first, the red-check that requires them to fail, N workers, a real build, the judge, the merge
 * audit — is identical for all three, so duplicating it per kind bought nothing and cost the two
 * kinds that were duplicated badly.
 *
 * <p>The briefs are deliberately about EVIDENCE rather than tone. A bugfix's brief exists to make
 * the acceptance test a reproduction, because a reproduction that fails first and passes after is
 * the only thing that distinguishes a fixed bug from a claim. A refactor's exists to make the
 * acceptance test structural, because a refactor's promise is that behaviour did NOT change, and a
 * test asserting behaviour that already holds would be green before any work started — which the
 * red-check refuses, correctly.
 */
public final class RunBrief {

    private RunBrief() {
    }

    /** The goal as the design and planning roles should read it, given the run's kind. */
    public static String forKind(WorkflowKind kind, String goal) {
        return forKind(kind, goal, null);
    }

    /**
     * The goal, what the kind demands of the evidence, and — for a change against a codebase that
     * already exists — what that codebase actually contains around the change.
     *
     * <p>The neighbourhood is {@code ChangeNeighbourhood}'s rendered brief: the types the report
     * names, with the file and line each is declared on, what touches them, and the test classes
     * that already assert this behaviour today. It arrives as text rather than as an object so
     * this class stays free of {@code sc-knowledge}, and it is appended to the BUGFIX and
     * ENHANCEMENT briefs only.
     *
     * <p><b>REFACTOR is deliberately untouched</b> (design §6). A refactor's promise is that
     * behaviour did not change and its criteria are structural; the types an operator's sentence
     * happens to name are not what shapes that design.
     *
     * <p><b>A blank neighbourhood changes nothing at all.</b> A greenfield project's structural
     * index knows none of the types a goal string names, so it comes back empty and every brief is
     * byte-for-byte what it was before this parameter existed. That is design §2.1's fail-open rule
     * reaching the prompt: nothing in a run may block on the index having an answer.
     *
     * @param neighbourhood the rendered neighbourhood of the change, or null/blank when there is
     *                      none — a greenfield project, a target the index could not parse, or a
     *                      report naming nothing this repository declares
     */
    public static String forKind(WorkflowKind kind, String goal, String neighbourhood) {
        String stated = withNeighbourhood(kind, goal == null ? "" : goal, neighbourhood);
        return switch (kind == null ? WorkflowKind.GREENFIELD : kind) {
            case BUGFIX -> stated + """


                THIS IS A BUGFIX RUN, so the work is bounded by the fault and the evidence is a \
                reproduction.

                - Reproduce first. The acceptance test for this run must reproduce the reported \
                fault: it must FAIL against the code exactly as it stands today, and pass only once \
                the fault is gone. A run whose acceptance tests already pass is stopped before any \
                worker starts, so a test that merely describes correct behaviour that already holds \
                will halt this run.
                - Say what the fault is in terms of an observable difference between what happens \
                and what should happen, and make the acceptance criteria assert that difference.
                - Change what the fix needs and no more. Do not redesign surrounding code, do not \
                rename, do not take the opportunity to tidy. Every existing test must still pass.""";
            case REFACTOR -> stated + """


                THIS IS A REFACTOR RUN, so behaviour must not change and the evidence is that \
                nothing broke.

                - No behaviour change at all: same inputs, same outputs, same side effects, same \
                errors. Every existing test must still pass unaltered — a candidate that changes a \
                test to make it pass has failed this run.
                - The acceptance criteria are STRUCTURAL, not behavioural: they assert the shape the \
                code is being moved to (a type exists, a call site is gone, a duplicate has one home \
                left). They must therefore be false today and true afterwards; a criterion that \
                already holds cannot show the refactor happened, and a run whose acceptance tests \
                already pass is stopped before any worker starts.
                - Do not add features and do not fix bugs on the way. If something is wrong, say so \
                in the design and leave it wrong.""";
            default -> stated;
        };
    }

    /**
     * The heading the neighbourhood is rendered under, in the words the roles are asked to act on.
     *
     * <p>Public so a harness can assert that the text really reached a prompt rather than being a
     * string somebody built and dropped. The recurring failure this whole product measures against
     * is a stage that exits 0 having done nothing, and a brief that was assembled and never sent is
     * that failure wearing a knowledge channel's uniform.
     */
    public static final String NEIGHBOURHOOD_HEADING =
        "THE CODE THIS CHANGE IS ABOUT, READ OUT OF THE REPOSITORY ITSELF";

    /**
     * The goal with the neighbourhood under it, for the two kinds that are changes to code that
     * already exists.
     *
     * <p>The closing sentence is the one that matters and it is the finding design §2.3 turns on:
     * the test classes named here are where this project already asserts this behaviour, so they
     * are the nearest worked example there is. Naming them in the DESIGN brief is what makes the
     * architect state contracts for existing types, which is in turn what keeps the worker's
     * worked-example channel alive at all — {@code Librarian.workedExample} returns nothing when a
     * task delivers no contract.
     */
    private static String withNeighbourhood(WorkflowKind kind, String goal, String neighbourhood) {
        if (neighbourhood == null || neighbourhood.isBlank()) {
            return goal;
        }
        WorkflowKind resolved = kind == null ? WorkflowKind.GREENFIELD : kind;
        if (resolved != WorkflowKind.BUGFIX && resolved != WorkflowKind.ENHANCEMENT) {
            return goal;
        }
        return goal + "\n\n" + NEIGHBOURHOOD_HEADING + "\n\n"
            + neighbourhood.strip()
            + "\n\nEvery file and line above was read out of this repository, so those types exist "
            + "and those are their real declarations — do not invent a type that is not there, and "
            + "do not assume a member the shapes above do not show. The test classes named as "
            + "already covering this area are where this project asserts this behaviour today: "
            + "they are the nearest worked example there is, so the change should look like the "
            + "code they exercise, and any new test should look like the tests already in them.";
    }
}
