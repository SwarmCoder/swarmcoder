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
package com.swarmcoder.verify;

import com.swarmcoder.domain.AssertionResult;
import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.BuildReachability;
import com.swarmcoder.domain.GuidelineCheckResult;
import com.swarmcoder.domain.PageCheck;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;

import java.util.List;

/**
 * The single definition of "this candidate survived verification" (rule R2 in
 * docs/DEVELOPER_CORRECTIONS.md). A candidate that does not survive MUST NOT reach the judge.
 *
 * <p><b>An empty check is not a pass</b> (author decision, §17.1). Survival used to be
 * "compiles and no acceptance failures and no existing-test failures", and no tests means no
 * failures — so an acceptance stage that selected nothing at all read exactly like one that ran and
 * was green. That is not hypothetical: every {@code verify.yaml} in the project carried a selector
 * that matched no test, so the stage had run zero tests since the day it was written and waved
 * every candidate through. When a task answers to requirement-checks, an acceptance stage that
 * executed no tests is now a FAILURE, and the reason names the checks nothing verified.
 *
 * <p><b>A house rule that declared how to prove itself is part of survival</b> (author decision,
 * §21). A guideline may carry a command; when it does, a candidate whose workspace fails that
 * command has NOT survived, exactly as if a test had failed. This widens what "survived" means, and
 * it is the second time this class has been widened deliberately — the first is recorded above. The
 * argument is the same one: a rule the operator wrote, that names a command, that the command says
 * was broken, is evidence of the same kind as a red test, and a candidate holding it must not reach
 * the judge. A guideline that declares NO command changes nothing here; it stays advice the judge
 * weighs.
 *
 * <p><b>Code the build never compiles has not survived</b> (author decision, 2026-08-30). This is
 * the third widening, and unlike the two above it corrects an existing signal rather than adding
 * one. {@code compiles()} means "the contract's compile commands exited 0" and has never meant
 * "your files were among what they built"; where a candidate wrote outside every source root the
 * build owns, those two came apart completely and the second is the one that matters. A stage that
 * could not read the repository's layout establishes nothing and kills nobody.
 *
 * <p><b>What is deliberately NOT changed.</b> A task that claims no checks keeps the documented M1
 * allowance (§5) exactly as it was: a repository with no acceptance suite yet still swarms. And the
 * new rule fires only on positive evidence that the stage ran and executed nothing — a report from
 * before this rule existed, an unreadable one, and one whose id list was truncated are all
 * inconclusive and kill nothing. Verification failing closed must not mean verification inventing
 * failures it cannot substantiate.
 */
public final class Verdicts {

    private Verdicts() {}

    /**
     * A survival decision together with the reason, so a run can be read rather than guessed at.
     *
     * @param survived true when this candidate may reach the judge
     * @param reason   null when it survived; otherwise plain English saying what went wrong
     */
    public record Verdict(boolean survived, String reason) {

        static final Verdict OK = new Verdict(true, null);

        static Verdict failed(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * Survived = compiles, no acceptance-test failures or errors, no existing-test failures
     * or errors, and — when browser checks ran — every page loaded with every assertion
     * passing. Lint results are recorded but currently advisory.
     *
     * <p>This overload knows nothing about requirement-checks, so it keeps the M1 allowance for a
     * task that claims none. Callers that hold a task use {@link #assess}.
     */
    public static boolean survived(VerificationReport report) {
        return assess(report, List.of()).survived();
    }

    /**
     * The full verdict for a candidate whose task claims {@code claimedChecks}.
     *
     * @param claimedChecks one readable label per requirement-check the task answers for — its ref,
     *                      its wording and the test it names. Empty (or null) means the task claims
     *                      none, and the M1 empty-suite allowance applies unchanged.
     */
    public static Verdict assess(VerificationReport report, List<String> claimedChecks) {
        return assess(report, claimedChecks, AcceptanceProvenance.UNKNOWN);
    }

    /**
     * What the same acceptance tests did on the tree BEFORE this candidate's diff — the cheapest
     * honest proof that running them reached the candidate at all.
     *
     * @param measured            false when nothing established this either way; then this record
     *                            changes no verdict, because an absent instrument is not evidence
     * @param greenWithoutTheDiff true when the tests already passed without the candidate's change
     * @param nothingDeliveredYet true when the tree they passed on carried nothing this run
     *                            delivered. This is what separates a test that measures itself
     *                            from a test an earlier wave legitimately made green — see
     *                            {@link com.swarmcoder.domain.ChecksAlreadyProved}, whose whole
     *                            point is that the second is success and must not stop a run
     * @param note                what ran and what it did, for the operator's sentence
     */
    public record AcceptanceProvenance(boolean measured, boolean greenWithoutTheDiff,
                                       boolean nothingDeliveredYet, String note) {

        public static final AcceptanceProvenance UNKNOWN =
            new AcceptanceProvenance(false, false, false, null);

        /** The tests passed with nothing delivered: they prove nothing about any candidate. */
        public static AcceptanceProvenance provesNothing(String note) {
            return new AcceptanceProvenance(true, true, true, note);
        }

        /** The tests failed before the diff, so running them green now reached the candidate. */
        public static AcceptanceProvenance redWithoutTheDiff(String note) {
            return new AcceptanceProvenance(true, false, false, note);
        }

        boolean provesNoCandidate() {
            return measured && greenWithoutTheDiff && nothingDeliveredYet;
        }
    }

    /**
     * The full verdict, plus what the acceptance tests did before this candidate's diff existed.
     *
     * <p><b>An acceptance pass that would have happened anyway is not a pass</b> (author decision,
     * 2026-09-05, harness run 30). This is the fifth deliberate widening of "survived", and like
     * the third it corrects an existing signal rather than adding one. "The acceptance stage was
     * green" has never meant "the candidate's code ran"; where the test supplied its own
     * implementation of the contract those two came apart completely — the test was green on an
     * empty tree, both candidates "passed" it without a line of their own code being executed, and
     * the story was stamped delivered on it.
     *
     * <p>The signal is the run's own pre-change execution of the same tests, which every task
     * already has: red before the diff means running them reached what the diff changed. Green
     * before the diff means it did not. The one case that is green and still honest — the waves in
     * front of this one delivered what the tests measure — is exactly what {@code
     * ChecksAlreadyProved} records, and it stays a fact and not a fault; only a pass on a tree
     * carrying nothing this run delivered kills a candidate here.
     *
     * <p><b>An enabler is untouched.</b> A task that claims no requirement-check has no acceptance
     * test to pass in the first place, so there is nothing here that could be hollow, and the M1
     * allowance applies exactly as it did.
     */
    public static Verdict assess(VerificationReport report, List<String> claimedChecks,
                                 AcceptanceProvenance provenance) {
        Verdict verdict = assessStages(report, claimedChecks);
        if (!verdict.survived() || provenance == null || !provenance.provesNoCandidate()
                || claimedChecks == null || claimedChecks.isEmpty()) {
            return verdict;
        }
        StringBuilder reason = new StringBuilder(
            "the acceptance test passes without this candidate's change; it proves nothing about "
            + "it. The same test(s) were already green on the tree before any of this run's work, "
            + "so this candidate's code was never on the stack when they passed");
        if (provenance.note() != null && !provenance.note().isBlank()) {
            reason.append(" (").append(provenance.note().strip()).append(')');
        }
        reason.append(".\n\nThat leaves ")
            .append(claimedChecks.size() == 1 ? "the check" : "the checks")
            .append(" this task answers for proved by nothing:");
        for (String check : claimedChecks) {
            reason.append("\n  - ").append(check);
        }
        reason.append("\n\nA test that goes green with nothing delivered measures only itself. "
            + "Every candidate would 'pass' it, so it cannot choose between them and it cannot "
            + "stamp a story delivered. Correct the acceptance test so that it exercises the "
            + "delivered implementation and is red until that implementation exists.");
        return Verdict.failed(reason.toString());
    }

    private static Verdict assessStages(VerificationReport report, List<String> claimedChecks) {
        if (report == null) {
            return Verdict.failed("no verification report was produced");
        }
        if (!report.parses()) {
            return Verdict.failed("the candidate's diff does not parse");
        }
        if (!report.compiles()) {
            // The sentence is the compile stage's own attribution when it has one (author decision,
            // 2026-09-02): "the candidate does not compile" was said four times in one run about
            // candidates whose files were fine, in a tree whose acceptance tests import classes
            // that exist nowhere. Survival is unchanged either way - a tree that does not compile
            // cannot prove a candidate, whoever broke it - only the reason is. A report from
            // before attribution existed keeps the old sentence.
            return Verdict.failed(report.compileFailure() == null
                ? "the candidate does not compile" : report.compileFailure().describe());
        }
        String unbuilt = unbuiltFiles(report.buildReachability());
        if (unbuilt != null) {
            return Verdict.failed(unbuilt);
        }
        String broken = guidelineFailure(report.guidelineChecks());
        if (broken != null) {
            return Verdict.failed(broken);
        }
        String testFailure = testFailure("acceptance", report.acceptance());
        if (testFailure != null) {
            return Verdict.failed(testFailure);
        }
        testFailure = testFailure("existing", report.existing());
        if (testFailure != null) {
            return Verdict.failed(testFailure);
        }
        String browser = browserFailure(report.browser());
        if (browser != null) {
            return Verdict.failed(browser);
        }
        return emptyCheckVerdict(report.acceptance(), claimedChecks);
    }

    /**
     * The gate this class exists for: a task that claims requirement-checks, and an acceptance
     * stage that proved nothing about any of them.
     *
     * <p>The message names every claimed check on purpose. "Verification failed" is a red mark
     * nobody can act on; the actionable fact is that these named checks were claimed, each names a
     * test, and not one test ran — which almost always means the repository's own verification
     * contract is selecting nothing, or the tests were never written. That is precisely the
     * situation this gate exists to shout about, and it stayed silent for weeks.
     */
    private static Verdict emptyCheckVerdict(TestResults acceptance, List<String> claimedChecks) {
        if (claimedChecks == null || claimedChecks.isEmpty()) {
            return Verdict.OK; // the M1 allowance: nothing was claimed, so nothing is unproven
        }
        TestStageOutcome outcome =
            acceptance == null ? TestStageOutcome.INCONCLUSIVE : acceptance.stageOutcome();
        String cause = switch (outcome) {
            case EXECUTED -> acceptance.executed() == 0
                ? "the acceptance stage ran and executed NO tests at all" : null;
            case NOT_CONFIGURED -> "the verification contract (.swarmcoder/verify.yaml) declares no "
                + "acceptance stage, so no test could be run";
            // SKIPPED cannot reach here (an earlier stage failed, and that already lost the
            // verdict above). INCONCLUSIVE — an unreadable report, or one written before this
            // rule existed — establishes nothing, and must not fail a candidate on its own.
            case SKIPPED, INCONCLUSIVE -> null;
        };
        if (cause == null) {
            return Verdict.OK;
        }
        StringBuilder reason = new StringBuilder(cause)
            .append(", yet this task answers for ").append(claimedChecks.size())
            .append(claimedChecks.size() == 1 ? " requirement-check" : " requirement-checks")
            .append(" that only a test can settle:");
        for (String check : claimedChecks) {
            reason.append("\n  - ").append(check);
        }
        reason.append("\n\nNothing here verified any of them, so this candidate has NOT survived "
            + "verification. An acceptance stage that runs no tests reports no failures and looks "
            + "identical to a green one; that is why it is failed rather than passed. Check the "
            + "acceptance selector in .swarmcoder/verify.yaml, and that the acceptance tests were "
            + "actually written.");
        return Verdict.failed(reason.toString());
    }

    /**
     * Null unless this candidate wrote files the build will never compile or package.
     *
     * <p>This is the third deliberate widening of "survived", and it is a correction to the one
     * directly above it rather than a new kind of evidence. {@code compiles()} only ever meant
     * "the commands in the verification contract exited 0". On 2026-08-30 a candidate wrote six
     * files into a source root that does not exist — the root of a repository whose root pom is a
     * bare aggregator — and the contract's compile command built the three untouched modules,
     * exited 0, and certified it. It was true that the build compiled; it was false that the build
     * compiled this. A candidate whose code is not in the build has delivered nothing, so it must
     * not reach the judge, and a stage that could not determine the layout kills nobody.
     */
    private static String unbuiltFiles(BuildReachability reachability) {
        if (reachability == null || !reachability.orphaned()) {
            return null;
        }
        return reachability.describe();
    }

    /**
     * Null when every house rule that declared a check was obeyed, or when none declared one;
     * otherwise the rules this candidate broke, named, with the command that proved it and what
     * the command said.
     *
     * <p>The message names the rule's own words rather than its slug. "Guideline check failed" is
     * a red mark nobody can act on; "you declared a Java record, and this repository's rule says
     * never to" is a fix.
     */
    private static String guidelineFailure(List<GuidelineCheckResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        List<GuidelineCheckResult> broken = results.stream()
            .filter(r -> r != null && !r.passed())
            .toList();
        if (broken.isEmpty()) {
            return null;
        }
        StringBuilder reason = new StringBuilder("this candidate broke ")
            .append(broken.size())
            .append(broken.size() == 1
                ? " house rule that declared how to prove itself:"
                : " house rules that declared how to prove themselves:");
        for (GuidelineCheckResult result : broken) {
            reason.append("\n  - ").append(result.describe());
        }
        reason.append("\n\nA rule carrying a check is not advice: the command it names decides "
            + "survival the same way a test does. Fix the code so the command passes, or change "
            + "the rule on the Guidelines screen — which only the operator can do.");
        return reason.toString();
    }

    /** Null when the stage is clean or said nothing; otherwise why it killed the candidate. */
    private static String testFailure(String stage, TestResults results) {
        FailureSummary summary = summarizeTestFailures(stage, results);
        return summary == null ? null : summary.oneLineSentence();
    }

    /**
     * What one test stage's failures are, in the one form the verdict, the judge's verification
     * block ({@code JudgeClient.acceptanceLine}) and the repair prompt
     * ({@code SwarmEngineImpl.failureEvidence}) all read from — so the same failure is never
     * worded three different ways by three call sites that each formatted it themselves.
     *
     * <p>Names the first failed or errored test in full — its id, exception, message, and where in
     * the kept stack it happened — and counts the rest, exactly as {@code CompileFailureAttribution}
     * does for a failed compile: the evidence a tool already produced, turned into a sentence,
     * instead of a bare count a consumer has no way to act on.
     *
     * @param stage "acceptance" or "existing", exactly as it reads in the sentence
     * @return null when {@code results} is null or the stage has nothing to report (both counts
     *         zero); never null once either count is positive
     */
    public static FailureSummary summarizeTestFailures(String stage, TestResults results) {
        if (results == null || (results.failed() == 0 && results.errored() == 0)) {
            return null;
        }
        String counts = results.failed() + " " + stage + " test(s) failed and "
            + results.errored() + " errored";
        List<TestFailure> failures = results.failures();
        if (failures == null || failures.isEmpty()) {
            // A report persisted before per-test failure detail was recorded, or a runner that
            // gave counts with no detail. The counts are still true; there is nothing more to add.
            return new FailureSummary(counts, counts, false);
        }
        AcceptanceFailureAttribution.Detail detail = AcceptanceFailureAttribution.describe(failures.get(0));
        // Stamped onto the failure itself — not just folded into this sentence — so a consumer
        // that holds the report (SwarmEngineImpl, deciding whether every candidate of a task died
        // on the acceptance test's own bug) can read the fact directly rather than re-deriving it.
        failures.get(0).setInsideTestItself(detail.insideTestItself());
        int more = failures.size() - 1;
        String tail = more <= 0 ? ""
            : " (" + more + (more == 1 ? " more test not shown)" : " more tests not shown)");
        return new FailureSummary(counts + ": " + detail.oneLine() + tail,
            counts + ": " + detail.fullText() + tail, detail.insideTestItself());
    }

    /**
     * @param oneLineSentence  the verdict's and the judge's sentence: the stage counts, then the
     *                         first failure's one-line summary. Guaranteed to carry no newline, so
     *                         a consumer that shows only the first line of a longer reason (a hover
     *                         card) shows this in full.
     * @param fullText         the same sentence with the first failure's kept stack frames added —
     *                         for a consumer with room for a paragraph, namely the repair prompt.
     * @param insideTestItself true when the first failure's trace never left the failing test's
     *                         own class: nothing in it points at the candidate's code at all.
     */
    public record FailureSummary(String oneLineSentence, String fullText, boolean insideTestItself) {}

    /**
     * Null unless a browser check positively established that the candidate's application does not
     * run, or comes up and renders something broken.
     *
     * <p><b>This is the fourth deliberate widening of "survived", and the first about running the
     * product at all</b> (author decision, 2026-08-31). Everything above this line can be satisfied
     * by code that compiles and passes tests, while every acceptance criterion the operator actually
     * writes is about a running application — adding a book makes it appear in the list, the books
     * are still there tomorrow. None of that was ever proved, because nothing ever started the
     * application. Where the contract carries a browser block, "it starts and serves its first page"
     * is now as hard a gate as a red test.
     *
     * <p><b>And the failure mode that gate has to avoid.</b> A hard gate that cannot be attempted
     * would park every run, which is the shape of the most expensive time-losses this project has
     * had. So the stage records which of the two things happened, and only one of them is a verdict.
     * {@link com.swarmcoder.domain.BrowserStageOutcome#EXECUTED} means a browser really ran and
     * these page results are the truth.
     * {@link com.swarmcoder.domain.BrowserStageOutcome#COULD_NOT_TRY} means there was no browser to
     * drive, no way to start a background process on this execution target, or the port was already
     * taken — nothing was learned, so nothing may be concluded, and the candidate is untouched. That
     * is the same line {@code EndpointOutage} draws between a model that refused and a model that
     * was not there, for the same reason: an absent instrument is not evidence.
     */
    private static String browserFailure(BrowserCheckResults browser) {
        if (browser == null || browser.checks() == null) {
            return null; // no browser block configured
        }
        if (browser.couldNotTry()) {
            return null; // an absent instrument, not a verdict; the reason is in the log
        }
        for (PageCheck check : browser.checks()) {
            if (!check.loaded()) {
                return "the application did not serve " + check.url() + " in a real browser: "
                    + firstFailureMessage(check)
                    + "\n\nThis candidate compiles and it does not run. Every acceptance criterion "
                    + "this project answers to is about a working application, so a candidate whose "
                    + "application never came up has delivered nothing, whatever its tests say.";
            }
            if (check.assertions() != null) {
                for (AssertionResult assertion : check.assertions()) {
                    if (!assertion.passed()) {
                        return "the application started, but " + check.url()
                            + " did not hold up in a real browser: "
                            + assertion.selector() + " — " + assertion.message();
                    }
                }
            }
        }
        return null;
    }

    /** The first failing assertion's message on a page, for the sentence the operator reads. */
    private static String firstFailureMessage(PageCheck check) {
        if (check.assertions() != null) {
            for (AssertionResult assertion : check.assertions()) {
                if (!assertion.passed() && assertion.message() != null) {
                    return assertion.message();
                }
            }
        }
        return "the page did not load";
    }
}
