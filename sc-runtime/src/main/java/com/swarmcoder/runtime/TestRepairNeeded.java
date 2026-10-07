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

import java.util.UUID;

/**
 * Thrown from inside the swarm when every candidate of a task died on a bug in the acceptance
 * test's OWN code — never on a bug of theirs — so a repair round would spend workers fixing code
 * that was never broken (author decision, 2026-09-05: a broken acceptance test goes back to the
 * test author).
 *
 * <p><b>Why this needs its own signal, distinct from a normal repair.</b> A worker cannot edit an
 * acceptance test — it is protected, on purpose, so a candidate can never make its own gate pass.
 * When {@code AcceptanceFailureAttribution} says a failure never leaves the test's own class, the
 * candidate's code was never even exercised, and every repair worker sent after it would be asked
 * to fix a cause that does not exist in anything it is allowed to touch. {@code SwarmEngineImpl}
 * detects this — every candidate of the task failed, and every one of those failures carries that
 * flag — and raises this instead of running the normal repair round.
 *
 * <p>Mirrors {@link RunMustPark} in shape (both stop the run for something only the workflow layer
 * can act on), but the two are handled differently: a park needs an operator, while this is handed
 * straight to the test author — see {@code GreenfieldWorkflow}'s EXECUTING handling, right beside
 * where {@code RunMustPark} is caught. Bounded to one attempt per task by
 * {@code Task#testRepairAttempted()}, checked before this is ever thrown.
 */
public class TestRepairNeeded extends RuntimeException {

    private final UUID taskId;
    private final String testClass;
    private final String testMethod;
    private final String failureMessage;
    private final String frames;
    /**
     * Null for the original fault — the test crashed inside its own code. Otherwise the plain
     * sentence saying the test reached code that can only run in a browser (harness run 37,
     * 2026-09-25): every candidate died with {@code UnsatisfiedLinkError} on a TeaVM native method,
     * because the test called the client module, whose classes exist as JavaScript in a page and not
     * on this JVM. The candidates' code ran — it is on the stack — so "inside the test's own code"
     * is false, yet no candidate could ever pass, and a worker may not edit the test. Same owner,
     * the test author; a different thing to tell it. See {@code BrowserOnlyCode.reachedIn}.
     */
    private final String browserOnlyReason;

    /**
     * @param taskId         the task whose acceptance test is at fault
     * @param testClass      the failing test's fully-qualified class name
     * @param testMethod     the failing test's method name, or null when the test id carried none
     * @param failureMessage the exception's message, exactly as the runner reported it
     * @param frames         the kept stack frames (and any "Caused by:" lines) — the same trace
     *                       text a repair worker would have been shown, had this been its fault
     */
    public TestRepairNeeded(UUID taskId, String testClass, String testMethod,
                            String failureMessage, String frames) {
        super("acceptance test " + testClass + (testMethod == null || testMethod.isBlank()
                ? "" : "#" + testMethod)
            + " failed inside its own code before it could exercise any candidate"
            + (failureMessage == null || failureMessage.isBlank() ? "" : ": " + failureMessage));
        this.taskId = taskId;
        this.testClass = testClass;
        this.testMethod = testMethod;
        this.failureMessage = failureMessage;
        this.frames = frames;
        this.browserOnlyReason = null;
        this.compilerLines = null;
        this.testPath = null;
    }

    /**
     * The fault for a test that reached browser-only code — see {@link #browserOnlyReason()}.
     *
     * @param browserOnlyReason the sentence {@code BrowserOnlyCode.reachedIn} wrote; never blank
     */
    public TestRepairNeeded(UUID taskId, String testClass, String testMethod,
                            String failureMessage, String frames, String browserOnlyReason) {
        super("acceptance test " + testClass + (testMethod == null || testMethod.isBlank()
                ? "" : "#" + testMethod)
            + " can never pass on the JVM: " + browserOnlyReason);
        this.taskId = taskId;
        this.testClass = testClass;
        this.testMethod = testMethod;
        this.failureMessage = failureMessage;
        this.frames = frames;
        this.browserOnlyReason = browserOnlyReason;
        this.compilerLines = null;
        this.testPath = null;
    }

    /**
     * Null for the two faults above. Otherwise the compiler's own lines — {@code file:line:
     * message} — for a test that can never COMPILE for any candidate (brownfield harness run 43,
     * 2026-09-26): both candidates of "Guard Document.ensureMetaCharsetElement against empty XML
     * documents" wrote the right guard and failed verification on the same line of the acceptance
     * test, {@code incompatible types: org.jsoup.parser.Parser cannot be converted to
     * java.lang.String}, and four repair workers then finished without a change, because the guard
     * was already in their checkout and the test is not theirs to edit. A test that misuses a type
     * which already exists is the test author's to fix, like the other two.
     */
    private final String compilerLines;
    /** The test file's repo-relative path, for the compile fault; null for the other two. */
    private final String testPath;

    /** The leading flag only keeps this signature apart from the public five-argument one. */
    private TestRepairNeeded(boolean doesNotCompile, UUID taskId, String testClass, String testPath,
                             String firstError, String compilerLines) {
        super("acceptance test " + testClass + " does not compile for any candidate, and no "
            + "candidate can make it compile: " + firstError);
        this.taskId = taskId;
        this.testClass = testClass;
        this.testMethod = null;
        this.failureMessage = firstError;
        this.frames = null;
        this.browserOnlyReason = null;
        this.compilerLines = compilerLines;
        this.testPath = testPath;
    }

    /**
     * The fault for an acceptance test that does not compile, identically, for every verified
     * candidate, on an error no candidate could fix — see {@link #compilerLines()}.
     *
     * @param testClass     the test's fully-qualified class name, read off its path
     * @param testPath      the test file, repo-relative
     * @param firstError    the first error, {@code file:line message}, for the one-line message
     * @param compilerLines every error line quoted, for the test author
     */
    public static TestRepairNeeded doesNotCompile(UUID taskId, String testClass, String testPath,
                                                  String firstError, String compilerLines) {
        return new TestRepairNeeded(true, taskId, testClass, testPath, firstError, compilerLines);
    }

    /**
     * The fourth shape (harness run 79, 2026-10-04): the test is SUSPECT, not known to be broken.
     * Every independently written first candidate compiled and failed the same test method(s)
     * with the same assertion. The test author is asked which side is wrong before any repair
     * round; see {@code SameFailureForEveryCandidate}.
     *
     * @param evidence what the author is shown: each candidate's failure and its change
     */
    public static TestRepairNeeded suspect(UUID taskId, String testClass, String testMethod,
                                           String failureMessage, int candidates,
                                           String evidence) {
        TestRepairNeeded fault = new TestRepairNeeded("acceptance test " + testClass
            + (testMethod == null || testMethod.isBlank() ? "" : "#" + testMethod)
            + " failed the same way for all " + candidates + " independently written "
            + "candidates, so the test itself is suspect"
            + (failureMessage == null || failureMessage.isBlank() ? "" : ": " + failureMessage),
            taskId, testClass, testMethod, failureMessage);
        fault.suspectEvidence = evidence == null ? "" : evidence;
        return fault;
    }

    private TestRepairNeeded(String message, UUID taskId, String testClass, String testMethod,
                             String failureMessage) {
        super(message);
        this.taskId = taskId;
        this.testClass = testClass;
        this.testMethod = testMethod;
        this.failureMessage = failureMessage;
        this.frames = null;
        this.browserOnlyReason = null;
        this.compilerLines = null;
        this.testPath = null;
    }

    private String suspectEvidence;

    /** True for the fourth shape: asked about, not declared broken. */
    public boolean suspect() { return suspectEvidence != null; }
    public String suspectEvidence() { return suspectEvidence; }

    public UUID taskId() { return taskId; }
    public String testClass() { return testClass; }
    public String testMethod() { return testMethod; }
    public String failureMessage() { return failureMessage; }
    public String frames() { return frames; }
    /** Null unless the test reached code that can only run in a browser; then why, in one sentence. */
    public String browserOnlyReason() { return browserOnlyReason; }
    /** True when the fault is a test that reached browser-only code, not one that crashed in itself. */
    public boolean reachedBrowserOnlyCode() { return browserOnlyReason != null; }
    /** Null unless the test does not compile for any candidate; then the compiler's lines. */
    public String compilerLines() { return compilerLines; }
    /** The test file, repo-relative, for the compile fault; null otherwise. */
    public String testPath() { return testPath; }
    /** True when the fault is a test that can never compile, not one that ran and crashed. */
    public boolean doesNotCompile() { return compilerLines != null; }
}
