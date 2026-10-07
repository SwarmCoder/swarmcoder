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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a verification report as evidence about criteria, and decides whether a story's quality
 * gates permit delivery.
 *
 * <p>A criterion names a test, and the only three answers are these. The named test ran and failed
 * (or errored): FAILED. The named test ran and passed: PASSED. <b>Anything else is UNKNOWN</b> — no
 * test was named, nothing ran, the named test was skipped, or nothing the runner reported is that
 * test. UNKNOWN is never "passed": treating absence of evidence as success is how a requirement
 * ends up claiming to be implemented because nobody checked.
 *
 * <p>That last case is the one this class exists for. Until 2026-08-27 the rule was "the suite ran
 * and nothing matching it failed", so a criterion naming a test that had never been written came
 * out PASSED as soon as any other acceptance test ran — the requirement reached IMPLEMENTED on
 * evidence about somebody else's test. The rule now needs a positive match against an id the runner
 * actually reported as passing, which is why {@code TestResults} carries the ids at all.
 */
public final class CriterionEvidence {

    private CriterionEvidence() {}

    public enum Outcome { PASSED, FAILED, UNKNOWN }

    /**
     * The gate decision for a run.
     *
     * @param blocked   true when an enforced quality gate failed — integration must not proceed
     * @param blocking  human-readable reasons the run is blocked
     * @param warnings  advisory problems: DRAFT gates that failed, ACTIVE gates with nothing to
     *                  measure them, and criteria whose tests could not be located
     */
    public record GateVerdict(boolean blocked, List<String> blocking, List<String> warnings) {}

    /** Per-criterion outcome for the story's own slice, keyed by criterion id. */
    public static Map<UUID, Outcome> outcomes(StoryScope scope, VerificationReport report) {
        Map<UUID, Outcome> outcomes = new LinkedHashMap<>();
        for (AcceptanceCriterion criterion : scope.criteria()) {
            outcomes.put(criterion.id(), outcomeOf(criterion, report));
        }
        return outcomes;
    }

    /**
     * One readable line per criterion in the slice: its ref, its outcome and the reason.
     *
     * <p>Exists because "not every criterion is satisfied" is an answer nobody can act on. The
     * common cause is a criterion naming a test that never ran, and the only way to see that is to
     * be told which criterion and which test.
     */
    public static List<String> explain(StoryScope scope, VerificationReport report) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < scope.criteria().size(); i++) {
            AcceptanceCriterion criterion = scope.criteria().get(i);
            Evidence evidence = evidenceFor(criterion.testClassOrFile(), report);
            lines.add(scope.criterionRefs().get(i) + " " + evidence.outcome() + " — "
                + criterion.text() + " [" + evidence.reason() + "]");
        }
        return lines;
    }

    /**
     * Evaluates the non-functional requirements gating this story, per the enforcement table in
     * docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md §2.6.
     *
     * <p>The distinction that matters: an ACTIVE NFR with an accepted, measurable fitness criterion
     * BLOCKS when it fails, because the operator deliberately promoted it to enforced. A DRAFT one
     * only warns, so a half-written quality requirement cannot wedge every run. An ACTIVE NFR with
     * nothing to measure it warns and nags rather than blocking — it would otherwise block forever,
     * since there is no test that could ever satisfy it.
     */
    public static GateVerdict evaluateGates(StoryScope scope, VerificationReport report) {
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (BrdRequirement nfr : scope.gatingNfrs()) {
            if (nfr.status() == RequirementStatus.DEPRECATED) {
                continue;
            }
            List<AcceptanceCriterion> fitness = new ArrayList<>();
            for (AcceptanceCriterion c : nfr.criteria()) {
                if (c.status() == CriterionStatus.ACCEPTED && c.testClassOrFile() != null
                        && !c.testClassOrFile().isBlank()) {
                    fitness.add(c);
                }
            }
            boolean enforced = nfr.status() == RequirementStatus.ACTIVE;
            if (fitness.isEmpty()) {
                String note = nfr.handle() + " (" + nfr.title() + ") has no measurable fitness "
                    + "criterion, so nothing can verify it";
                if (enforced) {
                    warnings.add(note + " — it is ACTIVE but cannot gate anything until one names a test");
                } else {
                    warnings.add(note);
                }
                continue;
            }
            for (AcceptanceCriterion c : fitness) {
                Evidence evidence = evidenceFor(c.testClassOrFile(), report);
                Outcome outcome = evidence.outcome();
                if (outcome == Outcome.FAILED) {
                    String note = nfr.handle() + " failed: " + c.text()
                        + " (" + c.testClassOrFile() + ")";
                    if (enforced) {
                        blocking.add(note);
                    } else {
                        warnings.add(note + " — advisory, " + nfr.handle() + " is still DRAFT");
                    }
                } else if (outcome == Outcome.UNKNOWN && enforced) {
                    warnings.add(nfr.handle() + ": the gate could not be evaluated — "
                        + evidence.reason());
                }
            }
        }
        return new GateVerdict(!blocking.isEmpty(), List.copyOf(blocking), List.copyOf(warnings));
    }

    /** True when every criterion in the slice passed — the story may move to REVIEW. */
    public static boolean allDelivered(StoryScope scope, VerificationReport report) {
        if (scope.isEmpty()) {
            return answersToNoRequirement(scope);
        }
        for (Outcome outcome : outcomes(scope, report).values()) {
            if (outcome != Outcome.PASSED) {
                return false;
            }
        }
        return true;
    }


    /**
     * True when this story has no criteria <b>because it answers to no requirement</b> — the
     * freeform escape hatch, and the only way in on a project nobody has written requirements for.
     *
     * <p><b>Why an empty slice is not always a failure.</b> "Every criterion passed" is vacuously
     * true when there are none, and the rule here used to be a flat {@code return false} for any
     * empty scope. That is right for a story that names requirements and produced no checks — it
     * promised something and proved nothing — and wrong for an AD_HOC story, whose whole definition
     * is that it carries no requirement yet. Measured on 2026-08-28: an unscoped run designed,
     * planned, had four acceptance tests written for it, watched them go red, ran two workers,
     * verified both candidates green (4 acceptance and 7 existing tests passing each), selected a
     * winner and merged it onto an integration branch — and was then recorded as ABORTED, because
     * the story it had been given carried no requirement-checks. The change was real, verified and
     * already merged; only the run's state disagreed with what it had done.
     *
     * <p><b>It was hidden by a second bug.</b> {@code recordDelivery} returns true immediately for
     * a run with no story at all, and the chat used to attach the ad-hoc story AFTER handing the
     * run to the engine — a binding that is always lost, because the engine advances the run on its
     * own thread at once. So the run the engine held had no story, took the early return, and
     * delivered. Fixing the binding is what made this visible.
     *
     * <p><b>What this does not do.</b> It does not claim a requirement was met — there is none, and
     * no criterion is stamped. The story goes to REVIEW, which is the operator's question ("is this
     * what I asked for?"), never DONE. And a story that DOES name requirements but has no accepted
     * criteria is still blocked, exactly as before: it promised something specific, and an empty
     * slice there means its checks were never agreed or have all been retired, which is a defect
     * rather than a shape of work.
     */
    private static boolean answersToNoRequirement(StoryScope scope) {
        var story = scope.story();
        if (story == null) {
            // No story at all is the plainest unscoped run there is, and recordDelivery already
            // treats one as deliverable before it ever reaches here.
            return true;
        }
        boolean namesNoRequirement =
            story.requirementIds() == null || story.requirementIds().isEmpty();
        return namesNoRequirement
            && story.origin() == com.swarmcoder.domain.StoryOrigin.AD_HOC;
    }

    private static Outcome outcomeOf(AcceptanceCriterion criterion, VerificationReport report) {
        return outcomeOf(criterion.testClassOrFile(), report);
    }

    /**
     * What the acceptance stage says about one test reference.
     *
     * <p>The rule, and the reason for it: <b>a criterion may only be PASSED when a test matching it
     * is reported as having run and passed.</b> The previous rule was "the suite ran and nothing
     * matching it failed", which reads absence of evidence as success — a criterion naming a test
     * nobody ever wrote came out PASSED as long as some other test ran. That is the exact failure
     * that certifies unbuilt work as delivered.
     */
    static Outcome outcomeOf(String testRef, VerificationReport report) {
        return evidenceFor(testRef, report).outcome();
    }

    /**
     * True when {@code testRef} names one of {@code reportedIds}, by the same segment-matching rule
     * this class uses against a real runner's output.
     *
     * <p>Exposed so TEST_AUTHORING can ask "does the test this criterion names actually exist among
     * the ones just written?" — the same question, one stage earlier, where the answer is cheap.
     * Both sides must use ONE matcher: a criterion that matched here but not there, or the other
     * way round, would be worse than no check at all.
     */
    public static boolean namesOneOf(String testRef, java.util.Collection<String> reportedIds) {
        TestRef wanted = TestRef.parse(testRef);
        if (wanted == null || reportedIds == null) {
            return false;
        }
        for (String id : reportedIds) {
            if (wanted.matches(id)) {
                return true;
            }
        }
        return false;
    }

    /** An outcome together with the reason for it, so a warning can say what was actually missing. */
    public record Evidence(Outcome outcome, String reason) {}

    /** The outcome for one test reference, with the reason it came out that way. */
    public static Evidence evidenceFor(String testRef, VerificationReport report) {
        if (testRef == null || testRef.isBlank()) {
            return new Evidence(Outcome.UNKNOWN, "no test is named");
        }
        if (report == null || report.acceptance() == null) {
            return new Evidence(Outcome.UNKNOWN, "no acceptance results were reported");
        }
        TestRef wanted = TestRef.parse(testRef);
        if (wanted == null) {
            return new Evidence(Outcome.UNKNOWN,
                "\"" + testRef + "\" is not a usable test reference");
        }
        TestResults acceptance = report.acceptance();

        List<TestFailure> failures =
            acceptance.failures() == null ? List.of() : acceptance.failures();
        for (TestFailure failure : failures) {
            if (failure != null && wanted.matches(failure.testId())) {
                return new Evidence(Outcome.FAILED, failure.testId() + " failed");
            }
        }
        for (String id : acceptance.passedIds()) {
            if (wanted.matches(id)) {
                return new Evidence(Outcome.PASSED, id + " passed");
            }
        }
        for (String id : acceptance.skippedIds()) {
            if (wanted.matches(id)) {
                // Skipped is not passed. A disabled test proves nothing, and disabling one is the
                // cheapest possible way to turn a red criterion green.
                return new Evidence(Outcome.UNKNOWN, id + " was skipped, so it proves nothing");
            }
        }
        int executed = acceptance.passed() + acceptance.failed() + acceptance.errored();
        if (executed == 0) {
            return new Evidence(Outcome.UNKNOWN, "the acceptance suite ran no tests at all");
        }
        if (acceptance.passedIds().isEmpty() && acceptance.passed() > 0) {
            // A report from before ids were recorded. It cannot answer the question, so it does not
            // get to answer it: unknown, never passed.
            return new Evidence(Outcome.UNKNOWN, "this report records no test ids, so whether "
                + testRef + " ran cannot be established");
        }
        if (acceptance.idsTruncated()) {
            return new Evidence(Outcome.UNKNOWN, "the report's test-id list was truncated, so "
                + testRef + " being absent from it means nothing");
        }
        return new Evidence(Outcome.UNKNOWN, "no test matching " + testRef + " ran; "
            + executed + " other test(s) did");
    }

    /**
     * A test reference split into the parts that can actually be compared.
     *
     * <p>The two sides are written by different authors and neither can be made to change. A
     * criterion is typed by a person or emitted by a model — {@code GuestCheckoutTest#noAccount}, a
     * bare method name, a fully-qualified class, a source path. The ids come from whatever the
     * runner chose to print. Real Maven Surefire output for JUnit 5 is {@code <classname>#<name>},
     * where the classname carries {@code $} for a nested class
     * ({@code swarm.accept.SpellingsTest$Inner}) and the name carries the signature and the
     * invocation index for a parameterised test ({@code parameterised(int)[2]}).
     *
     * <p>Comparison is on whole segments, never on substrings. The old matcher asked whether either
     * string contained the other, so a criterion naming {@code multiply} matched a failure in
     * {@code multiplyByZero} — a criterion could be failed, or spared, by a test that had nothing
     * to do with it.
     */
    record TestRef(String className, String method) {

        static TestRef parse(String raw) {
            if (raw == null) {
                return null;
            }
            String ref = raw.trim().replace("::", "#");
            if (ref.endsWith("()")) {
                ref = ref.substring(0, ref.length() - 2);
            }
            if (ref.isEmpty()) {
                return null;
            }
            // A source path names a class and nothing else.
            String slashed = ref.replace('\\', '/');
            if (slashed.contains("/") || endsWithJava(slashed)) {
                String last = slashed.substring(slashed.lastIndexOf('/') + 1);
                if (endsWithJava(last)) {
                    last = last.substring(0, last.length() - 5);
                }
                return last.isEmpty() ? null : new TestRef(last, null);
            }
            int hash = ref.indexOf('#');
            if (hash >= 0) {
                String type = ref.substring(0, hash).trim();
                String member = methodBase(ref.substring(hash + 1).trim());
                return new TestRef(type.isEmpty() ? null : type, member.isEmpty() ? null : member);
            }
            // No marker at all. A trailing segment starting lower-case is a method name; anything
            // else is a type. This is the one genuinely ambiguous spelling, and the convention is
            // universal enough in Java to be worth honouring.
            int dot = ref.lastIndexOf('.');
            String last = dot >= 0 ? ref.substring(dot + 1) : ref;
            if (!last.isEmpty() && Character.isLowerCase(last.charAt(0))) {
                String type = dot >= 0 ? ref.substring(0, dot) : null;
                return new TestRef(type == null || type.isEmpty() ? null : type, methodBase(last));
            }
            return new TestRef(ref, null);
        }

        private static boolean endsWithJava(String value) {
            return value.length() > 5 && value.regionMatches(true, value.length() - 5, ".java", 0, 5);
        }

        /** {@code parameterised(int)[2]} and {@code works(TestInfo)} both reduce to the bare name. */
        private static String methodBase(String name) {
            int cut = name.length();
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                if (ch == '(' || ch == '[' || ch == ' ') {
                    cut = i;
                    break;
                }
            }
            return name.substring(0, cut).trim();
        }

        /** True when {@code reportedId} — a {@code classname#name} the runner emitted — is this test. */
        boolean matches(String reportedId) {
            if (reportedId == null || reportedId.isBlank()) {
                return false;
            }
            String id = reportedId.trim();
            int hash = id.lastIndexOf('#');
            String reportedClass;
            String reportedMethod;
            if (hash >= 0) {
                reportedClass = id.substring(0, hash);
                reportedMethod = methodBase(id.substring(hash + 1));
            } else {
                // No marker. Our own JUnit XML parser always writes classname#name, but a report
                // reaching us from anywhere else may use the dotted form, and reading
                // com.acme.CartTest.total as a class called "total" would silently match nothing.
                int dot = id.lastIndexOf('.');
                String tail = dot >= 0 ? id.substring(dot + 1) : id;
                if (dot > 0 && !tail.isEmpty() && Character.isLowerCase(tail.charAt(0))) {
                    reportedClass = id.substring(0, dot);
                    reportedMethod = methodBase(tail);
                } else {
                    reportedClass = id;
                    reportedMethod = null;
                }
            }

            if (className != null && !classMatches(reportedClass)) {
                return false;
            }
            if (method == null) {
                // A class-level criterion is about every test in the class.
                return true;
            }
            return reportedMethod != null && !reportedMethod.isEmpty()
                && reportedMethod.equalsIgnoreCase(method);
        }

        private boolean classMatches(String reportedClass) {
            for (String candidate : classNamesOf(reportedClass)) {
                if (candidate.equalsIgnoreCase(className)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Every name by which a reported class can legitimately be called: fully qualified, without
         * its package, each enclosing and nested part on its own, and the {@code $}-written-as-
         * {@code .} spelling a person would use for a nested class.
         */
        private static List<String> classNamesOf(String reportedClass) {
            List<String> names = new ArrayList<>();
            String simple = reportedClass.substring(reportedClass.lastIndexOf('.') + 1);
            names.add(reportedClass);
            names.add(simple);
            names.add(reportedClass.replace('$', '.'));
            names.add(simple.replace('$', '.'));
            for (String part : simple.split("\\$")) {
                if (!part.isEmpty()) {
                    names.add(part);
                }
            }
            int dollar = reportedClass.indexOf('$');
            if (dollar > 0) {
                names.add(reportedClass.substring(0, dollar));
            }
            return names;
        }
    }
}
