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
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.TraceHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * One role's working session with the expert's read-only lookups: the architect, the planner and
 * the test author as agents that check a fact before they rely on it (owner decision, 2026-10-02).
 *
 * <p><b>Why.</b> Those three roles were handed one prompt of pre-selected context and had to answer
 * in one shot. Nearly every failed live run of the two days before this was one of them guessing a
 * fact it could have looked up: a library method that does not exist, a library class that does not
 * exist, a second copy of a class the project already has, the wrong package for an existing one.
 * Each was patched by putting more pre-selected text into the prompt. The workers and the expert
 * could always look; now these roles can too.
 *
 * <p><b>Nothing new is run here.</b> The loop is the same {@link AgentRuntime} session the workers
 * and the expert run on; the lookups are {@link ExpertTools}, unchanged, over the same knowledge
 * roots (the project checkout and every reference folder); the turn guard is the expert's
 * ({@link TurnMeter}). What a role adds is its own tools - a draft check, and the tool it hands in
 * with - and its own words.
 *
 * <p><b>The other agents too</b> (owner addition, 2026-10-02). A role is not limited to raw
 * lookups: {@code find_example} and {@code docs_for} are the Librarian on demand, and
 * {@code ask_expert} is the help desk the workers ask, with the run's remembered answers, so a
 * question one role asked is not researched again for another role or a worker
 * ({@link RunAnswerCache#forRun}). The session's prompt says which is for what.
 *
 * <p><b>Safety stops, not budgets</b> (owner decision, 2026-10-02). A role looks up and asks as
 * much as it needs; see {@link Limits}. A high turn ceiling ends a session that has lost the
 * thread, a high ceiling refuses further lookups or expert questions, and the very same call made
 * over and over is refused as a loop. Each says so in the log when it trips. The caller decides what to do with a session that ended
 * without handing in; {@link Outcome#material} is what the lookups returned, for a one-shot answer
 * written from it.
 *
 * <p><b>Accounted for.</b> The session holds one place on the model server for as long as it runs,
 * through the same {@link InferenceScheduler} the workers are admitted by, when one is given; every
 * model call is on the run's cost record under the session's role (the runtime does that); every
 * turn is charged to the run's {@link CloudGate}; every tool call and its result is one log line.
 */
public final class LookupAgent {

    private static final Logger log = LoggerFactory.getLogger(LookupAgent.class);

    /** The name the agent runtime ends a session on. A role's hand-in tool must carry it. */
    public static final String SUBMIT_TOOL = KoogAgentRuntime.DONE_TOOL;

    /**
     * Housekeeping on a role's own conversation (live run 75, 2026-10-03): the architect was sent
     * 2,493,741 prompt tokens over 47 calls, 53,000 a call, because a role's session resent every
     * file an earlier turn had read on every later turn. The expert's and the workers' sessions
     * have replaced old results by their first lines since run 66 ({@code HistoryTrim.tidy});
     * the roles' sessions never asked for it.
     *
     * <p>A role keeps more than a worker does - a plan is written from many files held at once -
     * so the figures are higher than the workers' 12,000 and 3,000: results older than the last
     * four turns are left alone until they add up to {@link #TIDY_ABOVE_TOKENS}, then the oldest
     * (superseded ones first) are cut to their first {@link #DIGEST_CHARS} characters until
     * {@link #TIDY_TO_TOKENS} are left whole. First lines kept by an earlier tidy count toward
     * the mark and become one line each at the next (run 93: after 134 lookups they were about
     * 20,000 tokens on their own). Not a limit: nothing is stopped, and one lookup
     * gets a cut result back. {@code -Dswarmcoder.roles.tidyAboveTokens=0} switches it off.
     */
    static final int TIDY_ABOVE_TOKENS = Integer.getInteger("swarmcoder.roles.tidyAboveTokens", 20_000);
    static final int TIDY_TO_TOKENS = Integer.getInteger("swarmcoder.roles.tidyToTokens", 8_000);
    static final int DIGEST_CHARS = 600;

    /** How a role's session is run: tidied as above, and told of a turn's calls before the first. */
    static AgentRuntime.SessionOptions sessionOptions(
            java.util.function.BiConsumer<String, String> upcoming) {
        return sessionOptions(upcoming, 0);
    }

    /**
     * The share of a role's working context its old lookup results may hold before they are
     * tidied: a quarter.
     *
     * <p>Harness run 77 (2026-10-04): the architect and the planner ran on a server whose
     * working context was about 34,000 tokens, and were sent 27,000 and 25,000 prompt tokens a
     * call over 180 calls. {@link #TIDY_ABOVE_TOKENS} is 24,000 tokens of old results, which a
     * conversation in that room never reaches: it is compacted at three quarters of the room
     * first (29 and 11 times in that run, never tidied once), so it ran at the top of its room
     * on every call. The workers, tidied at 12,000, were sent 10,000 a call. The threshold is a
     * figure for a large room and has to shrink with a small one.
     */
    static final int TIDY_SHARE_OF_ROOM = 4;

    /**
     * @param workingContextTokens the role's own working context; 0 or less is unknown, and the
     *                             configured figures are used as they stand
     */
    static AgentRuntime.SessionOptions sessionOptions(
            java.util.function.BiConsumer<String, String> upcoming, int workingContextTokens) {
        int above = Math.max(0, TIDY_ABOVE_TOKENS);
        int to = Math.max(0, Math.min(TIDY_TO_TOKENS, above));
        if (above > 0 && workingContextTokens > 0
                && workingContextTokens / TIDY_SHARE_OF_ROOM < above) {
            above = Math.max(2_000, workingContextTokens / TIDY_SHARE_OF_ROOM);
            // Half, not a third (run 79): cut to a third, a 32,768 room kept 2,700 tokens of
            // results whole - two files - and the planner read the others again after every tidy.
            to = Math.min(to, above / 2);
        }
        // The same mark on a server that reports prompt-cache hits (section 67, reversing the
        // cache exception of section 53): the owner judges a run by raw input tokens.
        return new AgentRuntime.SessionOptions(above, above == 0 ? 0 : to, DIGEST_CHARS, upcoming);
    }

    /** How much of what the lookups returned {@link Outcome#material} holds, at the baseline room. */
    static final int MATERIAL_CHARS = 16_000;

    private final KnowledgeCurator curator;
    /** Nullable - without one there are no documentation lookups, only structural ones. */
    private final Librarian librarian;
    private final Path targetRoot;
    private final CloudGate cloudGate;
    private final AgentRuntime runtime;
    /** Nullable - then the session is not admitted through a scheduler (a test, a cloud role). */
    private final InferenceScheduler scheduler;
    /** Model server address to the scheduler pool of that server; null when it has none. */
    private final Function<String, String> poolOfEndpoint;

    /**
     * @param curator       the project checkout and every reference folder - the lookups' whole
     *                      world; nothing outside it is readable
     * @param librarian     nullable; supplies the documentation lookups
     * @param targetRoot    the project repository
     * @param runtime       the agent runtime the workers and the expert run on; null opens a
     *                      private one
     * @param scheduler     nullable; the request scheduler a session takes its place on the model
     *                      server from
     * @param poolOfEndpoint which scheduler pool a model server's address belongs to, or null
     *                      for a server the scheduler does not admit for (a role on an endpoint of
     *                      its own); a session on such a server takes no place. Null with no
     *                      scheduler.
     */
    public LookupAgent(KnowledgeCurator curator, Librarian librarian, Path targetRoot,
                       CloudGate cloudGate, AgentRuntime runtime, InferenceScheduler scheduler,
                       Function<String, String> poolOfEndpoint) {
        this.curator = curator;
        this.librarian = librarian;
        this.targetRoot = targetRoot;
        this.cloudGate = cloudGate;
        this.runtime = runtime == null ? new KoogAgentRuntime(TraceHub.NONE) : runtime;
        this.scheduler = scheduler;
        this.poolOfEndpoint = poolOfEndpoint;
    }

    /**
     * One session's SAFETY STOPS. None of them is a budget or a time target (owner decision,
     * 2026-10-02: in production these roles run on fast models, and the slow local one is only a
     * development economy - correctness first). A role looks up and asks as much as it needs to
     * get the right information; these exist so that a session which has lost the thread ends,
     * and the log says so whenever one of them trips.
     *
     * @param maxTurns           model round trips before the session is ended
     * @param maxLookups         single-fact lookups before every further one is refused;
     *                           questions to the expert and the librarian are not counted
     * @param maxExpertQuestions expert questions before every further one is refused
     * @param maxRepeats         how often the very same call may be made before it is refused
     */
    public record Limits(int maxTurns, int maxLookups, int maxExpertQuestions, int maxRepeats) {

        public Limits(int maxTurns, int maxLookups) {
            this(maxTurns, maxLookups, DEFAULT_EXPERT_QUESTIONS, DEFAULT_REPEATS);
        }

        static final int DEFAULT_TURNS = 120;
        static final int DEFAULT_LOOKUPS = 300;
        static final int DEFAULT_EXPERT_QUESTIONS = 25;
        static final int DEFAULT_REPEATS = 3;

        /**
         * The stops every role runs with: the defaults above, each replaceable by a system
         * property - {@code swarmcoder.roles.maxTurns}, {@code swarmcoder.roles.maxLookups},
         * {@code swarmcoder.roles.maxExpertQuestions}, {@code swarmcoder.roles.maxRepeats}.
         */
        public static Limits configured() {
            return new Limits(Integer.getInteger("swarmcoder.roles.maxTurns", DEFAULT_TURNS),
                Integer.getInteger("swarmcoder.roles.maxLookups", DEFAULT_LOOKUPS),
                Integer.getInteger("swarmcoder.roles.maxExpertQuestions", DEFAULT_EXPERT_QUESTIONS),
                Integer.getInteger("swarmcoder.roles.maxRepeats", DEFAULT_REPEATS));
        }
    }

    /** Closes without an exception, so it reads as a plain try-with-resources. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** The run the sessions opened on this thread belong to; see {@link #inRun}. */
    private static final ThreadLocal<java.util.UUID> RUN = new ThreadLocal<>();

    /**
     * For the sessions opened on this thread until the scope is closed, the expert's answers are
     * remembered with run {@code runId}'s: the same {@link RunAnswerCache} its workers ask
     * through, so no question is researched twice in a run, whoever asked it first. Without a run
     * the sessions of one agent share a cache of their own.
     */
    public static Scope inRun(java.util.UUID runId) {
        java.util.UUID before = RUN.get();
        RUN.set(runId);
        return () -> {
            if (before == null) {
                RUN.remove();
            } else {
                RUN.set(before);
            }
        };
    }

    /** Nullable - then no session is offered {@code ask_expert}. */
    private volatile java.util.function.Supplier<com.swarmcoder.runtime.ExpertHelp> expertFactory;
    /** The answers remembered for sessions opened outside any run. */
    private final RunAnswerCache ownCache = new RunAnswerCache();

    /**
     * Gives the roles the help desk the workers ask: {@code factory} builds one desk per session,
     * exactly as it builds one per worker.
     */
    public LookupAgent withExpert(
            java.util.function.Supplier<com.swarmcoder.runtime.ExpertHelp> factory) {
        this.expertFactory = factory;
        return this;
    }

    /** One session's own desk, wired into the run's answers; null when there is no expert. */
    private com.swarmcoder.runtime.ExpertHelp expertFor(MaterialBudget room, String role) {
        java.util.function.Supplier<com.swarmcoder.runtime.ExpertHelp> factory = expertFactory;
        com.swarmcoder.runtime.ExpertHelp help = factory == null ? null : factory.get();
        if (help instanceof ExpertDesk desk) {
            java.util.UUID run = RUN.get();
            desk.sharedAcrossRun(run == null ? ownCache : RunAnswerCache.forRun(run),
                RunAnswerCache.ASKED_BY_A_ROLE);
            desk.sizedFor(room);
            if (run != null) {
                // The run's record of what the expert was asked and what became of it.
                desk.recordedIn(com.swarmcoder.runtime.ExpertAnswerLog.forRun(run),
                    com.swarmcoder.runtime.ExpertAnswerLog.Asker.role(role));
            }
        }
        return help;
    }

    /**
     * Says in the run's record of expert answers what became of the role's work: it handed its
     * draft in (the hand-in tool refuses a draft that fails the role's own checks, so a draft
     * handed in is one that passed them), or its session ended without one.
     */
    private static void workFollowed(String role, boolean handedIn) {
        java.util.UUID run = RUN.get();
        if (run != null) {
            com.swarmcoder.runtime.ExpertAnswerLog.forRun(run).workFollowed(
                com.swarmcoder.runtime.ExpertAnswerLog.Asker.role(role), handedIn);
        }
    }

    /**
     * Which tool is for what, said once to every role - only the tools this session really has.
     * And the one rule above all of them: ask whenever unsure, never guess.
     */
    static String toolGuide(List<String> toolNames) {
        StringBuilder guide = new StringBuilder("\n\nWHICH TOOL FOR WHAT. Look up and ask as "
            + "often as you need to: nobody counts your questions, and a wrong guess costs "
            + "the whole build. Whenever you are not sure, ask - never guess.\n"
            + "- THE PROJECT'S OBJECT GRAPH FIRST. What the project is made of - its types, "
            + "their members, what extends or uses what, what each module's build declares - is "
            + "held as a graph the compiler resolved, and these tools answer from it in a few "
            + "hundred characters: public_shape (a type's members), body_of (ONE method or type, "
            + "as Type#method), types_in (the types of a package, module or folder), find_usages, find_implementations, files_using, "
            + "types_annotated_with, call_chain, build_of (a module's dependencies, plugin configuration, test-scope dependencies), resources_of (a module's beans.xml, persistence and property files), "
            + "dependency_declaring. The project map at the top of your task says where "
            + "everything is. Start from these.\n"
            + "- DOCUMENTS BY SECTION. The project's written documents - requirements, "
            + "architecture, a framework primer - are read one section at a time: doc_outline "
            + "(empty: every document with its sections; a document: its headings with sizes), "
            + "doc_section <document>#<number or heading> for ONE section, doc_search for the "
            + "sections on a subject.\n"
            + "- A WHOLE FILE IS THE LAST RESORT: read_file, for a resource or a script, or "
            + "for lines no query returns (<path>:LINE, <path>:FROM-TO). Any file may be read "
            + "whole when the whole file is what you need.\n"
            + "- SEVERAL AT ONCE. Put every lookup you can already name into ONE reply, as "
            + "several tool calls: they are all answered together. Each further reply sends this "
            + "whole conversation to the model again, so five files asked for one by one cost "
            + "five times what the same five cost asked for together.\n");
        if (toolNames.contains("search")) {
            guide.append("- WHERE SOMETHING IS, when you do not know its name: search, with "
                + "any words or names. It answers with places - types, usages, files and "
                + "documentation sections as addresses - which you follow up with public_shape "
                + "or read_file.\n");
        }
        if (toolNames.contains("find_example")) {
            guide.append("- REAL CODE that already does the kind of thing you are writing: "
                + "find_example. Name the library and project types involved and say whether you "
                + "want an implementation or a test; you get one whole file chosen by the types "
                + "it uses.\n");
        }
        if (toolNames.contains("docs_for")) {
            guide.append("- WHAT THE DOCUMENTATION SAYS about a class or a topic: docs_for. One "
                + "word works best.\n");
        }
        if (toolNames.contains("ask_expert")) {
            guide.append("- A HOW-TO QUESTION that needs several sources read and put together "
                + "(\"how does a test give the server its database?\"): ask_expert. It "
                + "investigates for you and answers with the files it read. Use it whenever the "
                + "lookups have not made it clear how something is done in this project. It "
                + "takes minutes where a lookup takes none, and it reads the same files your "
                + "lookups read: where a file is, what a file or the documentation says, and "
                + "whether something exists are answered at once by search, read_file and "
                + "docs_for - and when your own search finds nothing, the project does not "
                + "have it, which the expert can only confirm. Ask one thing per question and "
                + "say what you already know. An answer that begins by naming the question it "
                + "was researched for is the expert's own earlier answer, read from the files "
                + "it lists: use it, and ask again only for a part it does not cover, saying "
                + "which part.\n");
        }
        return guide.toString().stripTrailing();
    }

    /**
     * What a role's own tools are built from: the session's toolbox, so each of them runs as a
     * lookup does (one log line, the turn countdown on its result, never throwing) by going
     * through {@link ExpertTools#runOwnTool}.
     */
    public interface OwnTools extends Function<ExpertTools, List<ToolBinding>> {}

    /**
     * @param role         the session's name and its role on the run's cost record: "architect",
     *                     "planner", "test author". No dash - what follows one is not the role.
     * @param system       the role's system prompt, with its instruction to verify before use
     * @param opening      the task, and the pre-selected context the role starts from
     * @param finishAdvice one sentence the countdown repeats as the turns run out: how this role
     *                     hands in
     * @param contracts    the design's contracts, for {@code skeleton_for}; empty is normal
     * @param ownTools     the role's own tools, one of which is named {@link #SUBMIT_TOOL}
     */
    public record Ask(String role, String system, String opening, Limits limits,
                      String finishAdvice, List<ApiContract> contracts, OwnTools ownTools) {}

    /**
     * How a session ended.
     *
     * @param submitted the role called its hand-in tool, or ended on a plain text answer
     * @param finalText what the session ended on: the hand-in tool's return value, or the text
     * @param stopped   why the session was ended early, when it was
     * @param material  what the lookups returned that carried anything, sized to the role's room -
     *                  "" when they found nothing
     * @param conversation the session's conversation, still open, when it ended cleanly and can
     *                  be continued with {@link #resume}; null otherwise. Whoever receives it
     *                  either resumes it or {@link Conversation#close closes} it.
     */
    public record Outcome(boolean submitted, String finalText, Optional<KillReason> stopped,
                          int turns, List<String> toolsUsed, String material,
                          Conversation conversation) {

        public Outcome(boolean submitted, String finalText, Optional<KillReason> stopped,
                       int turns, List<String> toolsUsed, String material) {
            this(submitted, finalText, stopped, turns, toolsUsed, material, null);
        }

        /** The session could not run at all: no endpoint, no tools, a spent budget. */
        public boolean neverRan() {
            return turns == 0 && !submitted;
        }
    }

    /**
     * A role's session that handed in and was kept (section 54): its history, with every lookup
     * the role made, is still there, so a rejection of what it handed in can be delivered as the
     * next message of the SAME conversation by {@link #resume} instead of opening a new session
     * that learns the project again. Run 80 paid about 690,000 of 2,237,000 tokens for that.
     */
    public static final class Conversation {
        private final String role;
        private final AgentRuntime.AgentSession session;
        private final ExpertTools tools;
        private final TurnMeter guard;
        private final AtomicLong charged;
        private final MaterialBudget room;
        private final ModelQuirks quirks;

        private Conversation(String role, AgentRuntime.AgentSession session, ExpertTools tools,
                             TurnMeter guard, AtomicLong charged, MaterialBudget room,
                             ModelQuirks quirks) {
            this.role = role;
            this.session = session;
            this.tools = tools;
            this.guard = guard;
            this.charged = charged;
            this.room = room;
            this.quirks = quirks;
        }

        /** The role's name, as on the cost record. */
        public String role() {
            return role;
        }

        /** True while the conversation still holds its history and can take another message. */
        public boolean canContinue() {
            return session.canContinue();
        }

        /** Lets the conversation go; safe to call more than once. */
        public void close() {
            closeQuietly(session);
        }
    }

    /**
     * The share of a role's working context a continued conversation may fill before a retry
     * opens a fresh session instead: three quarters. A conversation past that would be compacted
     * on its first call, and that rewrites its history at full price.
     */
    static final int CONTINUE_UP_TO_PERCENT = 75;

    /** The lookup tool names a role is opened with, for a startup log line. */
    public List<String> lookupNames() {
        return new ExpertTools(curator, librarian, null, null).lookupBindings().stream()
            .map(ToolBinding::name).toList();
    }

    /**
     * Runs one session to its end and says how it ended. Never throws: a session that could not be
     * opened, lost its endpoint or ran out of turns is an {@link Outcome} the caller falls back
     * from, not an exception.
     *
     * @param client the role's own client: its endpoint, model and quirks are the session's
     */
    public Outcome run(VllmClient client, Ask ask) {
        ModelQuirks quirks = client.quirks();
        AgentRuntime.ModelEndpoint endpoint = new AgentRuntime.ModelEndpoint(client.baseUrl(),
            client.apiKey(), client.modelName(), quirks.servedContextTokens(), quirks);
        MaterialBudget room = MaterialBudget.of(quirks);
        int maxTurns = Math.max(2, ask.limits().maxTurns());
        // Escalation-free by construction (null expert): skeleton_for cannot open an expert.
        ExpertDesk skeletons = curator == null ? null : new ExpertDesk(curator, targetRoot,
            ask.contracts() == null ? List.of() : ask.contracts(), null);
        ExpertTools tools = new ExpertTools(curator, librarian, skeletons, cloudGate, room, maxTurns,
            new ExpertTools.User(ask.role(), ask.finishAdvice(),
                Math.max(0, ask.limits().maxLookups()), expertFor(room, ask.role()),
                Math.max(0, ask.limits().maxExpertQuestions()),
                Math.max(0, ask.limits().maxRepeats())));
        List<ToolBinding> bindings = new ArrayList<>(tools.lookupBindings());
        // One tool of a name: a role's own tool replaces the session's (the test author of a
        // screen task says what texts_of is for there).
        List<ToolBinding> own = ask.ownTools().apply(tools);
        bindings.removeIf(tool -> own.stream().anyMatch(o -> o.name().equals(tool.name())));
        bindings.addAll(own);
        String system = ask.system()
            + toolGuide(bindings.stream().map(ToolBinding::name).toList());

        // Where everything in the repository is, first in the opening and the same text for
        // every role and story: see ProjectMap.
        String map = ProjectMap.enabled()
            ? ProjectMap.of(targetRoot, room.chars(ProjectMap.MAP_CHARS), curator) : "";
        String opening = map.isEmpty() ? ask.opening() : map + "\n" + ask.opening();

        long opened = CloudGate.estimateTokens(system) + CloudGate.estimateTokens(opening);
        try {
            cloudGate.charge(opened);
        } catch (CloudGate.BudgetExhaustedException e) {                   // noqa
            log.warn("the {}'s lookup session was not opened: this run's model budget is spent",
                ask.role());
            return new Outcome(false, "", Optional.of(KillReason.BUDGET_EXCEEDED), 0, List.of(), "");
        }
        AtomicLong charged = new AtomicLong(opened);
        TurnMeter guard = new TurnMeter(cloudGate, charged, tools);
        // Two questions to the expert in one turn run side by side: each takes minutes, they are
        // independent, and the second need not wait for the first (2026-10-02).
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec(ask.role(), system,
            endpoint, 0.2, maxTurns, List.copyOf(bindings), guard)
            .with(sessionOptions(tools::upcoming, quirks.workingContextTokens()));

        log.info("the {} starts a lookup session (safety stops: {} turns, {} lookups, {} expert "
            + "questions, the same call {} times); resent on every call: system prompt about {} "
            + "tokens, opening about {} (project map {} of it), plus {} tool descriptions",
            ask.role(), maxTurns, ask.limits().maxLookups(),
            ask.limits().maxExpertQuestions(), ask.limits().maxRepeats(),
            CloudGate.estimateTokens(system), CloudGate.estimateTokens(opening),
            CloudGate.estimateTokens(map), bindings.size());
        return drive(client, quirks, ask.role(), () -> runtime.open(spec), opening, tools, guard,
            charged, room);
    }

    /**
     * Continues a kept conversation: {@code message} - the rejection of what the role handed in,
     * with its objections in full - is the next message of the same session, with its history and
     * lookups intact; the role works on from there and hands in again. The same safety stops
     * count across the whole conversation.
     *
     * <p>Empty when the conversation cannot be continued - it is gone (the session failed or was
     * closed), or it would not fit its room - and the conversation is then closed: the caller
     * opens a fresh session with {@link #run}. Never throws, like {@link #run}.
     */
    public Optional<Outcome> resume(VllmClient client, Conversation conversation, String message) {
        if (conversation == null) {
            return Optional.empty();
        }
        String role = conversation.role;
        if (!conversation.canContinue()) {
            log.info("the {}'s earlier conversation is gone; opening a new session", role);
            conversation.close();
            return Optional.empty();
        }
        long working = conversation.quirks.workingContextTokens();
        long held = conversation.session.conversationTokens();
        long added = CloudGate.estimateTokens(message);
        if (working > 0 && held >= 0 && (held + added) * 100 > working * CONTINUE_UP_TO_PERCENT) {
            log.info("the {}'s earlier conversation (about {} tokens) would not fit its room of {} "
                + "with this message; opening a new session", role, held, working);
            conversation.close();
            return Optional.empty();
        }
        try {
            cloudGate.charge(added);
        } catch (CloudGate.BudgetExhaustedException e) {                   // noqa
            log.warn("the {}'s conversation was not continued: this run's model budget is spent",
                role);
            conversation.close();
            return Optional.of(new Outcome(false, "", Optional.of(KillReason.BUDGET_EXCEEDED), 0,
                List.of(), ""));
        }
        conversation.charged.addAndGet(added);
        conversation.guard.startsAnotherRun();
        log.info("the {} continues its conversation (about {} tokens held) with a message of "
            + "about {} tokens", role, held, added);
        return Optional.of(drive(client, conversation.quirks, role, () -> conversation.session,
            message, conversation.tools, conversation.guard, conversation.charged,
            conversation.room));
    }

    /**
     * Runs {@code input} through the session {@code opened} yields, on a place of its own on the
     * model server, and says how it ended; keeps the session as the outcome's conversation when
     * it ended cleanly and can go on, closes it otherwise.
     */
    private Outcome drive(VllmClient client, ModelQuirks quirks, String role,
                          java.util.function.Supplier<AgentRuntime.AgentSession> opened,
                          String input, ExpertTools tools, TurnMeter guard, AtomicLong charged,
                          MaterialBudget room) {
        AgentRuntime.SessionResult result;
        InferenceScheduler.Lease lease = null;
        AgentRuntime.AgentSession session = null;
        try {
            String pool = scheduler == null || poolOfEndpoint == null ? null
                : poolOfEndpoint.apply(client.baseUrl());
            if (pool != null) {
                // One place on the model server for as long as the session runs, taken from the
                // scheduler the workers are admitted by: the server serves only so many at once.
                try {
                    lease = scheduler.acquireLease(pool, quirks.servedContextTokens());
                } catch (IllegalArgumentException tooBig) {
                    // The pool cannot hold a request of this role's size. That is a sizing
                    // mismatch to be told about, not a reason to take the role's tools away.
                    log.warn("the {}'s session could not take a place in scheduler pool '{}' "
                        + "({}); running it without one", role, pool, tooBig.getMessage());
                }
            }
            session = opened.get();
            result = session.run(input);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(session);
            cloudGate.refund(charged.get());
            return new Outcome(false, "", Optional.of(KillReason.WORKER_ERROR), 0, tools.used(), "");
        } catch (Exception e) {                                            // noqa
            log.warn("the {}'s lookup session could not be run: {}", role, e.toString());
            closeQuietly(session);
            cloudGate.refund(charged.get());
            return new Outcome(false, "", Optional.of(KillReason.WORKER_ERROR), 0, tools.used(), "");
        } finally {
            if (lease != null) {
                scheduler.releaseLease(lease);
            }
        }

        List<String> used = tools.used();
        String material = tools.whatWasFound(room.chars(MATERIAL_CHARS));
        if (result.killReason().isPresent()) {
            KillReason reason = result.killReason().get();
            closeQuietly(session);
            if (reason == KillReason.ENDPOINT_OUTAGE && guard.modelTokens.get() == 0) {
                cloudGate.refund(charged.get()); // nothing was ever generated, so nothing is owed
            }
            log.warn("{}the {}'s lookup session was stopped ({}) after {} turn(s) and {} tool "
                + "call(s)", reason == KillReason.TURN_CAP ? "SAFETY STOP: " : "", role,
                reason, result.turns(), used.size());
            workFollowed(role, false);
            return new Outcome(false, "", Optional.of(reason), result.turns(), used, material);
        }
        log.info("the {}'s lookup session ended after {} turn(s) and {} tool call(s): {}",
            role, result.turns(), used.size(), used);
        workFollowed(role, true);
        Conversation kept = null;
        if (session.canContinue()) {
            kept = new Conversation(role, session, tools, guard, charged, room, quirks);
        } else {
            closeQuietly(session);
        }
        return new Outcome(true, ExpertEscalation.plainText(result.finalOutput()), Optional.empty(),
            result.turns(), used, material, kept);
    }

    private static void closeQuietly(AgentRuntime.AgentSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException e) {                                 // noqa
                log.debug("closing a lookup session: {}", e.toString());
            }
        }
    }
}
