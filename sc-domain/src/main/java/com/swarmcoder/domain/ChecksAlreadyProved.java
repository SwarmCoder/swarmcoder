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
package com.swarmcoder.domain;

import com.zeroz4j.api.DataModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A task whose acceptance tests were ALREADY GREEN before its own wave started — because the waves
 * in front of it delivered what those tests measure.
 *
 * <h2>Why this is a record and not a reason to stop</h2>
 *
 * <p>On 2026-09-03 a three-wave run reached its last wave and parked. The story was "Assign and
 * display a book rating"; the check was "A rating can be assigned to a book and is displayed
 * alongside the book's details"; the two waves in front had built the data model and the
 * server-side update, and the acceptance test — a JUnit test, in the one module that sees every
 * other module — proved the check through the service. So it passed. The wave-time red-check found
 * a test that was not red, concluded the tests were wrong, and asked a person to revise them.
 *
 * <p>That is wrong on both readings of the situation. If the check really is proved, the run has
 * succeeded and should finish. If the work the task describes — the screen — is still to be built,
 * stopping does not build it. Either way, a person was interrupted at the last step of a run that
 * had gone right, and told to fix something that was not broken.
 *
 * <p>So it is written down instead. The task still runs, because its instructions describe work its
 * tests do not measure; its candidates are still verified (the tests must pass); and the judge is
 * told that the tests were green before the candidate and therefore separate nothing, so it must
 * decide on the diff. This record is what carries that fact from the wave gate to the judge, to the
 * run graph and to whoever reads the run afterwards.
 *
 * <p><b>Only at wave time, never at TEST_AUTHORING.</b> Before the first wave nothing in the run has
 * touched the tree, so a green acceptance test is evidence about the TEST — the behaviour was there
 * all along and the test author wrote something that measures nothing new. That still parks, and the
 * test author can still be re-run. At a later wave the RUN is what made it green, and that is
 * success.
 */
@DataModel
public class ChecksAlreadyProved {

    private Instant provedAt;
    /** The tree the wave was cut from: the run's pinned base plus every earlier wave's winner. */
    private String waveBase;
    /** The acceptance tests that were already passing, by id, e.g. {@code swarm.accept.X#y}. */
    private List<String> tests = new ArrayList<>();
    /** One readable line per check this task answers for — its handle and its wording. */
    private List<String> checks = new ArrayList<>();
    /** How many acceptance tests passed on that tree. */
    private int passed;
    /**
     * Whether any of those checks is worded about what a person SEES (see
     * {@link UserFacingWording}). When it is, the green test is provably not the whole check: a
     * JUnit acceptance test cannot open a browser.
     */
    private boolean userFacing;
    /** Where the part the tests do not measure is proved, in the words the operator reads. */
    private String remainingProof;
    /**
     * True when the tree these tests were green on carried NOTHING this run delivered — the run's
     * own pinned base, untouched. Appended 2026-09-05 after harness run 30 (see {@code
     * SelfImplementedContract}): the ordinary reading of this record is "the waves in front of this
     * one delivered what these tests measure", which is success and is why this record exists. The
     * other reading is a test that measures itself, and the two must not be confused, because only
     * the first is a reason to carry on. Append-only: false on any record written before this field
     * existed, which is the honest reading — an old record never positively established that
     * nothing had been delivered.
     */
    private boolean nothingDeliveredYet;
    /**
     * True when the tests were green on the tree the RUN started from, before anything of this
     * run existed, and were accepted as proof (owner decision after the audit of 2026-10-02):
     * the code delivered by earlier stories already satisfies the check, and the tests were shown
     * to execute assertions against that code. {@link #waveBase} is then the commit they passed
     * on. Appended last and false on every record stored before it existed.
     */
    private boolean beforeTheRun;

    public ChecksAlreadyProved() {}

    public ChecksAlreadyProved(Instant provedAt, String waveBase, List<String> tests,
                               List<String> checks, int passed, boolean userFacing,
                               String remainingProof) {
        this(provedAt, waveBase, tests, checks, passed, userFacing, remainingProof, false);
    }

    public ChecksAlreadyProved(Instant provedAt, String waveBase, List<String> tests,
                               List<String> checks, int passed, boolean userFacing,
                               String remainingProof, boolean nothingDeliveredYet) {
        this.nothingDeliveredYet = nothingDeliveredYet;
        this.provedAt = provedAt;
        this.waveBase = waveBase;
        this.tests = tests == null ? new ArrayList<>() : new ArrayList<>(tests);
        this.checks = checks == null ? new ArrayList<>() : new ArrayList<>(checks);
        this.passed = passed;
        this.userFacing = userFacing;
        this.remainingProof = remainingProof;
    }

    public Instant provedAt() { return provedAt; }
    public Instant getProvedAt() { return provedAt; }
    public void setProvedAt(Instant provedAt) { this.provedAt = provedAt; }
    public String waveBase() { return waveBase; }
    public String getWaveBase() { return waveBase; }
    public void setWaveBase(String waveBase) { this.waveBase = waveBase; }
    public List<String> tests() { return tests == null ? List.of() : tests; }
    public List<String> getTests() { return tests; }
    public void setTests(List<String> tests) { this.tests = tests; }
    public List<String> checks() { return checks == null ? List.of() : checks; }
    public List<String> getChecks() { return checks; }
    public void setChecks(List<String> checks) { this.checks = checks; }
    public int passed() { return passed; }
    public int getPassed() { return passed; }
    public void setPassed(int passed) { this.passed = passed; }
    public boolean userFacing() { return userFacing; }
    public boolean isUserFacing() { return userFacing; }
    public void setUserFacing(boolean userFacing) { this.userFacing = userFacing; }
    public String remainingProof() { return remainingProof; }
    public String getRemainingProof() { return remainingProof; }
    public void setRemainingProof(String remainingProof) { this.remainingProof = remainingProof; }
    public boolean nothingDeliveredYet() { return nothingDeliveredYet; }
    public boolean isNothingDeliveredYet() { return nothingDeliveredYet; }
    public void setNothingDeliveredYet(boolean nothingDeliveredYet) {
        this.nothingDeliveredYet = nothingDeliveredYet;
    }

    public boolean beforeTheRun() { return beforeTheRun; }
    public boolean isBeforeTheRun() { return beforeTheRun; }
    public void setBeforeTheRun(boolean beforeTheRun) { this.beforeTheRun = beforeTheRun; }

    /**
     * The sentence the run log, the task record and the judge's brief all use, so one fact is
     * worded one way wherever it is read.
     */
    public String describe(String taskTitle) {
        if (beforeTheRun) {
            return describeBeforeTheRun(taskTitle);
        }
        StringBuilder sb = new StringBuilder("The checks of task '")
            .append(taskTitle == null ? "?" : taskTitle)
            .append("' are already proved by the winners of the earlier waves: ")
            .append(tests().isEmpty() ? passed + " acceptance test(s)" : String.join(", ", tests()))
            .append(" passed on the tree this wave is cut from, before any worker of this task ran.");
        if (!checks().isEmpty()) {
            sb.append("\nThe check(s) it answers for:");
            for (String check : checks()) {
                sb.append("\n  - ").append(check);
            }
        }
        sb.append("\n\nThis is not a fault and it does not stop the run. The task still runs: its "
            + "instructions describe work these tests do not measure. What changes is how its "
            + "candidates are chosen — the tests no longer separate them, so the judge decides on "
            + "whether the diff delivers the task's instructions, and a candidate that changed "
            + "nothing cannot win.");
        if (remainingProof != null && !remainingProof.isBlank()) {
            sb.append('\n').append(remainingProof);
        }
        return sb.toString();
    }

    private String describeBeforeTheRun(String taskTitle) {
        StringBuilder sb = new StringBuilder("The checks of task '")
            .append(taskTitle == null ? "?" : taskTitle)
            .append("' are already satisfied by the code this run started from: ")
            .append(tests().isEmpty() ? passed + " acceptance test(s)" : String.join(", ", tests()))
            .append(" passed on ").append(waveBase == null ? "the start tree" : waveBase)
            .append(", before anything of this run was built, and they execute assertions "
                + "against the project's own code.");
        if (!checks().isEmpty()) {
            sb.append("\nThe check(s) recorded as already satisfied:");
            for (String check : checks()) {
                sb.append("\n  - ").append(check);
            }
        }
        sb.append("\n\nThis is not a fault and it does not stop the run. Nothing has to be built "
            + "to turn these tests green; they stay in the run and must still pass on the merged "
            + "tree. If the task is still built, the tests do not separate its candidates, so the "
            + "judge decides on whether the diff delivers the task's instructions.");
        if (remainingProof != null && !remainingProof.isBlank()) {
            sb.append('\n').append(remainingProof);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChecksAlreadyProved that = (ChecksAlreadyProved) o;
        return passed == that.passed
            && userFacing == that.userFacing
            && nothingDeliveredYet == that.nothingDeliveredYet
            && beforeTheRun == that.beforeTheRun
            && Objects.equals(provedAt, that.provedAt)
            && Objects.equals(waveBase, that.waveBase)
            && Objects.equals(tests(), that.tests())
            && Objects.equals(checks(), that.checks())
            && Objects.equals(remainingProof, that.remainingProof);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provedAt, waveBase, tests(), checks(), passed, userFacing,
            remainingProof, nothingDeliveredYet);
    }
}
