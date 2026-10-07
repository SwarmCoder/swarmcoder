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

import com.swarmcoder.domain.KillReason;

/**
 * The mechanical stopping rules for one worker (spec §11.3), plus the stall guard (§32).
 *
 * <p><b>The stall guard, and the incident that produced it.</b> On 2026-09-02 a worker ran for 121
 * turns and 105,529 tokens, was killed for exhausting its budget, and produced nothing. A second
 * worker on the same run did the same for 102 turns and 105,935 tokens. Both spent their run
 * unpacking a compiler's own jars into {@code /tmp} and reading the class files with {@code javap}.
 *
 * <p>Reverse-engineering was not disobedience and not irrational. The documentation tool answered
 * six different questions with the same irrelevant section, the worker said so twice in its own
 * words, and only then went to the jars — where it immediately got the method signatures it had
 * been asking for. The rules telling it not to were in its prompt the whole time, and five separate
 * project guidelines forbade it by name. Prompting had already been tried and had already failed.
 *
 * <p>So the guard here is not about HOW a worker investigates. It is about whether the attempt is
 * still converging on a change. A worker that has not altered a single file in twenty-four
 * consecutive tool calls is not going to, whatever it is doing in them; a worker whose last six
 * tool calls all came back with nothing is guessing. Four workers run in parallel, so stopping one
 * that is going nowhere at turn twenty costs one attempt and saves the eighty-odd thousand tokens
 * it would otherwise spend proving it.
 */
public class EarlyKillEnforcer {

    /**
     * How many tool calls a worker may spend purely investigating — reading, running commands,
     * looking things up — since its last change to a file, before it is told to start writing.
     *
     * <p><b>Why eight.</b> Measured, from the run that caused this rule: ten workers pointed at a
     * documented framework whose documentation never reached them spent their entire budget
     * inside {@code jar xf} / {@code javap} and wrote nothing, dying at 14 to 22 turns against a
     * 30-turn cap. Every one of them was still investigating at turn eight. Eight also sits
     * clear above what a healthy worker uses before its first write — read the file, read a
     * neighbour, run the build, write — which is three to five. So eight separates the two
     * populations without touching the honest one.
     *
     * <p>It is a NUDGE, not a kill. Reading before writing is correct, and an undocumented
     * dependency really can need reverse-engineering; the failure is not investigating, it is
     * never stopping. So the worker is told what reference material it has and asked to start,
     * and it keeps its remaining turns either way.
     */
    public static final int INVESTIGATION_TOOL_CALLS_BEFORE_NUDGE = 8;

    /**
     * The second nudge, at twice the first.
     *
     * <p>There used to be only one. It fired once per worker, on the exact eighth call, and never
     * again — and a worker that had ever written anything was excluded from it entirely. In the
     * 121-turn run that meant a single steer at turn 8 and then silence: the worker wrote two files
     * at turn 52, which permanently disabled its own nudge, and then spent forty-one more tool
     * calls in the jars with nothing watching. Once is not a policy. The count is per run of
     * investigation — a write resets it — so a worker doing real work never sees either nudge.
     */
    public static final int INVESTIGATION_TOOL_CALLS_BEFORE_SECOND_NUDGE = 16;

    /**
     * Where investigating stops being investigation and becomes a stall the worker is killed for.
     *
     * <p><b>Why twenty-four.</b> Three times the nudge, and measured against the run it was written
     * for: that worker passed twenty-four consecutive looking-only calls at turn twenty-two on
     * 38,750 tokens, and went on to burn 105,529 before the budget stopped it. Twenty-four is also
     * far outside anything an honest worker reaches, because the count resets on every successful
     * write: crossing it means twenty-four tool calls in a row that changed nothing at all.
     */
    public static final int INVESTIGATION_TOOL_CALLS_BEFORE_KILL = 24;

    /**
     * How many looking-only tool calls may come back with nothing, one after another, before the
     * worker is stopped.
     *
     * <p>A call is fruitless when its result carried no information: a command whose output was
     * empty or that timed out, a file that was not there, or an answer byte-identical to one this
     * worker has already been given. That is the signature of guessing — the same grep at a
     * different spelling, a listing of a directory that does not exist, the same {@code javap}
     * filtered six ways. Six in a row cannot happen to a worker that is learning anything, and a
     * single useful result resets it to zero.
     */
    public static final int FRUITLESS_CALLS_BEFORE_KILL = 6;

    /**
     * How many turns in a row may change no file and bring back no new tool result before the
     * worker is stopped (live run 74, 2026-10-03).
     *
     * <p>Four repair workers there took 12 to 18 turns over 65 to 78 minutes, each turn 2,500 to
     * 4,600 tokens of reasoning, and changed nothing: the file that had to change was outside
     * their write set. The tool-call counts above did not stop them in time because a turn that
     * is mostly thinking makes few calls. This counts TURNS: a turn after which the worker has
     * neither changed a file nor been shown anything it had not already seen moved nothing. It
     * is a safety stop, not a budget - one write, or one new result, starts the count again.
     * {@code -Dswarmcoder.worker.idleTurnsBeforeStop} changes it.
     */
    public static final int IDLE_TURNS_BEFORE_KILL =
        Math.max(2, Integer.getInteger("swarmcoder.worker.idleTurnsBeforeStop", 5));

    /** NO_PROGRESS when that many turns in a row moved nothing, else null. */
    public KillReason checkIdleTurns(int idleTurnsInARow) {
        return idleTurnsInARow >= IDLE_TURNS_BEFORE_KILL ? KillReason.NO_PROGRESS : null;
    }

    public KillReason checkState(int malformedCount, int writeSetViolations, long usedTokens, long maxTokens) {
        if (malformedCount >= 2) return KillReason.TOOLCALL_MALFORMED;
        if (writeSetViolations >= 2) return KillReason.WRITESET_VIOLATION;
        if (usedTokens > maxTokens) return KillReason.BUDGET_EXCEEDED;
        return null;
    }

    public KillReason checkParallelCompile(boolean thisFailed, int totalOtherCandidatesCompiled) {
        if (thisFailed && totalOtherCandidatesCompiled >= 2) {
            return KillReason.COMPILE_FAIL_TWICE; // Using twice logic to kill if others succeeded
        }
        return null;
    }

    /**
     * NO_PROGRESS when this worker has stopped converging on a change, else null.
     *
     * <p>Both inputs are counted since the last successful write, so a worker that is editing
     * files can never trip either one however much it reads between edits.
     *
     * @param investigationCallsSinceWrite looking-only tool calls since the last file change
     * @param fruitlessCallsInARow         consecutive looking-only calls that returned nothing new
     */
    public KillReason checkProgress(int investigationCallsSinceWrite, int fruitlessCallsInARow) {
        if (investigationCallsSinceWrite >= INVESTIGATION_TOOL_CALLS_BEFORE_KILL) {
            return KillReason.NO_PROGRESS;
        }
        if (fruitlessCallsInARow >= FRUITLESS_CALLS_BEFORE_KILL) {
            return KillReason.NO_PROGRESS;
        }
        return null;
    }

    /**
     * Which stall rule fired, in the operator's words — the run graph's red chip says the worker
     * stopped getting anywhere, and this says which way it showed. Blank when neither fired.
     */
    public String progressDiagnosis(int investigationCallsSinceWrite, int fruitlessCallsInARow) {
        return progressDiagnosis(investigationCallsSinceWrite, fruitlessCallsInARow, false);
    }

    /**
     * {@link #progressDiagnosis(int, int)}, with the read-pause's own part of the story added when
     * it applies.
     *
     * <p>Reaching the twenty-four call backstop ({@code investigationCallsSinceWrite} at or past
     * it) always means the worker passed through the read-pause first: the pause starts at call
     * seventeen (one past the second nudge, at sixteen) and the count since the last write only
     * ever goes up between writes, so a worker that reaches twenty-four was refused real reads,
     * execs and lookups for the last eight calls it made. The fruitless-repeat path
     * ({@code fruitlessCallsInARow}) can fire well before sixteen calls and says nothing about the
     * pause, so {@code readingWasPaused} is not inferred here — the caller ({@link WorkerToolbox}
     * knows whether it was ever paused for this run of investigation) says so directly.
     *
     * @param readingWasPaused whether {@link WorkerToolbox#readingPaused()} was true when this
     *                         worker was killed — that is, whether it had already been told to
     *                         write and had kept reading (or trying to) instead
     */
    public String progressDiagnosis(int investigationCallsSinceWrite, int fruitlessCallsInARow,
                                    boolean readingWasPaused) {
        if (investigationCallsSinceWrite >= INVESTIGATION_TOOL_CALLS_BEFORE_KILL) {
            String base = investigationCallsSinceWrite + " tool calls in a row without changing any file";
            return readingWasPaused
                ? base + " - it was asked to write after " + INVESTIGATION_TOOL_CALLS_BEFORE_SECOND_NUDGE
                    + " reads and read on instead"
                : base;
        }
        if (fruitlessCallsInARow >= FRUITLESS_CALLS_BEFORE_KILL) {
            return fruitlessCallsInARow + " tool calls in a row that came back with nothing";
        }
        return "";
    }

    /**
     * True when a worker has now investigated long enough since its last change to be told so.
     *
     * <p>Fires on the threshold calls only — the eighth and the sixteenth — so the worker is
     * steered twice per run of investigation rather than on every turn afterwards.
     *
     * <p><b>{@code hasWritten} no longer suppresses it.</b> It used to, and that is the hole the
     * 121-turn worker went through. The count is already "calls since the last write", so a worker
     * that is making changes resets it and never reaches eight; the flag is kept only because the
     * wording of the steer differs for a worker that has never written anything at all.
     */
    public boolean shouldNudgeOutOfInvestigation(int investigationToolCalls, boolean hasWritten) {
        return investigationToolCalls == INVESTIGATION_TOOL_CALLS_BEFORE_NUDGE
            || investigationToolCalls == INVESTIGATION_TOOL_CALLS_BEFORE_SECOND_NUDGE;
    }
}
