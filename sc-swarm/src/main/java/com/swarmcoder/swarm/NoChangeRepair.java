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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.verify.Verdicts;

import java.util.List;

/**
 * A repair round in which every worker found nothing to change, after a candidate that built and
 * passed its tests was failed by a verification objection (a check the task's own tree is held to,
 * such as the contract delivery check).
 *
 * <p>Harness run 72, 2026-10-02: the delivery check wrongly said a generated class did not exist.
 * Four repair workers spent 34 minutes, each reporting "already done, nothing to change", and the
 * task was BLOCKED with a generic sentence that never mentioned the objection. A person reading
 * that cannot tell a failing candidate from a false alarm in the check. The task stays BLOCKED
 * (nothing is passed automatically); only the text changes.
 */
final class NoChangeRepair {

    private static final String MARKER = "[verdict] NOT SURVIVED — ";

    private NoChangeRepair() {}

    /**
     * The headline for the blocked-task question when this happened, or null when it did not.
     *
     * @param seeds    the verified candidates the repair workers started from
     * @param repaired what the repair workers produced
     */
    static String headline(String taskTitle, List<CandidateSolution> seeds,
                           List<CandidateSolution> repaired) {
        if (repaired == null || repaired.isEmpty() || seeds == null) {
            return null;
        }
        for (CandidateSolution worker : repaired) {
            boolean changedNothing = worker.diffUnified() == null || worker.diffUnified().isBlank();
            boolean unfinished = worker.state() == CandidateState.FAILED && worker.killReason() == null
                || worker.state() == CandidateState.KILLED
                    && worker.killReason() == KillReason.NO_PROGRESS;
            if (!changedNothing || !unfinished || worker.verification() != null) {
                return null; // somebody changed something, or died for another reason
            }
        }
        for (CandidateSolution seed : seeds) {
            VerificationReport report = seed.verification();
            // The build and the tests were fine: what failed this candidate was an objection.
            if (report == null || !Verdicts.survived(report)) {
                continue;
            }
            String objection = objectionIn(report.logTail());
            if (objection != null) {
                return "Task BLOCKED after swarm + repair round: '" + taskTitle + "'\n\n"
                    + "All " + repaired.size() + " repair workers found nothing to change, and the "
                    + "candidate they started from compiled and passed its tests. It was failed by "
                    + "this verification objection:\n\n> " + objection.replace("\n", "\n> ")
                    + "\n\nThis looks like a false alarm in the check rather than a fault in the "
                    + "code, so look at the objection first. The task stays BLOCKED; nothing was "
                    + "passed automatically.\n\n";
            }
        }
        return null;
    }

    /** The text of the last verdict recorded in a report's log tail, or null when there is none. */
    static String objectionIn(String logTail) {
        if (logTail == null) {
            return null;
        }
        int at = logTail.lastIndexOf(MARKER);
        if (at < 0) {
            return null;
        }
        String text = logTail.substring(at + MARKER.length()).strip();
        return text.isEmpty() ? null : text;
    }
}
