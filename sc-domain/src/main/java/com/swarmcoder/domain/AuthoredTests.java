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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What the test-authoring stage did for one task - written onto the task the moment it happens.
 *
 * <p>The stage authors tasks one at a time and used to say nothing until every one of them was
 * done: for the minutes it took, the run graph showed the task boxes and nothing else, which is
 * exactly what a hung stage looks like. This record exists so the graph can say, per task and as
 * it happens, "writing its tests" and then "3 tests, 2 checks" the moment those tests are on disk.
 *
 * <h2>Three states, told apart by what is set</h2>
 *
 * <ul>
 *   <li><b>absent</b> ({@code null} on the task): the stage has not reached this task, or the
 *       run predates this record. Nothing is said.
 *   <li><b>started, not written</b> ({@link #startedAt} set, {@link #writtenAt} not): the author
 *       is working on this task right now.
 *   <li><b>written</b> ({@link #writtenAt} set): the files are on disk and have been read back.
 *       {@link #checksOffered} says how many checks the author was asked for - zero means an
 *       enabler that claims none, which is not "no tests yet" and is deliberately shown as nothing
 *       at all rather than as a {@code 0 tests} badge.
 * </ul>
 *
 * <p>It records what was WRITTEN. It says nothing about whether any worker will run these tests -
 * that is decided per task, by which files {@link Task#authoredTestPaths()} names and by the
 * run's own {@code swarm/tests/<runId>} ref they are committed on (see
 * {@code GreenfieldWorkflow.authorAcceptanceTests}), not by anything recorded here.
 */
@DataModel
public class AuthoredTests {
    private Instant startedAt;
    private Instant writtenAt;
    /** How many checks the author was handed for this task; 0 for a task that claims none. */
    private int checksOffered;
    /** Repo-relative paths of the files written. */
    private List<String> files = new ArrayList<>();
    /** Every test found in those files, in file order. */
    private List<AuthoredTest> tests = new ArrayList<>();
    /** Checks that point at a test nobody wrote - the findings that park the run, one line each. */
    private List<String> problems = new ArrayList<>();
    /**
     * Why the author's own call failed to produce anything, in its own honest words - null when
     * the call worked (whether or not it wrote files for every check). Set only on a genuine
     * failure (a reply that would not parse, or one that named no files, each after being asked
     * again once) - never on the author simply choosing to write nothing for a task that had
     * nothing to write. Quoted directly by the red-check park message in place of a guess.
     */
    private String failureReason;
    /**
     * Set when this task's own test failed inside its own code before any candidate's failure was
     * measured — every candidate died on the same bug, in the test's own class — and the test
     * author corrected it (author decision, 2026-09-05). Null in the normal case. Shown on the run
     * graph's badge so an operator reading the task sees it, and quoted to the judge so a surviving
     * candidate is never blamed for a failure that was the test's own fault.
     */
    private String repairedNote;
    /**
     * Set when every first candidate failed this task's test in the same way and the test went
     * back to its author to say which side is wrong (owner decision, 2026-10-04): what the
     * author answered, whether or not the test was changed. Null in the normal case. Read by the
     * run report.
     */
    private String reviewNote;

    public AuthoredTests() {}

    /** The record for a task the author has just been handed. */
    public static AuthoredTests started(int checksOffered, Instant at) {
        AuthoredTests record = new AuthoredTests();
        record.startedAt = at;
        record.checksOffered = checksOffered;
        return record;
    }

    public Instant startedAt() { return startedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant writtenAt() { return writtenAt; }
    public Instant getWrittenAt() { return writtenAt; }
    public void setWrittenAt(Instant writtenAt) { this.writtenAt = writtenAt; }
    public int checksOffered() { return checksOffered; }
    public int getChecksOffered() { return checksOffered; }
    public void setChecksOffered(int checksOffered) { this.checksOffered = checksOffered; }
    public List<String> files() { return files == null ? List.of() : files; }
    public List<String> getFiles() { return files; }
    public void setFiles(List<String> files) { this.files = files; }
    public List<AuthoredTest> tests() { return tests == null ? List.of() : tests; }
    public List<AuthoredTest> getTests() { return tests; }
    public void setTests(List<AuthoredTest> tests) { this.tests = tests; }
    public List<String> problems() { return problems == null ? List.of() : problems; }
    public List<String> getProblems() { return problems; }
    public void setProblems(List<String> problems) { this.problems = problems; }
    public String failureReason() { return failureReason; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String repairedNote() { return repairedNote; }
    public String getRepairedNote() { return repairedNote; }
    public void setRepairedNote(String repairedNote) { this.repairedNote = repairedNote; }
    public String reviewNote() { return reviewNote; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }

    /** The author has this task and has not finished with it. */
    public boolean inProgress() {
        return startedAt != null && writtenAt == null;
    }

    /** The files are on disk and were read back. */
    public boolean written() {
        return writtenAt != null;
    }

    /**
     * How many distinct checks are proved by a test that was actually written.
     *
     * <p>Distinct by handle AND wording: a task that owns its checks (an enabler with criteria of
     * its own) has no R7:C1 handles, so every one of its checks carries the same placeholder
     * handle, and counting handles alone would say one check however many were proved.
     */
    public int checksProved() {
        Set<String> checks = new LinkedHashSet<>();
        for (AuthoredTest test : tests()) {
            if (test.provesACheck()) {
                checks.add(test.provesRef() + " | " + test.provesText());
            }
        }
        return checks.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuthoredTests that = (AuthoredTests) o;
        return checksOffered == that.checksOffered
            && Objects.equals(startedAt, that.startedAt)
            && Objects.equals(writtenAt, that.writtenAt)
            && Objects.equals(files(), that.files())
            && Objects.equals(tests(), that.tests())
            && Objects.equals(problems(), that.problems())
            && Objects.equals(failureReason, that.failureReason)
            && Objects.equals(repairedNote, that.repairedNote)
            && Objects.equals(reviewNote, that.reviewNote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startedAt, writtenAt, checksOffered, files(), tests(), problems(),
            failureReason, repairedNote, reviewNote);
    }
}
