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

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;
import java.util.Objects;

/**
 * What the compile stage's own output said about WHY it failed, and whose fault that is.
 *
 * <p>Carried on the {@link VerificationReport} beside the compile flag, because the flag alone
 * says "the compile command exited non-zero" and nothing else. On 2026-09-02 four candidates were
 * told "the candidate does not compile" when each had written its two files correctly and what
 * failed was {@code test-compile} on acceptance tests committed by earlier runs — files the
 * candidates were forbidden from touching, importing classes that exist nowhere. This record is
 * the difference between those two readings. {@code Verdicts} reads {@link #describe()} for the
 * verdict sentence, so every surface that shows the verdict says the same thing.
 *
 * <p>A report written before this field existed carries null, which reads as the old sentence —
 * never as an attribution.
 */
@JsonTypeName("CompileFailure")
public class CompileFailure {

    private CompileFailureCause cause;
    /** The file the sentence names, repo-relative where that could be worked out. Null when none. */
    private String file;
    /** The line of the named error, or 0 when the compiler did not say. */
    private int line;
    /** The named error, as prose where the compiler's message is one the parser knows. */
    private String message;
    /** Every distinct file the compiler reported an error in, in the order it named them. */
    private List<String> failingFiles;
    /** True when the named file is a test source (under src/test or the acceptance directory). */
    private boolean testFile;
    /** True when the named file is inside the task's protected acceptance-test directory. */
    private boolean acceptanceTest;
    /** True when the build tool said main code compiled and the test compile is what failed. */
    private boolean mainCompiled;
    /** Plain English: what was read and what could not be established. */
    private String explanation;

    public CompileFailure() {}

    public CompileFailure(CompileFailureCause cause, String file, int line, String message,
                          List<String> failingFiles, boolean testFile, boolean acceptanceTest,
                          boolean mainCompiled, String explanation) {
        this.cause = cause;
        this.file = file;
        this.line = line;
        this.message = message;
        this.failingFiles = failingFiles;
        this.testFile = testFile;
        this.acceptanceTest = acceptanceTest;
        this.mainCompiled = mainCompiled;
        this.explanation = explanation;
    }

    public CompileFailureCause cause() { return cause; }
    public CompileFailureCause getCause() { return cause; }
    public void setCause(CompileFailureCause cause) { this.cause = cause; }
    public String file() { return file; }
    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }
    public int line() { return line; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
    public String message() { return message; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<String> failingFiles() { return failingFiles; }
    public List<String> getFailingFiles() { return failingFiles; }
    public void setFailingFiles(List<String> failingFiles) { this.failingFiles = failingFiles; }
    public boolean testFile() { return testFile; }
    public boolean getTestFile() { return testFile; }
    public void setTestFile(boolean testFile) { this.testFile = testFile; }
    public boolean acceptanceTest() { return acceptanceTest; }
    public boolean getAcceptanceTest() { return acceptanceTest; }
    public void setAcceptanceTest(boolean acceptanceTest) { this.acceptanceTest = acceptanceTest; }
    public boolean mainCompiled() { return mainCompiled; }
    public boolean getMainCompiled() { return mainCompiled; }
    public void setMainCompiled(boolean mainCompiled) { this.mainCompiled = mainCompiled; }
    public String explanation() { return explanation; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    /**
     * The verdict sentence. One line, because the run graph's hover card shows the first line of
     * the verdict and nothing else, so everything the operator needs has to be on it: whose fault,
     * which file, what the compiler said. No full stop at the end: the card adds its own.
     */
    public String describe() {
        CompileFailureCause why = cause == null ? CompileFailureCause.UNATTRIBUTED : cause;
        String where = file == null ? null : file + (line > 0 ? ":" + line : "");
        String what = message == null || message.isBlank() ? "" : " " + message.trim();
        String more = others();
        return switch (why) {
            case CANDIDATE -> "the candidate does not compile: " + where + what + more;
            case PRE_EXISTING -> "the tree does not compile before this candidate's change: "
                + where + what + more + ". "
                + (acceptanceTest
                    ? "This is an acceptance test committed before this candidate ran, not this "
                        + "candidate's work; workers may not edit that directory"
                    : testFile
                        ? "This is a test this candidate did not touch, not this candidate's work"
                        : "This candidate did not touch that file, so this is not its work");
            case CAUSED_BY_CHANGE -> "this candidate's change broke a file it did not change: "
                + where + what + more + ". That file compiled on the tree this candidate started "
                + "from, so the change is the cause"
                + (explanation == null || explanation.isBlank() ? "" : ": " + explanation.trim());
            case TEST_TREE -> "the main code compiles, but the test tree does not: " + where + what
                + more + ". This candidate changed that test file. A test-compile failure is rarely "
                + "one a worker can fix, because the acceptance tests that judge it live in a "
                + "directory workers may not edit; this one is outside it, so the candidate can";
            // Names both halves: the coordinate the build could not find, and the build file the
            // candidate changed to ask for it. The fix is a different library, and a judge cannot
            // work that out from "the candidate does not compile".
            case UNRESOLVABLE_DEPENDENCY -> "the candidate declared" + what
                + (where == null ? "" : " in " + where)
                + ", which the offline Maven repository does not hold — candidate builds run with "
                + "no network, so that dependency can never resolve";
            case UNATTRIBUTED -> where == null
                ? "the candidate does not compile, and the compiler named no file"
                    + (explanation == null || explanation.isBlank() ? "" : ": " + explanation.trim())
                : "the candidate does not compile: " + where + what + more
                    + (explanation == null || explanation.isBlank() ? "" : " (" + explanation.trim() + ")");
        };
    }

    private String others() {
        if (failingFiles == null || failingFiles.size() <= 1) {
            return "";
        }
        int rest = failingFiles.size() - 1;
        return " (and " + rest + (rest == 1 ? " more file" : " more files") + " with errors)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompileFailure that = (CompileFailure) o;
        return this.cause == that.cause
            && this.line == that.line
            && this.testFile == that.testFile
            && this.acceptanceTest == that.acceptanceTest
            && this.mainCompiled == that.mainCompiled
            && Objects.equals(this.file, that.file)
            && Objects.equals(this.message, that.message)
            && Objects.equals(this.failingFiles, that.failingFiles)
            && Objects.equals(this.explanation, that.explanation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cause, file, line, message, failingFiles, testFile, acceptanceTest,
            mainCompiled, explanation);
    }
}
