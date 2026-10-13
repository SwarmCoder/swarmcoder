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
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.verify.ErroredForEveryCandidate;
import com.swarmcoder.verify.Verdicts;

import java.util.List;

/**
 * Why a task was blocked, said once at the top of its question (section 83).
 *
 * <p>The question of a blocked task listed, for each worker, its state, {@code compiles=false}
 * and pass counts, and nothing else. Whoever had to answer it - a person, or a supervising model
 * shown about 1,300 characters - was told that eight workers failed and not on what. The reason
 * is what verification already recorded for the first candidate that has one: the file and line
 * the compile failed at with the compiler's message, or the first acceptance test that failed
 * with its message. It goes before the list of workers, and it is short. When no test failed and
 * some errored, it is the first errored test with its exception (section 86). When the
 * candidates compiled and passed every test, it is the verification objection that failed
 * them (section 87).
 *
 * <p>Pure functions over what verification recorded.
 */
final class BlockedReason {

    private BlockedReason() {
    }

    /** The longest a compiler's or a test's message is quoted at. */
    static final int MESSAGE_LIMIT = 300;

    /**
     * The reason, as one short paragraph ending in a blank line, or "" when verification
     * recorded none or {@code headline} already says where the failure is.
     *
     * @param evidence the task's candidates, in the order the question lists them
     * @param headline what the question starts with
     */
    static String of(Task task, List<CandidateSolution> evidence, String headline) {
        if (evidence == null) {
            return "";
        }
        String said = headline == null ? "" : headline;
        for (CandidateSolution candidate : evidence) {
            if (candidate == null || candidate.verification() == null
                    || candidate.verification().compiles()) {
                continue;
            }
            CompileFailure failure = candidate.verification().compileFailure();
            if (failure == null || failure.file() == null) {
                continue;
            }
            String where = failure.file().replace('\\', '/')
                + (failure.line() > 0 ? ":" + failure.line() : "");
            if (said.contains(where)) {
                return "";
            }
            return "Why: it does not compile - " + where + " " + shortened(failure.message())
                + (ownTest(task, failure.file())
                    ? "\nThat file is this task's own protected acceptance test; no worker may "
                        + "change it."
                    : "")
                + "\n\n";
        }
        String errored = erroredReason(evidence, said);
        if (errored != null) {
            return errored;
        }
        for (CandidateSolution candidate : evidence) {
            TestResults acceptance = candidate == null || candidate.verification() == null
                ? null : candidate.verification().acceptance();
            if (acceptance == null || acceptance.failures() == null) {
                continue;
            }
            for (TestFailure failure : acceptance.failures()) {
                if (failure == null || failure.testId() == null) {
                    continue;
                }
                if (said.contains(failure.testId())) {
                    return "";
                }
                return "Why: acceptance test " + failure.testId() + " fails - "
                    + shortened(failure.message()) + "\n\n";
            }
        }
        return objectionReason(evidence, said);
    }

    /**
     * The reason when nothing failed to compile and no test failed or errored (section 87): the
     * verification objection recorded for the first candidate that compiled and passed every
     * acceptance test, and, when every verified candidate was failed by that same objection,
     * that it was. "" when verification recorded no such objection or {@code said} already
     * quotes it.
     *
     * <p>Live supervised build, 2026-10-12: eight candidates compiled, passed 4 of 4 acceptance
     * tests and were failed by one contract objection. The question listed eight workers as
     * "[FAILED] compiles=true acceptance 4p/0f/0e" and gave no reason.
     */
    private static String objectionReason(List<CandidateSolution> evidence, String said) {
        String first = null;
        TestResults passed = null;
        int verified = 0;
        int same = 0;
        for (CandidateSolution candidate : evidence) {
            if (candidate == null || candidate.verification() == null) {
                continue;
            }
            verified++;
            if (!Verdicts.survived(candidate.verification())) {
                continue;
            }
            String objection = NoChangeRepair.objectionIn(candidate.verification().logTail());
            if (objection == null) {
                continue;
            }
            if (first == null) {
                first = objection;
                passed = candidate.verification().acceptance();
            }
            if (first.equals(objection)) {
                same++;
            }
        }
        if (first == null) {
            return "";
        }
        String quoted = shortened(first);
        if (said.replaceAll("\\s+", " ").contains(
                quoted.endsWith("...") ? quoted.substring(0, quoted.length() - 3) : quoted)) {
            return "";
        }
        return "Why: it compiles and passes every acceptance test (" + counts(passed)
            + "); verification failed it with this objection - " + quoted
            + (verified > 1 && same == verified
                ? "\nAll " + verified + " verified candidate(s) were failed by that same "
                    + "objection."
                : "")
            + "\n\n";
    }

    /**
     * The reason when the first candidate that has anything to show had no test FAIL and some
     * ERROR (section 86): the first errored test with its exception class and message, and,
     * when every verified candidate shows that same error and passed nothing, that the test is
     * the likely cause. Null when that is not what verification recorded.
     *
     * <p>A test that throws before it asserts anything is counted as errored, not failed. The
     * question of a task blocked on one read "0p/0f" for every worker and gave no reason at
     * all.
     */
    private static String erroredReason(List<CandidateSolution> evidence, String said) {
        List<TestResults> verdicts = new java.util.ArrayList<>();
        for (CandidateSolution candidate : evidence) {
            if (candidate != null && candidate.verification() != null
                    && candidate.verification().compiles()
                    && candidate.verification().acceptance() != null) {
                verdicts.add(candidate.verification().acceptance());
            }
        }
        TestResults first = null;
        for (TestResults acceptance : verdicts) {
            if (acceptance.failed() > 0 || acceptance.errored() > 0) {
                first = acceptance;
                break;
            }
        }
        if (first == null || first.failed() > 0) {
            return null;
        }
        TestFailure failure = first.failures() == null ? null : first.failures().stream()
            .filter(f -> f != null && f.testId() != null).findFirst().orElse(null);
        String counts = first.errored() + " acceptance test(s) errored, " + first.passed()
            + " passed and none failed";
        if (failure == null) {
            return "Why: " + counts + "; the runner reported which only as a count.\n\n";
        }
        String type = ErroredForEveryCandidate.exceptionType(failure);
        String line = "Why: acceptance test " + failure.testId() + " errored before it could "
            + "fail or pass - " + (type.isBlank() ? "" : type + ": ")
            + shortened(failure.message()) + " (" + counts + ")";
        if (said.contains(line)) {
            return "";
        }
        return line + (ErroredForEveryCandidate.sameError(verdicts)
            ? "\nAll " + verdicts.size() + " verified candidate(s) errored on that same test "
                + "with that same exception and passed nothing: the test, not the candidates, "
                + "is the likely cause, and no worker may change it."
            : "")
            + "\n\n";
    }

    /**
     * What a stop question says first when the task's repair round was stopped by its time
     * limit (section 91): after how many turns, and whether the stopped workers were still
     * making progress. "" when the round was not stopped by the limit, or no worker was still
     * going when it was.
     *
     * <p>Live supervised build, 2026-10-12/13: a round was stopped at 30 minutes after 8 and 11
     * turns with both workers changing files. Its question read like any failed repair, the
     * supervisor could not tell that more time was the remedy, and the same instruction with 90
     * minutes succeeded.
     *
     * @param instructed whether the round was given on an instruction (section 84); it is then
     *                   not counted against the rounds a task may be given that way
     */
    static String timeLimit(RepairRound.Outcome round, boolean instructed) {
        if (round == null || round.ended() != RepairRound.Ended.TIME_LIMIT
                || round.stoppedWorkers() == 0) {
            return "";
        }
        String stopped = "The repair round was stopped by the time limit ("
            + RepairRound.Outcome.minutes(round.limitMillis()) + " minutes) after "
            + round.stoppedTurns() + " turns";
        if (!round.onlyTheTimeLimit()) {
            return stopped + "; the workers had stopped making progress.\n\n";
        }
        return stopped + "; the workers were still making progress. More time, not a different "
            + "instruction, is the remedy: `repair` with the same instruction gives the round "
            + "again"
            + (instructed ? ", and this round is not counted against the rounds a task may be "
                + "given on an instruction" : "")
            + ". -D" + RepairRound.ROUND_MINUTES_PROPERTY + " sets the limit in minutes.\n\n";
    }

    /** {@code 3p/1f/2e}: passed, failed and errored, wherever a candidate's counts are shown. */
    static String counts(TestResults results) {
        return results == null ? "none"
            : results.passed() + "p/" + results.failed() + "f/" + results.errored() + "e";
    }

    /** One line of at most {@link #MESSAGE_LIMIT} characters. */
    static String shortened(String message) {
        String line = message == null ? "" : message.strip().replaceAll("\\s+", " ");
        if (line.isEmpty()) {
            return "(no message)";
        }
        return line.length() <= MESSAGE_LIMIT ? line : line.substring(0, MESSAGE_LIMIT) + "...";
    }

    private static boolean ownTest(Task task, String file) {
        if (task == null || file == null) {
            return false;
        }
        String wanted = file.replace('\\', '/');
        for (String path : task.authoredTestPaths()) {
            String own = path.replace('\\', '/');
            if (own.equals(wanted) || own.endsWith("/" + wanted) || wanted.endsWith("/" + own)) {
                return true;
            }
        }
        return false;
    }
}
