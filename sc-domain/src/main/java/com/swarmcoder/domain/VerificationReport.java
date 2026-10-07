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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class VerificationReport {
    private UUID id;
    private boolean parses;
    private boolean compiles;
    private TestResults acceptance;
    private TestResults existing;
    private LintResults lint;
    private BrowserCheckResults browser;
    private Duration wallTime;
    private String logTail;
    private String fullLogRef;
    /**
     * What each guideline that declared a proof command did in this candidate's workspace
     * (author decision, §21). Null on any report written before this field existed, which reads
     * as "no rule declared a check" — never as "a rule was broken".
     */
    private List<GuidelineCheckResult> guidelineChecks;
    /**
     * Whether the build that just passed would compile or package what this candidate wrote
     * (author decision, 2026-08-30). It sits beside {@link #compiles} because it is the claim
     * {@link #compiles} does not make: a command exiting 0 means the commands succeeded, never
     * that the candidate's files were among what they built. Null on any report written before
     * this field existed, which reads as "nothing was established" — never as "orphaned".
     */
    private BuildReachability buildReachability;
    /**
     * Whose fault a failed compile stage was, read from the compiler's own output against the
     * candidate's diff (author decision, 2026-09-02). It sits beside {@link #compiles} because
     * {@code compiles=false} only ever meant "the compile command exited non-zero", and on the day
     * this was added that was said four times about candidates whose own files were fine, in a
     * tree whose acceptance tests did not compile. Null on any report written before this field
     * existed, which reads as the old sentence — never as an attribution.
     */
    private CompileFailure compileFailure;

    public VerificationReport() {}

    public VerificationReport(UUID id, boolean parses, boolean compiles, TestResults acceptance, TestResults existing, LintResults lint, BrowserCheckResults browser, Duration wallTime, String logTail, String fullLogRef) {
        this.id = id;
        this.parses = parses;
        this.compiles = compiles;
        this.acceptance = acceptance;
        this.existing = existing;
        this.lint = lint;
        this.browser = browser;
        this.wallTime = wallTime;
        this.logTail = logTail;
        this.fullLogRef = fullLogRef;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public boolean parses() { return parses; }
    public boolean getParses() { return parses; }
    public void setParses(boolean parses) { this.parses = parses; }
    public boolean compiles() { return compiles; }
    public boolean getCompiles() { return compiles; }
    public void setCompiles(boolean compiles) { this.compiles = compiles; }
    public TestResults acceptance() { return acceptance; }
    public TestResults getAcceptance() { return acceptance; }
    public void setAcceptance(TestResults acceptance) { this.acceptance = acceptance; }
    public TestResults existing() { return existing; }
    public TestResults getExisting() { return existing; }
    public void setExisting(TestResults existing) { this.existing = existing; }
    public LintResults lint() { return lint; }
    public LintResults getLint() { return lint; }
    public void setLint(LintResults lint) { this.lint = lint; }
    public BrowserCheckResults browser() { return browser; }
    public BrowserCheckResults getBrowser() { return browser; }
    public void setBrowser(BrowserCheckResults browser) { this.browser = browser; }
    public Duration wallTime() { return wallTime; }
    public Duration getWallTime() { return wallTime; }
    public void setWallTime(Duration wallTime) { this.wallTime = wallTime; }
    public String logTail() { return logTail; }
    public String getLogTail() { return logTail; }
    public void setLogTail(String logTail) { this.logTail = logTail; }
    public String fullLogRef() { return fullLogRef; }
    public String getFullLogRef() { return fullLogRef; }
    public void setFullLogRef(String fullLogRef) { this.fullLogRef = fullLogRef; }
    public List<GuidelineCheckResult> guidelineChecks() { return guidelineChecks; }
    public List<GuidelineCheckResult> getGuidelineChecks() { return guidelineChecks; }
    public void setGuidelineChecks(List<GuidelineCheckResult> guidelineChecks) { this.guidelineChecks = guidelineChecks; }
    public BuildReachability buildReachability() { return buildReachability; }
    public BuildReachability getBuildReachability() { return buildReachability; }
    public void setBuildReachability(BuildReachability buildReachability) { this.buildReachability = buildReachability; }
    public CompileFailure compileFailure() { return compileFailure; }
    public CompileFailure getCompileFailure() { return compileFailure; }
    public void setCompileFailure(CompileFailure compileFailure) { this.compileFailure = compileFailure; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VerificationReport that = (VerificationReport) o;
        return Objects.equals(this.id, that.id) && this.parses == that.parses && this.compiles == that.compiles && Objects.equals(this.acceptance, that.acceptance) && Objects.equals(this.existing, that.existing) && Objects.equals(this.lint, that.lint) && Objects.equals(this.browser, that.browser) && Objects.equals(this.wallTime, that.wallTime) && Objects.equals(this.logTail, that.logTail) && Objects.equals(this.fullLogRef, that.fullLogRef) && Objects.equals(this.guidelineChecks, that.guidelineChecks) && Objects.equals(this.buildReachability, that.buildReachability) && Objects.equals(this.compileFailure, that.compileFailure);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, parses, compiles, acceptance, existing, lint, browser, wallTime, logTail, fullLogRef, guidelineChecks, buildReachability, compileFailure);
    }
}

