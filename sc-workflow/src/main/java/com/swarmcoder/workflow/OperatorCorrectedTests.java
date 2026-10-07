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

import com.swarmcoder.domain.Run;
import com.swarmcoder.git.GitService;

/**
 * An acceptance test an operator corrected by hand while the run was parked is the test the run
 * uses when it is taken up again.
 *
 * <p><b>Why (live run 63, 2026-10-02).</b> The run parked on an acceptance test that could not
 * compile and that its author could not repair. The question named the run's tests branch; the
 * operator committed a corrected test to that branch and answered "retry". The retry placed the
 * old file into the red-check tree and into every candidate, and failed on the same line: the
 * engine never reads the branch. It reads {@code Run.acceptanceTestsCommit}, a commit recorded
 * when the engine itself last wrote the tests, and nothing moved it.
 *
 * <p>So on resume the recorded commit follows the branch, when the branch has moved and still
 * builds on this run: on the recorded commit (the operator added a commit), or on the run's own
 * start point (the operator amended the engine's commit). A tip that is neither is somebody
 * else's history and is left alone, with a sentence in the log saying so.
 */
final class OperatorCorrectedTests {

    private OperatorCorrectedTests() {}

    /**
     * Points {@code run} at the tip of its tests branch when an operator moved it.
     *
     * @return the sentence for the run log, or null when nothing changed and nothing needs saying
     */
    static String adopt(GitService git, Run run) {
        if (git == null || !git.isEnabled() || run == null) {
            return null;
        }
        String recorded = run.acceptanceTestsCommit();
        if (recorded == null || recorded.isBlank()) {
            return null; // this run has no tests commit to correct
        }
        String branch = GreenfieldWorkflow.testsBranch(run);
        String tip = git.headSha(branch);
        if (tip == null || tip.equals(recorded)) {
            return null;
        }
        String start = run.startPoint();
        boolean buildsOnThisRun = git.isAncestor(recorded, tip)
            || (start != null && !start.isBlank() && git.isAncestor(start, tip));
        if (!buildsOnThisRun) {
            return "The tests branch " + branch + " now points at " + abbreviated(tip)
                + ", which builds neither on this run's tests commit " + abbreviated(recorded)
                + " nor on the commit the run started from - it is NOT used; the run keeps its "
                + "tests as they were";
        }
        run.setAcceptanceTestsCommit(tip);
        return "The tests branch " + branch + " moved from " + abbreviated(recorded) + " to "
            + abbreviated(tip) + " while the run was parked - a correction made by hand. The run "
            + "now takes its acceptance tests from " + abbreviated(tip) + ": the red check and "
            + "every candidate get the corrected file(s)";
    }

    /** What a park brief about a broken acceptance test tells the operator to do, and where. */
    static String whereToCorrect(Run run) {
        return "\n\nTo correct the test by hand: commit the corrected file on the branch "
            + GreenfieldWorkflow.testsBranch(run) + " in the project repository (a new commit on "
            + "its tip, at the same path), then resume the run. On resume the run takes its "
            + "acceptance tests from that branch's tip. Editing the file anywhere else - a "
            + "candidate's checkout, the main branch - changes nothing: every tree is given the "
            + "test from that branch.";
    }

    private static String abbreviated(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
    }
}
