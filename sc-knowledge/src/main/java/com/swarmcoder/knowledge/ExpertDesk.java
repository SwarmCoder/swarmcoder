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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.runtime.ExpertAnswerLog;
import com.swarmcoder.runtime.ExpertHelp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a stuck worker's question is answered, out of the codebase rather than out of a model.
 *
 * <p><b>The order matters and it is not negotiable.</b> Every question is put to the free,
 * deterministic sources first — the reference material's real call sites, the nearest worked
 * example, the task's own contracts — and only what none of them can answer is escalated. That is
 * not a cost optimisation; it is what makes the answer trustworthy. A call site pulled out of code
 * that compiles today cannot be a hallucinated method signature, and the worker can see the file
 * it came from.
 *
 * <p><b>What "answered deterministically" means for an API question.</b> Not a summary of the API
 * and not a signature list. The smallest whole method in this codebase that actually makes the
 * call, the imports that method's file needs for it, and — when the type comes from a module the
 * asking project's build does not declare — the dependency line that has to be added. That is the
 * shape of answer that lets a worker write the next line, which a signature never is.
 *
 * <p><b>The escalation is a seam, and by default it is not wired.</b> {@code expert} is null unless
 * a caller supplies one, and every test supplies a fake. In production it is the utility/architect
 * role, which is a paid endpoint, so it goes through the run's cloud gate; there is no per-worker
 * cap on how many times a worker may escalate.
 *
 * <p><b>And the expert on the other end of that seam looks things up.</b> It is not one chat call
 * any more but an agent session with read-only tools over this project's own code — see
 * {@link ExpertEscalation} and {@link ExpertTools}. That changes nothing here: the free tiers are
 * still tried first, the escalation is still the only path that costs anything, and a session that
 * reaches no answer still says so plainly instead of inventing one. What it adds is that the
 * answer, when there is one, was read out of files rather than recalled.
 */
public final class ExpertDesk implements ExpertHelp {

    private static final Logger log = LoggerFactory.getLogger(ExpertDesk.class);

    /**
     * How much of a file's text an answer may quote. Two ordinary methods with their imports.
     *
     * <p><b>At the baseline room</b> (2026-09-25): the answer goes to a worker, so it is scaled by
     * the worker's room ({@link #sizedFor}; see {@code MaterialBudget}) — 4,000 at 51,200 tokens,
     * 20,480 at the DeepSeek workers' 262,144. Twice it still bounds a free answer and a whole file
     * or member, and once it still bounds an escalated answer, so every ratio between them holds.
     *
     * <p>{@link #coverage} is the exception and scores the first {@code 2 * ANSWER_CHARS} of a
     * candidate whatever the room: coverage is the ranking, {@link #COVERAGE_THRESHOLD} was measured
     * against that window, and a bigger window would let a longer answer buy its way over the bar
     * — the one thing the cap before scoring exists to prevent.
     */
    private static final int ANSWER_CHARS = 4_000;

    /**
     * What the expert is handed besides the question, at the baseline room: the best of the free
     * tiers, then sources and documentation. Scaled by the EXPERT's room (the escalation's own
     * model, {@link ToolUsingExpert#room()}), because the expert is the one reading it.
     */
    private static final int ESCALATION_FREE_CONTEXT_CHARS = 3_000;
    private static final int ESCALATION_SOURCES_CHARS = 6_000;
    private static final int ESCALATION_DOCS_CHARS = 4_000;

    /** How many call sites are searched before the best one is chosen. */
    private static final int MAX_CALL_SITES = 40;

    /**
     * Below this share of the question's own DISTINGUISHING words (see {@link
     * #distinguishingTokens}) — not every word in the question, but what is left once the words
     * already in the worker's own task brief, this codebase's own documentation-search filler,
     * and any name in the question that the reference material treats as ubiquitous are all
     * dropped — a free candidate is a keyword hit on shared vocabulary, not an answer.
     *
     * <p>Raised from 0.34 measured 2026-09-03: seven questions in three minutes about one
     * unfamiliar RMI-proxy API were each scored against the WHOLE question, including the
     * framework's own name and the words the worker's own brief had already used, so a keyword hit
     * covering 0.4–0.6 of the raw question passed every single time while the free tier answered a
     * different quarter of it on each ask and never the part the worker actually needed — its next
     * question said so outright ("I need the exact, untrimmed method bodies"). Distinguishing
     * tokens fix WHAT is counted; this bar fixes how much of it must be covered.
     */
    private static final double COVERAGE_THRESHOLD = 0.5;

    /**
     * A name in the question that at least this fraction of the reference material's files use
     * (see {@link SemanticIndex#howCommon}) is framework scaffolding, not the subject of the
     * question — every question about an unfamiliar framework repeats its own name and its own
     * commonest types, and a keyword hit on those is not evidence the answer is ABOUT anything.
     */
    private static final double FRAMEWORK_COMMON_THRESHOLD = 0.3;

    /**
     * Below this many parsed files, {@link SemanticIndex#howCommon} is not trusted at all: it is a
     * fraction of the indexed population, and a fraction over a handful of files calls everything
     * in them "common" by definition — a two-file reference folder makes its only class "used in
     * 100% of files". A reference folder this small teaches a worker almost nothing anyway, so
     * this guard costs nothing where the mechanism would matter.
     */
    private static final int MIN_FILES_FOR_COMMONALITY = 20;

    private final KnowledgeCurator curator;
    private final Path targetRoot;
    private final List<ApiContract> contracts;
    /** Nullable. (question, context) -> answer. Null means: never escalate, ever. */
    private final BiFunction<String, String, String> expert;
    private final AtomicInteger modelCalls = new AtomicInteger();
    // Copy-on-write, both: one asker may put several questions at once (2026-10-02), and each
    // list is read far more often than it grows.
    private final List<Answer> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Every question this worker asked, its tokens, and what it was told — see {@link #askedAgainReason}. */
    private final List<AskedQuestion> askedQuestions =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Nullable — where every answer of the run is recorded. See {@link #recordedIn}. */
    private volatile ExpertAnswerLog answerLog;
    private volatile ExpertAnswerLog.Asker asker;
    /** The server's own token counts of the escalation made on this thread, for the record. */
    private final ThreadLocal<long[]> escalationTokens = new ThreadLocal<>();

    /**
     * How long an asker waits for an answer somebody else of its run is already having
     * researched before it asks for itself. A SAFETY STOP: the longest answer measured took 13
     * minutes (harness run 66), and nobody may wait for ever on a session that died.
     */
    static final long SHARED_WAIT_MINUTES = Long.getLong("swarmcoder.expert.sharedWaitMinutes", 30);
    private volatile Map<String, Path> artifactCache;
    /** Nullable — shared across every worker of one run. See {@link #sharedAcrossRun}. */
    private volatile RunAnswerCache runCache;
    /** This worker's own index, named in a later worker's cache-hit reason. -1 until wired. */
    private volatile int workerIndex = -1;
    /** Words already in this worker's own task brief/prefix. See {@link #excludingBriefWords}. */
    private volatile Set<String> briefTokens = Set.of();
    /** The room of the worker this desk answers. See {@link #sizedFor}. */
    private volatile MaterialBudget workerRoom = MaterialBudget.BASELINE;

    /** One question this desk answered for this worker: the text, its tokens, and the answer. */
    private record AskedQuestion(String question, List<String> tokens, Answer answer,
                                 ExpertAnswerLog.Handle recorded) {}

    public ExpertDesk(KnowledgeCurator curator, Path targetRoot, List<ApiContract> contracts) {
        this(curator, targetRoot, contracts, null);
    }

    /**
     * @param expert the escalation. Null — the default everywhere except production — means a
     *               question nothing deterministic can answer comes back saying so, and no model
     *               is called. Tests pass a fake; the plain-harness experiment passes the free
     *               local endpoint.
     */
    public ExpertDesk(KnowledgeCurator curator, Path targetRoot, List<ApiContract> contracts,
                      BiFunction<String, String, String> expert) {
        this.curator = curator;
        this.targetRoot = targetRoot;
        this.contracts = contracts == null ? List.of() : List.copyOf(contracts);
        this.expert = expert;
    }

    /** Every answer this worker was given, in order — for the candidate's record and the judge. */
    public List<Answer> asked() {
        return List.copyOf(asked);
    }

    /**
     * Wires this desk into the run it belongs to: {@code cache} is the SAME {@link RunAnswerCache}
     * given to every other worker's own desk for this run, so a question this worker's expert
     * already answered for an earlier worker is served free instead of escalating twice for the
     * same topic, and {@code workerIndex} names this worker so a LATER worker's cache hit can say
     * whose answer it is reusing.
     *
     * <p>A setter, not a constructor argument, for the same reason {@code WorkerLoop#withExpert} is
     * one: every existing constructor and every existing caller — every test, and the two
     * production factories that build one desk per worker with no idea yet which run or index it
     * belongs to — keeps working unchanged; only the dispatcher, which is the one place that
     * actually knows the run, wires this in. Never called means never shared, which is exactly
     * today's behaviour: one desk, one worker, no cross-worker reuse.
     */
    public ExpertDesk sharedAcrossRun(RunAnswerCache cache, int workerIndex) {
        this.runCache = cache;
        this.workerIndex = workerIndex;
        return this;
    }

    /**
     * Has every answer this desk gives recorded in {@code log}, as given to {@code asker} - the
     * run's own record of what the expert was asked, what each answer cost, and what became of
     * the work that followed it ({@link ExpertAnswerLog}). A setter for the reason
     * {@link #sharedAcrossRun} is one; never called means nothing is recorded.
     */
    public ExpertDesk recordedIn(ExpertAnswerLog log, ExpertAnswerLog.Asker asker) {
        this.answerLog = log;
        this.asker = asker;
        return this;
    }

    /**
     * The worker's own task brief/prefix — everything the shared prompt prefix already told it —
     * so its words are dropped from a question's distinguishing tokens (see {@link
     * #distinguishingTokens}). A word the worker was already handed is not evidence that a free
     * candidate is ABOUT the question; it is evidence the question quoted its own brief back.
     */
    public ExpertDesk excludingBriefWords(String briefText) {
        this.briefTokens = briefText == null || briefText.isBlank() ? Set.of()
            : Set.copyOf(WorkedExamples.tokens(briefText));
        return this;
    }

    /**
     * Sizes every answer this desk gives for the room of the worker that asks (see
     * {@code MaterialBudget}). A setter for the same reason as {@link #sharedAcrossRun}: only the
     * factories that know the worker models have anything to say, and never called means the
     * baseline, which is every answer this desk always gave.
     */
    public ExpertDesk sizedFor(MaterialBudget room) {
        this.workerRoom = room == null ? MaterialBudget.BASELINE : room;
        return this;
    }

    /** Nullable - the free first step. See {@link #judgedBy}. */
    private volatile StoredAnswerJudge judge;

    /**
     * Gives this desk its free first step ({@link StoredAnswerJudge}, 2026-10-04): before the
     * expert is asked, {@code judge} reads the question beside the answers the expert already
     * researched in this run and beside the best the project's own code offered, and when one of
     * them answers it that one is handed back, under the question it was researched for. With a
     * judge the desk no longer decides by shared words that a stored answer answers a question.
     * A setter for the reason {@link #sharedAcrossRun} is one; never called, or null, is the
     * behaviour before it existed.
     */
    public ExpertDesk judgedBy(StoredAnswerJudge judge) {
        this.judge = judge;
        return this;
    }

    /** How many stored answers one check is handed, newest first. */
    private static final int MAX_JUDGED = 12;

    /**
     * The stored answer, or the project's own material, that {@link #judge} says answers
     * {@code question}; null when none does. {@code reused[0]} is set when it is a stored one.
     */
    private Answer judged(String question, RunAnswerCache cache, String freeCandidate,
                          boolean[] reused) {
        StoredAnswerJudge judging = judge;
        if (judging == null) {
            return null;
        }
        List<RunAnswerCache.Stored> stored = cache == null ? List.of() : cache.stored();
        if (stored.size() > MAX_JUDGED) {
            stored = stored.subList(0, MAX_JUDGED);
        }
        List<StoredAnswerJudge.Candidate> candidates = new ArrayList<>();
        for (RunAnswerCache.Stored one : stored) {
            candidates.add(new StoredAnswerJudge.Candidate(one.question(), one.answer()));
        }
        boolean withFree = freeCandidate != null && !freeCandidate.isBlank();
        if (withFree) {
            candidates.add(new StoredAnswerJudge.Candidate("", freeCandidate));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int chosen;
        try {
            chosen = judging.whichAnswers(question, candidates);
        } catch (RuntimeException e) {                                     // noqa
            log.warn("the check of stored answers failed: {}", e.toString());
            return null;
        }
        if (chosen < 0 || chosen >= candidates.size()) {
            return null;
        }
        if (chosen < stored.size()) {
            RunAnswerCache.Stored one = stored.get(chosen);
            reused[0] = true;
            return new Answer(RunAnswerCache.labelled(one.question(), one.answer()),
                Source.DETERMINISTIC, 0, 0, List.of(), "free: a local check found that the "
                    + "expert's earlier answer for " + RunAnswerCache.askerWords(one.workerIndex())
                    + " answers this");
        }
        return new Answer(trim(freeCandidate, answerChars() * 2), Source.DETERMINISTIC, 0, 0,
            List.of(), "free: a local check found that the project's own code and documentation "
                + "answer this");
    }

    /** {@link #ANSWER_CHARS} for this desk's worker. */
    private int answerChars() {
        return workerRoom.chars(ANSWER_CHARS);
    }

    // ---------------------------------------------------------------------------------------
    // ask_expert
    // ---------------------------------------------------------------------------------------

    @Override
    public Answer askExpert(String question, String whatITried) {
        long started = System.currentTimeMillis();
        List<String> tokens = questionTokens(question);
        escalationTokens.remove();
        // Another worker of THIS RUN may already have paid to have the expert answer this exact
        // topic — checked before the free tiers even run, because if it is there it is a better
        // answer than anything the free tiers could find on their own for a rephrasing.
        RunAnswerCache cache = runCache;
        // With a judge, whether a stored answer answers this question is the judge's reading, made
        // below once the free tiers have had their look - not a count of shared words here.
        StoredAnswerJudge theJudge = judge;
        boolean judging = theJudge != null;
        Answer cached = cache == null || judging ? null : cache.answerFor(tokens).orElse(null);
        Answer answer;
        boolean reused = cached != null;
        if (cached != null) {
            answer = cached;
        } else {
            FreeAttempt free = tryFree(question);
            boolean[] storedOne = {false};
            Answer settled = free.answer() != null ? null
                : judged(question, cache, free.candidate(), storedOne);
            // ALWAYS, when nothing free could answer. A worker that asks and is told "I cannot
            // answer, write your best attempt" has been given exactly what it had before it
            // asked, and the measured consequence is that it goes back to reading. The only
            // honest "no answer" is an expert that is not there or is out of budget, and both
            // say so.
            if (free.answer() != null) {
                answer = free.answer();
            } else if (settled != null) {
                answer = settled;
                reused = storedOne[0];
            } else if (cache == null) {
                answer = escalate(question, whatITried, free.freeContext(), free.reason());
            } else {
                // One investigation per question per run, even when two askers put it at the
                // same moment: the second waits for the first's answer instead of paying for
                // its own (RunAnswerCache.Claim).
                RunAnswerCache.Claim claim = cache.claim(tokens, workerIndex, question, !judging);
                Answer shared = claim.finished != null ? claim.finished
                    : claim.mine() ? null : sharedAnswer(claim, question);
                if (shared != null && judging && claim.finished == null
                        && theJudge.whichAnswers(question, List.of(
                            new StoredAnswerJudge.Candidate("", shared.text()))) != 0) {
                    // It waited for a question that only looked like its own.
                    shared = null;
                }
                if (shared != null) {
                    answer = shared;
                    reused = true;
                } else if (!claim.mine()) {
                    // The one it waited for reached no answer. It asks for itself, unshared:
                    // a second wait behind a third asker would be a queue with no end.
                    answer = escalate(question, whatITried, free.freeContext(), free.reason());
                    if (answer.source() == Source.MODEL) {
                        cache.record(tokens, workerIndex, answer, question);
                    }
                } else {
                    answer = null;
                    try {
                        answer = escalate(question, whatITried, free.freeContext(), free.reason());
                    } finally {
                        cache.finish(claim, answer);
                    }
                }
            }
        }
        double seconds = (System.currentTimeMillis() - started) / 1000.0;
        ExpertAnswerLog.Handle recorded = record(question, tokens, answer, reused, seconds);
        askedQuestions.add(new AskedQuestion(question, tokens, answer, recorded));
        asked.add(answer);
        String reasonSuffix = answer.reason() == null || answer.reason().isBlank() ? ""
            : " — " + answer.reason();
        log.info("ask_expert '{}' answered {} ({} tokens) in {}s{}", trim(question, 80),
            answer.source(), answer.tokens(), Math.round(seconds), reasonSuffix);
        return answer;
    }

    /**
     * The answer another asker of this run is having researched right now, once it arrives -
     * free, and marked as shared. Null when that research reached no answer or took longer than
     * {@link #SHARED_WAIT_MINUTES}.
     */
    private Answer sharedAnswer(RunAnswerCache.Claim claim, String question) {
        log.info("ask_expert '{}': the expert is already answering this for {}; waiting for that "
            + "answer instead of asking twice", trim(question, 80),
            RunAnswerCache.askerWords(claim.theirWorker));
        Answer theirs;
        try {
            theirs = claim.theirs.get(SHARED_WAIT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.TimeoutException e) {                // noqa
            log.warn("SAFETY STOP: waited {} minutes for an answer the expert was giving {}; "
                + "asking again instead", SHARED_WAIT_MINUTES,
                RunAnswerCache.askerWords(claim.theirWorker));
            return null;
        } catch (Exception e) {                                            // noqa
            return null;
        }
        if (theirs == null || theirs.source() != Source.MODEL || !theirs.answered()) {
            return null;
        }
        return new Answer(theirs.text(), Source.DETERMINISTIC, 0, 0, List.of(),
            "free: the expert was already answering this for "
                + RunAnswerCache.askerWords(claim.theirWorker) + ", and its answer was shared");
    }

    /** Writes one answer to the run's record, and marks the earlier ones it shows did not settle. */
    private ExpertAnswerLog.Handle record(String question, List<String> tokens, Answer answer,
                                          boolean reused, double seconds) {
        ExpertAnswerLog answers = answerLog;
        if (answers == null) {
            return null;
        }
        // The same asker asking the same thing again is the plainest sign there is that the
        // earlier answer did not help.
        for (AskedQuestion earlier : askedQuestions) {
            if (earlier.recorded() != null && WorkedExamples.tokenOverlap(tokens, earlier.tokens())
                    >= RunAnswerCache.OVERLAP_THRESHOLD) {
                answers.askedAgain(earlier.recorded());
            }
        }
        ExpertAnswerLog.From from = reused ? ExpertAnswerLog.From.REUSED
            : answer.source() == Source.MODEL ? ExpertAnswerLog.From.EXPERT_RESEARCH
            : answer.source() == Source.DETERMINISTIC ? ExpertAnswerLog.From.PROJECT_CODE
            : ExpertAnswerLog.From.NO_ANSWER;
        long[] sent = escalationTokens.get();
        escalationTokens.remove();
        boolean modelRan = !reused && sent != null;
        return answers.record(asker, question, from, answer.expertTurns(),
            answer.toolsUsed().size(), modelRan ? sent[0] : 0, modelRan ? sent[1] : 0, seconds);
    }

    /**
     * What trying the free tiers came to: either an answer worth giving (in which case
     * {@code freeContext} and {@code reason} are unused), or nothing good enough — in which case
     * {@code freeContext} is the best material the free tiers found (handed to the expert so it
     * builds on it rather than starting blind) and {@code reason} says why the free tier's best
     * candidate did not count as an answer.
     */
    private record FreeAttempt(Answer answer, String freeContext, String reason,
                               String candidate) {
        FreeAttempt(Answer answer, String freeContext, String reason) {
            this(answer, freeContext, reason, null);
        }
    }

    /**
     * Every free source, scored the same way, best answer first.
     *
     * <p><b>One ranking, not a chain of special cases.</b> An earlier version asked "does this
     * question look like an artifact question? a type question? a concept question?" and ran a
     * different search for each. That is a guess about question SHAPE, and the shapes a worker
     * actually asks in are not enumerable — the one that broke it was "how do I persist a root
     * object with EclipseStore through zerozstack-store-eclipsestore in a ZeroZ Stack server
     * module", which is all three at once and matched none of them.
     *
     * <p>So every source offers what it has and they are ranked by the same number: <b>how much of
     * the question this answer actually covers</b>. A question mentioning an artifact gets the
     * artifact resolver's answer because that answer contains the artifact's name and its module
     * and its declaration; a question mentioning a type gets the call site because the call site
     * contains the type and the method and the argument; a question of pure prose gets the index's
     * section because that section is the only thing that contains those words at all. Nothing had
     * to decide in advance which kind of question it was.
     */
    private FreeAttempt tryFree(String question) {
        List<String> askedTokens = questionTokens(question);
        if (askedTokens.isEmpty()) {
            lastTier = "none";
            return new FreeAttempt(null, "", NOTHING_FREE_REASON);
        }
        List<Candidate> candidates = new ArrayList<>();
        semanticCandidates(question, candidates);
        artifactCandidates(question, askedTokens, candidates);
        callSiteCandidates(question, candidates);
        indexCandidates(question, candidates);
        namedSourceCandidates(question, candidates);
        List<String> distinguishing = distinguishingTokens(askedTokens, question);
        for (Candidate candidate : candidates) {
            candidate.score = coverage(distinguishing, candidate.text);
        }
        // The one number decides, and where it cannot, the source that KNOWS decides. Two answers
        // covering the same words are not equally trustworthy: one of them is the compiler's
        // answer with a file and a line on it, and the other is a keyword hit. The tier is a
        // tie-break and nothing more — a semantic answer that covers less of the question still
        // loses, which is what keeps this a ranking rather than the chain of special cases it
        // replaced.
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed()
            .thenComparingInt(c -> c.tier));
        if (candidates.isEmpty()) {
            lastTier = "none";
            return new FreeAttempt(null, "", NOTHING_FREE_REASON);
        }
        Candidate best = candidates.get(0);
        lastTier = best.kind;
        // When the free tier's best has nothing NEW — this desk already gave this worker the
        // same material, word for word — it is not an answer either, it is the same non-answer
        // said again. Checked first because it is the most specific signal there is: the exact
        // text, not a guess from the question's wording.
        if (repeats(best.text)) {
            return new FreeAttempt(null, best.text, REPEAT_REASON);
        }
        // This worker already asked something this close, in different words — the free tier
        // does not get a second look on the strength of a rephrasing; see askedAgainReason. This
        // is unconditional: even a rephrasing that NOW scores above the coverage bar is not new
        // evidence that the topic is settled, because a worker that had settled it would not be
        // asking again.
        String askedAgain = askedAgainReason(askedTokens);
        if (askedAgain != null) {
            return new FreeAttempt(null, withHistory(best.text), askedAgain);
        }
        // A free answer counts as an answer only when it covers the question. Below the bar, the
        // best free material still goes to the expert as context — it is not wasted, it just is
        // not, on its own, an answer.
        if (best.score < COVERAGE_THRESHOLD) {
            return new FreeAttempt(null, best.text, escalatedReason(best.score), best.text);
        }
        StringBuilder sb = new StringBuilder(best.text);
        // The runner-up too, when it is of a different kind and nearly as good. A question about
        // an artifact is usually also a question about the code behind it, and answering only one
        // half sends the worker straight back to reading for the other.
        for (Candidate other : candidates) {
            if (!other.kind.equals(best.kind) && other.score >= best.score * 0.6) {
                sb.append("\n").append(other.text);
                break;
            }
        }
        sb.append("\nIf this does not settle it, write your best attempt and let the build "
            + "correct you — that is faster and more certain than reading further.\n");
        Answer answer = withReason(
            Answer.deterministic(trim(sb.toString(), answerChars() * 2)), freeReason(best.score));
        return new FreeAttempt(answer, "", "");
    }

    /**
     * Whether the free tier's best candidate is something this desk already gave this worker —
     * word for word the same, or wholly contained in (or containing) an earlier answer. A repeat
     * is not new material: repeating it costs the worker the same read a second time for nothing.
     * Compared against every answer this worker has already been given, free or escalated, because
     * a free tier that only repeats what the EXPERT already said is just as stale.
     */
    private boolean repeats(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (Answer prior : asked) {
            String given = prior.text();
            if (given != null && !given.isBlank()
                    && (given.contains(text) || text.contains(given))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why this question has already had the free tier's one chance: an earlier question THIS
     * worker asked (in the same normalisation {@link #questionTokens} always uses) overlaps this
     * one by {@link RunAnswerCache#OVERLAP_THRESHOLD} or more — the same bar and the same overlap
     * measure ({@link WorkedExamples#tokenOverlap}, shared tokens over the smaller set) this class
     * already uses everywhere else to decide "is this about the same thing".
     *
     * <p>This is the fix for the shape of bug that {@link #repeats} cannot catch: seven questions
     * about one unfamiliar API, each phrased differently enough that the free tier's keyword
     * search found a DIFFERENT snippet every time, so no two answers were ever textually the same
     * and {@code repeats} never fired even though the worker was plainly asking the same thing
     * louder each time.
     */
    private String askedAgainReason(List<String> askedTokens) {
        for (int i = 0; i < askedQuestions.size(); i++) {
            double overlap = WorkedExamples.tokenOverlap(askedTokens, askedQuestions.get(i).tokens());
            if (overlap >= RunAnswerCache.OVERLAP_THRESHOLD) {
                return "escalated: asked again (" + String.format(Locale.ROOT, "%.1f", overlap)
                    + " overlap with ask #" + (i + 1) + ")";
            }
        }
        return null;
    }

    /**
     * The free tier's best material, plus every earlier question this worker asked in this run
     * and what it was told — so the expert sees what did NOT settle it instead of starting from
     * just the latest rephrasing. Carried to {@link #escalate} through the same {@code
     * freeContext} channel an ordinary coverage shortfall already uses.
     */
    private String withHistory(String bestText) {
        StringBuilder sb = new StringBuilder();
        if (bestText != null && !bestText.isBlank()) {
            sb.append(bestText).append('\n');
        }
        sb.append("\nThis worker already asked ").append(askedQuestions.size())
          .append(askedQuestions.size() == 1 ? " question" : " questions")
          .append(" that look like the same topic, and none of them settled it:\n");
        for (int i = 0; i < askedQuestions.size(); i++) {
            AskedQuestion prior = askedQuestions.get(i);
            sb.append("\n#").append(i + 1).append(" \"").append(trim(prior.question(), 200))
              .append("\"\n").append(trim(prior.answer() == null ? "" : prior.answer().text(), 600))
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * {@link #questionTokens} with everything dropped that cannot distinguish this question from
     * another: the words already in the worker's own task brief (see {@link
     * #excludingBriefWords}) — it was already told those, so repeating them back is not an
     * answer; the words {@link ReferenceIndex}'s own search already treats as filler ({@link
     * SourceShaper#NOISE}, the same stop-list the docs index scores search terms against); and the
     * words of any name in the question that the semantic index says is common across the
     * reference material ({@link #FRAMEWORK_COMMON_THRESHOLD}) — a type or method nearly every
     * file uses is framework scaffolding, not the subject being asked about.
     *
     * <p>A keyword hit on what is left over is a hit on the SUBJECT of the question; a keyword hit
     * on what is dropped here is a hit on vocabulary every question about this framework shares,
     * which is exactly what let "how do I obtain a proxy for an @RmiService interface" and "I need
     * the exact client-side APIs for obtaining a proxy" both score as answered by two completely
     * different snippets that each happened to say "RmiService" and "proxy".
     */
    private List<String> distinguishingTokens(List<String> askedTokens, String question) {
        if (askedTokens.isEmpty()) {
            return askedTokens;
        }
        Set<String> exclude = new LinkedHashSet<>(SourceShaper.NOISE);
        exclude.addAll(briefTokens);
        SemanticIndex index = semanticIndexOrNull();
        // howCommon is a FRACTION of the indexed files, and a fraction over a handful of files is
        // not evidence of anything — a demo reference folder with two files makes every type in
        // it "used in 100% of files" by definition. Only trust it once there is a real population
        // to be common ACROSS.
        if (index != null && index.stats().parsedFiles() >= MIN_FILES_FOR_COMMONALITY) {
            for (String name : index.namesKnownIn(question)) {
                if (index.howCommon(name) >= FRAMEWORK_COMMON_THRESHOLD) {
                    exclude.addAll(WorkedExamples.tokens(name));
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String token : askedTokens) {
            if (!exclude.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * The semantic index, or null when it is not usable — the same fallback {@link
     * #semanticCandidates} makes, without repeating its own "say it once" logging (that method
     * still says so; this is a second, silent caller of the same index).
     */
    private SemanticIndex semanticIndexOrNull() {
        try {
            SemanticIndex index = curator.semanticIndex();
            return index != null && index.available() ? index : null;
        } catch (Throwable e) {                                            // noqa
            return null;
        }
    }

    /** "free: covered 0.8 of the question" — how much of the question the winning answer covers. */
    private static String freeReason(double score) {
        return "free: covered " + String.format(Locale.ROOT, "%.1f", score) + " of the question";
    }

    /** "escalated: free answer covered 0.2" — the free tier tried, and it was not enough. */
    private static String escalatedReason(double score) {
        return "escalated: free answer covered " + String.format(Locale.ROOT, "%.1f", score);
    }

    /** No free source had anything at all to offer — not even a weak keyword hit. */
    private static final String NOTHING_FREE_REASON = "escalated: free tier had nothing to offer";

    /** The free tier's best candidate was a repeat of material this worker was already given. */
    private static final String REPEAT_REASON =
        "escalated: free tier had only what it already gave";

    /** Carries {@code reason} onto an {@code Answer} without disturbing anything else about it. */
    private static Answer withReason(Answer answer, String reason) {
        return new Answer(answer.text(), answer.source(), answer.tokens(), answer.expertTurns(),
            answer.toolsUsed(), reason);
    }

    /**
     * One thing a free source can offer, and how much of the question it covers.
     *
     * @param tier how much this source KNOWS, used only to break a tie in the score: 0 is a fact
     *             read out of a type-attributed parse, 4 is a keyword hit in a source file
     */
    private static final class Candidate {
        final String kind;
        final String text;
        final int tier;
        double score;

        Candidate(String kind, int tier, String text) {
            this.kind = kind;
            this.tier = tier;
            this.text = text;
        }
    }

    /** Which free source won the last deterministic answer — for the corpus ledger and the log. */
    private volatile String lastTier = "";

    /**
     * The kind of source that answered the last question: {@code semantic}, {@code artifact},
     * {@code call site}, {@code documentation}, {@code source}, or {@code none}.
     *
     * <p>Reporting only. Nothing branches on it; it exists so a person can see WHICH of the free
     * tiers is carrying a question, which is the thing that was invisible before and the reason
     * two rounds of fixes were shaped to the wrong tier.
     */
    public String lastTier() {
        return lastTier;
    }

    /**
     * The fraction of the question's own distinctive words this answer contains.
     *
     * <p>The same number for a build file, a method body and a documentation section, which is
     * what makes them rankable against each other at all. Longer answers naturally cover more,
     * and that is not a bug: an answer that mentions more of what was asked has answered more of
     * it. Every candidate is capped at the answer budget before it is scored, so nothing wins by
     * being enormous.
     */
    static double coverage(List<String> asked, String answer) {
        if (asked.isEmpty() || answer == null || answer.isBlank()) {
            return 0;
        }
        Set<String> words = new LinkedHashSet<>(
            WorkedExamples.tokens(answer.length() > ANSWER_CHARS * 2
                ? answer.substring(0, ANSWER_CHARS * 2) : answer));
        int shared = 0;
        for (String token : asked) {
            if (words.contains(token)) {
                shared++;
            }
        }
        return (double) shared / asked.size();
    }

    /** The question's own distinctive words: what an answer has to be about. */
    static List<String> questionTokens(String question) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : WorkedExamples.tokens(question == null ? "" : question)) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return new ArrayList<>(tokens);
    }

    /**
     * Words that are in every question and therefore distinguish none of them. Kept deliberately
     * short: this is a list of English filler, not a list of subjects, and nothing about any
     * particular framework or question shape belongs in it.
     */
    private static final Set<String> STOPWORDS = Set.of(
        "how", "the", "and", "for", "you", "that", "this", "with", "what", "does", "did", "can",
        "not", "but", "are", "was", "were", "have", "has", "had", "from", "into", "out", "get",
        "use", "using", "used", "need", "needs", "want", "should", "would", "could", "when",
        "where", "why", "who", "which", "there", "their", "them", "they", "its", "it's", "one",
        "two", "any", "all", "some", "way", "make", "made", "work", "works", "example", "please",
        "show", "tell", "help", "question", "answer", "code", "here", "then", "than", "also");

    /**
     * A coordinate the question names: {@code group:artifact}, or a bare artifactId that some
     * build file under the roots declares.
     *
     * <p>This is a LOOKUP, not a rule about question shape. It fires when the question contains an
     * identifier that resolves to a build artifact, exactly as the call-site search fires when it
     * contains an identifier that resolves to a type. A question with no such identifier produces
     * no candidate here and costs nothing.
     */
    private void artifactCandidates(String question, List<String> asked, List<Candidate> into) {
        Map<String, Path> artifacts = artifactIndex();
        Set<String> named = new LinkedHashSet<>();
        for (String raw : (question == null ? "" : question).split("[^A-Za-z0-9_.:-]+")) {
            String token = raw.contains(":") ? raw.substring(raw.lastIndexOf(':') + 1) : raw;
            if (artifacts.containsKey(token)) {
                named.add(token);
            }
        }
        for (String artifact : named) {
            Path pom = artifacts.get(artifact);
            String group = groupOf(pom);
            StringBuilder sb = new StringBuilder("`").append(artifact)
                .append("` is a module of this project's reference material, declared in `")
                .append(pom).append("`.\n\nDeclare it like this — the version is managed by the "
                    + "parent, so no `<version>` element:\n\n```xml\n<dependency>\n  <groupId>")
                .append(group.isEmpty() ? "(the group of your reference material)" : group)
                .append("</groupId>\n  <artifactId>").append(artifact)
                .append("</artifactId>\n</dependency>\n```\n");
            Set<String> own = TaskBrief.dependenciesOf(pom);
            if (!own.isEmpty()) {
                sb.append("\nIt brings in: ").append(String.join(", ", own)).append("\n");
            }
            String types = publicTypesOf(pom);
            if (!types.isEmpty()) {
                sb.append("\nThe types it provides include: ").append(types).append("\n");
            }
            into.add(new Candidate("artifact", 1, sb.toString()));
        }
    }

    /** Every artifactId any build file under the roots declares, to the pom that declares it. */
    private Map<String, Path> artifactIndex() {
        Map<String, Path> found = artifactCache;
        if (found != null) {
            return found;
        }
        found = new LinkedHashMap<>();
        for (KnowledgeCurator.Root root : curator.roots()) {
            try (var stream = Files.walk(root.path())) {
                for (Path pom : stream.filter(f -> f.getFileName() != null
                        && f.getFileName().toString().equals("pom.xml"))
                        .filter(f -> !f.toString().contains("target")).toList()) {
                    String text = Files.readString(pom, StandardCharsets.UTF_8);
                    int at = text.indexOf("<artifactId>");
                    // The FIRST artifactId outside <parent> is the module's own; a pom names many.
                    int parent = text.indexOf("</parent>");
                    at = parent >= 0 ? text.indexOf("<artifactId>", parent) : at;
                    if (at >= 0) {
                        String name = text.substring(at + 12, text.indexOf("</artifactId>", at))
                            .strip();
                        found.putIfAbsent(name, pom);
                    }
                }
            } catch (Exception e) {                                        // noqa
                log.debug("could not index the build files under {}: {}", root.path(),
                    e.getMessage());
            }
        }
        artifactCache = found;
        return found;
    }

    /** A few of the public types a module declares, so an artifact answer says what is in it. */
    private String publicTypesOf(Path pom) {
        Path module = pom.getParent();
        List<String> names = new ArrayList<>();
        for (WorkedExamples.Shape shape : curator.shapes()) {
            if (module != null && shape.file().startsWith(module)
                && !shape.file().toString().contains("src/test")
                && !shape.file().toString().contains("src\\test")) {
                names.add(shape.simpleName());
            }
            if (names.size() >= 14) {
                break;
            }
        }
        return String.join(", ", names);
    }

    /** The call sites, as candidates rather than as an early return. */
    private void callSiteCandidates(String question, List<Candidate> into) {
        Answer answer = answerFromCallSites(question);
        if (answer.answered()) {
            into.add(new Candidate("call site", 2, answer.text()));
        }
    }

    // ---------------------------------------------------------------------------------------
    // The semantic tier
    // ---------------------------------------------------------------------------------------

    /**
     * What the COMPILER knows about the names in the question, with the file and the line.
     *
     * <p><b>Why this tier is different from the four below it.</b> Every other free source matches
     * words. This one resolves names. "How is an RmiService implementation discovered by the
     * server" was previously answered by whichever file's text contained the most of those words;
     * it is now answered by the list of types that actually carry that annotation, each with the
     * file and line it is declared on, because that is a query and not a search. "What implements
     * DataRootProvider" finds the class that implements it through an intermediate interface and
     * never names it. "Which pom declares zerozstack-store-eclipsestore" finds the
     * {@code <dependency>} element and not the release note that mentions it.
     *
     * <p><b>And it is still ranked, not privileged.</b> The answer is scored by exactly the same
     * coverage number as the others, and it loses to a documentation section that covers more of
     * the question. The only edge it has is a tie-break, because when two answers say as much
     * about the question, the one the compiler produced is the one to show. A question naming
     * nothing the index holds produces no candidate here and costs nothing.
     *
     * <p><b>It is never required.</b> {@link SemanticIndex#available()} false — an unparseable
     * language, a missing classpath, a JVM without the parser — means this method adds nothing and
     * the desk behaves exactly as it did before it existed.
     */
    private void semanticCandidates(String question, List<Candidate> into) {
        SemanticIndex index;
        try {
            index = curator.semanticIndex();
        } catch (Throwable e) {                                            // noqa
            log.debug("the semantic index is not usable for this question: {}", e.toString());
            return;
        }
        if (index == null || !index.available()) {
            if (index != null && semanticFallbackSaid.compareAndSet(false, true)) {
                log.info("the semantic index is not available ({}), so the desk answers from the "
                    + "text index alone", index.unavailableReason());
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String name : index.namesKnownIn(question)) {
            String block = whatIsKnownAbout(index, name);
            if (!block.isEmpty()) {
                sb.append(block);
            }
            if (sb.length() > answerChars()) {
                break;
            }
        }
        for (String method : index.methodNamesKnownIn(question)) {
            List<SemanticIndex.Ref> calls = index.usagesOf(method);
            if (calls.isEmpty()) {
                continue;
            }
            sb.append("\nWhere `").append(method).append("()` is called, with what it is called "
                + "on:\n");
            for (SemanticIndex.Ref ref : calls.subList(0, Math.min(3, calls.size()))) {
                sb.append("  ").append(ref.where()).append("  ").append(ref.detail()).append('\n');
                sb.append(quote(ref));
            }
            if (sb.length() > answerChars()) {
                break;
            }
        }
        for (String artifact : index.artifactsNamedIn(question)) {
            List<SemanticIndex.Ref> declared = index.dependencyDeclaring(artifact);
            if (declared.isEmpty()) {
                continue;
            }
            sb.append("\n`").append(artifact).append("` is declared in ")
              .append(declared.size() == 1 ? "one build file" : declared.size() + " build files")
              .append(" of the reference material:\n");
            for (SemanticIndex.Ref ref : declared.subList(0, Math.min(4, declared.size()))) {
                sb.append("  ").append(ref.where()).append("  — ").append(ref.detail())
                  .append('\n');
            }
        }
        if (sb.isEmpty()) {
            return;
        }
        into.add(new Candidate("semantic", 0,
            "Read out of this project's reference material with its types resolved — these are "
                + "facts the compiler agrees with, not text matches.\n" + trim(sb.toString(),
                answerChars())));
    }

    /** Everything the index holds about one name, in the order a stuck worker needs it. */
    private String whatIsKnownAbout(SemanticIndex index, String name) {
        StringBuilder sb = new StringBuilder();
        String shape = index.publicShape(name);
        if (!shape.isEmpty()) {
            sb.append("\n`").append(name).append("`, as the compiler resolves it:\n```\n")
              .append(shape).append("\n```\n");
        }
        List<SemanticIndex.Ref> carriers = index.typesAnnotatedWith(name);
        if (!carriers.isEmpty()) {
            sb.append("\nTypes annotated `@").append(name).append("` — this is how the framework "
                + "finds them, so a new one must carry it too:\n");
            for (SemanticIndex.Ref ref : carriers.subList(0, Math.min(6, carriers.size()))) {
                sb.append("  ").append(ref.where()).append("  ").append(ref.detail()).append('\n');
            }
        }
        List<SemanticIndex.Ref> implementations = index.implementationsOf(name);
        if (!implementations.isEmpty()) {
            sb.append("\nWhat implements or extends `").append(name)
              .append("` (resolved, so this includes the ones that reach it through another "
                  + "type and never name it):\n");
            for (SemanticIndex.Ref ref
                    : implementations.subList(0, Math.min(6, implementations.size()))) {
                sb.append("  ").append(ref.where()).append("  ").append(ref.detail()).append('\n');
            }
        }
        List<SemanticIndex.Ref> usages = index.usagesOf(name);
        if (!usages.isEmpty()) {
            sb.append("\nWhere `").append(name).append("` is actually used:\n");
            for (SemanticIndex.Ref ref : usages.subList(0, Math.min(4, usages.size()))) {
                sb.append("  ").append(ref.where()).append("  ").append(ref.detail()).append('\n');
                String quoted = quote(ref);
                if (!quoted.isEmpty()) {
                    sb.append(quoted);
                }
            }
        }
        return sb.toString();
    }

    /**
     * The lines around a reference, quoted from the file it names.
     *
     * <p>A file and a line is a place to look; the lines themselves are an answer. Six of them —
     * enough to see the call and what it is called on, not enough to be a second file to read.
     */
    private String quote(SemanticIndex.Ref ref) {
        if (ref.line() <= 0) {
            return "";
        }
        for (KnowledgeCurator.Root root : curator.roots()) {
            Path file = root.path() == null ? null : root.path().resolve(ref.file());
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int from = Math.max(0, ref.line() - 3);
                int to = Math.min(lines.size(), ref.line() + 3);
                StringBuilder sb = new StringBuilder("```java\n");
                for (int at = from; at < to; at++) {
                    sb.append(at + 1 == ref.line() ? "> " : "  ").append(lines.get(at))
                      .append('\n');
                }
                return sb.append("```\n").toString();
            } catch (Exception e) {                                        // noqa
                return "";
            }
        }
        return "";
    }

    /** So "the index is not available" is said once per desk and not once per question. */
    private final java.util.concurrent.atomic.AtomicBoolean semanticFallbackSaid =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** The reference index over BOTH kinds, each as its own candidate so both can be ranked. */
    private void indexCandidates(String question, List<Candidate> into) {
        String docs = curator.relevantDocs(question, 2, answerChars(), true);
        if (!docs.isBlank()) {
            into.add(new Candidate("documentation", 3,
                "What this project's reference documentation says about it.\n" + docs));
        }
        String code = curator.relevantSources(question, 2, answerChars(), true);
        if (!code.isBlank()) {
            into.add(new Candidate("source", 4,
                "The closest code in this project's reference material.\n" + code));
        }
    }

    // ---------------------------------------------------------------------------------------
    // A named file or a named member: given whole, not excerpted
    // ---------------------------------------------------------------------------------------

    /** {@code a/b/c.java} or a bare {@code Foo.java} — a file the question names outright. */
    private static final Pattern SLASHED_PATH_TOKEN =
        Pattern.compile("[\\w.-]+(?:/[\\w.-]+)+\\.[A-Za-z0-9]{1,6}");
    private static final Pattern BARE_JAVA_FILE_TOKEN =
        Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]*\\.java\\b");

    /** {@code Type#member} or {@code Type.member(} — a member the question names outright. */
    private static final Pattern HASH_MEMBER_TOKEN =
        Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)#([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern DOT_CALL_MEMBER_TOKEN =
        Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\(");

    /**
     * A question that names a source file or a member outright gets that source, whole — not the
     * trimmed keyword excerpt every other free tier offers. This is a new candidate, ranked by the
     * same coverage number as every other one; it wins because its coverage is high (it contains
     * the very path or name the question asked for), not because it is privileged.
     *
     * <p>Exists because a worker cannot read a reference-root file itself — this desk is its only
     * route to one — and a "show me the FULL file contents of X" or "the body of the Y method"
     * question was, before this, answered with the same {@code ANSWER_CHARS}-capped excerpt as any
     * other question, which is precisely not what was asked for.
     */
    private void namedSourceCandidates(String question, List<Candidate> into) {
        if (question == null || question.isBlank()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher slashed = SLASHED_PATH_TOKEN.matcher(question);
        while (slashed.find()) {
            addFileCandidate(slashed.group(), seen, into);
        }
        Matcher bare = BARE_JAVA_FILE_TOKEN.matcher(question);
        while (bare.find()) {
            addFileCandidate(bare.group(), seen, into);
        }
        Matcher hashed = HASH_MEMBER_TOKEN.matcher(question);
        while (hashed.find()) {
            addMemberCandidate(hashed.group(1), hashed.group(2), seen, into);
        }
        Matcher dotted = DOT_CALL_MEMBER_TOKEN.matcher(question);
        while (dotted.find()) {
            addMemberCandidate(dotted.group(1), dotted.group(2), seen, into);
        }
    }

    private void addFileCandidate(String token, Set<String> seen, List<Candidate> into) {
        if (!seen.add("file:" + token)) {
            return;
        }
        Candidate candidate = wholeFileCandidate(token);
        if (candidate != null) {
            into.add(candidate);
        }
    }

    private void addMemberCandidate(String type, String member, Set<String> seen,
                                    List<Candidate> into) {
        if (!seen.add("member:" + type + "#" + member)) {
            return;
        }
        Candidate candidate = wholeMemberCandidate(type, member);
        if (candidate != null) {
            into.add(candidate);
        }
    }

    /** The whole content of the file the question named, wherever it resolves under the roots. */
    private Candidate wholeFileCandidate(String token) {
        String needle = token.replace('\\', '/');
        boolean bareName = !needle.contains("/");
        for (WorkedExamples.Shape shape : curator.shapes()) {
            String relative = shape.relative();
            String withRoot = shape.rootLabel() + "/" + relative;
            boolean matches = bareName
                ? shape.file().getFileName().toString().equals(needle)
                : relative.equals(needle) || withRoot.equals(needle)
                    || relative.endsWith("/" + needle) || withRoot.endsWith("/" + needle)
                    || needle.endsWith("/" + relative);
            if (!matches) {
                continue;
            }
            try {
                String body = Files.readString(shape.file(), StandardCharsets.UTF_8);
                return new Candidate("named file", 0, "The question names this file outright, so "
                    + "here it is, whole rather than excerpted.\n\n```java\n// " + withRoot + "\n"
                    + trim(body, answerChars() * 2) + "\n```\n");
            } catch (Exception e) {                                        // noqa
                return null;
            }
        }
        return null;
    }

    /** The whole source of the member the question named, wherever its declaring type resolves. */
    private Candidate wholeMemberCandidate(String type, String member) {
        for (WorkedExamples.Shape shape : curator.shapes()) {
            if (!shape.simpleName().equals(type)) {
                continue;
            }
            try {
                String source = Files.readString(shape.file(), StandardCharsets.UTF_8);
                JavaOutline outline = JavaOutline.of(source);
                JavaOutline.Member owner = outline.primaryType();
                if (owner == null) {
                    continue;
                }
                for (JavaOutline.Member candidate : owner.descendants()) {
                    if (candidate.name().equals(member)) {
                        return new Candidate("named member", 0, "The question names this member "
                            + "outright, so here it is, in full — not an excerpt — as declared in "
                            + "`" + shape.rootLabel() + "/" + shape.relative() + "`.\n\n```java\n"
                            + trim(candidate.text(), answerChars() * 2) + "\n```\n");
                    }
                }
            } catch (Exception e) {                                        // noqa
                // try the next shape of the same simple name, if any
            }
        }
        return null;
    }

    /**
     * The real call sites of every type the question names, ranked, best one quoted whole.
     *
     * <p>A "call site" is the smallest declaration in a file that actually CALLS something
     * on the type. The body is the part a signature cannot tell you: which object you reach
     * it through, what you pass, what you do with what comes back.
     */
    private Answer answerFromCallSites(String question) {
        // Only names this codebase actually knows. A question is a sentence and the first word of
        // a sentence is capitalised: without this filter "How do I write with LedgerSession" asks
        // about a type called How, matches whatever file happens to contain that string, and
        // answers with the wrong method entirely.
        Set<String> declared = new LinkedHashSet<>();
        for (WorkedExamples.Shape shape : curator.shapes()) {
            declared.add(shape.simpleName());
            // IMPORTED as well as declared. The types a worker is most stuck on are the framework's
            // own, and a framework arrives as a jar — its source is not under any reference root,
            // so nothing there declares it. What the reference material does have is code that
            // imports and calls it, which is the whole point.
            for (String imported : shape.imports()) {
                String simple = JavaSourceFacts.importedSimpleName(imported);
                if (!simple.isEmpty()) {
                    declared.add(simple);
                }
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (String candidate : typeNamesIn(question)) {
            if (declared.contains(candidate)) {
                names.add(candidate);
            }
        }
        if (names.isEmpty()) {
            return Answer.none("");
        }
        List<Site> sites = new ArrayList<>();
        for (WorkedExamples.Shape shape : curator.shapes()) {
            for (String name : names) {
                if (!shape.imports().stream().anyMatch(i ->
                        JavaSourceFacts.importedSimpleName(i).equals(name))
                    && !shape.simpleName().equals(name)) {
                    continue;
                }
                Site site = siteIn(shape, name);
                if (site != null) {
                    sites.add(site);
                }
                if (sites.size() >= MAX_CALL_SITES) {
                    break;
                }
            }
        }
        if (sites.isEmpty()) {
            return Answer.none("");
        }
        // Across files, the same rule: the member that does the most with the type wins, and
        // between two that do the same amount, the shorter one — a long method that happens to
        // touch the type teaches the call plus fifty lines of something else.
        sites.sort(Comparator.<Site>comparingInt(s -> -s.calls())
            .thenComparingInt(s -> s.body().length()));
        Site best = sites.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("This is how `").append(String.join("`, `", names))
          .append("` is used in code that compiles today.\n\n")
          .append("```java\n// ").append(best.shape().relative()).append('\n');
        for (String imported : best.imports()) {
            sb.append("import ").append(imported).append(";\n");
        }
        sb.append('\n').append(best.body()).append("\n```\n");

        String dependency = dependencyFor(declaringShape(names));
        if (!dependency.isEmpty()) {
            sb.append("\nThat type comes from `").append(dependency)
              .append("`, which your build does not declare yet. ")
              .append(MavenRecipes.howToDeclare(
                  dependency.substring(0, dependency.indexOf(':')),
                  dependency.substring(dependency.indexOf(':') + 1), true))
              .append('\n');
        }
        sb.append("\nIt is declared in `").append(best.shape().rootLabel())
          .append("`, so this is the real API and not a guess. ")
          .append(sites.size() > 1
              ? "There are " + sites.size() + " other call sites; this is the shortest."
              : "This is the only call site in the reference material.")
          .append('\n');
        return Answer.deterministic(trim(sb.toString(), answerChars()));
    }

    /**
     * The second free tier: the reference index, when no call site matched.
     *
     * <p><b>Measured, and it is why this exists.</b> A worker with no brief asked exactly the right
     * question — "how do I persist a root object with EclipseStore through
     * zerozstack-store-eclipsestore, how do I save a nested object explicitly, does the server
     * module need a Maven dependency" — and got nothing. Every key term in it was an ARTIFACT name
     * and a CONCEPT, not a type name, so the call-site search had nothing to look for and the
     * question went straight past the free path to an escalation that then failed. The material
     * that answers it was sitting in the index the whole time.
     *
     * <p>Code before prose, and both before a model. A worker asking how to do something wants the
     * lines that do it.
     */
    private Answer answerFromTheIndex(String question) {
        String code = curator.relevantSources(question, 2, answerChars(), true);
        String docs = curator.relevantDocs(question, 2, answerChars(), true);
        if (code.isBlank() && docs.isBlank()) {
            return Answer.none("");
        }
        // Documentation FIRST in this tier, which is the opposite of the call-site tier and is
        // deliberate. A question that reaches here is prose-shaped -- "how do I persist a root
        // object" -- and its words are concepts and artifact names, so the code search is matching
        // on a word rather than on a call. Measured: "...ZeroZ Stack server module..." returned a
        // UI component called Stack. The guide that answers the question is a better first answer
        // than the best keyword hit in the source tree.
        StringBuilder sb = new StringBuilder();
        if (!docs.isBlank()) {
            sb.append("No single call site matched that; here is what the reference "
                + "documentation says about it.\n").append(docs);
        }
        if (!code.isBlank()) {
            sb.append(sb.isEmpty()
                    ? "No single call site matched that, so here is the closest real code in "
                        + "this project's reference material.\n"
                    : "\nAnd the closest code in the reference material.\n")
              .append(code);
        }
        sb.append("\nIf this does not settle it, write your best attempt and let the build "
            + "correct you — that is faster and more certain than reading further.\n");
        return Answer.deterministic(trim(sb.toString(), answerChars() * 2));
    }

    /** One declaration in one file that uses the named type, with the imports it needs. */
    private record Site(WorkedExamples.Shape shape, String preamble, String body,
                        List<String> imports, int calls) {}

    private Site siteIn(WorkedExamples.Shape shape, String name) {
        try {
            String source = Files.readString(shape.file(), StandardCharsets.UTF_8);
            JavaOutline outline = JavaOutline.of(source);
            JavaOutline.Member type = outline.primaryType();
            if (type == null) {
                return null;
            }
            // The handles that reach this type. The type NAME almost never appears in the method
            // that uses it: the field is declared `private LedgerSession session;` and the call is
            // `session.append(...)`. Matching on the type name alone therefore finds the field
            // declaration and nothing else, which is the one place with no call in it. So: the
            // type name (for a static call or a `new`), plus every field declared of that type.
            Set<String> handles = new LinkedHashSet<>();
            handles.add(name);
            for (JavaSourceFacts.Declared member : JavaSourceFacts.of(source).membersOf(type.name())) {
                if (!member.method() && sameType(member.type(), name)) {
                    handles.add(member.name());
                }
            }
            JavaOutline.Member best = null;
            for (JavaOutline.Member member : type.descendants()) {
                // A method or a constructor, never a field. `private LedgerSession session;` is
                // the shortest declaration that mentions the type and it teaches nothing: it says
                // the type exists, which the asker already knew. What is wanted is a body — what
                // you call on it, what you pass, what you do with what comes back.
                if (member.kind() != JavaOutline.Kind.METHOD
                    && member.kind() != JavaOutline.Kind.CONSTRUCTOR) {
                    continue;
                }
                if (!member.hasBody() || invocationsOn(member.text(), handles) == 0) {
                    continue;
                }
                if (best == null || better(member, best, handles)) {
                    best = member;
                }
            }
            if (best == null) {
                return null;
            }
            // The field that holds the handle comes too. A method body that reads
            // `session.append(...)` is unusable on its own — the reader cannot see what `session`
            // is or how it got there, which is exactly the question being asked.
            StringBuilder preamble = new StringBuilder();
            for (JavaOutline.Member member : type.children()) {
                if (member.kind() == JavaOutline.Kind.FIELD
                    && handles.contains(member.name()) && !member.name().equals(name)) {
                    preamble.append(JavaOutline.collapse(member.text()).strip()).append('\n');
                }
            }
            String shown = preamble + best.text();
            List<String> imports = new ArrayList<>();
            for (String imported : outline.imports) {
                String simple = JavaSourceFacts.importedSimpleName(imported);
                if (!simple.isEmpty() && (shown.contains(simple) || simple.equals(name))) {
                    imports.add(imported);
                }
            }
            return new Site(shape, preamble.toString(), best.text(), imports,
                invocationsOn(best.text(), handles));
        } catch (Exception e) {                                            // noqa
            return null;
        }
    }

    /**
     * Where the asked-about type is DECLARED, which is not where it is called.
     *
     * <p>Measured on the first version: asked about a session type, it named the example
     * application's own module — because that is where the call site was — instead of the runtime
     * module the type actually comes from. A worker that added that line would have pulled in the
     * example application and still not had the type.
     */
    private WorkedExamples.Shape declaringShape(Set<String> names) {
        for (WorkedExamples.Shape shape : curator.shapes()) {
            if (names.contains(shape.simpleName())) {
                return shape;
            }
        }
        return null;
    }

    /**
     * How many times this text calls a method ON one of the handles.
     *
     * <p>Zero disqualifies the member entirely, and that rule is why this method exists. Ranking
     * the members that merely MENTION the type by length answered a question about a database node
     * with {@code ZeroZDbNode node() { return db; }} — a one-line getter from a test, the shortest
     * member in the whole reference material that names the type, and completely useless. What the
     * asker wants is a member that does something with it.
     */
    static int invocationsOn(String text, Set<String> handles) {
        int calls = 0;
        for (String handle : handles) {
            int at = text.indexOf(handle + ".");
            while (at >= 0) {
                boolean leftClear = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
                if (leftClear) {
                    calls++;
                }
                at = text.indexOf(handle + ".", at + 1);
            }
            // `new LedgerSession(...)` is a use worth showing even with no call after it.
            if (text.contains("new " + handle + "(")) {
                calls++;
            }
        }
        return calls;
    }

    /** More calls on the handle wins; between two that do the same, the shorter one wins. */
    private static boolean better(JavaOutline.Member candidate, JavaOutline.Member incumbent,
                                  Set<String> handles) {
        int mine = invocationsOn(candidate.text(), handles);
        int theirs = invocationsOn(incumbent.text(), handles);
        return mine != theirs ? mine > theirs
            : candidate.text().length() < incumbent.text().length();
    }

    /** {@code List<Book>} and {@code Book} are the same type for the purpose of a handle. */    /** {@code List<Book>} and {@code Book} are the same type for the purpose of a handle. */
    private static boolean sameType(String declared, String name) {
        if (declared == null) {
            return false;
        }
        String text = declared.replaceAll("[^A-Za-z0-9_]", " ");
        for (String token : text.split(" +")) {
            if (token.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@code group:artifact} of the module declaring this file, when the target lacks it. */
    private String dependencyFor(WorkedExamples.Shape shape) {
        if (shape == null || targetRoot == null || shape.module() == null) {
            return "";
        }
        Path pom = shape.module().getParent() == null ? null
            : shape.module().resolve("pom.xml");
        if (pom == null || !Files.isRegularFile(pom)) {
            return "";
        }
        String artifact = shape.module().getFileName().toString();
        for (Path module : TaskBrief.mavenModulesOf(targetRoot)) {
            if (TaskBrief.dependenciesOf(module.resolve("pom.xml")).stream()
                    .anyMatch(d -> d.endsWith(":" + artifact))) {
                return "";
            }
        }
        String group = groupOf(pom);
        return group.isEmpty() ? "" : group + ":" + artifact;
    }

    private static String groupOf(Path pom) {
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            int at = text.indexOf("<groupId>");
            return at < 0 ? "" : text.substring(at + 9, text.indexOf("</groupId>", at)).strip();
        } catch (Exception e) {                                            // noqa
            return "";
        }
    }

    // ---------------------------------------------------------------------------------------
    // request_skeleton
    // ---------------------------------------------------------------------------------------

    @Override
    public Answer requestSkeleton(String typeOrTask) {
        Answer answer = skeletonFromContracts(typeOrTask);
        if (!answer.answered()) {
            answer = nearestExampleFile(typeOrTask);
        }
        if (!answer.answered()) {
            answer = escalate("Write a compiling starting point for: " + typeOrTask, null, "", "");
        }
        asked.add(answer);
        log.info("request_skeleton '{}' answered {} ({} tokens)", trim(typeOrTask, 80),
            answer.source(), answer.tokens());
        return answer;
    }

    /**
     * The FREE half of {@link #requestSkeleton} — this task's contracts, else the nearest existing
     * file of the right shape — with no escalation and no entry on this desk's record.
     *
     * <p>It exists for one caller: the expert's own {@code skeleton_for} tool. An expert that
     * reached {@link #requestSkeleton} would escalate to itself when nothing free matched, which
     * is an infinite regress; and a skeleton the EXPERT looked up on its way to an answer is not a
     * question the WORKER asked, so it must not appear on the worker's help record beside the ones
     * that were.
     */
    public Answer skeletonWithoutEscalating(String typeOrTask) {
        Answer answer = skeletonFromContracts(typeOrTask);
        return answer.answered() ? answer : nearestExampleFile(typeOrTask);
    }

    private Answer skeletonFromContracts(String typeOrTask) {
        ApiContract wanted = contractNamed(typeOrTask);
        if (wanted == null) {
            return Answer.none("");
        }
        WorkedExamples.Selection selection = WorkedExamples.select(curator.shapes(), contracts,
            TaskBrief.taskWords(typeOrTask), curator.semanticIndex());
        WorkedExamples.Match match = null;
        for (WorkedExamples.Match candidate : selection.matches()) {
            if (candidate.contract().typeName().equals(wanted.typeName())) {
                match = candidate;
                break;
            }
        }
        boolean test = wanted.simpleTypeName().endsWith("Test");
        String source = Skeleton.source(wanted, match, contracts, test, new ArrayList<>(),
            selection.matches());
        Path module = Skeleton.homeFor(wanted, match, TaskBrief.mavenModulesOf(targetRoot),
            targetRoot);
        String where = targetRoot == null || module == null ? ""
            : targetRoot.relativize(module).toString().replace('\\', '/')
                + "/src/" + (test ? "test" : "main") + "/java/"
                + wanted.packageName().replace('.', '/') + "/"
                + wanted.simpleTypeName() + ".java";

        StringBuilder sb = new StringBuilder("A compiling starting point for `")
            .append(wanted.typeName()).append("`.");
        if (!where.isEmpty()) {
            sb.append(" Write it to `").append(where).append("`.");
        }
        sb.append("\n\n```java\n").append(source).append("```\n")
          .append("\nThe names and members come from this task's own contract, so a later task's "
              + "tests are written against exactly them — do not rename anything. ");
        sb.append(match == null
            ? "Nothing in the reference material resembled it closely enough to copy an idiom from, "
                + "so this carries no framework annotations; check whether it needs any."
            : "The annotations, imports and injected fields are copied from `"
                + match.example().relative() + "`, which is the nearest working implementation of "
                + "this shape in the reference material.");
        sb.append('\n');
        return Answer.deterministic(trim(sb.toString(), answerChars()));
    }

    /**
     * When no contract names it: the existing file in this codebase that most resembles what was
     * asked for, whole.
     *
     * <p>A worker asks for a skeleton because it does not know how to begin. Answering "no
     * contract of that name" leaves it exactly where it was, and the measured consequence of
     * leaving a worker where it was is that it goes back to reading. A real file of the right
     * shape is a starting point even when it carries somebody else's domain names.
     */
    private Answer nearestExampleFile(String typeOrTask) {
        List<String> asked = questionTokens(typeOrTask);
        if (asked.isEmpty()) {
            return Answer.none("");
        }
        WorkedExamples.Shape best = null;
        double bestScore = 0;
        for (WorkedExamples.Shape shape : curator.shapes()) {
            double score = WorkedExamples.tokenOverlap(asked,
                WorkedExamples.tokens(shape.simpleName() + " "
                    + shape.packageName().replace('.', ' ')));
            if (score > bestScore) {
                bestScore = score;
                best = shape;
            }
        }
        if (best == null || bestScore <= 0) {
            return Answer.none("");
        }
        try {
            String body = JavaOutline.of(Files.readString(best.file(), StandardCharsets.UTF_8))
                .withoutLicense.strip();
            return Answer.deterministic("No contract of this task names that, so here is the "
                + "closest existing file in this codebase. Copy its shape and put your own names "
                + "in it.\n\n```java\n// " + best.relative() + "\n" + body + "\n```\n");
        } catch (Exception e) {                                            // noqa
            return Answer.none("");
        }
    }

    private ApiContract contractNamed(String typeOrTask) {
        if (typeOrTask == null || typeOrTask.isBlank()) {
            return null;
        }
        String asked = typeOrTask.strip();
        for (ApiContract contract : contracts) {
            if (contract.namesAType() && (contract.typeName().equalsIgnoreCase(asked)
                || contract.simpleTypeName().equalsIgnoreCase(asked))) {
                return contract;
            }
        }
        // A task-shaped ask ("the server side of the book list") rather than a type name: take the
        // contract whose own words overlap it most, which is what the asker is describing.
        ApiContract best = null;
        double bestScore = 0;
        List<String> words = WorkedExamples.tokens(asked);
        for (ApiContract contract : contracts) {
            if (!contract.namesAType()) {
                continue;
            }
            double score = WorkedExamples.tokenOverlap(words,
                WorkedExamples.tokens(contract.typeName().replace('.', ' ') + " "
                    + (contract.description() == null ? "" : contract.description())));
            if (score > bestScore) {
                bestScore = score;
                best = contract;
            }
        }
        return bestScore >= 0.34 ? best : null;
    }

    // ---------------------------------------------------------------------------------------
    // The escalation
    // ---------------------------------------------------------------------------------------

    /**
     * The only path that costs anything, and the only one that can be wrong.
     *
     * <p>It is handed the same reference material the deterministic path searched, so its answer is
     * grounded in the same code rather than in its own memory of some other framework — and, since
     * the expert became a tool-using session, that material is its STARTING point rather than all
     * it will ever see: it can go and look up whatever else it needs before answering.
     */
    /**
     * @param freeContext the best material the free tiers found even though it did not clear
     *                     {@link #COVERAGE_THRESHOLD} (or "" when nothing free had anything at
     *                     all) — handed to the expert as a starting point, so it builds on what
     *                     was already found rather than searching from nothing.
     * @param reason       why this is being escalated, carried onto the returned {@code Answer} so
     *                     the worker's help record says which of the free tier's shortcomings sent
     *                     this question here.
     */
    private Answer escalate(String question, String whatITried, String freeContext,
                            String reason) {
        if (expert == null) {
            // Not "there is no answer" — "nobody is on the other end". A worker told the first
            // will stop asking; told the second it knows the tool is broken, not the question.
            return withReason(Answer.none("No expert endpoint is configured for this project, so "
                + "this could not be escalated. That is a setup problem, not a sign that your "
                + "question was wrong. Write your best attempt and let the build correct you."),
                reason);
        }
        modelCalls.incrementAndGet();
        // Sized for the model that reads it: the expert's own room when the escalation is the real
        // agent session, the baseline for a plain (question, context) function, which has none.
        MaterialBudget expertRoom = expert instanceof ToolUsingExpert tooled
            ? tooled.room() : MaterialBudget.BASELINE;
        // Grounded, always. The index can come back empty on a narrow question, and an expert
        // handed nothing answers out of its own memory of some other framework -- which is the
        // one failure mode this whole mechanism exists to avoid.
        StringBuilder grounding = new StringBuilder("The reference material for this project:\n");
        for (KnowledgeCurator.Root root : curator.roots()) {
            grounding.append("- ").append(root.label()).append(" at ").append(root.version())
                     .append(" (").append(root.path()).append(")\n");
        }
        if (freeContext != null && !freeContext.isBlank()) {
            // What the free tiers already found, even though it fell short — a starting point,
            // not a dead end. An expert handed this builds on it instead of searching from zero,
            // and it is why the free tiers' own excerpts (call sites, keyword hits) were never
            // wasted work even on a question that ends up escalated.
            grounding.append("\nThe best the free tiers found, which did not fully answer this — "
                + "build on it rather than starting over:\n")
                .append(trim(freeContext, expertRoom.chars(ESCALATION_FREE_CONTEXT_CHARS)))
                .append('\n');
        }
        // An expert that searches the index itself before its first turn is not also handed
        // three whole source files and three documentation sections picked by keyword: that
        // was most of an opening every later turn resends (harness run 66: about 20,000 prompt
        // tokens per call, 49 calls), and its own search is shaped to the question.
        boolean searchesForItself = expert instanceof ToolUsingExpert self
            && self.searchesForItself();
        String context = searchesForItself ? grounding.toString() : grounding
            + curator.relevantSources(question, 3, expertRoom.chars(ESCALATION_SOURCES_CHARS), true)
            + "\n" + curator.relevantDocs(question, 3, expertRoom.chars(ESCALATION_DOCS_CHARS), true);
        String tried = (whatITried == null || whatITried.isBlank() ? ""
            : "\n\nWhat the worker already tried, which did not work:\n" + whatITried)
            + alreadyResearched(expertRoom)
            // The asker is handed this much and no more (trim, below). Harness run 77: 21 of 26
            // answers reached the asker cut off at exactly this length, part-way through, and
            // the asker asked again for the part it never saw.
            + "\n\nYour answer may be at most " + Math.max(500, answerChars()
                - ExpertEscalation.EVIDENCE_CHARS - 100) + " characters; what is past "
            + "that is cut off and the asker never sees it. Put the fact asked for first.";
        try {
            // The richer report when the escalation is the real one - an agent session with
            // tools, which knows how many turns it took and what it looked up. Every test's lambda
            // and every hand-wired fake is a plain BiFunction and takes the second path; nothing
            // about the ANSWER differs, only how much the record can say about where it came from.
            ToolUsingExpert.Report report = expert instanceof ToolUsingExpert tooled
                ? tooled.answer(question, context + tried)
                : plainReport(expert.apply(question, context + tried), context);
            escalationTokens.set(new long[] {report.promptTokens(), report.completionTokens()});
            if (!report.answered()) {
                // In the escalation's own words, unchanged: it is the only layer that knows WHICH
                // way the session failed, and a worker told "it looked and ran out of turns" knows
                // something different from one told "nobody answered".
                return withReason(
                    Answer.none(report.text(), report.turns(), report.toolsUsed()), reason);
            }
            return withReason(new Answer(trim(report.text(), answerChars()), Source.MODEL,
                report.tokens(), report.turns(), report.toolsUsed()), reason);
        } catch (Exception e) {                                            // noqa
            log.warn("the expert could not be reached: {}", e.getMessage());
            return withReason(Answer.none("The expert could not be reached. Write your best "
                + "attempt and let the build correct you."), reason);
        }
    }

    /** How much of each earlier answer of the run the expert is handed, at the baseline room. */
    private static final int RELATED_ANSWER_CHARS = 1_500;
    /** How many of them. */
    private static final int RELATED_ANSWERS = 4;

    /**
     * The answers the expert already researched in this run, newest first, for the opening of
     * its next session (2026-10-04) - so a question that needs one fact more than an earlier one
     * starts from what was already read out of the files instead of reading them again. Harness
     * run 77 researched where the acceptance tests live from nothing in five separate sessions.
     * "" when the run holds none.
     */
    private String alreadyResearched(MaterialBudget expertRoom) {
        RunAnswerCache cache = runCache;
        List<RunAnswerCache.Stored> stored = cache == null ? List.of() : cache.stored();
        if (stored.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## Answers you already researched in this run"
            + "\nEach was read out of the project's files. Use what they state; look up only "
            + "what they do not cover.\n");
        int each = expertRoom.chars(RELATED_ANSWER_CHARS);
        for (int i = 0; i < stored.size() && i < RELATED_ANSWERS; i++) {
            RunAnswerCache.Stored one = stored.get(i);
            sb.append("\n### Asked: ").append(trim(one.question(), 300).strip()).append('\n')
              .append(trim(one.answer(), each).strip()).append('\n');
        }
        return sb.toString();
    }

    /** A plain {@code (question, context) -> answer} escalation's result, expressed as a report. */
    private static ToolUsingExpert.Report plainReport(String answer, String context) {
        if (answer == null || answer.isBlank()) {
            return new ToolUsingExpert.Report("The expert had no answer. Write your best attempt "
                + "and let the build correct you.", false, 0, List.of(), 0);
        }
        return new ToolUsingExpert.Report(answer, true, 0, List.of(),
            answer.length() / 4 + context.length() / 4);
    }

    // ---------------------------------------------------------------------------------------

    /** Every capitalised dotted-or-simple identifier in a question — what it is asking about. */
    static Set<String> typeNamesIn(String question) {
        Set<String> names = new LinkedHashSet<>();
        if (question == null) {
            return names;
        }
        for (String token : question.split("[^A-Za-z0-9_.]+")) {
            String word = token.contains(".") ? token.substring(token.lastIndexOf('.') + 1) : token;
            if (word.length() >= 3 && Character.isUpperCase(word.charAt(0))
                && !word.equals(word.toUpperCase(Locale.ROOT))) {
                names.add(word);
            }
        }
        return names;
    }

    private static String trim(String text, int max) {
        return text == null ? "" : text.length() <= max ? text : text.substring(0, max) + "\n…\n";
    }
}
