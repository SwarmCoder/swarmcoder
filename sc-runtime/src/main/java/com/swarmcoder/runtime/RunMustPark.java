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
package com.swarmcoder.runtime;

/**
 * Thrown from inside the swarm when the run cannot honestly go any further and needs an operator.
 *
 * <p><b>Why the swarm needs one at all.</b> Until waves built on each other, everything the swarm
 * could discover was about ONE task, and a task that cannot be finished is recorded BLOCKED while
 * the rest of the run carries on. Merging one wave's winners before the next wave starts is the
 * first thing the swarm does that is about the RUN: when two winners will not merge, or the tree
 * they make together will not compile, there is no next base to cut worktrees from, and every task
 * still to come would be built on a tree that is never going to exist.
 *
 * <p>So the run stops where it is, with the brief in {@link #brief()} queued as the operator's
 * decision. Nothing is lost: every candidate branch is still there, every diff is still readable,
 * and the run resumes from the same stage once the conflict is settled — the tasks that already
 * have a winner are not swarmed again.
 *
 * <p>Deliberately not an outage-style pause: an outage is waited out because it fixes itself,
 * and this never does.
 */
public class RunMustPark extends RuntimeException {

    private final String brief;
    private final boolean questionAlreadyRaised;

    /**
     * @param brief what the operator reads: what stopped, which pieces of work are involved, and
     *              what to do about it. Becomes the pending decision's text verbatim.
     */
    public RunMustPark(String brief) {
        this(brief, false);
    }

    /**
     * @param questionAlreadyRaised true when the swarm has ALREADY put the question the operator
     *                              has to answer on the table — a blocked task's own decision — and
     *                              this park only stops the run behind it. The run is marked parked
     *                              with {@code brief} as its reason, and no second decision is
     *                              raised: two questions about one fault is what the card must not
     *                              show (harness run 39, 2026-09-25 — see {@code
     *                              SwarmEngineImpl.executeRun} on tasks waiting for a blocked one).
     */
    public RunMustPark(String brief, boolean questionAlreadyRaised) {
        super(brief == null ? "the run must park" : brief.lines().findFirst().orElse("the run must park"));
        this.brief = brief;
        this.questionAlreadyRaised = questionAlreadyRaised;
    }

    /** The full operator-facing brief, in full — the message() is only its first line. */
    public String brief() {
        return brief;
    }

    /** True when the question behind this park is already pending; see the two-argument constructor. */
    public boolean questionAlreadyRaised() {
        return questionAlreadyRaised;
    }
}
