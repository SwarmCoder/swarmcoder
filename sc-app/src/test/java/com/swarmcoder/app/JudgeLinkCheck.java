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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chain link 13, "the judge was told whether the candidate was verified", measured from the
 * archived candidates and the judge's brief.
 *
 * <p>Harness run 85 (2026-10-04) broke here on a run that delivered: since the scheduling change
 * ({@code SwarmEngineImpl.judgeSurvivors}) the judge is not called for a task whose only candidate
 * to pass verification has nothing to be compared with, and the link still demanded a scored
 * candidate. So the link is now measured per task:
 *
 * <ul>
 *   <li>a task with two or more candidates that passed verification must have every one of them
 *       scored, and the brief must carry a verification line that does not say NOT RUN while
 *       reports exist;</li>
 *   <li>a task with exactly one such candidate, carrying a verification report, holds without a
 *       judge call - that is the skip;</li>
 *   <li>no task with a candidate that passed verification is a break, as it always was.</li>
 * </ul>
 */
final class JudgeLinkCheck {

    record Verdict(boolean ok, String detail) {}

    private JudgeLinkCheck() {}

    /**
     * @param candidates  every archived candidate of the run
     * @param judgePrompt the first judge brief the run sent, or null when no judge was called
     */
    static Verdict evaluate(List<CandidateSolution> candidates, String judgePrompt) {
        Map<UUID, List<CandidateSolution>> passedByTask = new LinkedHashMap<>();
        for (CandidateSolution c : candidates) {
            if (c.state() == CandidateState.SURVIVED || c.state() == CandidateState.SELECTED) {
                passedByTask.computeIfAbsent(c.taskId(), k -> new ArrayList<>()).add(c);
            }
        }
        long scored = candidates.stream().filter(c -> c.judge() != null).count();
        boolean anyReport = candidates.stream().anyMatch(c -> c.verification() != null);
        if (passedByTask.isEmpty()) {
            return new Verdict(false, "no candidate passed verification, so there was nothing to "
                + "judge; " + scored + " candidate(s) scored");
        }
        List<String> problems = new ArrayList<>();
        int skipped = 0;
        int judgedTasks = 0;
        for (List<CandidateSolution> passed : passedByTask.values()) {
            List<Integer> unscored = passed.stream().filter(c -> c.judge() == null)
                .map(CandidateSolution::workerIndex).toList();
            if (passed.size() == 1) {
                CandidateSolution only = passed.get(0);
                if (only.judge() != null) {
                    judgedTasks++;
                } else if (only.verification() == null) {
                    problems.add("worker " + only.workerIndex() + " is its task's only candidate "
                        + "to pass, was not judged, and carries no verification report");
                } else {
                    skipped++;
                }
            } else if (!unscored.isEmpty()) {
                problems.add(passed.size() + " candidates of one task passed verification and "
                    + "worker(s) " + unscored + " were never scored: the judge should have run");
            } else {
                judgedTasks++;
            }
        }
        if (judgedTasks > 0 || scored > 0) {
            if (judgePrompt == null) {
                problems.add("candidates carry a score but no judge brief was recorded");
            } else if (!judgePrompt.contains("Verification:")) {
                problems.add("the judge's brief has no verification line at all");
            } else if (judgePrompt.contains("Verification: NOT RUN") && anyReport) {
                problems.add("the judge's brief said NOT RUN although candidates carry a "
                    + "verification report");
            }
        }
        String counted = scored + " candidate(s) scored in " + judgedTasks + " task(s); " + skipped
            + " task(s) had exactly one candidate that passed verification, so the judge was "
            + "rightly not called for them";
        return new Verdict(problems.isEmpty(),
            problems.isEmpty() ? counted : counted + "; " + String.join("; ", problems));
    }
}
