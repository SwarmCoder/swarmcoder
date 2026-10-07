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
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.TraceHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ExpertDesk}'s escalation in production: an agent session on the configured
 * utility/architect role's endpoint, with read-only tools over this project's own code, spent
 * through the run's {@link CloudGate} exactly like every other paid role.
 *
 * <p><b>What changed, and why.</b> This used to be one chat call. The worker's question, what it
 * had tried, and whatever the desk's free tiers had scraped together went into a prompt, and
 * whatever came back was the answer. Everything the expert was not handed, it had to remember — and
 * a model remembering an API it has not read is precisely the failure the help desk exists to
 * prevent. The worker gets to read this codebase before it writes; the expert did not.
 *
 * <p>Now it does. The session opens with the same question, the same "what I tried", and the same
 * free material — nothing is taken away — and it also has {@link ExpertTools}: the semantic index
 * (what implements this, where is it used, what does the compiler say its public shape is, which
 * pom declares it), the reference index, and confined reads of the project and its reference
 * folders. The instruction is to look things up before answering and never to guess an API.
 *
 * <p><b>It fails honestly.</b> There is no path here that invents an answer. A session that runs
 * out of turns, meets a dead endpoint, exhausts the run's budget or ends with nothing to say
 * returns a {@link Report} that says which of those happened, and the desk passes that sentence to
 * the worker unchanged, telling it to write its best attempt. The measured alternative — a worker
 * given a confident wrong signature — is worse than a worker told nobody could help.
 *
 * <p><b>But it does not throw away what it read (harness run 37, 2026-09-25).</b> An expert that
 * runs out of turns having read the right material is made to answer from that material, in one
 * closing turn with no lookups ({@link #closingAnswer}); and every lookup's result tells it how
 * many turns are left as they run out, so it rarely gets that far. Only when the lookups found
 * nothing at all, or the closing turn itself says nothing, does the worker get the failure
 * sentence.
 *
 * <p><b>Wiring only.</b> All the judgement about WHEN to escalate is {@link ExpertDesk}'s: the free
 * deterministic tiers are tried first, always, and only what none of them can answer reaches here.
 */
public final class ExpertEscalation implements ToolUsingExpert {

    private static final Logger log = LoggerFactory.getLogger(ExpertEscalation.class);

    /**
     * How many turns the expert may take.
     *
     * <p>Enough for a real investigation — resolve the type, read its public shape, find the one
     * file that uses it, read that file, check which module declares it, answer — and short enough
     * that a session which has lost the thread is stopped rather than left to wander on a paid
     * endpoint. A worker asking a question is already stalled; an expert taking thirty turns over
     * it has stopped being cheaper than the worker reading for itself.
     *
     * <p>Deliberately NOT raised after harness run 37 (2026-09-25), when the expert was stopped here
     * with nothing said: it had read the answer by its fourteenth lookup. The turns went on walking
     * folders one per call and on looking for a better example with no idea the turns were
     * finite; more of them would have bought more of the same. What changed instead is the
     * countdown on its lookup results, the tree listing, and the closing turn
     * ({@link #closingAnswer}).
     */
    public static final int MAX_TURNS = Integer.getInteger("swarmcoder.expert.maxTurns", 12);
    // 30 until 2026-10-04. Harness run 77: twelve answers took 17 to 32 turns each and were sent
    // 6.6 million prompt tokens, about 22,000 a turn, for questions like "where do the acceptance
    // tests live". The turns went on wording one search eight ways for a thing the project does
    // not hold. Twelve is the six steps above twice over; a session stopped there still answers
    // from what it read (closingAnswer), so no question goes unanswered for it.

    static final String SYSTEM = """
        You are the expert for THIS codebase. Somebody building it - a coding worker, or the \
        architect or planner deciding what to build - could not settle a question from their own \
        material, so they asked you.

        The question is NOT searched for you, and no file is quoted to you: the project's own \
        map (every folder with its types) opens this conversation when the project has one, and \
        everything else is a lookup away. Answers you already researched in this run on related \
        questions may follow the question: what an earlier answer states was read out of the \
        files, so use it and do not look it up again. When the question can be answered from \
        what you already hold, answer at once; most questions need one or two lookups.

        When it is not enough, look up only what is missing, in this order. FIRST the project's syntax \
        tree, for anything you can name: public_shape for a type's members, body_of for ONE \
        method or type (Type#method), types_in for what a package or module holds, find_usages, \
        files_using, build_of, resources_of. THEN search, only for what you cannot name: any words or names; \
        it answers with places, not text. A document is read by section: doc_outline for its \
        headings, doc_section <document>#<number or heading> for ONE section. LAST read_file, \
        for lines no query \
        returns - <path>:LINE reads the lines around a line. Make the lookups you need together, in one turn. Do NOT walk folders with \
        list_files to find an example, do not read whole files to find one method, and do not \
        put the same search again in other words: a second wording finds what the first found.

        THAT SOMETHING IS NOT THERE IS AN ANSWER. When a search for a name, a package, a file or \
        a rule finds nothing, and one look at the place it would be confirms it, the project \
        does not have it. Say exactly that, say where you looked, and say what the project has \
        instead. Do not go on looking for it.

        Never name a method, a class or an annotation you have not seen in that material or \
        confirmed with a tool; a signature you half-remember from another framework is the one \
        thing that makes this worse than no answer at all.

        Answer what was asked, in the form it was asked. A question about how to call or write \
        something: WORKING JAVA CODE for exactly that - the imports the file needs, the call \
        itself, and - when the answer needs a dependency the build does not already declare - \
        the pom.xml <dependency> line to add (no <version>; the parent manages it). A question \
        about where something is, whether something is allowed, or what the project's rules \
        say: the fact in a sentence, first. Either way, after every fact name the file it was \
        read from, as path:LINE - that is what lets the asker trust the answer instead of asking \
        again. When the question has several parts, answer each part under its own number. Keep \
        inside the length you are given below; what is past it is cut off.

        Your turns are limited, and each lookup's result tells you when they are running out. \
        Answer as soon as you have read real code or documentation that settles what was \
        asked: the first real example is enough, and a better one found later is worth less than \
        an answer now.

        When you have the answer, call report_done with it. That is the only way the asker ever \
        sees anything you write, so put the whole answer in it. If the material genuinely does not \
        contain the answer, call report_done saying so in one line - do not invent an API.\
        """;

    /**
     * The closing turn's instruction: answer from what the lookups returned, and from nothing else.
     *
     * <p>The same rule as {@link #SYSTEM} — never name what you have not seen — made mechanical: the
     * closing turn has no lookups at all, so the ONLY material it holds is the question, what the
     * desk handed the expert, and what the expert's own lookups returned. Nothing it could name
     * came from anywhere but a tool result or the desk.
     */
    static final String CLOSING_SYSTEM = """
        You are the expert for THIS codebase. An AI coding worker is stuck and asked you a \
        question. You investigated it with read-only lookups and ran out of turns before you \
        answered. Below are the question and everything your lookups returned. There are no more \
        lookups.

        Answer now, from that material ONLY. Give WORKING JAVA CODE for exactly what the worker \
        asked: the imports its file needs, the call itself, and - when a dependency is missing - \
        the pom.xml <dependency> line to add (no <version>). Every class, method and annotation you \
        name must appear in the material below; name the file you took each one from. If the \
        material shows only part of the answer, give that part and say plainly what it does not \
        show. If it shows nothing that answers the question, say so in one line - do not invent an \
        API.

        Call report_done with your whole answer.\
        """;

    /**
     * How much of what the lookups returned the closing turn is handed, at the baseline room;
     * scaled by the expert's own room. Four or five whole files and documentation sections — at
     * the harness's DeepSeek room, 122,880 characters, more than run 37's fifty-three lookups
     * returned that carried anything.
     */
    static final int CLOSING_MATERIAL_CHARS = 24_000;

    /**
     * The closing turn's allowance: one to answer, one more for a model that writes its answer as
     * text and has to be asked once for {@code report_done}.
     */
    static final int CLOSING_TURNS = 2;

    /**
     * Housekeeping on the expert's own conversation: once the lookup results older than its last
     * few turns add up to more than {@link #TIDY_ABOVE_TOKENS}, the oldest are replaced by their
     * first {@link #DIGEST_CHARS} characters until {@link #TIDY_TO_TOKENS} of them are left whole
     * ({@code HistoryTrim.tidy}). Harness run 66: 989,781 prompt tokens sent to write 24,531,
     * because every turn resent every file an earlier turn had read. Not a limit; nothing stops.
     */
    static final int TIDY_ABOVE_TOKENS = 6_000;
    static final int TIDY_TO_TOKENS = 1_500;
    static final int DIGEST_CHARS = 600;

    /**
     * How many expert sessions run at once, at most - a SAFETY STOP on the model server's places,
     * not a ration on questions: a question over it waits its turn and is still answered. Each
     * asker already holds one place on its server while it waits for its answer, so this only
     * ever binds when one turn asks several questions at once.
     */
    static final int MAX_AT_ONCE = Integer.getInteger("swarmcoder.expert.maxAtOnce", 4);

    private final AgentRuntime.ModelEndpoint endpoint;
    private final CloudGate cloudGate;
    private final KnowledgeCurator curator;
    /** The project repository, for the project map; nullable. */
    private final java.nio.file.Path targetRoot;
    /** Nullable — without one the expert has no documentation tools, only structural ones. */
    private final Librarian librarian;
    /** The free half of the desk's skeleton generator; never escalates, so it cannot recurse. */
    private final ExpertDesk skeletons;
    private final AgentRuntime runtime;
    private final int maxTurns;
    private final java.util.concurrent.Semaphore places =
        new java.util.concurrent.Semaphore(Math.max(1, MAX_AT_ONCE), true);

    /**
     * @param client   the paid role's client. Its base URL, key, model id and quirks become the
     *                 session's endpoint, so the expert runs on exactly the endpoint the operator
     *                 configured for that role, with the working context that was discovered for
     *                 it at startup.
     * @param curator  this project's knowledge roots — the project repository and every configured
     *                 reference folder. The tools' whole world; nothing outside it is readable.
     * @param librarian nullable; supplies {@code lookup_docs} and, when a documentation-server key
     *                 is configured, {@code library_docs}
     * @param targetRoot the project repository, for the skeleton generator's module layout
     * @param contracts the task's API contracts, for the skeleton generator. Empty is normal.
     */
    public ExpertEscalation(VllmClient client, CloudGate cloudGate, KnowledgeCurator curator,
                            Librarian librarian, Path targetRoot, List<ApiContract> contracts) {
        this(client, cloudGate, curator, librarian, targetRoot, contracts,
            new KoogAgentRuntime(TraceHub.NONE), MAX_TURNS);
    }

    /**
     * @param runtime  the agent runtime to open the session on — the same {@link KoogAgentRuntime}
     *                 the workers run on. Injectable so a caller with a {@link TraceHub} can have
     *                 the expert's whole investigation recorded beside the workers' sessions.
     * @param maxTurns the turn allowance; {@link #MAX_TURNS} unless a caller has a reason
     */
    public ExpertEscalation(VllmClient client, CloudGate cloudGate, KnowledgeCurator curator,
                            Librarian librarian, Path targetRoot, List<ApiContract> contracts,
                            AgentRuntime runtime, int maxTurns) {
        // Thinking on, explicitly. The expert is the one call in the system where reasoning is
        // worth paying for: it is answering a question a whole worker is stalled on, once, and the
        // answer goes straight into code. Everything else about the endpoint — the served ceiling,
        // the working context discovered for it, whether its chat template can take native
        // tool-call history — is the role's own configuration and is left alone.
        ModelQuirks quirks = client.quirks().withThinking(true);
        this.endpoint = new AgentRuntime.ModelEndpoint(client.baseUrl(), client.apiKey(),
            client.modelName(), quirks.servedContextTokens(), quirks);
        this.cloudGate = cloudGate;
        this.curator = curator;
        this.targetRoot = targetRoot;
        this.librarian = librarian;
        this.runtime = runtime == null ? new KoogAgentRuntime(TraceHub.NONE) : runtime;
        this.maxTurns = maxTurns <= 0 ? MAX_TURNS : maxTurns;
        // Escalation-free by construction (null expert), so skeleton_for can never open an expert
        // session from inside an expert session.
        this.skeletons = curator == null ? null
            : new ExpertDesk(curator, targetRoot, contracts == null ? List.of() : contracts, null);
    }

    /**
     * Which planning role is asking, for the call on this thread - so the run's cost record shows
     * an expert session opened for the architect under "expert for architect", apart from the
     * ones the workers opened (2026-10-02). Null is a worker, and the session is "expert".
     */
    private static final ThreadLocal<String> ASKED_BY = new ThreadLocal<>();

    /** Runs {@code question} with every expert session it opens on this thread named for {@code role}. */
    public static <T> T askedBy(String role, java.util.function.Supplier<T> question) {
        String before = ASKED_BY.get();
        ASKED_BY.set(role);
        try {
            return question.get();
        } finally {
            if (before == null) {
                ASKED_BY.remove();
            } else {
                ASKED_BY.set(before);
            }
        }
    }

    /** "expert", or "expert for architect": no dash, which the cost record would cut the role at. */
    private static String sessionRole() {
        String asker = ASKED_BY.get();
        return asker == null || asker.isBlank() ? "expert" : "expert for " + asker.replace('-', ' ');
    }

    /** The tool names this expert is opened with, for the ledger line and for startup logging. */
    public List<String> toolNames() {
        return ExpertTools.toolNames(curator, librarian);
    }

    /**
     * The expert's own room — the role's configured or discovered working context — which sizes
     * both what the desk hands it and what each of its tools may return. Not the worker's: the
     * worker never reads the tool results, only the final answer, and the desk sizes that.
     */
    @Override
    public MaterialBudget room() {
        return MaterialBudget.of(endpoint.quirks());
    }

    /**
     * The desk need not paste whole source files and documentation sections into the question:
     * this expert has the project map and the lookups to fetch what it needs, and a pasted file
     * is resent on every later turn.
     */
    @Override
    public boolean searchesForItself() {
        return curator != null;
    }

    @Override
    public Report answer(String question, String context) {
        boolean waited = !places.tryAcquire();
        if (waited) {
            log.info("{} expert sessions are already running, the most that run at once; this "
                + "question waits for a place", MAX_AT_ONCE);
            try {
                places.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Report("The expert could not be reached. Write your best attempt and "
                    + "let the build correct you.", false, 0, List.of(), 0);
            }
        }
        try {
            return answerInPlace(question, context);
        } finally {
            places.release();
        }
    }

    private Report answerInPlace(String question, String context) {
        // The turn allowance goes to the tools too, so every lookup's result can say how many
        // turns are left as they run out (harness run 37: the expert had no clock at all).
        ExpertTools tools = new ExpertTools(curator, librarian, skeletons, cloudGate, room(),
            maxTurns);
        // The project map from the object graph opens the session, as it does every role's; the
        // expert fetches the rest with the tree queries. No search is run for it (section 54,
        // CLAUDE.md section 1: no prompt-stuffing).
        String map = ProjectMap.enabled() && targetRoot != null
            ? ProjectMap.of(targetRoot, room().chars(ProjectMap.MAP_CHARS), curator) : "";
        String opening = (map.isEmpty() ? "" : map + "\n") + question + "\n\n"
            + (context == null ? "" : context);

        // Charged BEFORE the session opens, like every other cloud role — a runaway is stopped
        // before it spends, not after (CloudGate javadoc). Refunded below when nothing was ever
        // generated, which is what keeps an outage retry inside the original budget.
        long opened = CloudGate.estimateTokens(SYSTEM) + CloudGate.estimateTokens(opening);
        try {
            cloudGate.charge(opened);
        } catch (CloudGate.BudgetExhaustedException e) {                   // noqa
            return new Report("The expert could not be asked: this run's model budget is spent. "
                + "Write your best attempt and let the build correct you.", false, 0, List.of(), 0);
        }

        // Every turn's cost, at the moment the server reports it — see TurnMeter.
        AtomicLong charged = new AtomicLong(opened);
        TurnMeter guard = new TurnMeter(cloudGate, charged, tools);
        AtomicLong modelTokens = guard.modelTokens;

        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec(sessionRole(), SYSTEM, endpoint,
            0.2, maxTurns, tools.bindings(), guard).with(new AgentRuntime.SessionOptions(
                TIDY_ABOVE_TOKENS, TIDY_TO_TOKENS, DIGEST_CHARS, null));

        AgentRuntime.SessionResult result;
        long[] sent = {-1, -1};
        try (AgentRuntime.AgentSession session = runtime.open(spec)) {
            result = session.run(opening);
            sent[0] = session.promptTokensSent();
            sent[1] = session.completionTokensGenerated();
        } catch (Exception e) {                                            // noqa
            // The runtime classifies an outage into a KillReason rather than throwing, so anything
            // arriving here is something else — but the worker's position is the same either way.
            log.warn("the expert session could not be opened: {}", e.getMessage());
            cloudGate.refund(charged.get());
            return new Report("The expert could not be reached. Write your best attempt and let "
                + "the build correct you.", false, 0, tools.used(), 0);
        }

        int turns = result.turns();
        List<String> used = tools.used();
        int cost = (int) Math.min(Integer.MAX_VALUE, charged.get());

        if (result.killReason().isPresent()) {
            KillReason reason = result.killReason().get();
            if (reason == KillReason.ENDPOINT_OUTAGE && modelTokens.get() == 0) {
                // Nothing was ever generated, so nothing is owed. The workflow retries a dead
                // endpoint until it answers; charging every attempt would let one outage eat a
                // whole run's budget without a single token having been produced.
                cloudGate.refund(charged.get());
                cost = 0;
            }
            log.warn("the expert session ended {} after {} turn(s) and {} lookup(s)", reason, turns,
                used.size());
            Report failed = new Report(failureSentence(reason, turns, used.size()), false, turns,
                used, cost, sent[0], sent[1]);
            // Stopped with material in hand and budget left: a turn cap, or a conversation that
            // outgrew the expert's room (BUDGET_EXCEEDED with the run's budget NOT spent — see
            // KoogAgentRuntime.compactIfNeeded). Either way what it read is still here.
            boolean stoppedWithMaterial = reason == KillReason.TURN_CAP
                || (reason == KillReason.BUDGET_EXCEEDED && !cloudGate.exhausted());
            return stoppedWithMaterial
                ? closingAnswer(question, context, tools, charged, failed)
                : failed;
        }

        String text = plainText(result.finalOutput());
        if (text.isBlank()) {
            log.warn("the expert session finished after {} turn(s) with nothing to say", turns);
            return closingAnswer(question, context, tools, charged, new Report("The expert worked "
                + "on this for " + turns + " turn(s) and produced no answer. Write your best "
                + "attempt and let the build correct you.", false, turns, used, cost, sent[0],
                sent[1]));
        }
        log.info("the expert answered after {} turn(s) and {} lookup(s): {}", turns, used.size(),
            used);
        return new Report(text + readFrom(tools), true, turns, used, cost, sent[0], sent[1]);
    }

    /** How long the list of files under an answer may be. */
    static final int EVIDENCE_CHARS = 500;

    /**
     * The files the expert read for an answer, as a line under it (2026-10-04) - the evidence
     * that travels with the answer when it is handed to a later asker, so that asker can open
     * the same files instead of asking for the research to be done again. "" when it read none.
     */
    static String readFrom(ExpertTools tools) {
        java.util.Set<String> files = new java.util.LinkedHashSet<>();
        for (ExpertTools.Found found : tools.lookups()) {
            if ("read_file".equals(found.tool()) && found.foundSomething()
                    && found.argument() != null && !found.argument().isBlank()) {
                files.add(found.argument().strip());
            }
        }
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nRead from:");
        for (String file : files) {
            if (sb.length() + file.length() + 2 > EVIDENCE_CHARS) {
                break;
            }
            sb.append(sb.charAt(sb.length() - 1) == ':' ? " " : ", ").append(file);
        }
        return sb.toString();
    }

    /**
     * One more turn, with no lookups, to answer from what the lookups returned.
     *
     * <p><b>Why (harness run 37, 2026-09-25).</b> A worker implementing a store on EclipseStore
     * asked for real code that creates an {@code EmbeddedStorageManager}. The expert read a store
     * test with that code at its seventh lookup and the persistence guide at its fourteenth, then
     * kept looking — thirty-one turns, fifty-three lookups, under five minutes — and was stopped at
     * its turn cap. The worker was handed "could not be answered", NONE, and carried on unaided with
     * the answer sitting in the expert's tool results. An imperfect answer from code the expert
     * actually read beats no answer at all; that is what this turn is for.
     *
     * <p><b>It stays grounded.</b> A fresh, short session: {@link #CLOSING_SYSTEM}, the question,
     * the desk's own material, and {@link ExpertTools#whatWasFound} — refusals and "nothing
     * matched" left out, sized to the expert's room. Its only tool is {@code report_done}, so there
     * is nothing it can reach for that it has not been shown, and the instruction is the one the
     * expert always had: name nothing that is not in the material, and say so in one line when the
     * material does not answer it. It is charged through the cloud gate like every other turn.
     *
     * <p><b>It is labelled.</b> The worker is told the expert ran out of turns and answered from
     * what its lookups returned, with the counts, so it knows to check the answer against the
     * build rather than take it as a finished investigation.
     *
     * <p><b>When there is nothing to answer from</b> — no lookup found anything, the budget is
     * spent, the closing session fails or says nothing — the original failure is returned
     * unchanged. There is still never an invented answer.
     */
    private Report closingAnswer(String question, String context, ExpertTools tools,
                                 AtomicLong charged, Report failed) {
        String material = tools.whatWasFound(room().chars(CLOSING_MATERIAL_CHARS));
        if (material.isBlank()) {
            log.warn("the expert's lookups found nothing to answer from, so there is no closing turn");
            return failed;
        }
        String opening = question + "\n\n" + (context == null ? "" : context)
            + "\n\n## What your lookups returned\n\n" + material
            + "\nAnswer now, from the material above only, with report_done.";
        long cost = CloudGate.estimateTokens(CLOSING_SYSTEM) + CloudGate.estimateTokens(opening);
        try {
            cloudGate.charge(cost);
            charged.addAndGet(cost);
        } catch (CloudGate.BudgetExhaustedException e) {                   // noqa
            charged.addAndGet(cost);
            return failed;
        }
        TurnMeter guard = new TurnMeter(cloudGate, charged, null);
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec(sessionRole() + "-closing",
            CLOSING_SYSTEM, endpoint, 0.2, CLOSING_TURNS, tools.answerOnlyBindings(), guard);
        AgentRuntime.SessionResult result;
        long promptSent = failed.promptTokens();
        long generated = failed.completionTokens();
        try (AgentRuntime.AgentSession session = runtime.open(spec)) {
            result = session.run(opening);
            promptSent = plus(promptSent, session.promptTokensSent());
            generated = plus(generated, session.completionTokensGenerated());
        } catch (Exception e) {                                            // noqa
            log.warn("the expert's closing turn could not be opened: {}", e.getMessage());
            return failed;
        }
        int turns = failed.turns() + result.turns();
        int tokens = (int) Math.min(Integer.MAX_VALUE, charged.get());
        String text = result.killReason().isPresent() ? "" : plainText(result.finalOutput());
        if (text.isBlank()) {
            log.warn("the expert's closing turn ended {} with nothing to say",
                result.killReason().map(Enum::name).orElse("COMPLETED"));
            return new Report(failed.text(), false, turns, failed.toolsUsed(), tokens, promptSent,
                generated);
        }
        log.info("the expert ran out of turns after {} lookup(s) and answered from what they "
            + "returned ({} characters of material)", failed.toolsUsed().size(), material.length());
        return new Report("The expert ran out of turns (" + failed.turns() + " turn(s), "
            + failed.toolsUsed().size() + " lookup(s)) before answering, so it was made to answer "
            + "from what its lookups had returned, and nothing else. Check it against the build.\n\n"
            + text + readFrom(tools), true, turns, failed.toolsUsed(), tokens, promptSent,
            generated);
    }

    /** Two server counts added, where -1 is "not reported" and does not poison the other. */
    private static long plus(long one, long other) {
        if (one < 0) {
            return other;
        }
        return other < 0 ? one : one + other;
    }

    /**
     * The answer as a person would read it, not as JSON encoded it.
     *
     * <p>A tool result comes back from the agent framework serialized: {@code report_done}'s String
     * return value arrives wrapped in quotes with every newline as a two-character {@code \\n}.
     * That does not matter for a worker's {@code report_done}, whose summary goes into a log line
     * and a commit message. It matters completely here, because THIS text is handed straight to a
     * stalled worker as the code it is about to write, and a Java snippet delivered as one line of
     * escapes is a worse answer than the same snippet delivered as code. Measured on the live probe
     * of 2026-09-05, where a correct twenty-six-lookup answer arrived unreadable.
     *
     * <p>Only a value that IS a JSON string literal is decoded; anything else is returned as it
     * stands, so an ordinary text answer that happens to start with a quote is left alone.
     */
    static String plainText(String finalOutput) {
        String text = finalOutput == null ? "" : finalOutput.strip();
        if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"') {
            return text;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(text, String.class).strip();
        } catch (Exception e) {                                            // noqa
            return text;
        }
    }

    /**
     * What the worker is told when the session reached no answer.
     *
     * <p>Each sentence says which of the four things happened, because they are different problems
     * even though the worker's next move is the same. "Nobody was on the other end" is a setup
     * fault; "it looked for thirty turns and could not find it" is evidence about the material. A
     * worker told the first will keep asking; told the second it knows the material is thin.
     * Neither ever reads as "there is no answer, stop".
     */
    private static String failureSentence(KillReason reason, int turns, int lookups) {
        String how = " (" + turns + " turn(s), " + lookups + " lookup(s))";
        return switch (reason) {
            case TURN_CAP -> "The expert investigated this and ran out of turns before it reached "
                + "an answer" + how + ". Write your best attempt and let the build correct you.";
            case ENDPOINT_OUTAGE -> "The expert could not be reached" + how + ". Write your best "
                + "attempt and let the build correct you.";
            case BUDGET_EXCEEDED -> "The expert was stopped part-way: this run's model budget is "
                + "spent" + how + ". Write your best attempt and let the build correct you.";
            default -> "The expert's session ended without an answer" + how + ". Write your best "
                + "attempt and let the build correct you.";
        };
    }
}
