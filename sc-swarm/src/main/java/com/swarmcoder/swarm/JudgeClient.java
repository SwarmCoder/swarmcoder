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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.ChecksAlreadyProved;
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.PageCheck;
import com.swarmcoder.domain.RuleDispute;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.verify.Verdicts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.runtime.CloudGate;
import java.io.IOException;

/**
 * LLM judge (spec §11.5): scores verification-surviving candidates. The judge only ever sees
 * the diff, the active house rules and a verification summary — never a worker trajectory (the
 * cost-model rule from architecture §4.2). A judging failure yields a neutral score rather than
 * losing the candidate, and every candidate of that task then holds the same neutral number, so
 * {@link SelectionLogic} decides on what it ranks below the score: whether the candidate survived
 * verification, then how many workers independently produced the same change, then the size of the
 * diff. That last one was claimed here for months and was not true — selection never read diff size
 * until 2026-08-31, when it was made true rather than struck out, because at equal evidence the
 * smaller change is the safer one.
 *
 * <p><b>The judge is shown the house rules</b> (author decision, §21). It used to be handed the
 * diff, the task and a verification summary and nothing else, so a candidate that ignored a rule
 * the operator had written — and that demonstrably reached the worker's prompt — could not be
 * marked down even in principle. The rules are free information that was simply being withheld
 * from the one stage whose whole job is judgement.
 *
 * <p>Two things are deliberate about how they are shown. The judge's budget is not the worker's:
 * the rules are capped at {@link #MAX_GUIDELINE_CHARS}, a fraction of the worker's, because the
 * diff is what the judge is actually reading and a long rulebook must never crowd it out. And a
 * breach has to be VISIBLE — the judge is asked to name every rule it thinks was broken, and any
 * it names is written into the rationale the operator reads in the Gallery. A score that dropped
 * for a reason nobody can see is not enforcement, it is noise.
 *
 * <p><b>WHICH rules fit is now a decision</b> (2026-09-01). The cap was applied to one already
 * concatenated string, so the rules the judge enforced were whichever ones happened to be rendered
 * first — on the operator's live project, about a tenth of them, while the prompt above told the
 * judge to check the diff against every rule. {@link JudgeRules} decides instead: a rule a person
 * stated outranks one a model proposed, a rule about this diff outranks one that is not, and every
 * rule that still does not fit is NAMED in the brief with a sentence saying it is in force and was
 * left out for space. The cap itself doubled, which is the smaller half of the fix.
 *
 * <p><b>The judge is told when nothing was verified</b> (2026-08-31). The verification summary was
 * written into the brief only when a {@link VerificationReport} existed, and it usually does not:
 * a repository with no {@code .swarmcoder/verify.yaml} takes the short path through the swarm
 * engine that returns the candidate untouched, so it arrives here marked SURVIVED with a null
 * report. In the operator's real store, 775 of 776 archived candidates carry no report, and all
 * fifteen ever judged were in that state — meaning the judge has never once been told whether the
 * code in front of it compiles. Absence read as silence and silence read as "fine": a run in which
 * every candidate was unverified came back with a flat 1.0 on every candidate, one of which was
 * then selected. The block is now always written, and says plainly that nothing was built or run.
 *
 * <p>Being told is necessary and not sufficient, so the limit is also enforced in code — see
 * {@link #verificationCeiling} — and the scale the judge is asked to use now has anchors on it.
 */
public class JudgeClient {

    private static final Logger log = LoggerFactory.getLogger(JudgeClient.class);
    private static final int MAX_DIFF_CHARS = 24_000;
    /**
     * Cap on the house rules put in front of the judge — half the worker's briefing and a quarter
     * of the diff allowance. The cap exists because the judge is reading a DIFF: a rulebook that
     * crowds the diff out of the window makes the judge worse at the only thing it is for, and
     * that reasoning is unchanged.
     *
     * <p>It was 3,000 and is now 6,000, and the number is the SMALLER half of this change. The
     * operator's live project carries 28,441 characters of active rules, so at 3,000 the judge was
     * shown about a tenth of them — and WHICH tenth was decided by the order the rules happened to
     * be concatenated in, while the same prompt told it to "check the diff against every rule".
     * Doubling the window alone would only move that line, because a project can always have more
     * rules than fit. What decides which rules go in is {@link JudgeRules}: a person's rule before
     * a machine's, a rule about this diff before one that is not, and everything left out NAMED
     * rather than silently cut off.
     */
    static final int MAX_GUIDELINE_CHARS = 6_000;

    private final VllmClient client;
    private final CloudGate cloudGate;
    private final String guidelines;
    private final List<LearnedGuideline> ruleSet;
    private final String knowledgeBrief;
    private final ObjectMapper mapper = new ObjectMapper();

    public JudgeClient(VllmClient client) {
        this(client, null, null, null);
    }

    public JudgeClient(VllmClient client, CloudGate cloudGate) {
        this(client, cloudGate, null, null);
    }

    /**
     * @param guidelines the project's ACTIVE house rules as rendered for the shared prefix — the
     *                   same text the workers were given. Null when the project has none.
     */
    public JudgeClient(VllmClient client, CloudGate cloudGate, String guidelines) {
        this(client, cloudGate, guidelines, null);
    }

    /**
     * @param guidelines the rendered rules, used only when no rule set reached this class — that
     *                   fallback is capped and marked exactly as it was before {@link JudgeRules}
     *                   existed
     * @param ruleSet    the same ACTIVE rules as objects. This is what lets the judge be shown the
     *                   rules that bear on THIS diff instead of the ones that happened to sort
     *                   first. Null when nothing wired the rule set in.
     */
    public JudgeClient(VllmClient client, CloudGate cloudGate, String guidelines,
                       List<LearnedGuideline> ruleSet) {
        this(client, cloudGate, guidelines, ruleSet, null);
    }

    /**
     * @param knowledgeBrief the worker's fully rendered {@code KnowledgeBrief} markdown for this
     *                       task — the SAME text every worker of this task was given. Used only to
     *                       pull out the "Documentation relevant to this task" slice
     *                       {@link com.swarmcoder.knowledge.KnowledgeCurator} already chose (see
     *                       {@link #documentationSection}), so the judge stops inventing framework
     *                       facts the project's own guides already answer. Null when nothing wired
     *                       it in, which degrades to exactly the old brief.
     */
    public JudgeClient(VllmClient client, CloudGate cloudGate, String guidelines,
                       List<LearnedGuideline> ruleSet, String knowledgeBrief) {
        this.client = client;
        this.cloudGate = cloudGate;
        this.guidelines = guidelines;
        this.ruleSet = ruleSet;
        this.knowledgeBrief = knowledgeBrief;
    }

    /**
     * Schema for the judge's structured verdict.
     *
     * <p>{@code violations} is optional and additive on purpose. Local models have emitted doubled
     * braces and prose-wrapped JSON here (see {@link LlmJson}), so the two fields that were always
     * required stay required and unchanged: a reply that omits the new field, or gets its type
     * wrong, still parses into a usable score exactly as before.
     */
    public static class JudgeVerdict {
        public double score;
        public String rationale;
        public List<String> violations;
        /**
         * Rules the worker disputed with evidence the judge found credible, each "<rule name>:
         * why the evidence holds". Optional and additive, like {@link #violations}: a reply
         * without it parses exactly as before (harness runs 53 and 55, 2026-10-01).
         */
        public List<String> disputed;
    }

    /**
     * A verdict's rule findings sorted into the three kinds that are treated differently: a HARD
     * break may stop a task, a PREFERENCE break only costs score, and a credible DISPUTE is a
     * question about the rule rather than a defect in the code.
     */
    record RuleFindings(List<String> hard, List<String> preference, List<String> disputed) {}

    /**
     * Sorts a verdict's findings (harness runs 53 and 55, 2026-10-01 — see {@link RuleDispute}).
     *
     * <p>With no rule objects to classify against, every violation is hard, exactly as before
     * this existed. With them, a violation is hard only when it names a rule recorded as HARD; one
     * that names a preference, or that cannot be pinned to any rule, is a preference — only a rule
     * a person marked hard may stop a task. A "disputed" entry counts only when this candidate's
     * worker actually disputed that rule: a judge cannot invent a dispute on a worker's behalf.
     * A violation of a rule whose dispute counts is moved out of the violations.
     */
    static RuleFindings classify(JudgeVerdict verdict, List<LearnedGuideline> rules,
                                 List<RuleDispute> disputes) {
        List<String> accepted = new java.util.ArrayList<>();
        for (String entry : brokenRulesFrom(verdict.disputed)) {
            boolean workerDisputedIt = disputes != null && disputes.stream()
                .anyMatch(d -> RuleMatch.sameRule(entry, d.rule(), rules)
                    || RuleMatch.sameRule(d.rule(), entry, rules));
            if (workerDisputedIt) {
                accepted.add(entry);
            }
        }
        boolean classifiable = rules != null && !rules.isEmpty();
        List<String> hard = new java.util.ArrayList<>();
        List<String> preference = new java.util.ArrayList<>();
        for (String violation : brokenRulesFrom(verdict.violations)) {
            if (accepted.stream().anyMatch(d -> RuleMatch.sameRule(violation, d, rules))) {
                continue;
            }
            if (!classifiable) {
                hard.add(violation);
                continue;
            }
            LearnedGuideline rule = RuleMatch.named(violation, rules);
            (rule != null && rule.hard() ? hard : preference).add(violation);
        }
        return new RuleFindings(List.copyOf(hard), List.copyOf(preference), List.copyOf(accepted));
    }

    /**
     * Judges several candidates of one task side by side; the verdicts come back in the order the
     * candidates were given (2026-10-02).
     *
     * <p>They used to be judged one after another, and a verdict takes minutes on a slow model
     * (harness run 66: 221 seconds a call on average), so a task with two survivors waited twice
     * that for nothing: the calls are independent - each sees one diff, never another candidate.
     * Each call still takes one of its server's places like any other request, so side by side
     * never means more requests than the server serves.
     */
    public List<CandidateSolution> judgeAll(List<CandidateSolution> candidates, Task task) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() == 1) {
            return List.of(judge(candidates.get(0), task));
        }
        List<CandidateSolution> judged = new java.util.ArrayList<>();
        try (java.util.concurrent.ExecutorService pool =
                 java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<CandidateSolution>> verdicts = new java.util.ArrayList<>();
            for (CandidateSolution candidate : candidates) {
                verdicts.add(pool.submit(() -> judge(candidate, task)));
            }
            for (int i = 0; i < verdicts.size(); i++) {
                try {
                    judged.add(verdicts.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    judged.add(candidates.get(i)); // left unjudged rather than lost
                } catch (java.util.concurrent.ExecutionException e) {
                    // judge() turns every failure into a neutral score itself; this is an Error.
                    log.warn("Judging candidate {} failed outright: {}", candidates.get(i).id(),
                        String.valueOf(e.getCause()));
                    judged.add(candidates.get(i));
                }
            }
        }
        return judged;
    }

    /**
     * The mechanical evidence the judge is shown about a candidate, when it is the same for every
     * one of {@code candidates}; null when they differ, or when there are fewer than two.
     *
     * <p>That evidence is what {@link #judgeBrief} writes beside the diff: what verification
     * found ({@link #verificationLine}) and what the candidate did outside its own paths
     * ({@link #writeSetLine}). When it is identical for every survivor, nothing mechanical
     * separates them and the choice rests on the judge's reading of the diffs alone - which the
     * run log should say, because harness run 66 scored three of four tasks' survivors exactly
     * the same.
     */
    static String sameMechanicalEvidence(List<CandidateSolution> candidates, Task task) {
        if (candidates == null || candidates.size() < 2) {
            return null;
        }
        String first = null;
        for (CandidateSolution candidate : candidates) {
            String evidence = (verificationLine(candidate.verification(), task) + " "
                + writeSetLine(candidate, task)).replaceAll("\\s+", " ").strip();
            if (first == null) {
                first = evidence;
            } else if (!first.equals(evidence)) {
                return null;
            }
        }
        return first.length() > 300 ? first.substring(0, 300) + "…" : first;
    }

    public CandidateSolution judge(CandidateSolution sol, Task task) {
        JudgeScore score;
        try {
            String brief = judgeBrief(sol, task);
            if (cloudGate != null) {
                cloudGate.charge(CloudGate.estimateTokens(brief));
            }
            List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt(hasGuidelines())),
                Map.of("role", "user", "content", brief));
            String json = client.as("judge", task == null || task.id() == null ? null
                    : task.id().toString(), String.valueOf(sol.workerIndex()))
                .chatCompletionStream(messages, JudgeVerdict.class, 0.0)
                .collect(Collectors.joining());
            if (cloudGate != null) {
                cloudGate.chargeOutput(CloudGate.estimateTokens(json));
            }
            JudgeVerdict verdict = parseVerdict(json);
            double clamped = Math.max(0.0, Math.min(1.0, verdict.score));
            double ceiling = verificationCeiling(sol.verification());
            RuleFindings findings = classify(verdict, ruleSet, sol.ruleDisputes());
            String rationale = rationaleWithEvidenceLimit(
                rationaleWithFindings(verdict.rationale, findings), ceiling, sol.verification());
            // The second ceiling, and the one a green test cannot catch - see deliveredNothing.
            if (deliveredNothing(sol.diffUnified())) {
                ceiling = Math.min(ceiling, DELIVERED_NOTHING_CEILING);
                rationale = withNote(rationale, "SCORE LIMITED: this candidate's diff changes no "
                    + "code at all, so whatever the tests say, it delivered nothing - it cannot "
                    + "score above " + DELIVERED_NOTHING_CEILING + ".");
            }
            // The third ceiling, and the one harness run 11 showed neither telling the judge nor
            // relying on it was enough - see StrayFileCheck.
            List<String> strayFiles = StrayFileCheck.strayPathsIn(sol.outOfWriteSetPaths());
            if (!strayFiles.isEmpty()) {
                ceiling = Math.min(ceiling, STRAY_FILE_CEILING);
                rationale = withNote(rationale, "SCORE LIMITED: it left "
                    + String.join(", ", strayFiles) + " in the repository, which the task did "
                    + "not ask for. It cannot score above " + STRAY_FILE_CEILING + ".");
            }
            score = new JudgeScore(clamped * ceiling, rationale, client.modelName());
            score.setBrokenRules(findings.hard());
            score.setPreferenceBreaks(findings.preference());
            score.setDisputedRules(findings.disputed());
        } catch (CloudGate.BudgetExhaustedException e) {
            log.warn("Cloud budget exhausted — candidate {} left unjudged", sol.id());
            score = new JudgeScore(0.5, "unjudged: " + e.getMessage(), client.modelName());
        } catch (Exception e) {
            log.warn("Judging failed for candidate {}: {} — using neutral score", sol.id(), e.getMessage());
            score = new JudgeScore(0.5, "judging unavailable: " + e.getMessage(), client.modelName());
        }
        // Judging used to log ONLY when it failed, so a run in which every candidate was scored
        // 0.0 left no trace at all: there was not one judge line anywhere in ~/.swarmcoder/logs.
        // Verification logs a verdict per candidate; so does judging now. One line, with the
        // number, the evidence behind it and the judge's own words.
        log.info("Judged candidate {} (worker {}): score={} verified={} — {}",
            sol.id(), sol.workerIndex(), String.format(Locale.ROOT, "%.2f", score.score()),
            Verdicts.survived(sol.verification()),
            score.rationale() == null ? "" : score.rationale().replace('\n', ' '));
        return new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(),
            sol.branch(), sol.sampling(), sol.diffUnified(), sol.verification(), sol.cluster(),
            score, sol.state(), sol.killReason()).carryingAuditFrom(sol);
    }

    private boolean hasGuidelines() {
        return hasRuleSet() || (guidelines != null && !guidelines.isBlank());
    }

    private boolean hasRuleSet() {
        return ruleSet != null && !ruleSet.isEmpty();
    }

    /**
     * The most a candidate may score given what was actually PROVEN about it, and the second half
     * of the fix described on {@link #verificationLine}.
     *
     * <p>Telling the judge is necessary and not sufficient. A model asked for a number between 0
     * and 1, with no anchor, drifts to the top — which is what the store shows: of the fifteen
     * candidates ever judged, the two that scored a flat 1.0 were both ones nothing had compiled.
     * So the evidence limit is also enforced here, in code, where no wording can talk it away.
     *
     * <p>It MULTIPLIES rather than truncates. A ceiling applied with {@code min} would flatten every
     * unverified candidate of a task onto the same number and destroy the one signal the judge does
     * produce well — the rationales in the store are specific, name real classes and separate a
     * complete candidate from a half-finished one. Scaling keeps that ordering intact while putting
     * the top of the scale out of reach of anything unproven.
     *
     * <p><b>A failed compile is not always the candidate's failure</b> (2026-09-02, see
     * {@link CompileFailureCause}). The two multipliers exist to tell two different things apart:
     * {@link #UNVERIFIED_CEILING} means "no evidence" — nothing was ever built or run against this
     * candidate — and {@link #DOES_NOT_COMPILE_CEILING} means "evidence of failure" — the build ran
     * and this candidate's own code broke it. A {@link CompileFailureCause#PRE_EXISTING} failure is
     * neither a pass nor a proven failure of the candidate: the tree was already broken, in a file
     * the candidate never touched, before it started. Nothing was measured about the candidate's
     * own code — it belongs with the "no evidence" cases, not the "caught failing" ones, so it gets
     * the unverified ceiling instead of the does-not-compile one.
     *
     * <p>A failure attributed to the candidate's own file ({@link CompileFailureCause#CANDIDATE}),
     * or to a test the candidate changed ({@link CompileFailureCause#TEST_TREE} — workers may not
     * edit the protected acceptance directory, so a changed test is necessarily one the candidate
     * chose to touch), stays at the strict ceiling: the candidate's own change is what broke the
     * build. So does {@link CompileFailureCause#UNATTRIBUTED} and a report with no
     * {@link VerificationReport#compileFailure()} at all — unchanged from before this attribution
     * existed, because nothing here can say the candidate is innocent.
     */
    static double verificationCeiling(VerificationReport report) {
        if (report == null) {
            return UNVERIFIED_CEILING;
        }
        if (!report.compiles()) {
            CompileFailure failure = report.compileFailure();
            if (failure != null && failure.cause() == CompileFailureCause.PRE_EXISTING) {
                return UNVERIFIED_CEILING;
            }
            return DOES_NOT_COMPILE_CEILING;
        }
        return 1.0;
    }

    /**
     * The ceiling for a candidate nothing was ever run against. Deliberately NOT 0.5: that number
     * already means "the judge could not be reached" (see the catch blocks), and an operator
     * reading the Gallery must be able to tell a candidate nobody checked from a candidate nobody
     * judged.
     */
    static final double UNVERIFIED_CEILING = 0.4;

    /** The ceiling for a candidate the build could not compile. */
    static final double DOES_NOT_COMPILE_CEILING = 0.2;

    /**
     * The ceiling for a candidate that changed no code - the same one as "nothing was ever run
     * against it", because it is the same amount of delivered work: none.
     *
     * <p><b>Why a code ceiling is needed for this and not for a merely poor diff</b> (author
     * decision, 2026-09-03). Every other ceiling in this class is about evidence. This one is about
     * the absence of a change, and it exists because a green test stopped being evidence for a whole
     * class of task on the same day: when a task's acceptance tests were already made green by the
     * waves in front of it (see {@link ChecksAlreadyProved}), a candidate that edits nothing
     * compiles, passes every test, and reaches the judge indistinguishable - to verification - from
     * one that did the work. Verification cannot separate those two and must not pretend to. So the
     * one thing that IS mechanically knowable about an empty diff is enforced here, and everything
     * subtler is left to the judge reading the diff, which is what the judge is for.
     */
    static final double DELIVERED_NOTHING_CEILING = UNVERIFIED_CEILING;

    /**
     * The ceiling for a candidate whose diff reaches a STRAY file outside the write set — a
     * helper script, a scratch note, a generated artefact, or anything else the task did not ask
     * for and no build in the repository would ever compile, package or read (see
     * {@link StrayFileCheck}).
     *
     * <p><b>Why this exists</b> (harness run 11, 2026-09-03). {@link #writeSetLine} already told
     * the judge about every out-of-write-set path and asked it to decide whether each was needed.
     * It was not enough: a worker's helper script, {@code insert_dep.py}, reached a winning diff
     * and the judge scored it 1.00, calling it "minimal, clean" — telling the judge is necessary
     * and, exactly as with {@link #verificationCeiling}, not sufficient on its own. So this is
     * enforced here too, where no wording can talk it away.
     *
     * <p>Unlike {@link #verificationCeiling}, this is not a blanket rule on every out-of-write-set
     * path — {@link #writeSetLine}'s own reasoning about a genuinely needed neighbouring file
     * still holds, and {@link StrayFileCheck} is what tells the two apart. Only a path that is
     * neither a build file nor under a conventional source or resource directory trips this
     * ceiling; a neighbouring class the task needed does not.
     */
    static final double STRAY_FILE_CEILING = 0.5;

    /**
     * Whether this diff changes no code: no added or removed line carrying anything but whitespace
     * or a comment.
     *
     * <p>Deliberately crude and deliberately generous to the candidate. It answers one question -
     * did this worker write anything at all - and everything subtler (is the change adequate, is it
     * the RIGHT change) is a judgement about content, which is the judge's job and not a pattern
     * match's. The comment markers are the C-family ones plus HTML's; {@code #} is left out on
     * purpose, because it opens a comment in a properties file and opens a statement in Python, and
     * calling a one-line Python change "nothing" would be a lie in the strict direction.
     */
    static boolean deliveredNothing(String diff) {
        if (diff == null || diff.isBlank()) {
            return true;
        }
        for (String line : diff.split("\\R")) {
            if (line.isEmpty() || line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            char marker = line.charAt(0);
            if (marker != '+' && marker != '-') {
                continue;
            }
            String body = line.substring(1).strip();
            if (body.isEmpty() || body.startsWith("//") || body.startsWith("/*")
                    || body.startsWith("*") || body.startsWith("<!--")) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** Appends one sentence to a rationale, keeping it readable when the rationale is empty. */
    static String withNote(String rationale, String note) {
        String base = rationale == null ? "" : rationale.strip();
        return base.isEmpty() ? note : base + " " + note;
    }

    /**
     * What the judge is told when this task's acceptance tests were ALREADY GREEN before any of its
     * candidates existed. Empty for every other task, so a normal task is judged by exactly the
     * brief it was judged by before.
     *
     * <p>A green acceptance run is the strongest signal this system produces, and for such a task it
     * is worth nothing: the waves in front delivered what those tests measure, so every candidate of
     * this task passes them, including one that changed a comment. Saying so is the difference
     * between "score the candidate on the evidence" and "score the candidate on the diff", and only
     * the second is possible here.
     */
    static String testsDoNotSeparateLine(Task task) {
        ChecksAlreadyProved proved = task == null ? null : task.checksAlreadyProved();
        if (proved == null) {
            return "";
        }
        return "THE TESTS WERE GREEN BEFORE THIS CANDIDATE EXISTED, SO THEY DO NOT SEPARATE THE "
            + "CANDIDATES FOR THIS TASK.\n" + proved.describe(task.title())
            + "\n\nGive this candidate no credit for a green test run: those tests would be green "
            + "if it had changed nothing at all. Score it on one question - reading the diff, does "
            + "it deliver what the task's instructions above actually ask for? A candidate whose "
            + "diff does not do that work has delivered nothing, however green the tests are, and "
            + "belongs at the bottom of the scale.";
    }

    /**
     * What the judge is told when this task's acceptance test failed inside its OWN code before any
     * candidate existed, was sent back to its author, and was corrected (author decision,
     * 2026-09-05). Empty for every other task. Read from {@code Task.authoredTests().repairedNote()}
     * — the same sentence the run log and the run graph's badge carry, so the judge, the operator
     * and the log never disagree about what happened.
     *
     * <p>Exists for the same reason {@link #testsDoNotSeparateLine} does: the candidates being judged
     * here already survived verification against the CORRECTED test, so nothing about the earlier
     * failure is theirs to answer for, and a judge shown only the final verification report has no
     * way to know a failure ever happened.
     */
    static String testWasRepairedLine(Task task) {
        String note = task == null || task.authoredTests() == null
            ? null : task.authoredTests().repairedNote();
        if (note == null || note.isBlank()) {
            return "";
        }
        return "THIS TASK'S ACCEPTANCE TEST WAS REPAIRED ONCE BEFORE THIS CANDIDATE WAS JUDGED.\n"
            + note + "\n\nEvery candidate here already survived the CORRECTED test. Do not hold the "
            + "earlier failure against any of them — it was never theirs; it was the test's own bug.";
    }

    /**
     * Why the number is lower than the judge's own, in the sentence the operator reads.
     *
     * <p>The wording has to match the cause named by {@link #verificationCeiling}, not just the
     * ceiling number: a candidate held back because nothing was ever run against it, and a
     * candidate held back because the tree was already broken before it started, both land on
     * {@link #UNVERIFIED_CEILING}, and the operator reading the hover card needs to know which one
     * happened — the second is not a mark against the candidate at all.
     */
    static String rationaleWithEvidenceLimit(String rationale, double ceiling, VerificationReport report) {
        if (ceiling >= 1.0) {
            return rationale;
        }
        CompileFailure failure = report == null ? null : report.compileFailure();
        String note;
        if (report == null) {
            note = "SCORE LIMITED: nothing was ever compiled or tested against this candidate, so it "
                + "cannot score above " + UNVERIFIED_CEILING + ".";
        } else if (failure != null && failure.cause() == CompileFailureCause.PRE_EXISTING) {
            note = "SCORE LIMITED: the tree was already broken before this candidate's change, in a "
                + "file it never touched, so nothing was proven about this candidate's own code — "
                + "it cannot score above " + UNVERIFIED_CEILING + ".";
        } else {
            note = "SCORE LIMITED: this candidate does not compile, so it cannot score above "
                + DOES_NOT_COMPILE_CEILING + ".";
        }
        String base = rationale == null ? "" : rationale.strip();
        return base.isEmpty() ? note : base + " " + note;
    }

    /**
     * What the judge is told about verification — and the defect this class was opened for.
     *
     * <p>The verification block used to be written only when a {@link VerificationReport} existed.
     * It usually does not: a repository with no {@code .swarmcoder/verify.yaml} takes the one path
     * through the swarm engine that returns the candidate untouched, so it reaches the judge marked
     * SURVIVED with a null report. Of the 776 candidates archived in the operator's real store,
     * 775 carry no report at all, and every one of the fifteen ever judged was in that state. The
     * judge has therefore never once been told whether the code in front of it compiles.
     *
     * <p>Absence read as silence, and silence reads as "fine". It now reads as what it is.
     *
     * <p><b>A compile failure now names its cause</b> (2026-09-02, see {@link CompileFailure}). The
     * bare {@code compiles=false} only ever meant "the compile command exited non-zero" — it could
     * not say whether the candidate wrote broken code or the tree was already broken in a file the
     * candidate never touched. When the report carries an attribution, the brief states it in the
     * same words {@code Verdicts} puts on the run graph, so the judge is never told "this does not
     * compile" about a candidate whose own code was fine.
     */
    static String verificationLine(VerificationReport report, Task task) {
        if (report == null) {
            return "Verification: NOT RUN. Nothing was compiled and no test was executed against "
                + "this candidate. Nobody has established that this code builds, let alone that it "
                + "works. Judge the diff on its own, and score it as the unproven change it is.";
        }
        StringBuilder sb = new StringBuilder("Verification: COMPILED: ");
        if (!report.compiles()) {
            sb.append("NO");
            if (report.compileFailure() != null) {
                sb.append(" — ").append(report.compileFailure().describe());
            } else {
                sb.append(" (THE BUILD COULD NOT COMPILE THIS CANDIDATE)");
            }
            sb.append('.');
        } else {
            sb.append("yes (the whole build, offline, in the sandbox).");
        }
        sb.append(' ').append(acceptanceLine(report.acceptance(), claimedCheckCount(task)));
        if (report.existing() != null) {
            sb.append(" EXISTING TESTS: ").append(report.existing().passed()).append(" passed, ")
              .append(report.existing().failed()).append(" failed.");
        }
        sb.append(browserLine(report.browser()));
        return sb.toString();
    }

    /**
     * How many requirement-checks this task answers for — the same count {@code Verdicts} is
     * given as {@code claimedChecks} (via {@code ArtifactStore.describeClaimedChecks}), computed
     * locally from the task itself so the judge needs no store dependency: one entry per
     * BRD-referenced criterion id, plus one per criterion an ENABLER task owns outright. Zero
     * means the task claims none, which is the documented M1 allowance — an enabler proves
     * itself by compiling, never by tests it was never asked to have.
     */
    static int claimedCheckCount(Task task) {
        if (task == null) {
            return 0;
        }
        int count = task.criterionIds().size();
        if (task.criteria() != null) {
            for (var criterion : task.criteria()) {
                if (criterion != null) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * The sentence the judge misread in harness run 13, every enabler candidate that night: a
     * verification line that said "acceptance 0 passed/0 failed" over a stage with nothing to run
     * BY DESIGN read to the judge as "not actually built or tested". The fix is to say the two
     * cases in words that cannot be confused for each other — an enabler with no checks claimed,
     * and a task whose checks were due, ran, and (for anything that reaches the judge at all,
     * since {@code Verdicts} kills anything that failed one) passed.
     */
    private static String acceptanceLine(TestResults acceptance, int claimedChecks) {
        if (claimedChecks == 0) {
            return "ACCEPTANCE TESTS: this task claims none, so none were due and none ran — "
                + "that is correct for an enabler, do not treat it as unproven.";
        }
        int ran = acceptance == null ? 0 : acceptance.executed();
        int passed = acceptance == null ? 0 : acceptance.passed();
        String line = "ACCEPTANCE TESTS: " + claimedChecks + " due, " + ran + " ran, " + passed
            + " passed.";
        // In production Verdicts already kills anything with a failed or errored acceptance test
        // before it reaches the judge, so this almost never fires here — but the same failure must
        // never be worded differently than the verdict and the repair prompt say it, so it is
        // rendered from the one place that renders it: Verdicts.summarizeTestFailures.
        Verdicts.FailureSummary failures = Verdicts.summarizeTestFailures("acceptance", acceptance);
        if (failures != null) {
            line += " " + failures.oneLineSentence() + ".";
        }
        return line;
    }

    /**
     * Whether anything ever started this application and looked at it - the one piece of evidence a
     * unit test can never supply, and the only evidence there is for a criterion worded about what
     * somebody SEES.
     *
     * <p>Three states, and they must not read alike. No block at all in the contract: nothing in
     * this project ever runs the product, so a judge reading a green test list must not conclude the
     * screen works. Attempted and not possible (no browser on the machine, a sandbox this process
     * cannot reach into, a port already taken): nothing was learned and nothing may be concluded -
     * the same line {@code Verdicts} draws before it declines to fail a candidate for it. Actually
     * driven: that is the strong evidence, and it is worth saying so.
     */
    static String browserLine(BrowserCheckResults browser) {
        if (browser == null) {
            return " BROWSER: not configured for this project.";
        }
        if (browser.couldNotTry()) {
            return " BROWSER: could not be tried (" + browser.couldNotTryReason()
                + ") — nothing looked at the running application and nothing about the screen was "
                + "established either way.";
        }
        long loaded = browser.checks() == null ? 0
            : browser.checks().stream().filter(PageCheck::loaded).count();
        int total = browser.checks() == null ? 0 : browser.checks().size();
        return " BROWSER: started and driven in a real browser, " + loaded + "/" + total
            + " page(s) loaded.";
    }

    /**
     * What the judge is told about files the worker changed outside its task's slice.
     *
     * <p>Until 2026-09-02 the worker was killed for this, so the judge never saw a candidate that
     * had done it. It is now allowed and recorded, and the judgement moves here - where the diff is
     * visible and the two cases can actually be told apart. On a multi-module project a task
     * genuinely spans more than one slice, and the write set is a guess made from a plan before any
     * code existed; on the operator's live run, every candidate killed for this was either
     * re-creating a class a parallel task had not delivered yet or writing a scratch file to check
     * its own work.
     *
     * <p><b>No code ceiling is applied here for a genuinely needed file, deliberately.</b> The
     * unverified ceiling exists because "nothing was built or run" is a measurement, true of the
     * candidate whatever the diff says. Whether an out-of-write-set path was NEEDED is not a
     * measurement of anything in general: one neighbouring file added to make the code compile is
     * right, half the repository rewritten is wrong, and only reading the diff separates them. So
     * most of this stays the judge's call. What changed (harness run 11, 2026-09-03) is the one
     * sliver that IS mechanical: a path that is not a build file and not under any conventional
     * source or resource directory — a helper script, a scratch note, a generated artefact — is a
     * defect on geometry alone, and {@link #STRAY_FILE_CEILING} enforces it in code because being
     * told was not enough (see that constant's javadoc).
     */
    /**
     * What this candidate asked for help with, and — stated outright — that asking was right.
     *
     * <p>Without the last sentence a judge reading "asked for help twice" scores it as a weakness,
     * because that is what the phrase sounds like. It is the opposite. The alternative to asking,
     * measured five times in a plain harness, is a worker that spends sixty-six shell commands
     * disassembling the framework's jars and delivers nothing at all.
     */
    static String helpLine(CandidateSolution sol) {
        List<String> calls = sol.helpCalls();
        if (calls.isEmpty()) {
            return "";
        }
        return "ASKED FOR HELP " + calls.size() + "×: " + String.join("; ", calls)
            + " — this is NOT a defect and must not lower the score. A worker that asks about an "
            + "API it does not know, and then writes working code, has done the right thing. Judge "
            + "the code.";
    }

    static String writeSetLine(CandidateSolution sol, Task task) {
        List<String> outside = sol.outOfWriteSetPaths();
        if (outside.isEmpty()) {
            return "";
        }
        return "FILES OUTSIDE THE TASK'S WRITE SET (" + outside.size() + "): "
            + String.join(", ", outside) + " — decide whether each is needed to deliver the "
            + "task. A helper script, a scratch file, a note, a generated artefact, or anything "
            + "the task did not ask for is a defect: cap the score at " + STRAY_FILE_CEILING
            + " and say which file."
            + "\nThe task was allotted " + (task == null || task.writeSet() == null
                || task.writeSet().isEmpty() ? "no particular paths" : task.writeSet().toString())
            + ". This is not automatically wrong: those paths were chosen from a plan before any "
            + "code existed, and a task can genuinely need a neighbouring file to compile or run. "
            + "Read the diff and decide which this is. A change that reached one file it needed is "
            + "fine; a change that scattered edits across the repository, or left scratch and build "
            + "files behind, is a defect and should score lower for it. Say which you found.";
    }

    /**
     * What the numbers on the scale mean. The judge used to be asked for "0.0-1.0" and nothing
     * else; an unanchored scale is why "correctly implements the task" came back as a flat 1.0 for
     * a candidate nothing had ever built.
     */
    static final String SCALE_ANCHORS =
        "Anchor the scale. 1.0 is reserved for a change that was BUILT AND TESTED and is complete "
        + "and clean; withhold it from anything unproven. 0.7 is a good change with a real "
        + "reservation. 0.4 is a change that plausibly does the job but nothing has checked it. "
        + "0.2 is incomplete or does not build. 0.0 is a change that does not do the task at all. "
        + "The verification line in the brief tells you which of these applies — read it first, "
        + "and never score above the band it puts this candidate in.";

    /**
     * A standing rule, not a note beside one candidate's evidence — because the misreading it
     * corrects was systematic, not a one-off. Harness run 13 marked every enabler candidate that
     * night "not actually built or tested" over an acceptance stage that had nothing to run BY
     * DESIGN — a task that claims no acceptance checks is not required to have any (the documented
     * M1 allowance, see {@code Verdicts}). A sentence living only beside one candidate's diff can
     * still be outweighed by "acceptance 0p/0f"; a standing rule the judge is told every time
     * cannot.
     */
    static final String ENABLER_RULE =
        "A task that claims no acceptance checks (an enabler) has its survival proved by "
        + "COMPILING, not by tests. When the verification line says none were due, that is "
        + "correct for an enabler and is not evidence the candidate is unproven — do not lower "
        + "the score for it.";

    /**
     * The judge's instructions. The rule-aware half is added only when there ARE rules, so a
     * project without any is judged by exactly the prompt it was judged by before.
     */
    static String systemPrompt(boolean withGuidelines) {
        String base = "You are a strict code-review judge. Score the candidate change for how well "
            + "it implements the task: correctness first, then simplicity and fit. "
            + SCALE_ANCHORS + " " + ENABLER_RULE;
        if (!withGuidelines) {
            return base + " Respond ONLY with JSON: "
                + "{\"score\": <0.0-1.0>, \"rationale\": \"<one or two sentences>\"}" + SHORT_VERDICT;
        }
        // Judged by PURPOSE, not by the letter (harness runs 53 and 55, 2026-10-01): held to its
        // literal words, "every type that crosses the wire is a @DataModel" marked down every
        // candidate that rightly left a natively-serialized enum unannotated.
        return base + " The repository also has HOUSE RULES, listed in the brief. They are not "
            + "suggestions: a change that breaks one is worse than a change that does not, however "
            + "good it otherwise is. Check the diff against every rule, and judge each by its "
            + "PURPOSE: where a rule says why it exists, flag a break only when this change causes "
            + "the problem the rule is there to prevent — a change that differs from the rule's "
            + "letter but causes none of that problem has not broken it. For each rule you find "
            + "broken, begin the entry with the rule's name exactly as the brief lists it, then say "
            + "where in the diff and what problem it causes. A broken HARD rule should not score "
            + "above 0.4; a preference not followed lowers the score a little. Respond ONLY with "
            + "JSON: {\"score\": <0.0-1.0>, \"rationale\": \"<one or two sentences>\", "
            + "\"violations\": [\"<rule name>: <where, and the problem it causes>\"], "
            + "\"disputed\": [\"<rule name>: <why the worker's evidence holds>\"]}. Use an empty "
            + "violations list when every rule was followed, and an empty disputed list unless "
            + "the brief shows a dispute whose evidence you accept." + SHORT_VERDICT;
    }

    /**
     * Asks for the verdict and nothing around it (2026-10-02).
     *
     * <p>Harness run 66: the judge wrote about 3,400 tokens a call for a verdict of under two
     * hundred. Nearly all of it was the model working the answer out at length before giving it,
     * on a server where reasoning cannot be switched off - 3.7 minutes a call at that server's
     * speed. The verdict was already structured; what this adds is saying, in the instructions,
     * that a verdict is all that is wanted. It is a request, not a cap: nothing here cuts the
     * answer off, because a verdict cut short is no verdict.
     */
    static final String SHORT_VERDICT =
        " Be brief. Read the diff once against the task and against each rule, decide, and "
        + "answer: do not work through it at length first. The whole answer is that one JSON "
        + "object and nothing else - the rationale in one or two sentences, each list entry on "
        + "one line.";

    /**
     * The verdict's {@code violations}, cleaned of blanks — the structured list
     * {@link SelectionLogic} ranks on (author decision, §21 addendum). {@code violations} arrives
     * as free-form strings the judge itself wrote, not references to a {@link LearnedGuideline}, so
     * this is the whole of what "which stated rule did this candidate break" means downstream: a
     * candidate that broke none has this empty, one that broke several carries them all, in the
     * judge's own order and words. Nothing here re-derives the list from {@link #rationale} —
     * {@link #rationaleWithViolations} folds these same entries INTO the rationale text for the
     * operator to read, so parsing that text back out would be reading a summary of this, not this.
     */
    static List<String> brokenRulesFrom(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new java.util.ArrayList<>();
        for (String violation : violations) {
            if (violation != null && !violation.isBlank()) {
                cleaned.add(violation.strip());
            }
        }
        return List.copyOf(cleaned);
    }

    /**
     * The rationale the operator reads, with any named breach folded in.
     *
     * <p>A breach must be legible in words, not only in a number. The Gallery shows the rationale;
     * if the judge names a rule it thought was broken, that sentence is what tells a human why this
     * candidate lost, months later.
     */
    static String rationaleWithViolations(JudgeVerdict verdict) {
        String rationale = verdict.rationale == null ? "" : verdict.rationale.strip();
        if (verdict.violations == null || verdict.violations.isEmpty()) {
            return rationale;
        }
        StringBuilder sb = new StringBuilder(rationale);
        sb.append(rationale.isEmpty() ? "" : " ").append("HOUSE RULES BROKEN:");
        for (String violation : verdict.violations) {
            if (violation != null && !violation.isBlank()) {
                sb.append("\n  - ").append(violation.strip());
            }
        }
        return sb.toString();
    }

    /**
     * The rationale the operator reads, with every rule finding folded in under a heading that
     * says which kind it is. Hard breaks keep the heading they always had.
     */
    static String rationaleWithFindings(String judgeRationale, RuleFindings findings) {
        StringBuilder sb = new StringBuilder(judgeRationale == null ? "" : judgeRationale.strip());
        appendSection(sb, "HOUSE RULES BROKEN:", findings.hard());
        appendSection(sb, "PREFERENCES NOT FOLLOWED (score only, never blocking):",
            findings.preference());
        appendSection(sb, "RULES DISPUTED WITH CREDIBLE EVIDENCE (a question about the rule, not "
            + "a defect):", findings.disputed());
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String heading, List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        sb.append(sb.length() == 0 ? "" : " ").append(heading);
        for (String entry : entries) {
            sb.append("\n  - ").append(entry);
        }
    }

    /**
     * What the judge is told about the rules this candidate's worker disputed, with the evidence,
     * next to the code. Empty when it disputed none.
     */
    static String disputesSection(CandidateSolution sol) {
        List<RuleDispute> disputes = sol.ruleDisputes();
        if (disputes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("THE WORKER DISPUTED " + disputes.size()
            + " RULE(S), WITH EVIDENCE. For each, decide whether the evidence really shows the rule "
            + "cannot be met here or does not fit this case. If it does, list the rule under "
            + "\"disputed\" with your reason and NOT under \"violations\". If the evidence is "
            + "thin, beside the point, or the dispute only avoids work, the rule stands and you "
            + "judge the code against it as usual.");
        for (RuleDispute dispute : disputes) {
            String evidence = dispute.evidence().strip();
            if (evidence.length() > MAX_DISPUTE_EVIDENCE_CHARS) {
                evidence = evidence.substring(0, MAX_DISPUTE_EVIDENCE_CHARS) + "…";
            }
            sb.append("\n- rule: ").append(dispute.rule().strip())
                .append("\n  why: ").append(dispute.reason().strip())
                .append("\n  evidence: ").append(evidence.replace("\n", "\n    "));
        }
        return sb.toString();
    }

    /** Per dispute — the judge is reading a diff, and three disputes must not crowd it out. */
    static final int MAX_DISPUTE_EVIDENCE_CHARS = 1_500;

    /**
     * The rules as the judge is shown them, chosen for THIS diff — see {@link JudgeRules}.
     *
     * <p>Falls back to the pre-rendered text when no rule set was wired in, and that fallback is
     * the old behaviour: cut at the cap. What it no longer does is say "[house rules truncated]"
     * and leave it there. A judge that is told rules were cut, and not what kind of thing was cut,
     * cannot even flag its own blindness — so the marker now says plainly that the list it has is
     * incomplete and that the missing rules still apply.
     */
    String ruleSection(String diff) {
        if (hasRuleSet()) {
            return JudgeRules.render(ruleSet, diff, MAX_GUIDELINE_CHARS);
        }
        if (guidelines == null || guidelines.isBlank()) {
            return "";
        }
        String rules = guidelines.strip();
        if (rules.length() > MAX_GUIDELINE_CHARS) {
            rules = rules.substring(0, MAX_GUIDELINE_CHARS)
                + "\n[MORE RULES ARE IN FORCE AND ARE NOT PRINTED HERE. They were left out for "
                + "space, not because they stopped applying, and this brief cannot say which ones. "
                + "Treat the list above as incomplete.]";
        }
        return rules;
    }

    /**
     * The heading {@link com.swarmcoder.knowledge.Librarian#assembleBrief} writes above the
     * task-relevant documentation slice it puts in every worker's prefix. This class does not
     * depend on {@code sc-knowledge} — it re-reads the SAME already-rendered brief text every
     * worker of this task received, rather than re-selecting documentation itself, so the judge
     * is provably reading what the worker read, not a second opinion of it.
     */
    private static final String DOC_SECTION_HEADING = "### Documentation relevant to this task";

    /**
     * What the judge is told about the framework, verbatim from the project's own guides — the
     * fix for the second defect this class was opened for. A judge with no access to the
     * documentation invented a fact ("{@code EmbeddedStorageManager} is not a valid CDI bean in
     * this stack") that the project's own persistence guide contradicts on its first page, and
     * picked the candidate that never saves over the one that follows the guide. Telling the
     * judge the documentation exists is not enough — it has to be IN the brief, the same slice
     * {@link com.swarmcoder.knowledge.KnowledgeCurator} already chose for the worker, so the judge
     * is reading the same authority the worker was, not guessing from training data.
     *
     * <p>Capped well under the worker's own budget ({@link #MAX_DOC_SLICE_CHARS}, measured to stay
     * under 1,500 tokens by {@code CloudGate.estimateTokens} — see the judge's documentation
     * test): the diff is still what the judge is principally reading.
     */
    static final String DOC_AUTHORITY_SENTENCE = "The framework's own documentation for this "
        + "task. When your opinion of an API disagrees with it, the documentation is right.";

    /**
     * Cap on the documentation slice shown to the judge — kept under 1,500 tokens
     * ({@code CloudGate.estimateTokens}, chars/4) with headroom for {@link #DOC_AUTHORITY_SENTENCE}
     * and this section's own heading, and well under the diff's own budget: the documentation
     * exists to correct the judge, not to replace the diff as what it is judging.
     */
    static final int MAX_DOC_SLICE_CHARS = 5_200;

    /**
     * Pulls the "Documentation relevant to this task" slice out of the worker's already-rendered
     * knowledge brief and fronts it with {@link #DOC_AUTHORITY_SENTENCE}. "" when no brief was
     * wired in, or the brief carries no such section (no documentation root was configured, or
     * nothing in it matched this task) — the judge then falls back to the diff and its own
     * knowledge exactly as it did before this fix, degrading rather than failing.
     */
    static String documentationSection(String knowledgeBrief) {
        String slice = extractDocumentationSlice(knowledgeBrief, MAX_DOC_SLICE_CHARS);
        if (slice.isEmpty()) {
            return "";
        }
        return "DOCUMENTATION FOR THIS TASK: " + DOC_AUTHORITY_SENTENCE + "\n\n" + slice;
    }

    /**
     * The raw slice, with no heading of its own — split out so a test can measure it independent
     * of {@link #documentationSection}'s wrapping. Takes the text between the worker brief's
     * "Documentation relevant to this task" heading and the next markdown heading (one or more
     * {@code #}), whichever section boundary {@link com.swarmcoder.knowledge.Librarian} used —
     * the primer that follows is headed {@code ##}, everything else in that brief {@code ###}.
     */
    static String extractDocumentationSlice(String knowledgeBrief, int maxChars) {
        if (knowledgeBrief == null) {
            return "";
        }
        int headingStart = knowledgeBrief.indexOf(DOC_SECTION_HEADING);
        if (headingStart < 0) {
            return "";
        }
        int contentStart = knowledgeBrief.indexOf('\n', headingStart);
        if (contentStart < 0) {
            return "";
        }
        contentStart++;
        int next = knowledgeBrief.indexOf("\n#", contentStart);
        String slice = (next < 0 ? knowledgeBrief.substring(contentStart)
            : knowledgeBrief.substring(contentStart, next)).strip();
        if (slice.isEmpty()) {
            return "";
        }
        return slice.length() <= maxChars ? slice
            : slice.substring(0, maxChars) + "\n… (documentation trimmed)";
    }

    private String judgeBrief(CandidateSolution sol, Task task) {
        StringBuilder sb = new StringBuilder();
        String rules = ruleSection(sol.diffUnified());
        if (!rules.isEmpty()) {
            sb.append("HOUSE RULES for this repository — a change that breaks one of these is a "
                + "defect, whatever else it does:\n").append(rules).append("\n\n");
        }
        if (task != null) {
            sb.append("Task: ").append(task.title()).append('\n')
              .append(task.instructions()).append("\n\n");
        }
        // ALWAYS written, report or no report — see verificationLine().
        sb.append(verificationLine(sol.verification(), task)).append("\n\n");
        String notSeparated = testsDoNotSeparateLine(task);
        if (!notSeparated.isEmpty()) {
            sb.append(notSeparated).append("\n\n");
        }
        String testRepaired = testWasRepairedLine(task);
        if (!testRepaired.isEmpty()) {
            sb.append(testRepaired).append("\n\n");
        }
        String outOfSet = writeSetLine(sol, task);
        if (!outOfSet.isEmpty()) {
            sb.append(outOfSet).append("\n\n");
        }
        String disputes = disputesSection(sol);
        if (!disputes.isEmpty()) {
            sb.append(disputes).append("\n\n");
        }
        if (sol.cluster() != null) {
            sb.append("Behavioral cluster size (identical candidates): ")
              .append(sol.cluster().clusterSize()).append("\n\n");
        }
        String documentation = documentationSection(knowledgeBrief);
        if (!documentation.isEmpty()) {
            sb.append(documentation).append("\n\n");
        }
        String diff = sol.diffUnified() == null ? "" : sol.diffUnified();
        if (diff.length() > MAX_DIFF_CHARS) {
            diff = diff.substring(0, MAX_DIFF_CHARS) + "\n[diff truncated]";
        }
        sb.append("Candidate diff:\n").append(diff);
        return sb.toString();
    }

    /** Defensive parse — see {@link LlmJson}. */
    JudgeVerdict parseVerdict(String raw) throws IOException {
        return LlmJson.parse(mapper, raw, JudgeVerdict.class);
    }

    static String extractJson(String text) {
        return LlmJson.extractFirstObject(text);
    }
}
