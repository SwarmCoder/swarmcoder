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

import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Task;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.verify.BrowserOnlyCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * An acceptance test is held to the project's standing rules, the way a design and a plan are.
 *
 * <p><b>Why</b> (live harness runs 56 and 58, 2026-10-01). A project's technical document said a
 * service is never constructed by hand in a test, because its database is injected, and named the
 * test server to get it from. The test author wrote a test that constructed the service by hand.
 * The red-check passed, because the only compile error was the class nobody had built yet. Both
 * workers then spent 80 minutes failing to make a hand-constructed service find a database nothing
 * would ever inject, wrote nothing, and the run was aborted. Telling the author the rules was not
 * enough, and nothing read what it wrote against them.
 *
 * <p><b>What happens.</b> The same shape as DESIGN_REVIEW's check of a design against the rules:
 * <ol>
 *   <li>One model call ({@link DesignReviewerClient#reviewTests}) reads the written test against
 *       the rules. An objection naming a rule the project does not have is dropped, so a reviewer
 *       cannot stop a run over a rule it made up.</li>
 *   <li>A conflict goes back to the test author ONCE, with the rule in full and the offending
 *       line ({@link TestAuthorClient#repairRuleBreakingTest}, the broken-test repair path).</li>
 *   <li>Still conflicting: a HARD rule parks the run, as a design that still breaks a rule does;
 *       a preference is recorded and the run carries on, because a preference never stops work
 *       (see {@link ConstraintBrief}). The park says in so many words that the answer may be to
 *       change the rule.</li>
 * </ol>
 *
 * <p>A correction made later, by any of the re-authoring paths, is read against the rules too
 * ({@link #afterReauthoring}) and gets no further attempt: it was the bounded attempt.
 *
 * <p>Fails soft: a reviewer that is unavailable or replies with nonsense reviews nothing and
 * blocks nothing. Nothing here knows any library or any project by name.
 */
final class TestsVersusRules {

    private TestsVersusRules() {}

    /** An objection whose quoted offending line is the test's own package statement. */
    private static final java.util.regex.Pattern PACKAGE_LINE =
        java.util.regex.Pattern.compile("^\\s*line\\s+'\\s*package\\s+[\\w.]+\\s*;?\\s*'");

    /**
     * @param corrected whether the test author rewrote at least one file — the caller must then
     *                  re-run every mechanical check it ran on the first writing
     * @param carried   preference-rule conflicts left standing; recorded, never a stop
     * @param parkBrief null when the run may carry on
     * @param hard      the hard-rule conflicts {@code parkBrief} is about, one objection each —
     *                  what an unattended run records as warnings instead of parking (this whole
     *                  check is a reviewer model's opinion; see {@code OpinionPolicy})
     */
    record Outcome(boolean corrected, List<String> carried, String parkBrief, List<String> hard) {

        static final Outcome CLEAN = new Outcome(false, List.of(), null, List.of());

        boolean parks() {
            return parkBrief != null;
        }
    }

    /**
     * Reviews one task's freshly written test file(s) against the rules, and sends them back to
     * their author once when they conflict.
     *
     * @param root  where the files are on disk; a correction is written here, over them
     * @param paths this task's test files, relative to {@code root}
     * @param log   one line per thing a person following the run should see
     */
    static Outcome hold(DesignReviewerClient reviewer, TestAuthorClient author, Path root, Task task,
                        DesignDocument design, List<String> paths, String rulesBrief,
                        Librarian librarian, Consumer<String> log) {
        Map<String, String> files = read(root, paths);
        List<String> conflicts = conflicts(reviewer, files, rulesBrief, task, log);
        if (conflicts.isEmpty()) {
            return Outcome.CLEAN;
        }
        log.accept("the test(s) for task '" + task.title() + "' conflict with the project's rules: "
            + conflicts + " — asking the test author to correct them (one attempt)");
        TestAuthorClient.Authored repaired = author.repairRuleBreakingTest(root, task, design,
            files, reask(conflicts, rulesBrief, librarian));
        if (repaired.failureReason() != null) {
            return limit(task, conflicts, rulesBrief, false, "The test author was asked once to "
                + "correct them, and its repair call did not produce a corrected file: "
                + repaired.failureReason(), log);
        }
        List<String> still = conflicts(reviewer, read(root, paths), rulesBrief, task, log);
        if (still.isEmpty()) {
            log.accept("the test(s) for task '" + task.title() + "' were corrected by the test "
                + "author and no longer conflict with the project's rules");
            return new Outcome(true, List.of(), null, List.of());
        }
        return limit(task, still, rulesBrief, true, "The test author was asked once to correct "
            + "them, and the corrected test(s) still conflict.", log);
    }

    /**
     * Reads a correction some OTHER repair just wrote against the rules. No further attempt: the
     * correction was the test author's one bounded attempt at that fault.
     *
     * @return the brief for a parked run when the correction breaks a HARD rule; otherwise null
     */
    static String afterReauthoring(DesignReviewerClient reviewer, Path root, Task task,
                                   List<String> paths, String rulesBrief, Consumer<String> log) {
        return reauthored(reviewer, root, task, paths, rulesBrief, log).parkBrief();
    }

    /** {@link #afterReauthoring} with the conflicts themselves, for a caller that may carry them. */
    static Outcome reauthored(DesignReviewerClient reviewer, Path root, Task task,
                              List<String> paths, String rulesBrief, Consumer<String> log) {
        List<String> conflicts = conflicts(reviewer, read(root, paths), rulesBrief, task, log);
        if (conflicts.isEmpty()) {
            return Outcome.CLEAN;
        }
        return limit(task, conflicts, rulesBrief, false, "This is the test author's correction of "
            + "an earlier fault in the same test, so it has had its one attempt.", log);
    }

    /**
     * The rule conflicts in these files: reviewer objections that name a rule this project really
     * has. Empty when the files are clean, when there are no rules, and when the reviewer failed.
     */
    static List<String> conflicts(DesignReviewerClient reviewer, Map<String, String> files,
                                  String rulesBrief, Task task, Consumer<String> log) {
        if (reviewer == null || files.isEmpty() || rulesBrief == null || rulesBrief.isBlank()) {
            return List.of();
        }
        DesignReviewerClient.Review review = reviewer.reviewTests(rulesBrief, files);
        List<String> objections = review.objections == null ? List.of() : review.objections;
        if (review.approved) {
            // Empty, or a fail-soft note (unavailable, twice malformed) — never a real objection.
            log.accept(objections.isEmpty()
                ? "the test(s) for task '" + task.title() + "' were reviewed against "
                    + ForbiddenTechGuard.ruleCount(rulesBrief) + " rule(s): no objections"
                : String.join("; ", objections));
            return List.of();
        }
        List<String> conflicts = new ArrayList<>();
        for (String objection : objections) {
            String title = RuleConflictFeedback.ruleTitleOf(objection);
            if (PACKAGE_LINE.matcher(objection).find()) {
                // Where an acceptance test lives is the system's choice; the author cannot move it.
                log.accept("ignoring a test review objection to the test's package, which the "
                    + "test author does not choose: " + objection);
            } else if (title != null && !ForbiddenTechGuard.chunkFor(rulesBrief, title).isBlank()) {
                conflicts.add(objection);
            } else {
                log.accept("ignoring a test review objection that names no rule this project "
                    + "has: " + objection);
            }
        }
        return conflicts;
    }

    /** Whether the rule this objection names is a HARD one — the only kind that stops a run. */
    static boolean breaksAHardRule(String objection, String rulesBrief) {
        String title = RuleConflictFeedback.ruleTitleOf(objection);
        return title != null && ForbiddenTechGuard.chunkFor(rulesBrief, title)
            .contains(ConstraintBrief.HARD_RULE_LINE);
    }

    /** What the test author is told: each conflict, the rule in full, and the offending line. */
    static String reask(List<String> conflicts, String rulesBrief, Librarian librarian) {
        return "Your test(s) break this project's standing rules. Each conflict below quotes the "
            + "offending line and gives the rule in full:\n\n  "
            + String.join("\n\n  ", RuleConflictFeedback.enrich(conflicts, rulesBrief, librarian))
            + "\n\nRewrite the test(s) so that no line breaks a rule. Where a rule says how a "
            + "test obtains or starts the code it exercises, do exactly what the rule says, with "
            + "the names the rule gives. A rule is not negotiable per test: do not keep the "
            + "offending line behind a fallback or a comment.";
    }

    /** One attempt was made (or none is owed). Hard conflicts park; preferences are recorded. */
    private static Outcome limit(Task task, List<String> conflicts, String rulesBrief,
                                 boolean corrected, String attempt, Consumer<String> log) {
        List<String> hard = new ArrayList<>();
        List<String> preference = new ArrayList<>();
        for (String conflict : conflicts) {
            (breaksAHardRule(conflict, rulesBrief) ? hard : preference).add(conflict);
        }
        if (!preference.isEmpty()) {
            log.accept("the test(s) for task '" + task.title() + "' go against a preference, "
                + "which costs quality and never stops the work: " + preference);
        }
        if (hard.isEmpty()) {
            return new Outcome(corrected, List.copyOf(preference), null, List.of());
        }
        log.accept("MISMATCH: the test(s) for task '" + task.title() + "' break a hard rule: "
            + hard);
        return new Outcome(corrected, List.copyOf(preference), brief(task.title(), hard,
            rulesBrief, attempt), List.copyOf(hard));
    }

    /** The brief for a parked run: the rule, the line, and both ways out. */
    static String brief(String taskTitle, List<String> hard, String rulesBrief, String attempt) {
        return "The acceptance test(s) for task '" + taskTitle + "' break a hard rule of this "
            + "project:\n\n  "
            + String.join("\n\n  ", RuleConflictFeedback.enrich(hard, rulesBrief, null))
            + "\n\n" + attempt + "\n\nEvery candidate is judged by this test, so a test that "
            + "breaks the rule asks every worker to make something work that the rule says "
            + "cannot: they would spend their whole budget on it and deliver nothing.\n\n"
            + "Either correct the test yourself so it does what the rule says, or, if the test is "
            + "right and the rule is what does not fit, reword the rule or make it a preference. "
            + "Then resume the run.";
    }

    /** Path to content for every file that can be read; a file that cannot is simply not reviewed. */
    static Map<String, String> read(Path root, List<String> paths) {
        Map<String, String> files = new LinkedHashMap<>();
        if (root == null || paths == null) {
            return files;
        }
        for (String path : paths) {
            try {
                files.put(path, Files.readString(root.resolve(path)));
            } catch (IOException | RuntimeException e) {
                // Not this check's failure to report: every other check reads the same file.
            }
        }
        return files;
    }

    /**
     * The first writing's result with every mechanical check run again over the corrected files —
     * a correction may not buy rule-compliance with a type nobody delivers, its own copy of the
     * contract, browser-only code or reflection.
     */
    static TestAuthorClient.Authored rechecked(Path root, DesignDocument design,
                                               TestAuthorClient.Authored first,
                                               BrowserOnlyCode.Survey survey, List<Task> planTasks) {
        List<String> paths = first.paths();
        return new TestAuthorClient.Authored(paths, first.claims(), null,
            AcceptanceTestVocabulary.check(root, design, paths, planTasks),
            SelfImplementedContract.check(root, design, paths, planTasks),
            AcceptanceTestReach.check(root, survey == null ? BrowserOnlyCode.Survey.NONE : survey,
                paths),
            AcceptanceTestReflection.check(root, paths), first.journeys(),
            first.journeyWaiver());
    }
}
