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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.verify.Verdicts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picks the candidate that is delivered, and says why in words the operator can read.
 *
 * <p><b>The defect this class was rewritten for (2026-08-31).</b> Selection ranked on the judge's
 * score, broke a tie on behavioural cluster size, and when those also tied it never reassigned the
 * winner — so the candidate that came first in dispatch order won, which is worker index and
 * therefore arbitrary. Verification was not consulted at any point. In the operator's real store
 * that produced exactly what it sounds like: the task "Compile all Java files" had three
 * candidates, every one scored 0.0 by the judge, with rationales saying in plain words that the
 * diff compiles nothing; the winner was one of the two in the larger behavioural cluster, picked
 * over its twin by nothing but arriving first. Two candidates in that store are SELECTED with a
 * judge score of 0.0.
 *
 * <p><b>Zero is not a winner.</b> The judge's scale (see {@link JudgeClient#SCALE_ANCHORS}) defines
 * 0.0 as "a change that does not do the task at all". A candidate holding that number is not a weak
 * winner, it is a stated non-answer, and delivering it is worse than delivering nothing because it
 * is silent — the task goes green and the work is not there. So a candidate the judge scored 0.0 is
 * NOT selectable, and when no candidate is selectable this returns no winner together with the
 * reason, which the engine turns into a BLOCKED task and a pending decision the operator sees. A
 * block is a bad outcome; a silent wrong delivery is a worse one, and the block at least says so.
 *
 * <p>The test is EXACTLY zero, not a band near zero, and that is deliberate — see the note on
 * double-counting below.
 *
 * <p><b>Proof outranks opinion.</b> {@link Verdicts} is the single definition of "this candidate
 * survived", and its first rule is that a null report has failed: nothing was compiled, nothing was
 * run, nothing is known. Selection now reads that verdict FIRST, and compares judge scores only
 * within a tier. A candidate that was verified beats one that was not, even when the unverified one
 * has the higher number, because the number is an opinion about a diff and the verdict is a
 * measurement of the code.
 *
 * <p><b>Why this does not double-count the judge's evidence ceiling.</b> {@link JudgeClient} already
 * multiplies an unverified candidate's score by {@link JudgeClient#UNVERIFIED_CEILING} and appends
 * "SCORE LIMITED: ..." to the rationale. That ceiling exists so the NUMBER the operator reads is
 * honest — an unproven change must never display as a 1.0. The tier here exists so the ORDER is
 * honest. Selection does not adjust the score a second time: it reads the score exactly as the
 * judge left it, and uses the verification verdict only to decide which tier the candidate is in.
 * The one place the two could have collided is the zero test, which is why that test is exact:
 * every ceiling is a non-zero multiplier, so a scaled score is 0.0 if and only if the judge itself
 * said 0.0. A band such as "below 0.05" would have rejected a candidate the judge gave 0.1 purely
 * because it was unverified — the ceiling's fact, counted twice.
 *
 * <p><b>A rule-keeper beats a rule-breaker before the score is even read</b> (2026-09-04). Harness
 * run 14 judged two candidates for "Implement BookService on the server", and both broke the rule
 * that persistence goes through EclipseStore — one used a plain in-memory map, the other only when
 * the store was not already set up, which the rule forbids just as much. Nothing compared them on
 * that fact, so the higher-scoring rule-breaker (0.70 against 0.40) won and was delivered. A stated
 * rule is not one more quality signal the score already folds in — it is a pass/fail the score must
 * never override, so it is now its own tier, checked with {@link #brokeStatedRule} straight after
 * verification and before the judge's number is read at all. This does not make a rule-breaker
 * unselectable (that would silently drop the ONLY answer when every survivor breaks something,
 * which is a worse silence than delivering an imperfect one) — it only loses to a candidate that
 * kept every rule; see {@code SwarmEngineImpl} for what happens when nothing did.
 *
 * <p><b>Every tie is broken by something about the candidate.</b> In order: survived verification,
 * kept every stated rule, then judge score, then behavioural cluster size (how many workers
 * independently produced the same behaviour), then the smaller diff, then the candidate id as a
 * final total order. Worker dispatch order is not in that list and decides nothing again.
 */
public class SelectionLogic {

    private static final Logger log = LoggerFactory.getLogger(SelectionLogic.class);

    /**
     * The outcome of selection: the candidate to deliver, or none, together with the sentence that
     * explains it.
     *
     * @param winner the candidate to deliver, or null when nothing was selectable
     * @param reason plain English saying why this candidate won, or why nothing could win
     */
    public record Selection(CandidateSolution winner, String reason) {}

    /**
     * Ranks the candidates and returns the winner with the reason it won.
     *
     * <p>The caller's list is never reordered; a sorted copy is used, so dispatch order survives
     * for archiving.
     */
    public Selection select(List<CandidateSolution> judged) {
        if (judged == null || judged.isEmpty()) {
            String reason = "No candidate reached selection, so there is nothing to deliver.";
            log.warn("Selection: {}", reason);
            return new Selection(null, reason);
        }
        List<CandidateSolution> selectable = new ArrayList<>(judged.stream()
            .filter(SelectionLogic::isSelectable)
            .toList());
        if (selectable.isEmpty()) {
            String reason = nothingSelectableReason(judged);
            log.warn("Selection: no candidate is deliverable — {}", oneLine(reason));
            return new Selection(null, reason);
        }
        selectable.sort(BEST_FIRST);
        CandidateSolution best = selectable.get(0);
        CandidateSolution runnerUp = selectable.size() > 1 ? selectable.get(1) : null;
        String reason = winnerReason(best, runnerUp, judged.size(), selectable.size());
        log.info("Selection: {}", oneLine(reason));
        return new Selection(claim(best), reason);
    }

    /** The winner alone, for callers that do not need the reason. */
    public CandidateSolution selectWinner(List<CandidateSolution> judged) {
        return select(judged).winner();
    }

    /**
     * Best first: survived verification, then whether the diff KEPT every stated house rule, then
     * the higher judge score, then the larger behavioural cluster, then the smaller diff, then the
     * candidate id so the order is total and repeatable.
     */
    static final Comparator<CandidateSolution> BEST_FIRST =
        Comparator.comparing(SelectionLogic::survivedVerification, Comparator.reverseOrder())
            .thenComparing(SelectionLogic::brokeStatedRule)
            .thenComparing(SelectionLogic::judgeScore, Comparator.reverseOrder())
            .thenComparing(SelectionLogic::clusterSize, Comparator.reverseOrder())
            .thenComparingInt(SelectionLogic::filesOutsideWriteSet)
            .thenComparingInt(SelectionLogic::changedLines)
            .thenComparing(SelectionLogic::idKey);

    /**
     * How many files this candidate changed outside its task's declared write set - a tie-break,
     * and never more than that.
     *
     * <p>It sits BELOW the judge's score on purpose. Writing outside the slice used to kill the
     * worker outright; the whole point of recording it instead is that a candidate which reached
     * one neighbouring file to make its code compile is usable, and only the diff says whether that
     * is what happened. The judge reads the diff and is shown the list, so its score is the
     * considered answer; this only decides two candidates the judge could not separate. At equal
     * proof, equal judgement and equal agreement, the change that stayed in its lane is the one
     * that will merge cleanly with its neighbours.
     */
    static int filesOutsideWriteSet(CandidateSolution candidate) {
        return candidate.outOfWriteSetPaths().size();
    }

    /**
     * A candidate the judge scored 0.0 is not selectable; anything else is.
     *
     * <p>A candidate with NO judge score at all stays selectable. "Nobody judged this" and "the
     * judge says this does not do the task" are different findings, and only the second is evidence
     * against the candidate. An unjudged candidate sorts below every judged one and wins only when
     * nothing judged is left.
     */
    static boolean isSelectable(CandidateSolution candidate) {
        if (candidate == null) {
            return false;
        }
        return candidate.judge() == null || candidate.judge().score() > 0.0;
    }

    /** True when {@link Verdicts} says this candidate survived; a null report has not. */
    static Boolean survivedVerification(CandidateSolution candidate) {
        return Verdicts.survived(candidate.verification());
    }

    /**
     * Whether the judge found this candidate's diff breaking a stated house rule (author decision,
     * §21 addendum, 2026-09-04). The defect this ranks against: harness run 14 judged two candidates
     * for "Implement BookService on the server" and BOTH broke the rule that persistence goes
     * through EclipseStore — one used a plain in-memory map outright, the other only when the store
     * was not already wired up, which the rule does not allow either. Nothing before this compared
     * them on that fact; the higher score won and was delivered, so the better of two rule-breakers
     * shipped as the answer. A rule the operator stated is not one more quality signal folded into
     * the number — it is a pass/fail the score must never override, so it gets its own tier, between
     * "did this candidate survive verification" and "what did the judge think of it otherwise".
     *
     * <p>Reads {@link JudgeScore#brokenRules()} — the judge's own structured list — never the
     * rationale text. A candidate nobody judged, or one the judge judged clean, is {@code false}:
     * "nobody said this breaks a rule" and "the judge said it breaks a rule" are different findings,
     * and only the second counts against a candidate here, exactly as {@link #isSelectable} already
     * treats an unjudged candidate as not proven guilty of scoring 0.0.
     */
    static boolean brokeStatedRule(CandidateSolution candidate) {
        return candidate.judge() != null && !candidate.judge().brokenRules().isEmpty();
    }

    /**
     * The first stated rule the judge named this candidate breaking, in the judge's own words — ""
     * when it broke none or nobody judged it. Used only to quote something concrete in the reason a
     * human reads; a candidate that broke several rules still only needs the first one named to make
     * the point that it broke a rule at all.
     */
    static String firstBrokenRule(CandidateSolution candidate) {
        if (candidate.judge() == null || candidate.judge().brokenRules().isEmpty()) {
            return "";
        }
        return candidate.judge().brokenRules().get(0);
    }

    /** The judge's score, or -1 for a candidate nobody judged, so it sorts below every judged one. */
    static Double judgeScore(CandidateSolution candidate) {
        return candidate.judge() == null ? -1.0 : candidate.judge().score();
    }

    /**
     * How many candidates independently produced this behaviour. Zero when the candidate was never
     * clustered — the null dereference this replaces threw on exactly that candidate, and the real
     * store is full of them.
     */
    static Integer clusterSize(CandidateSolution candidate) {
        return candidate.cluster() == null ? 0 : candidate.cluster().clusterSize();
    }

    /**
     * Lines the diff adds or removes — the "diff size" that {@link JudgeClient}'s javadoc has always
     * said selection falls back to and that selection never actually used. It is used now rather
     * than struck from the javadoc, because at equal proof, equal judgement and equal agreement the
     * smaller change is the lower-risk one, and it is a property of the candidate rather than of the
     * schedule. The real store shows the case it decides: a winning candidate whose rationale reads
     * "includes unnecessary extra test result files and binary JARs, making the diff bloated".
     */
    static int changedLines(CandidateSolution candidate) {
        String diff = candidate.diffUnified();
        if (diff == null || diff.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+") || line.startsWith("-")) {
                count++;
            }
        }
        return count;
    }

    /** A stable last resort. Never worker index — that is dispatch order, which decides nothing. */
    static String idKey(CandidateSolution candidate) {
        return candidate.id() == null ? "" : candidate.id().toString();
    }

    private static CandidateSolution claim(CandidateSolution best) {
        return new CandidateSolution(best.id(), best.taskId(), best.workerIndex(),
            best.branch(), best.sampling(), best.diffUnified(), best.verification(), best.cluster(),
            best.judge(), CandidateState.SELECTED, best.killReason()).carryingAuditFrom(best);
    }

    /** Why this candidate won, and what it beat the next one on. */
    private static String winnerReason(CandidateSolution best, CandidateSolution runnerUp,
                                       int judgedCount, int selectableCount) {
        StringBuilder sb = new StringBuilder("worker ").append(best.workerIndex())
            .append(" wins (")
            .append(survivedVerification(best) ? "passed verification" : "never verified")
            .append(", ").append(brokeStatedRule(best)
                ? "broke a stated rule: '" + firstBrokenRule(best) + "'" : "kept every stated rule")
            .append(", judge score ").append(format(judgeScore(best)))
            .append(", ").append(clusterSize(best)).append(" worker(s) produced the same change")
            .append(", ").append(changedLines(best)).append(" changed line(s)");
        if (filesOutsideWriteSet(best) > 0) {
            sb.append(", ").append(filesOutsideWriteSet(best))
                .append(" file(s) outside its write set: ")
                .append(String.join(", ", best.outOfWriteSetPaths()));
        }
        sb.append(")");
        if (selectableCount < judgedCount) {
            sb.append("; ").append(judgedCount - selectableCount)
                .append(" candidate(s) ruled out because the judge scored them 0.0");
        }
        if (runnerUp != null) {
            sb.append("; it beat worker ").append(runnerUp.workerIndex()).append(" on ")
                .append(decidingCriterion(best, runnerUp));
        }
        return sb.toString();
    }

    /** The first criterion on which the winner actually differs from the runner-up. */
    private static String decidingCriterion(CandidateSolution best, CandidateSolution runnerUp) {
        if (!survivedVerification(best).equals(survivedVerification(runnerUp))) {
            return "verification: this one passed and that one did not";
        }
        if (brokeStatedRule(best) != brokeStatedRule(runnerUp)) {
            return "keeping every stated rule (worker " + runnerUp.workerIndex() + " broke '"
                + firstBrokenRule(runnerUp) + "')";
        }
        if (!judgeScore(best).equals(judgeScore(runnerUp))) {
            return "the judge's score (" + format(judgeScore(best)) + " against "
                + format(judgeScore(runnerUp)) + ")";
        }
        if (!clusterSize(best).equals(clusterSize(runnerUp))) {
            return "how many workers produced the same change (" + clusterSize(best)
                + " against " + clusterSize(runnerUp) + ")";
        }
        if (filesOutsideWriteSet(best) != filesOutsideWriteSet(runnerUp)) {
            return "how far outside its own paths it went (" + filesOutsideWriteSet(best)
                + " file(s) against " + filesOutsideWriteSet(runnerUp) + ")";
        }
        if (changedLines(best) != changedLines(runnerUp)) {
            return "size of the change (" + changedLines(best) + " lines against "
                + changedLines(runnerUp) + ")";
        }
        return "nothing measurable — the two are equal on every test, so the lower candidate id "
            + "decided it, which keeps the choice repeatable";
    }

    /**
     * What the operator is told when nothing may be delivered. It names every candidate and quotes
     * the judge's own words, because a bare "the task is blocked" that nobody can act on is how this
     * project spent a week blocked.
     */
    private static String nothingSelectableReason(List<CandidateSolution> judged) {
        StringBuilder sb = new StringBuilder("No candidate can be delivered. All ")
            .append(judged.size())
            .append(" candidate(s) were scored 0.0 by the judge, which on its own scale means the "
                + "change does not do the task at all. Delivering one would mark the task done with "
                + "the work missing, so nothing is delivered and this is being said out loud "
                + "instead.\n");
        for (CandidateSolution candidate : judged) {
            sb.append("  - worker ").append(candidate.workerIndex()).append(": ")
                .append(candidate.judge() == null || candidate.judge().rationale() == null
                    ? "(no rationale)" : candidate.judge().rationale().strip())
                .append('\n');
        }
        sb.append("Re-word the task, split it smaller, or fix what the workers were missing, "
            + "then run it again.");
        return sb.toString();
    }

    private static String format(double score) {
        // Locale.ROOT, so a German-locale workstation does not write "0,50" into the sentence
        // the operator reads and the log line a script may grep.
        return score < 0 ? "not judged" : String.format(Locale.ROOT, "%.2f", score);
    }

    /** Keeps a multi-line reason to the single log line this project's logs can afford. */
    private static String oneLine(String reason) {
        return reason.replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }
}
