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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.UserFacingWording;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.verify.BlobSink;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.JourneyFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TEST_AUTHORING (spec §14): writes executable acceptance tests for each task into the
 * protected test path BEFORE any worker runs. Files are confined to the protected dir
 * mechanically (path traversal or escapes are rejected, never trusted). The red-check —
 * the tests MUST fail on the pre-change tree — runs after authoring, in the workflow.
 */
public class TestAuthorClient {

    private static final Logger log = LoggerFactory.getLogger(TestAuthorClient.class);

    public static class LlmTestFiles {
        public List<LlmTestFile> files;
        /** The author's own account of which test it wrote for which criterion. */
        public List<LlmWrote> wrote;
    }
    public static class LlmTestFile {
        public String path;    // repo-relative, must live under the protected dir
        public String content;
    }
    /** The reply to {@link #reviewSuspectTest}: which side is wrong, why, and the corrected file. */
    public static class LlmTestReview extends LlmTestFiles {
        public Boolean testIsWrong;
        public String reason;
    }
    public static class LlmWrote {
        public String criterion; // the criterion's wording, as it was given
        public String test;      // the id of the test written for it, e.g. pkg.Class#method
    }

    /**
     * What one TEST_AUTHORING call produced: the files it actually wrote, and its own statement of
     * which test answers which criterion.
     *
     * <p>The claims are a REPORT, never a source of truth. What the author says it wrote is another
     * string typed by a model; what it wrote is on disk. The claims exist so the run's log can show
     * the intended mapping in the author's own words, and so a claim that disagrees with the files
     * is itself visible. The decision to park is taken on the files.
     */
    public record Authored(List<String> paths, List<Claim> claims, String failureReason,
                           AcceptanceTestVocabulary.Check vocabulary,
                           SelfImplementedContract.Check selfImplemented,
                           AcceptanceTestReach.Check reach,
                           AcceptanceTestReflection.Check reflection,
                           List<String> journeys, String journeyWaiver) {

        /** A result with no journey says so with an empty list, never null. */
        public Authored {
            journeys = journeys == null ? List.of() : journeys;
        }

        /** The shape from before an author could answer in place of a journey (section 64). */
        public Authored(List<String> paths, List<Claim> claims, String failureReason,
                        AcceptanceTestVocabulary.Check vocabulary,
                        SelfImplementedContract.Check selfImplemented,
                        AcceptanceTestReach.Check reach,
                        AcceptanceTestReflection.Check reflection,
                        List<String> journeys) {
            this(paths, claims, failureReason, vocabulary, selfImplemented, reach, reflection,
                journeys, null);
        }

        /**
         * The same result with the author's answer given in place of a journey: why no person
         * using the application in a browser sees anything different. Null when there is none.
         */
        public Authored withJourneyWaiver(String why) {
            return new Authored(paths, claims, failureReason, vocabulary, selfImplemented, reach,
                reflection, journeys, why == null || why.isBlank() ? null : why.strip());
        }

        /** The shape from before a task could claim a journey (section 63). */
        public Authored(List<String> paths, List<Claim> claims, String failureReason,
                        AcceptanceTestVocabulary.Check vocabulary,
                        SelfImplementedContract.Check selfImplemented,
                        AcceptanceTestReach.Check reach,
                        AcceptanceTestReflection.Check reflection) {
            this(paths, claims, failureReason, vocabulary, selfImplemented, reach, reflection,
                List.of());
        }

        /**
         * The same result, with the journey files written beside the tests: valid
         * {@code <name>.journey.yaml} files in the protected directory, repo-relative. Kept apart
         * from {@link #paths}, which the build compiles and every check here reads as Java.
         */
        public Authored withJourneys(List<String> written) {
            return new Authored(paths, claims, failureReason, vocabulary, selfImplemented, reach,
                reflection, written, journeyWaiver);
        }

        public Authored(List<String> paths, List<Claim> claims, String failureReason,
                        AcceptanceTestVocabulary.Check vocabulary,
                        SelfImplementedContract.Check selfImplemented,
                        AcceptanceTestReach.Check reach) {
            this(paths, claims, failureReason, vocabulary, selfImplemented, reach,
                AcceptanceTestReflection.Check.CLEAN);
        }

        public Authored(List<String> paths, List<Claim> claims, String failureReason,
                        AcceptanceTestVocabulary.Check vocabulary,
                        SelfImplementedContract.Check selfImplemented) {
            this(paths, claims, failureReason, vocabulary, selfImplemented,
                AcceptanceTestReach.Check.CLEAN);
        }

        public Authored(List<String> paths, List<Claim> claims, String failureReason,
                        AcceptanceTestVocabulary.Check vocabulary) {
            this(paths, claims, failureReason, vocabulary, SelfImplementedContract.Check.CLEAN);
        }

        public static final Authored NOTHING =
            new Authored(List.of(), List.of(), null, AcceptanceTestVocabulary.Check.CLEAN);

        public boolean isEmpty() {
            return paths.isEmpty();
        }

        /** Convenience for the common success case: no failure to report. */
        public static Authored of(List<String> paths, List<Claim> claims) {
            return new Authored(paths, claims, null, AcceptanceTestVocabulary.Check.CLEAN);
        }

        /**
         * Nothing was written because the call itself failed — never because the author simply
         * chose not to. {@code failureReason} is the honest sentence the workflow's park message
         * quotes, in place of guessing at "the endpoint" for a failure that had a real cause.
         */
        public static Authored failed(String failureReason) {
            return new Authored(List.of(), List.of(), failureReason,
                AcceptanceTestVocabulary.Check.CLEAN);
        }

        /**
         * The files that were written, together with the names in them that nothing in this plan
         * will ever deliver — after the author was asked once to correct them and did not. The
         * workflow parks on this; see {@link AcceptanceTestVocabulary} for why it is not fixed
         * automatically.
         */
        public Authored namingUndeliverableTypes(AcceptanceTestVocabulary.Check check) {
            return new Authored(paths, claims, failureReason, check, selfImplemented, reach,
                reflection, journeys, journeyWaiver);
        }

        /**
         * The files that were written, together with the places they stand in for a contract
         * instead of using the delivered code — after the author was asked once to obtain the real
         * one and did not. The workflow parks on this; see {@link SelfImplementedContract}.
         */
        public Authored implementingContractsItself(SelfImplementedContract.Check check) {
            return new Authored(paths, claims, failureReason, vocabulary, check, reach, reflection,
                journeys, journeyWaiver);
        }

        /**
         * The files that were written, together with the places they call code that runs only in
         * a browser — after the author was asked once to prove the check through code that runs
         * on the JVM and did not. The workflow parks on this; see {@link AcceptanceTestReach}.
         */
        public Authored reachingBrowserOnlyCode(AcceptanceTestReach.Check check) {
            return new Authored(paths, claims, failureReason, vocabulary, selfImplemented, check,
                reflection, journeys, journeyWaiver);
        }

        /**
         * The files that were written, together with the places they find the project's own
         * constructors or methods by reflection — after the author was asked once to call the
         * contract's members directly and did not. The workflow parks on this; see
         * {@link AcceptanceTestReflection}.
         */
        public Authored reflectingOnProjectTypes(AcceptanceTestReflection.Check check) {
            return new Authored(paths, claims, failureReason, vocabulary, selfImplemented, reach,
                check, journeys, journeyWaiver);
        }

        /** What the tests reflect on, for the park brief. */
        public AcceptanceTestReflection.Check reflection() {
            return reflection == null ? AcceptanceTestReflection.Check.CLEAN : reflection;
        }

        /** True when no test locates the project's own members by reflection (harness run 54). */
        public boolean callsMembersDirectly() {
            return reflection == null || reflection.ok();
        }

        /** True when no test calls code that can only run in a browser (harness run 37). */
        public boolean runsOnTheJvm() {
            return reach == null || reach.ok();
        }

        /** True when the tests name only types the design agreed or the checkout already holds. */
        public boolean vocabularyIsAnswerable() {
            return vocabulary == null || vocabulary.ok();
        }

        /** True when no test supplies its own implementation of a contract it is measuring. */
        public boolean provesDeliveredCode() {
            return selfImplemented == null || selfImplemented.ok();
        }
    }

    /** "I wrote {@code testRef} for the criterion worded {@code criterion}" — the author's word. */
    public record Claim(String criterion, String testRef) {}

    private final VllmClient client;
    private final CloudGate cloudGate;
    private final BlobSink blobs;
    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------------------------------
    // The author as an agent (owner decision, 2026-10-02)
    // ------------------------------------------------------------------------------------------

    static final String AGENT_FINISH_ADVICE = "Stop looking things up: give your draft to "
        + "compile_test if you have not yet, then call report_done. A draft you have compiled is "
        + "handed in as it stands when the turns run out; a test you have not given to "
        + "compile_test is lost.";

    /** The session the author works in; null is the one-reply author it always was. */
    private volatile LookupAgent lookupAgent;
    /** What {@code compile_test} compiles with, for the call on this thread; see {@link #compilingDraftsWith}. */
    private final ThreadLocal<DraftCompiler> draftCompiler = new ThreadLocal<>();

    /**
     * Compiles a draft exactly as the red check after authoring will, and says which of its two
     * readings the result is. Supplied by the workflow, which owns the throwaway tree and the plan.
     */
    public interface DraftCompiler {
        /** @param files the whole draft, repo-relative path to source */
        TestAuthorTools.Verdict compile(Task task, Map<String, String> files);
    }

    /** Closes without an exception, so it reads as a plain try-with-resources. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Turns the author into a tool-using agent: every authoring and repair call first runs as a
     * lookup session over the project and its reference material, and falls back to the one reply
     * when that session cannot produce a test. Null (the default) leaves every call as it was.
     */
    public void setLookupAgent(LookupAgent agent) {
        this.lookupAgent = agent;
    }

    /** True when authoring and repair calls run as lookup sessions. */
    /**
     * The model server this author's calls go to, or null when it has none - so the workflow can
     * tell how many of its calls that server takes at once.
     */
    public String endpoint() {
        return client == null ? null : client.baseUrl();
    }

    public boolean worksAsAnAgent() {
        return lookupAgent != null;
    }

    /** Makes journeys on the tree a run started from; see {@link #tryingOnTheStartTreeWith}. */
    private final ThreadLocal<java.util.function.Function<List<JourneyFile.Journey>,
        com.swarmcoder.verify.JourneyRunner.Outcome>> startTree = new ThreadLocal<>();

    /**
     * For the calls made on this thread until the scope is closed, {@code check_journey} tries
     * what a draft expects before it does anything on the tree the run started from, with
     * {@code onStartTree} (section 75). Thread-scoped for the reason the draft compiler is: the
     * author serves every run, and the start tree is one run's.
     */
    public Scope tryingOnTheStartTreeWith(
            java.util.function.Function<List<JourneyFile.Journey>,
                com.swarmcoder.verify.JourneyRunner.Outcome> onStartTree) {
        var before = startTree.get();
        startTree.set(onStartTree);
        return () -> {
            if (before == null) {
                startTree.remove();
            } else {
                startTree.set(before);
            }
        };
    }

    /**
     * For the calls made on this thread until the scope is closed, {@code compile_test} compiles
     * with {@code compiler}. Thread-scoped because one author serves every run of a project, and
     * the tree a draft is compiled in belongs to one run.
     */
    public Scope compilingDraftsWith(DraftCompiler compiler) {
        DraftCompiler before = draftCompiler.get();
        draftCompiler.set(compiler);
        return () -> {
            if (before == null) {
                draftCompiler.remove();
            } else {
                draftCompiler.set(before);
            }
        };
    }

    /**
     * What one authoring or repair call's session is about.
     *
     * @param staticChecks true for a first authoring: the draft is also held to the vocabulary,
     *                     self-implementation, browser-only and reflection checks before it is
     *                     compiled, as the reply is after hand-in
     * @param followUp     for a repair: what is said to the author's earlier conversation about
     *                     this task when it is still there (section 54); null for a first
     *                     authoring, and then no earlier conversation is continued
     */
    private record AgentCall(Path repoRoot, Task task, DesignDocument design, String protectedDir,
                             String writeDir, List<Task> planTasks, BrowserOnlyCode.Survey browserOnly,
                             boolean staticChecks, String followUp, boolean journeyDue,
                             boolean journeyWaivable) {

        AgentCall(Path repoRoot, Task task, DesignDocument design, String protectedDir,
                  String writeDir, List<Task> planTasks, BrowserOnlyCode.Survey browserOnly,
                  boolean staticChecks) {
            this(repoRoot, task, design, protectedDir, writeDir, planTasks, browserOnly,
                staticChecks, null, false, false);
        }

        AgentCall followingUpWith(String followUp) {
            return new AgentCall(repoRoot, task, design, protectedDir, writeDir, planTasks,
                browserOnly, staticChecks, followUp, journeyDue, journeyWaivable);
        }

        /**
         * This task's session writes a journey too (section 63); {@code waivable} when its
         * author may answer "no visible effect" in place of one (section 64).
         */
        AgentCall writingAJourney(boolean due, boolean waivable) {
            return new AgentCall(repoRoot, task, design, protectedDir, writeDir, planTasks,
                browserOnly, staticChecks, followUp, due, due && waivable);
        }
    }

    /**
     * The test author's sessions that handed in a test, by task (section 54): a test sent back -
     * it failed in its own code, did not compile, could not run, broke a rule - goes to the same
     * conversation, with its lookups intact, instead of a new session that reads the project
     * again.
     */
    private final KeptConversations kept = new KeptConversations();

    private static String keyOf(Task task) {
        return "author:" + task.id();
    }

    /** What the author is told when its test is sent back, around the reason it was. */
    private static String testSentBack(String reason, Map<String, String> files) {
        StringBuilder text = new StringBuilder("THIS IS THE SAME CONVERSATION, NOT A NEW TASK. "
            + "The test you handed in was sent back. Everything you looked up above is still "
            + "true; do not look it up again unless the reason shows it was wrong.\n\n")
            .append(reason).append("\n\nThe file(s), as currently written:\n");
        for (Map.Entry<String, String> file : files.entrySet()) {
            text.append("\n--- ").append(file.getKey()).append(" ---\n").append(file.getValue())
                .append('\n');
        }
        return text.append("\nCorrect the test so it proves the SAME checks, compile it with "
            + "compile_test, and hand it in with report_done (one line per criterion, as before).")
            .toString();
    }

    /**
     * The first reply of an authoring or repair call, as the JSON object the rest of the call
     * parses: from a lookup session when one is configured, otherwise - and whenever the session
     * ends without a test - from the one schema-constrained reply this role always gave.
     */
    private String firstReply(String system, String user, AgentCall call) throws Exception {
        String material = "";
        LookupAgent agent = lookupAgent;
        if (agent != null && call != null && call.protectedDir() != null) {
            try {
                // What is sent back goes to the conversation that wrote it; a first authoring
                // starts afresh and lets go of any earlier one for the task.
                KeptConversations.Held prior = kept.take(keyOf(call.task()));
                AgentReply viaAgent = agentReply(agent, system, user, call, prior);
                if (viaAgent.reply() != null) {
                    return viaAgent.reply();
                }
                material = viaAgent.material();
            } catch (EndpointOutage outage) {
                throw outage;
            } catch (RuntimeException e) {
                log.warn("The test author's lookup session for task '{}' failed ({}); falling "
                    + "back to one reply without tools", call.task().title(), e.toString());
            }
        }
        return oneShot(system, material.isBlank() ? user : user
            + "\n\nWHAT YOU LOOKED UP BEFORE THIS REPLY (results of read-only lookups over this "
            + "project and its reference material - rely on these, not on memory):\n" + material);
    }

    private record AgentReply(String reply, String material) {}

    private AgentReply agentReply(LookupAgent agent, String system, String user, AgentCall call,
                                  KeptConversations.Held prior) throws IOException {
        TestAuthorTools[] own = new TestAuthorTools[1];
        // Read HERE, on the caller's thread: the tools run on the agent runtime's own threads,
        // where this call's thread-scoped compiler is not visible.
        DraftCompiler compiler = draftCompiler.get();
        java.util.function.Function<Map<String, String>, TestAuthorTools.Verdict> check =
            files -> checkDraft(call, files, compiler);
        var onStartTree = startTree.get(); // on the caller's thread, as the compiler is
        LookupAgent.Outcome outcome = null;
        if (prior != null && call.followUp() != null
                && prior.tools() instanceof TestAuthorTools again) {
            again.nextRound(check);
            outcome = agent.resume(client, prior.conversation(), call.followUp()).orElse(null);
            own[0] = outcome == null ? null : again;
        } else if (prior != null) {
            prior.conversation().close();
        }
        if (outcome == null) {
            outcome = agent.run(client, new LookupAgent.Ask("test author",
                agentSystem(system, call.writeDir(), call.journeyDue()), user,
                LookupAgent.Limits.configured(),
                AGENT_FINISH_ADVICE,
                call.design() == null ? List.of() : call.design().contracts(),
                session -> {
                    own[0] = new TestAuthorTools(session, call.protectedDir(), call.writeDir(),
                        check);
                    if (call.journeyDue()) {
                        own[0].expectingAJourney((path, content) ->
                            earlierJourneyObjection(call.repoRoot(), path, content))
                            .journeyMayBeWaived(call.journeyWaivable())
                            .knowingTheProjectsTexts(
                                com.swarmcoder.knowledge.ProjectTexts.heldIn(call.repoRoot()))
                            .knowingTheStartPage(onStartTree);
                    }
                    return own[0].bindings();
                }));
        }
        TestAuthorTools tools = own[0];
        // A hand-in that would take an earlier story's test away is not written (section 59).
        // It used to end the call there: the earlier file stayed, and the story had no test.
        // The author is told so in the conversation that wrote it, and hands in again
        // (section 60). The earlier file is not touched at any point.
        for (int asked = 0; asked < HAND_IN_REASKS && tools != null && tools.hasDraft()
                && outcome.conversation() != null; asked++) {
            String dropped = takenFromEarlierTests(call.repoRoot(), tools.submission());
            if (dropped == null) {
                break;
            }
            log.warn("Test author's hand-in for task '{}' is not taken and is sent back to its "
                + "own conversation: {}", call.task().title(), dropped);
            tools.nextRound(check);
            LookupAgent.Outcome again = agent.resume(client, outcome.conversation(),
                handInNotTaken(dropped)).orElse(null);
            if (again == null) {
                break; // the conversation is gone: what follows asks afresh
            }
            outcome = again;
        }
        // A task that changes a screen handed in its test and no journey (section 63): said
        // once, in the conversation that wrote the test. The test draft stays as it is.
        if (call.journeyDue() && call.followUp() == null && tools != null && tools.journeyDue()
                && tools.hasDraft() && tools.journeys().isEmpty()
                && tools.journeyWaiver() == null && outcome.conversation() != null) {
            log.warn("Test author handed in no journey for task '{}', which changes a screen; "
                + "asking for it in the same conversation", call.task().title());
            tools.handInAgain();
            LookupAgent.Outcome again = agent.resume(client, outcome.conversation(),
                journeyMissing(call.writeDir(), call.journeyWaivable())).orElse(null);
            if (again != null) {
                outcome = again;
            }
        }
        if (outcome.conversation() != null) {
            if (tools != null && tools.hasDraft()) {
                kept.keep(keyOf(call.task()),
                    new KeptConversations.Held(outcome.conversation(), tools));
            } else {
                outcome.conversation().close();
            }
        }
        String title = call.task().title();
        if (tools != null && tools.hasDraft()) {
            if (tools.handedIn()) {
                log.info("Test author handed in its test for task '{}' after {} turn(s), {} tool "
                    + "call(s) and {} compile(s)", title, outcome.turns(),
                    outcome.toolsUsed().size(), tools.compiles());
            } else {
                log.warn("Test author's lookup session for task '{}' ended ({}) before it handed "
                    + "in; taking the draft it last compiled", title,
                    outcome.stopped().map(Enum::name).orElse("no report_done"));
            }
            return new AgentReply(asReplyJson(tools), "");
        }
        if (outcome.submitted() && outcome.finalText().contains("\"files\"")) {
            // It answered with the JSON object as text instead of using its tools: still an answer.
            log.info("Test author answered task '{}' with the JSON object as text after {} "
                + "turn(s); taking it as its reply", title, outcome.turns());
            return new AgentReply(outcome.finalText(), "");
        }
        log.warn("Test author's lookup session for task '{}' gave no test ({} after {} turn(s), "
            + "{} tool call(s)); falling back to one reply without tools{}", title,
            outcome.stopped().map(Enum::name).orElse("ended with nothing compiled"),
            outcome.turns(), outcome.toolsUsed().size(),
            outcome.material().isBlank() ? "" : ", which is shown what its lookups returned");
        return new AgentReply(null, outcome.material());
    }

    /** How often a hand-in that would take an earlier test away is sent back before it stands. */
    static final int HAND_IN_REASKS = 2;

    /** What writing {@code files} would take from earlier stories' tests; null when nothing. */
    static String takenFromEarlierTests(Path repoRoot, Map<String, String> files) {
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, String> file : files.entrySet()) {
            String dropped = EarlierAcceptanceTests.objection(repoRoot, file.getKey(),
                file.getValue());
            if (dropped != null) {
                all.append(all.length() == 0 ? "" : "\n").append(dropped);
            }
        }
        return all.length() == 0 ? null : all.toString();
    }

    /** What the author is told when its hand-in was not taken for that reason. */
    static String handInNotTaken(String dropped) {
        return "THIS IS THE SAME CONVERSATION, NOT A NEW TASK. Everything you looked up above is "
            + "still true; do not look it up again. What you handed in was NOT taken, and the "
            + "file it was aimed at is exactly as it was before:\n\n" + dropped + "\n\nGive "
            + "the test to compile_test again - the same test methods of yours, in a file that "
            + "takes nothing away - until it is HEALTHY, and hand it in with report_done (one "
            + "line per criterion, naming the class you used).";
    }

    /** The files of a session's draft, as they are handed in. */
    private static LlmTestFiles filesOf(TestAuthorTools tools) {
        LlmTestFiles reply = new LlmTestFiles();
        reply.files = new ArrayList<>();
        for (Map.Entry<String, String> file : tools.submission().entrySet()) {
            LlmTestFile entry = new LlmTestFile();
            entry.path = file.getKey();
            entry.content = file.getValue();
            reply.files.add(entry);
        }
        // The journeys check_journey called valid travel with the test, as files like it.
        for (Map.Entry<String, String> journey : tools.journeys().entrySet()) {
            LlmTestFile entry = new LlmTestFile();
            entry.path = journey.getKey();
            entry.content = journey.getValue();
            reply.files.add(entry);
        }
        // So does the answer given in place of one; write() records it and writes no file.
        if (tools.journeyWaiver() != null && tools.journeys().isEmpty()) {
            LlmTestFile entry = new LlmTestFile();
            entry.path = tools.journeyWaiverPath();
            entry.content = JourneyFile.NO_VISIBLE_EFFECT + ": \""
                + tools.journeyWaiver().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            reply.files.add(entry);
        }
        return reply;
    }

    /** What the author is told when a task that changes a screen was handed in with no journey. */
    static String journeyMissing(String writeDir) {
        return journeyMissing(writeDir, false);
    }

    /** @param waivable true when "no visible effect" may be answered in place of the journey */
    static String journeyMissing(String writeDir, boolean waivable) {
        return "THIS IS THE SAME CONVERSATION, NOT A NEW TASK. Your test is kept exactly as you "
            + "handed it in; do not compile it again. What is missing is the JOURNEY: "
            + (waivable ? "this application is used in a browser, and nothing you handed in "
                    + "shows what a person sees there when this task is done. "
                : "this task changes a screen, and nothing you handed in shows that a person can "
                    + "reach and use it. ")
            + "Write " + writeDir + "/<name>" + JourneyFile.SUFFIX + " as described at the "
            + "start, give it to check_journey until it answers VALID"
            + (waivable ? " - or, only when no person using the application in a browser sees or "
                + "can do anything different, give check_journey the single line `"
                + JourneyFile.NO_VISIBLE_EFFECT + ": <why>` as the content -" : ",")
            + " and call report_done again with the same lines as before.";
    }

    /** What is wrong with writing this journey here, beyond its form; null when nothing is. */
    static String earlierJourneyObjection(Path repoRoot, String path, String content) {
        String earlier = EarlierAcceptanceTests.earlier(repoRoot, path);
        if (earlier == null || content == null
                || earlier.replace("\r\n", "\n").strip().equals(content.replace("\r\n", "\n").strip())) {
            return null;
        }
        return "`" + path + "` already holds an earlier story's journey, and an earlier journey "
            + "is never changed: the application must still pass it. Give yours a name of its "
            + "own.";
    }

    /** The draft as the reply this role always gave: {@code {"files":[...],"wrote":[...]}}. */
    private String asReplyJson(TestAuthorTools tools) throws IOException {
        LlmTestFiles reply = filesOf(tools);
        reply.wrote = new ArrayList<>();
        for (String[] claim : tools.claims()) {
            LlmWrote entry = new LlmWrote();
            entry.criterion = claim[0];
            entry.test = claim[1];
            reply.wrote.add(entry);
        }
        return mapper.writeValueAsString(reply);
    }

    /**
     * {@code compile_test}: the checks a handed-in test is held to anyway, then the compile.
     *
     * <p>The static checks read files, so the draft is placed in the authoring tree for as long as
     * they take and whatever was there before is put back - a repair's existing test included.
     */
    private TestAuthorTools.Verdict checkDraft(AgentCall call, Map<String, String> files,
                                               DraftCompiler compiler) {
        for (Map.Entry<String, String> file : files.entrySet()) {
            String dropped = EarlierAcceptanceTests.objection(call.repoRoot(), file.getKey(),
                file.getValue());
            if (dropped != null) {
                return new TestAuthorTools.Verdict(false, "BROKEN TEST - not compiled.\n" + dropped
                    + "\n\nFix it and call compile_test again.");
            }
        }
        if (call.staticChecks()) {
            String objection = staticObjection(call, files);
            if (objection != null) {
                return new TestAuthorTools.Verdict(false, "BROKEN TEST - found before compiling.\n"
                    + objection + "\n\nFix it and call compile_test again.");
            }
        }
        if (compiler == null) {
            return new TestAuthorTools.Verdict(true, "NOT COMPILED: no build is available to "
                + "compile a draft in here." + (call.staticChecks() ? " The draft passed the "
                + "checks that read it without compiling." : "") + " Hand it in with report_done.");
        }
        try {
            return compiler.compile(call.task(), files);
        } catch (RuntimeException e) {
            log.warn("compile_test could not compile a draft for task '{}': {}",
                call.task().title(), e.toString());
            return new TestAuthorTools.Verdict(true, "NOT COMPILED: the compile could not be run ("
                + e.getMessage() + "). Hand the draft in with report_done; it is checked again "
                + "after hand-in.");
        }
    }

    /** The first static check a draft fails, as that check's own re-ask; null when none does. */
    private String staticObjection(AgentCall call, Map<String, String> files) {
        Path root = call.repoRoot();
        Map<String, String> before = new LinkedHashMap<>();
        List<String> written = new ArrayList<>(files.keySet());
        try {
            for (String path : written) {
                Path target = root.resolve(path);
                before.put(path, Files.exists(target) ? Files.readString(target) : null);
                Files.createDirectories(target.getParent());
                Files.writeString(target, files.get(path));
            }
            AcceptanceTestVocabulary.Check vocabulary =
                vocabularyOf(root, call.design(), written, call.planTasks(), call.task());
            // vocabularyOf may have corrected a misplaced package in the file: keep that.
            for (String path : written) {
                files.put(path, Files.readString(root.resolve(path)));
            }
            if (!vocabulary.ok()) {
                return AcceptanceTestVocabulary.reask(vocabulary);
            }
            SelfImplementedContract.Check selfImplemented =
                SelfImplementedContract.check(root, call.design(), written, call.planTasks());
            if (!selfImplemented.ok()) {
                return SelfImplementedContract.reask(selfImplemented);
            }
            AcceptanceTestReach.Check reach =
                AcceptanceTestReach.check(root, call.browserOnly(), written);
            if (!reach.ok()) {
                return AcceptanceTestReach.reask(reach);
            }
            AcceptanceTestReflection.Check reflection = AcceptanceTestReflection.check(root, written);
            return reflection.ok() ? null : AcceptanceTestReflection.reask(reflection);
        } catch (IOException | RuntimeException e) {
            log.debug("the static checks could not read a draft: {}", e.toString());
            return null; // the compile, and the checks after hand-in, still judge it
        } finally {
            for (Map.Entry<String, String> was : before.entrySet()) {
                try {
                    Path target = root.resolve(was.getKey());
                    if (was.getValue() == null) {
                        Files.deleteIfExists(target);
                    } else {
                        Files.writeString(target, was.getValue());
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Could not put back {} after checking a draft: {}", was.getKey(),
                        e.toString());
                }
            }
        }
    }

    /**
     * The system prompt of a lookup session: the role's own prompt with its "answer with only this
     * JSON" sentence taken out, and how the session works put in its place.
     */
    static String agentSystem(String system, String writeDir) {
        return agentSystem(system, writeDir, false);
    }

    static String agentSystem(String system, String writeDir, boolean journeyDue) {
        String base = system;
        int from = system.indexOf("Respond ONLY with");
        int wrote = from < 0 ? -1 : system.indexOf("\"wrote\":", from);
        int end = wrote < 0 ? -1 : system.indexOf("]}", wrote);
        if (end > 0) {
            base = system.substring(0, from).stripTrailing() + system.substring(end + 2);
        }
        return base + "\n\nHOW YOU WORK IN THIS SESSION. You do not answer in one reply: you work "
            + "in steps, with tools, and you hand the test in through a tool.\n"
            + "1. CHECK EVERY FACT BEFORE YOU USE IT. Before your test names a library class, a "
            + "class of this project, a constructor, a method, its arguments or a package, confirm "
            + "it with a tool, and ask whenever you are not sure how something is done here (which "
            + "tool is for what is listed at the end). What you were given below - the contracts, "
            + "the existing types, the worked example - is where you start, not the limit of what "
            + "you may read or ask. GUESSING IS THE FAILURE THIS SESSION EXISTS TO PREVENT: a method you "
            + "remember but have not read is a test that cannot compile, and that stops the "
            + "build. The design contracts are the one exception - they are not written yet, so "
            + "they cannot be looked up; use them exactly as stated.\n"
            + "2. COMPILE YOUR DRAFT. Call compile_test with the path (" + writeDir
            + "/<ClassName>.java) and the complete Java source. It compiles the draft exactly as "
            + "the build's own check will and answers HEALTHY or BROKEN TEST with the compiler's "
            + "own lines. Fix what it reports - looking up whatever it shows you guessed - and "
            + "compile again.\n"
            + "3. HAND IN. When compile_test answers HEALTHY, call report_done with one line per "
            + "criterion: <the criterion> => <package>.<Class>#<method>. It hands in the file(s) "
            + "exactly as you last compiled them.\n"
            + (journeyDue ? "4. THE JOURNEY. Before you hand in, give your journey to check_journey ("
                + writeDir + "/<name>" + JourneyFile.SUFFIX + " and the complete YAML) until it "
                + "answers VALID. report_done hands it in with the test.\n" : "")
            + "Never reply with the test as plain text or JSON: only what you give to compile_test "
            + "is kept.";
    }

    /** The one schema-constrained reply this role always gave. */
    private String oneShot(String system, String user) throws Exception {
        return oneShot(system, user, LlmTestFiles.class);
    }

    private String oneShot(String system, String user, Class<?> schema) throws Exception {
        long prompt = CloudGate.estimateTokens(system) + CloudGate.estimateTokens(user);
        cloudGate.charge(prompt);
        String response;
        try {
            response = client.as("test author").chatCompletionStream(List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)),
                schema, 0.2).collect(Collectors.joining());
        } catch (Exception e) {
            EndpointOutage outage = EndpointOutage.from(client.baseUrl(), e);
            if (outage == null) {
                throw e;
            }
            cloudGate.refund(prompt);
            throw outage;
        }
        cloudGate.charge(CloudGate.estimateTokens(response));
        return response;
    }
    /** The project's standing rules for a repair call; see {@link #setStandingRules}. */
    private volatile java.util.function.Supplier<String> standingRules;

    /**
     * Wires in the project's rendered standing rules so that every REPAIR call carries them, as
     * the first authoring call always has (its caller passes them in). A repair used to be told
     * only what was wrong with the test, so a correction could trade one fault for a break of a
     * rule its author was never shown on that call. Unwired, a repair prompt is what it was.
     */
    public void setStandingRules(java.util.function.Supplier<String> rules) {
        this.standingRules = rules;
    }

    /** A real test from the reference material for a task; see {@link #setTestExamples}. */
    private volatile java.util.function.BiFunction<Task, DesignDocument, String> testExamples;

    /**
     * Wires in the librarian's worked example of a TEST, so the first authoring prompt and every
     * repair prompt carry one real test from the reference material.
     *
     * <p>Live harness runs 56 to 60 (2026-10-01): four of eight failed runs were the acceptance
     * test itself — an API guessed by reflection, a service constructed by hand twice, a builder
     * method that does not exist. The author had the contracts and the rules and had never been
     * shown a test that does it properly. Unwired, every prompt is what it was.
     */
    public void setTestExamples(java.util.function.BiFunction<Task, DesignDocument, String> examples) {
        this.testExamples = examples;
    }

    /** The example as a prompt section, or "" when none is wired or none was found. */
    private String exampleFor(Task task, DesignDocument design) {
        java.util.function.BiFunction<Task, DesignDocument, String> examples = testExamples;
        if (examples == null) {
            return "";
        }
        try {
            String example = examples.apply(task, design);
            return example == null || example.isBlank() ? "" : example;
        } catch (RuntimeException e) {
            return ""; // never the reason a test cannot be written or repaired
        }
    }

    /** The rules as a prompt section for a repair, or "" when none are wired or stated. */
    private String rulesForRepair() {
        java.util.function.Supplier<String> rules = standingRules;
        if (rules == null) {
            return "";
        }
        try {
            String brief = rules.get();
            return brief == null || brief.isBlank() ? ""
                : "\n\n" + brief.strip() + "\nYour corrected test must follow these rules too, "
                    + "above all any rule about how a test is written or how it obtains the code "
                    + "it exercises.";
        } catch (RuntimeException e) {
            return ""; // never the reason a repair cannot be attempted
        }
    }

    public TestAuthorClient(VllmClient client, CloudGate cloudGate) {
        this(client, cloudGate, BlobSink.NONE);
    }

    /** @param blobs where a reply kept after a second parse failure is stored — see {@link Authored#failed}. */
    public TestAuthorClient(VllmClient client, CloudGate cloudGate, BlobSink blobs) {
        this.client = client;
        this.cloudGate = cloudGate;
        this.blobs = blobs == null ? BlobSink.NONE : blobs;
    }

    /**
     * Authors acceptance tests for one task and writes them under the repo's protected dir.
     * Returns the repo-relative paths written; empty on failure (the workflow records it).
     */
    public Authored authorTests(Path repoRoot, Task task, DesignDocument design) {
        return authorTests(repoRoot, task, design, task.criteria());
    }

    public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                List<AcceptanceCriterion> forCriteria) {
        return authorTests(repoRoot, task, design, forCriteria, "");
    }

    /**
     * The shape from before the author was told what a JUnit test cannot prove. Kept so a caller
     * that knows nothing about the project's browser stage behaves exactly as it did.
     */
    public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                List<AcceptanceCriterion> forCriteria, String constraintBrief) {
        return authorTests(repoRoot, task, design, forCriteria, constraintBrief, false);
    }

    /**
     * Authors acceptance tests for one task against the criteria it is answerable for.
     *
     * <p>The criteria are passed in rather than read off the task because <b>a story-scoped task
     * does not own any</b>. In a scoped run the criteria belong to the BRD requirement and the task
     * only references them by id ({@code ArchitectClient.toTaskGraph}), deliberately, so that
     * requirement content is not copied into something that gets archived with the run. The
     * consequence was that this method's own guard — "no criteria, nothing to write" — fired on
     * every scoped task, so the test author never ran in exactly the mode the product is built
     * around: no acceptance test was ever written, the acceptance stage ran nothing, every
     * criterion came out UNKNOWN, and no requirement could reach IMPLEMENTED through a real run.
     * The caller resolves the criteria from the story's scope and hands them over.
     *
     * @param constraintBrief how this project must be built — the standing rules, rendered by
     *                        {@link com.swarmcoder.domain.ConstraintBrief}. This is the role the
     *                        gap hurt worst (author decision 2026-08-31). Told nothing about the
     *                        stack, the author writes the tests a Java web project usually needs:
     *                        against {@code dev/bookshelf-demo}, a project with no Spring, no JPA,
     *                        no SQL and no REST in it anywhere, it wrote acceptance tests asserting
     *                        a {@code @SpringBootApplication} class, a Flyway migration creating a
     *                        USERS table, and a Spring Data repository over a {@code @Entity}. None
     *                        of those classes exist or ever will, so those tests could never pass
     *                        and every candidate for that story would have failed for ever.
     * @param webProject      whether this project's verification contract starts the application
     *                        and looks at it with a browser. When it does, and a criterion is
     *                        worded about what a person SEES, the author is told plainly which
     *                        half of that criterion its JUnit test can prove and which half the
     *                        browser stage proves — see {@link #screenSteering}.
     */
    public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                List<AcceptanceCriterion> forCriteria, String constraintBrief,
                                boolean webProject) {
        return authorTests(repoRoot, task, design, forCriteria, constraintBrief, webProject, null, List.of());
    }

    /**
     * Same as {@link #authorTests(Path, Task, DesignDocument, List, String, boolean)}, and also
     * tells the author what its OWN test can and cannot import (author decision, 2026-09-05).
     *
     * <h2>The run this exists because of</h2>
     *
     * <p>Harness run 26. The acceptance module the test lives in does not depend on every module
     * in the project — that is exactly why {@code AcceptanceTestLocation} picks the one module
     * that reaches the most of them, not all of them at once. Told nothing about the boundary, the
     * author imported a package that exists in a sibling module the acceptance module cannot see,
     * the import compiled for nobody, and "does not compile" read as a healthy red state at every
     * gate until every candidate of that wave had already died against it.
     *
     * @param acceptanceModule the module this task's tests live in, repo-relative; null or blank
     *                         when it could not be read, and then nothing here changes — the
     *                         caller has no boundary to report, so none is claimed
     * @param moduleArtifactIds the artifact ids that module's own build file declares — what a
     *                          test written there can actually reach; empty when unreadable
     */
    public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                List<AcceptanceCriterion> forCriteria, String constraintBrief,
                                boolean webProject, String acceptanceModule,
                                List<String> moduleArtifactIds) {
        return authorTests(repoRoot, task, design, forCriteria, constraintBrief, webProject,
            acceptanceModule, moduleArtifactIds, BrowserOnlyCode.Survey.NONE);
    }

    /**
     * Same as the overload above, and also tells the author which modules of this build run only
     * in a browser, and refuses a test that calls them (harness run 37, 2026-09-25 — see
     * {@link AcceptanceTestReach} for the run that cost two workers and a repair round on a test
     * no code could ever pass).
     *
     * @param survey the build's browser-only modules; {@link BrowserOnlyCode.Survey#NONE} when the
     *               caller has no layout to read — then the author is told nothing new, and only a
     *               direct browser-runtime import is refused. {@code moduleArtifactIds} should
     *               already leave those modules out: they are on the classpath only to be packaged
     */
    public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                List<AcceptanceCriterion> forCriteria, String constraintBrief,
                                boolean webProject, String acceptanceModule,
                                List<String> moduleArtifactIds, BrowserOnlyCode.Survey survey) {
        return authorTests(repoRoot, task, design, forCriteria, constraintBrief, webProject,
            acceptanceModule, moduleArtifactIds, survey, List.of());
    }

    /**
     * Same as the overload above, and also gives the vocabulary and self-implementation checks
     * every OTHER task in this run's plan — not only the one these tests are for — so a concrete
     * implementation class some other task's write set promises is recognised as delivered even
     * though it is not a design contract (harness run 38, 2026-09-25; see
     * {@link AcceptanceTestVocabulary} for the run this exists because of).
     *
     * @param planTasks every task in this run's plan; empty when the caller has no plan to offer,
     *                  and then nothing here changes from before that run
     */
    public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                List<AcceptanceCriterion> forCriteria, String constraintBrief,
                                boolean webProject, String acceptanceModule,
                                List<String> moduleArtifactIds, BrowserOnlyCode.Survey survey,
                                List<Task> planTasks) {
        return authorTests(repoRoot, task, design, forCriteria, constraintBrief, webProject,
            acceptanceModule, moduleArtifactIds, survey, planTasks, false);
    }

    /**
     * Same as the overload above, and also asks for a JOURNEY when the task changes a screen
     * (section 63; the seven accepted stories whose screens no user could open, section 62).
     *
     * @param journeyDue true when this task is to write a {@code <name>.journey.yaml} beside its
     *                   test. Decided by the workflow from the build and the plan's write sets
     *                   ({@link com.swarmcoder.verify.ScreenChange}), never from what a criterion
     *                   says. The journeys written come back as {@link Authored#journeys()}
     */
    public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                List<AcceptanceCriterion> forCriteria, String constraintBrief,
                                boolean webProject, String acceptanceModule,
                                List<String> moduleArtifactIds, BrowserOnlyCode.Survey survey,
                                List<Task> planTasks, boolean journeyDue) {
        return authorTests(repoRoot, task, design, forCriteria, constraintBrief, webProject,
            acceptanceModule, moduleArtifactIds, survey, planTasks,
            journeyDue ? JourneyAsk.SCREEN : JourneyAsk.NONE);
    }

    /**
     * How a journey is asked of one task's author (section 64).
     *
     * @param due      true when the author is asked for a journey
     * @param mayWaive true when it may answer {@code noVisibleEffect: <why>} in place of one:
     *                 the project's contract starts an application for a browser, so every
     *                 story is asked, and the object graph shows no browser code using what
     *                 the plan writes. Decided by the workflow, never from a criterion's words
     * @param through  for a task whose code the graph shows browser code using: how, one line
     *                 a connection; empty otherwise
     */
    public record JourneyAsk(boolean due, boolean mayWaive, List<String> through) {

        public JourneyAsk {
            through = through == null ? List.of() : List.copyOf(through);
        }

        public static final JourneyAsk NONE = new JourneyAsk(false, false, List.of());
        /** A task that writes a screen itself (section 63). */
        public static final JourneyAsk SCREEN = new JourneyAsk(true, false, List.of());
    }

    /** Same as the overload above, with how the journey is asked (section 64). */
    public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                List<AcceptanceCriterion> forCriteria, String constraintBrief,
                                boolean webProject, String acceptanceModule,
                                List<String> moduleArtifactIds, BrowserOnlyCode.Survey survey,
                                List<Task> planTasks, JourneyAsk journey) {
        JourneyAsk journeyAsk = journey == null ? JourneyAsk.NONE : journey;
        boolean journeyDue = journeyAsk.due();
        BrowserOnlyCode.Survey browserOnly = survey == null ? BrowserOnlyCode.Survey.NONE : survey;
        // Valid journeys as they are written, path to content. Beside the tests, never among them.
        Map<String, String> journeys = new LinkedHashMap<>();
        String protectedDir = task.acceptanceTestDir();
        if (protectedDir == null || protectedDir.isBlank() || forCriteria == null
                || forCriteria.isEmpty()) {
            return Authored.NOTHING;
        }
        // Derived from the task's OWN protected tree, never from a constant. In a multi-module
        // project the protected tree is inside the module the build can actually compile tests in,
        // and a constant here would send the author to a root directory nothing compiles — which
        // is precisely what happened on run e01d1378: told to write to the root, its one attempt to
        // write into a real module was refused by the path policy for leaving the protected tree.
        String writeDir = ArchitectClient.acceptanceWriteDir(protectedDir);
        EarlierAcceptanceTests.remember(repoRoot, protectedDir);
        try {
            StringBuilder criteria = new StringBuilder();
            for (AcceptanceCriterion criterion : forCriteria) {
                criteria.append("- ").append(criterion.text());
                if (criterion.testClassOrFile() != null && !criterion.testClassOrFile().isBlank()) {
                    criteria.append(" (test: ").append(criterion.testClassOrFile()).append(')');
                }
                criteria.append('\n');
            }
            String system = "You are a test author. Write executable JUnit 5 acceptance tests for the "
                + "task's criteria, in package swarm.accept. The tests verify behavior that does NOT "
                + "exist yet — they must FAIL on the current codebase and pass once the task is "
                + "correctly implemented. Test real behavior; never use fail() placeholders or "
                + "assertions that are trivially true. EVERY file you write goes in '"
                + writeDir + "/<ClassName>.java', which is the "
                + "directory for package swarm.accept. Not '" + protectedDir + "' itself: a class "
                + "written there is in package swarm, every test in it is named swarm.<Class>#... "
                + "instead of swarm.accept.<Class>#..., and no criterion's agreed test reference "
                + "then matches, so nothing is proved and the run stops. "
                // The criterion's test reference is not a hint, it is an address: it is the string
                // the whole requirement-to-commit trace is looked up by, and it was agreed before
                // any of this ran. A test written under a different name proves nothing about the
                // criterion, however good the test is.
                + "EVERY criterion below that says \"(test: ...)\" names the EXACT class and method "
                + "you must write for it — same package, same class name, same method name, "
                + "character for character. That name is what the requirement is verified by; a "
                + "test under any other name does not count and will stop the build. "
                // The vocabulary rule. Without it the author names whatever type the design's prose
                // suggested to it, nobody delivers that type, and the test cannot compile for the
                // whole life of the run — see AcceptanceTestVocabulary.
                + "THE ONLY NEW TYPES THIS PROJECT WILL HAVE ARE THE DESIGN CONTRACTS LISTED "
                + "BELOW, with the exact packages and members given there. Use those and the "
                + "types this project already has, and nothing else: do not invent a type, do not "
                + "rename one, do not split one in two. A test naming a type nobody is building "
                + "can never compile, so it proves nothing and stops the run. "
                // Live harness run 51, 2026-09-30: a test booted the Helidon MP / CDI container,
                // which died at class level before any delivered code ran; no candidate could ever
                // pass it and a worker may not edit it. Harness run 56, 2026-10-01 (HamBook): the
                // opposite failure — ZeroZ services get their database injected, ZeroZ documents
                // its own in-process TestServer as THE way to test them, and a test that built the
                // service by hand handed it a null database. When the project's rules name how to
                // test, that wins.
                + "If this project's rules say how tests reach the code (a named test harness or "
                + "test server), use exactly that. Otherwise acceptance tests call the project's "
                + "own classes DIRECTLY: construct the "
                + "service or store, call its methods, assert on what comes back. They do NOT boot "
                + "the application server or a DI/CDI container (no @HelidonTest, SeContainer, "
                + "Server.start and the like) unless this project's own existing tests or its rules "
                + "already do so — that start-up can fail before any delivered code runs, and then no "
                + "implementation could ever make the test pass. "
                + "Also report what you wrote: for each criterion, the id of its test as "
                + "<package>.<Class>#<method>. "
                + "Respond ONLY with JSON: {\"files\":[{\"path\":\""
                + writeDir
                + "/<ClassName>.java\",\"content\":\"<java source>\"}],"
                + "\"wrote\":[{\"criterion\":\"<the criterion, copied>\","
                + "\"test\":\"<package>.<Class>#<method>\"}]}";
            // What this test's OWN module can even import (author decision, 2026-09-05, after
            // harness run 26 wrote an import of a client-only package into the server module's
            // acceptance test — a package that exists in the checkout, just not on THIS module's
            // classpath, which no earlier rule ever named). Silent when the caller has no build
            // layout to read; every existing caller behaves exactly as it did.
            //
            // Harness run 42, 2026-09-26: told the declared list and nothing else, the author
            // twice tried to WRITE bookshelf-demo-server/pom.xml — presumably to add an assertion
            // library the list did not include — and both attempts were mechanically rejected as
            // outside the protected test directory, costing the run its one correction round-trip
            // for nothing. Nothing before this sentence ever said a test author cannot touch a
            // build file at all (a fact that has been mechanically true since {@link #write} was
            // written, never stated); nothing said what to do instead when the assertion library
            // it reaches for by habit is not on the list. Both are said explicitly below now.
            if (acceptanceModule != null) {
                boolean hasAssertionLibrary = moduleArtifactIds != null && moduleArtifactIds.stream()
                    .anyMatch(id -> id != null && (id.contains("assertj") || id.contains("hamcrest")
                        || id.equals("truth")));
                system = system + " THIS TEST FILE LIVES IN THE MODULE '" + acceptanceModule
                    + "' (the repository root when that is blank), and a JUnit test there can "
                    + "import only what that module's own build file declares: "
                    + (moduleArtifactIds == null || moduleArtifactIds.isEmpty()
                        ? "no declared dependencies" : String.join(", ", moduleArtifactIds))
                    + ". Never import a package from a different, undeclared module — even one "
                    + "that exists elsewhere in this checkout — and never a package that belongs "
                    + "to this project's client or UI layer: that code is not on this module's "
                    + "classpath and the import will not compile for anybody. "
                    + "You may write ONLY test source files, under the path given above — never a "
                    + "pom.xml, a build.gradle(.kts), or any other file. This role cannot add, "
                    + "change or declare a dependency at all; a file written anywhere else is "
                    + "rejected outright and the attempt is wasted. If a library this test needs "
                    + "is not in the list above, do not try to declare it — write the test with "
                    + "what is already there instead."
                    + (hasAssertionLibrary ? "" : " In particular, this module has no assertion "
                        + "library such as assertj-core, hamcrest or truth on its test classpath: "
                        + "write assertions with JUnit 5's own `org.junit.jupiter.api.Assertions` "
                        + "static methods (`assertEquals`, `assertTrue`, `assertThrows`, ...), not "
                        + "AssertJ's `assertThat`.");
            }
            // Which modules run only in a browser, by name (harness run 37, 2026-09-25). The line
            // above says "never the client layer" in general terms; run 37's author read, in the
            // same breath, that bookshelf-demo-client WAS on its classpath (the server packages the
            // bundle), and wrote a test calling it. The survey is what makes the rule concrete.
            String browserOnlyLine = AcceptanceTestReach.authorBrief(browserOnly);
            if (!browserOnlyLine.isEmpty()) {
                system = system + " " + browserOnlyLine;
            }
            // Live harness run 54: a test that could not tell how to build a Store searched for
            // its constructor by reflection. Said before the first line is written; also checked
            // mechanically below.
            system = system + " " + AcceptanceTestReflection.AUTHOR_RULE;
            // The rules come FIRST, before the task and before the criteria. A test is written in
            // the project's own frameworks or it is written in the ones the model expects, and by
            // the time it has read the task it has already decided which.
            String screen = screenSteering(forCriteria, webProject);
            if (!screen.isEmpty()) {
                system = system + " " + screen;
            }
            if (journeyDue) {
                system = system + " " + journeyBrief(writeDir, lookupAgent != null, journeyAsk);
            }
            String rules = constraintBrief == null || constraintBrief.isBlank() ? ""
                : constraintBrief + "\nEvery test you write must compile and run inside THIS "
                    + "project as described above. A test that imports something the rules forbid, "
                    + "or names a class this project does not have, can never pass however well it "
                    + "is written — and it will stop the run rather than fail honestly.\n\n";
            String user = rules + "Task: " + task.title() + "\n" + task.instructions()
                + established(task)
                + "\n\nAcceptance criteria:\n" + criteria
                + (design == null ? "" : "\nDesign contracts:\n" + ArchitectClient.designSummary(design))
                // The project's own existing types, with their real packages and members (live
                // run 68, 2026-10-02: the author guessed a package for a store root that was
                // already in the checkout). Nothing for an empty repository.
                + existingTypesFor(repoRoot, task.title() + "\n" + task.instructions() + "\n"
                    + criteria + (design == null ? "" : ArchitectClient.designSummary(design)))
                + exampleFor(task, design);

            // In a lookup session when one is configured (2026-10-02), otherwise - and
            // whenever the session cannot give a test - the one reply it always was.
            String response = firstReply(system, user,
                new AgentCall(repoRoot, task, design, protectedDir, writeDir, planTasks, browserOnly, true)
                    .writingAJourney(journeyDue, journeyAsk.mayWaive()));

            LlmTestFiles parsed;
            try {
                parsed = LlmJson.parse(mapper, response, LlmTestFiles.class);
            } catch (IOException parseFailure) {
                // One retry, the reply and the parser's own complaint fed back — the same
                // allowance a person would get after handing in something that did not compile.
                // A second miss is final; nothing here is worth a third round trip.
                log.warn("Test author's reply for task '{}' was not valid JSON ({}); asking it "
                    + "to reply with only the JSON object", task.title(), parseFailure.getMessage());
                String retryAsk = "That was not valid JSON: " + parseFailure.getMessage()
                    + ". Reply with only the JSON object.";
                long retryPrompt = CloudGate.estimateTokens(retryAsk);
                cloudGate.charge(retryPrompt);
                String retryResponse;
                try {
                    retryResponse = client.as("test author").chatCompletionStream(List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user),
                            Map.of("role", "assistant", "content", response),
                            Map.of("role", "user", "content", retryAsk)),
                        LlmTestFiles.class, 0.2).collect(Collectors.joining());
                } catch (Exception e) {
                    EndpointOutage outage = EndpointOutage.from(client.baseUrl(), e);
                    if (outage == null) {
                        throw e;
                    }
                    cloudGate.refund(retryPrompt);
                    throw outage;
                }
                cloudGate.charge(CloudGate.estimateTokens(retryResponse));
                try {
                    parsed = LlmJson.parse(mapper, retryResponse, LlmTestFiles.class);
                } catch (IOException secondFailure) {
                    String description = LlmReplyBlobs.describeFailure(
                        blobs, "the test author's", retryResponse, secondFailure.getMessage());
                    log.warn("Test authoring failed for task '{}': {}", task.title(), description);
                    return Authored.failed(description);
                }
            }
            if (parsed.files == null || parsed.files.isEmpty()) {
                // A valid reply that simply writes nothing, while the task answers for at least
                // one check (the guard at the top of this method already ruled out "no checks" —
                // forCriteria is never empty here). Re-asked once, the same allowance a malformed
                // reply gets; giving up silently is what sent run 9… to the red-check with no
                // clue why the file was missing. See BacklogPlanning/RequirementsIntake for the
                // same "valid but empty → re-ask once" rule on the other front-half roles.
                log.warn("Test author produced no files for task '{}' though it answers for {} "
                    + "check(s); asking it once to write them", task.title(), forCriteria.size());
                String ask = "You returned no test file, but this task answers for these checks:\n"
                    + criteria + "\nWrite one JUnit test class under " + writeDir + " that proves "
                    + "each of them, using only the contract types listed above, and reply with "
                    + "the same JSON object with the files filled in.";
                long askTokens = CloudGate.estimateTokens(ask);
                cloudGate.charge(askTokens);
                String second;
                try {
                    second = client.as("test author").chatCompletionStream(List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user),
                            Map.of("role", "assistant", "content", response),
                            Map.of("role", "user", "content", ask)),
                        LlmTestFiles.class, 0.2).collect(Collectors.joining());
                } catch (Exception e) {
                    EndpointOutage outage = EndpointOutage.from(client.baseUrl(), e);
                    if (outage == null) {
                        throw e;
                    }
                    cloudGate.refund(askTokens);
                    throw outage;
                }
                cloudGate.charge(CloudGate.estimateTokens(second));
                LlmTestFiles reasked = null;
                try {
                    reasked = LlmJson.parse(mapper, second, LlmTestFiles.class);
                } catch (IOException notJson) {
                    log.warn("Test author's reply to the no-files re-ask for task '{}' was not "
                        + "valid JSON: {}", task.title(), notJson.getMessage());
                }
                if (reasked == null || reasked.files == null || reasked.files.isEmpty()) {
                    String description = LlmReplyBlobs.describeNoFilesTwice(blobs,
                        "the test author", second);
                    log.warn("Test authoring failed for task '{}': {}", task.title(), description);
                    return Authored.failed(description);
                }
                parsed = reasked;
                response = second; // so the vocabulary-check conversation below continues from here
            }

            List<String> written = write(repoRoot, protectedDir, parsed, journeys);

            // THE VOCABULARY CHECK (author decision, 2026-09-03). A test may name only the types
            // the design agreed and the types the checkout already holds; anything else is a name
            // nobody will ever deliver, and the test can never compile however good it is. See
            // AcceptanceTestVocabulary for the run that cost three waves proving that.
            AcceptanceTestVocabulary.Check vocabulary =
                vocabularyOf(repoRoot, design, written, planTasks, task);
            if (!vocabulary.ok()) {
                log.warn("Test author's tests for task '{}' name {} type(s) nothing in this plan "
                    + "delivers: {} — asking it once to use the contracts instead", task.title(),
                    vocabulary.unknowns().size(),
                    vocabulary.unknowns().stream().map(AcceptanceTestVocabulary.Unknown::typeName)
                        .toList());
                // The first attempt's files come off the tree BEFORE the second is written. A file
                // the second attempt does not rewrite would otherwise stay behind, naming the same
                // undeliverable type, and be committed with the run's tests.
                removeAll(repoRoot, written);
                String ask = AcceptanceTestVocabulary.reask(vocabulary);
                long askTokens = CloudGate.estimateTokens(ask);
                cloudGate.charge(askTokens);
                String second;
                try {
                    second = client.as("test author").chatCompletionStream(List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user),
                            Map.of("role", "assistant", "content", response),
                            Map.of("role", "user", "content", ask)),
                        LlmTestFiles.class, 0.2).collect(Collectors.joining());
                } catch (Exception e) {
                    EndpointOutage outage = EndpointOutage.from(client.baseUrl(), e);
                    if (outage == null) {
                        throw e;
                    }
                    cloudGate.refund(askTokens);
                    throw outage;
                }
                cloudGate.charge(CloudGate.estimateTokens(second));
                LlmTestFiles corrected;
                try {
                    corrected = LlmJson.parse(mapper, second, LlmTestFiles.class);
                } catch (IOException notJson) {
                    log.warn("Test author's corrected reply for task '{}' was not valid JSON: {}",
                        task.title(), notJson.getMessage());
                    corrected = null;
                }
                if (corrected != null && corrected.files != null && !corrected.files.isEmpty()) {
                    parsed = corrected;
                    written = write(repoRoot, protectedDir, parsed, journeys);
                    vocabulary = vocabularyOf(repoRoot, design, written, planTasks, task);
                } else {
                    // Nothing usable came back, so the first attempt's files are the only tests
                    // there are. Put them back and let the run park naming what is wrong with them.
                    written = write(repoRoot, protectedDir, parsed, journeys);
                }
            }

            // THE SELF-IMPLEMENTATION CHECK (author decision, 2026-09-05, harness run 30). Naming
            // a contract is required; BEING one is forbidden. A test that writes its own anonymous
            // BookService passes on an empty tree, so every candidate "passes" it without a line of
            // its own code running — see SelfImplementedContract for the story that was stamped
            // delivered on exactly that. Only asked when the vocabulary is already sound: a test
            // naming types nobody delivers is a different fault, and the run parks on it anyway.
            SelfImplementedContract.Check selfImplemented = vocabulary.ok()
                ? SelfImplementedContract.check(repoRoot, design, written, planTasks)
                : SelfImplementedContract.Check.CLEAN;
            if (!selfImplemented.ok()) {
                log.warn("Test author's tests for task '{}' implement {} contract(s) themselves: "
                    + "{} — asking it once to use the delivered code instead", task.title(),
                    selfImplemented.contractTypes().size(), selfImplemented.contractTypes());
                // The first attempt comes off the tree before the second is written, for the same
                // reason the vocabulary re-ask does it: a file the correction does not rewrite
                // would otherwise stay behind, still standing in for the contract.
                removeAll(repoRoot, written);
                String ask = SelfImplementedContract.reask(selfImplemented);
                long askTokens = CloudGate.estimateTokens(ask);
                cloudGate.charge(askTokens);
                String second;
                try {
                    second = client.as("test author").chatCompletionStream(List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user),
                            Map.of("role", "assistant", "content", response),
                            Map.of("role", "user", "content", ask)),
                        LlmTestFiles.class, 0.2).collect(Collectors.joining());
                } catch (Exception e) {
                    EndpointOutage outage = EndpointOutage.from(client.baseUrl(), e);
                    if (outage == null) {
                        throw e;
                    }
                    cloudGate.refund(askTokens);
                    throw outage;
                }
                cloudGate.charge(CloudGate.estimateTokens(second));
                LlmTestFiles corrected;
                try {
                    corrected = LlmJson.parse(mapper, second, LlmTestFiles.class);
                } catch (IOException notJson) {
                    log.warn("Test author's reply to the self-implementation re-ask for task '{}' "
                        + "was not valid JSON: {}", task.title(), notJson.getMessage());
                    corrected = null;
                }
                if (corrected != null && corrected.files != null && !corrected.files.isEmpty()) {
                    parsed = corrected;
                    written = write(repoRoot, protectedDir, parsed, journeys);
                    vocabulary = vocabularyOf(repoRoot, design, written, planTasks, task);
                    selfImplemented = vocabulary.ok()
                        ? SelfImplementedContract.check(repoRoot, design, written, planTasks)
                        : SelfImplementedContract.Check.CLEAN;
                } else {
                    written = write(repoRoot, protectedDir, parsed, journeys);
                }
            }

            // THE REACH CHECK (harness run 37, 2026-09-25). The names are right and the test does
            // not stand in for anything — and it still can never pass, because it calls code that
            // exists only as JavaScript in a browser. Nothing later can see this before a worker
            // is spent: the class it calls does not exist yet, so the red-check reads "does not
            // compile", a healthy red. Only asked when the two checks above are already sound.
            AcceptanceTestReach.Check reach = vocabulary.ok() && selfImplemented.ok()
                ? AcceptanceTestReach.check(repoRoot, browserOnly, written)
                : AcceptanceTestReach.Check.CLEAN;
            if (!reach.ok()) {
                log.warn("Test author's tests for task '{}' call code that runs only in a browser: "
                    + "{} — asking it once to prove the check through code that runs on the JVM",
                    task.title(), reach.names());
                removeAll(repoRoot, written);
                String ask = AcceptanceTestReach.reask(reach);
                long askTokens = CloudGate.estimateTokens(ask);
                cloudGate.charge(askTokens);
                String second;
                try {
                    second = client.as("test author").chatCompletionStream(List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user),
                            Map.of("role", "assistant", "content", response),
                            Map.of("role", "user", "content", ask)),
                        LlmTestFiles.class, 0.2).collect(Collectors.joining());
                } catch (Exception e) {
                    EndpointOutage outage = EndpointOutage.from(client.baseUrl(), e);
                    if (outage == null) {
                        throw e;
                    }
                    cloudGate.refund(askTokens);
                    throw outage;
                }
                cloudGate.charge(CloudGate.estimateTokens(second));
                LlmTestFiles corrected;
                try {
                    corrected = LlmJson.parse(mapper, second, LlmTestFiles.class);
                } catch (IOException notJson) {
                    log.warn("Test author's reply to the browser-only re-ask for task '{}' was not "
                        + "valid JSON: {}", task.title(), notJson.getMessage());
                    corrected = null;
                }
                if (corrected != null && corrected.files != null && !corrected.files.isEmpty()) {
                    parsed = corrected;
                    written = write(repoRoot, protectedDir, parsed, journeys);
                    // The correction is held to all three rules, not just this one: a test that
                    // stops calling the client by inventing a server type nobody delivers, or by
                    // writing its own, has swapped one broken test for another.
                    vocabulary = vocabularyOf(repoRoot, design, written, planTasks, task);
                    selfImplemented = vocabulary.ok()
                        ? SelfImplementedContract.check(repoRoot, design, written, planTasks)
                        : SelfImplementedContract.Check.CLEAN;
                    reach = vocabulary.ok() && selfImplemented.ok()
                        ? AcceptanceTestReach.check(repoRoot, browserOnly, written)
                        : AcceptanceTestReach.Check.CLEAN;
                } else {
                    written = write(repoRoot, protectedDir, parsed, journeys);
                }
            }

            // THE REFLECTION CHECK (live harness run 54, 2026-10-01). The test cannot build an
            // object the contracts do not say how to build, so it looks for the constructor by
            // reflection and throws when it finds none: every candidate then fails inside the
            // test's own code, and the run parks after three of four tasks were built. Asked once
            // to call the contract's members directly (or to say what is missing); only when the
            // three checks above are already sound. See AcceptanceTestReflection.
            AcceptanceTestReflection.Check reflection = vocabulary.ok() && selfImplemented.ok()
                    && reach.ok()
                ? AcceptanceTestReflection.check(repoRoot, written)
                : AcceptanceTestReflection.Check.CLEAN;
            if (!reflection.ok()) {
                log.warn("Test author's tests for task '{}' find the project's own members by "
                    + "reflection: {} — asking it once to call them directly", task.title(),
                    reflection.findings().stream().map(AcceptanceTestReflection.Use::evidence)
                        .toList());
                removeAll(repoRoot, written);
                String second = askAgain(system, user, response,
                    AcceptanceTestReflection.reask(reflection));
                LlmTestFiles corrected;
                try {
                    corrected = LlmJson.parse(mapper, second, LlmTestFiles.class);
                } catch (IOException notJson) {
                    log.warn("Test author's reply to the reflection re-ask for task '{}' was not "
                        + "valid JSON: {}", task.title(), notJson.getMessage());
                    corrected = null;
                }
                if (corrected != null && corrected.files != null && !corrected.files.isEmpty()) {
                    parsed = corrected;
                    written = write(repoRoot, protectedDir, parsed, journeys);
                    // Held to every rule, not just this one: a test that stops reflecting by
                    // inventing a type nobody delivers has swapped one broken test for another.
                    vocabulary = vocabularyOf(repoRoot, design, written, planTasks, task);
                    selfImplemented = vocabulary.ok()
                        ? SelfImplementedContract.check(repoRoot, design, written, planTasks)
                        : SelfImplementedContract.Check.CLEAN;
                    reach = vocabulary.ok() && selfImplemented.ok()
                        ? AcceptanceTestReach.check(repoRoot, browserOnly, written)
                        : AcceptanceTestReach.Check.CLEAN;
                    reflection = vocabulary.ok() && selfImplemented.ok() && reach.ok()
                        ? AcceptanceTestReflection.check(repoRoot, written)
                        : AcceptanceTestReflection.Check.CLEAN;
                } else {
                    written = write(repoRoot, protectedDir, parsed, journeys);
                }
            }

            List<Claim> claims = new ArrayList<>();
            if (parsed.wrote != null) {
                for (LlmWrote entry : parsed.wrote) {
                    if (entry != null && entry.test != null && !entry.test.isBlank()) {
                        claims.add(new Claim(entry.criterion == null ? "" : entry.criterion.strip(),
                            entry.test.strip()));
                    }
                }
            }
            log.info("Test author wrote {} file(s) for task '{}': {} — and claims {} "
                + "criterion-to-test mapping(s)", written.size(), task.title(), written, claims.size());
            // The answer given in place of a journey travels in the same map under a key no
            // path can have; a journey written as well makes it void.
            String waiver = journeys.remove(JOURNEY_WAIVER);
            Authored authored = Authored.of(List.copyOf(written), List.copyOf(claims))
                .withJourneys(List.copyOf(journeys.keySet()))
                .withJourneyWaiver(journeys.isEmpty() ? waiver : null);
            if (authored.journeyWaiver() != null) {
                log.info("Test author answered in place of a journey for task '{}': {}",
                    task.title(), authored.journeyWaiver());
            }
            if (!journeys.isEmpty()) {
                log.info("Test author wrote {} journey(s) for task '{}': {}", journeys.size(),
                    task.title(), journeys.keySet());
            }
            if (!vocabulary.ok()) {
                authored = authored.namingUndeliverableTypes(vocabulary);
            }
            if (!reach.ok()) {
                authored = authored.reachingBrowserOnlyCode(reach);
            }
            if (!reflection.ok()) {
                authored = authored.reflectingOnProjectTypes(reflection);
            }
            return selfImplemented.ok() ? authored
                : authored.implementingContractsItself(selfImplemented);
        } catch (EndpointOutage outage) {
            // Returning no files here would send the swarm out with no acceptance tests to fail
            // against, and the red-check would wave it through as the empty-suite allowance. The
            // tests are the whole basis of selection: wait for the endpoint instead.
            throw outage;
        } catch (Exception e) {
            // Carried onto the result, not just logged: a workflow that only sees NOTHING cannot
            // tell "the call failed" from "the author chose to write nothing", which is exactly
            // what used to send the park message at "check the testAuthor endpoint" for failures
            // that had nothing to do with the endpoint.
            log.warn("Test authoring failed for task '{}': {}", task.title(), e.getMessage());
            return Authored.failed("the test author's call failed: " + e.getMessage());
        }
    }

    /**
     * Sends ONE already-committed acceptance test back to its author, together with the error it
     * produced before it ever exercised a candidate, and asks for a corrected file (author decision,
     * 2026-09-05: a broken acceptance test goes back to the test author, not to a repair worker — a
     * worker may never edit this file, and the mistake here is the test's, not the candidate's).
     *
     * <p>One shot, no retries: unlike {@link #authorTests}, which asks twice for a malformed or
     * empty reply, this is already the bounded, once-per-task attempt {@code SwarmEngineImpl}
     * granted before it would ever raise the fault a second time. A reply that will not parse or
     * names no files is a failed repair, reported as such — the caller parks on it.
     *
     * @param existingPath    the file's repo-relative path, exactly as {@link Task#authoredTestPaths()} names it
     * @param existingContent the file's current content, read from the run's tests commit
     * @param errorText       what the test threw before any candidate's code ran — the same one-line-
     *                        plus-frames text a repair worker would have been shown, had this been a
     *                        candidate's fault (see {@code AcceptanceFailureAttribution})
     * @return the corrected file under the SAME path, or {@link Authored#failed} when the call itself failed
     */
    public Authored repairFailingTest(Path repoRoot, Task task, DesignDocument design,
                                      String existingPath, String existingContent,
                                      String errorText) {
        return repairFailingTest(repoRoot, task, design, existingPath, existingContent, errorText,
            "");
    }

    /**
     * As above, and told what the project's own types really declare (live harness run 54: the
     * repair of a test that could not build a {@code Store} guessed again, because nothing had
     * ever shown it the constructors the earlier tasks had since written).
     *
     * @param projectSignatures {@link TouchedProjectTypes#signatures} read off the tree the wave
     *                          was cut from; blank when nothing could be read
     */
    public Authored repairFailingTest(Path repoRoot, Task task, DesignDocument design,
                                      String existingPath, String existingContent,
                                      String errorText, String projectSignatures) {
        String protectedDir = task.acceptanceTestDir();
        try {
            String system = "You are a test author. You wrote an acceptance test for this task, and "
                + "it failed inside its OWN code before it could ever exercise the candidate — not a "
                + "test that correctly found the candidate wanting, one that never got that far. Fix "
                + "the test's setup or assertion; do not change what it proves; use only the contract "
                + "types listed below and the types this project already has. "
                + AcceptanceTestReflection.AUTHOR_RULE + " Respond ONLY with "
                + "JSON: {\"files\":[{\"path\":\"" + existingPath + "\",\"content\":\"<corrected java "
                + "source>\"}],\"wrote\":[]}";
            String user = "Task: " + task.title() + "\n" + task.instructions()
                + (design == null ? "" : "\n\nDesign contracts:\n" + ArchitectClient.designSummary(design))
                + rulesForRepair()
                + exampleFor(task, design)
                + "\n\nThe test file, " + existingPath + ":\n" + existingContent
                + TouchedProjectTypes.section(projectSignatures)
                + "\n\nYour test failed inside its own code before it could exercise the candidate: "
                + errorText + ". Fix the test's setup or assertion; do not change what it proves; "
                + "use only the contract types; reply with the same JSON object with the corrected "
                + "file.";
            // In a lookup session when one is configured (2026-10-02), otherwise - and
            // whenever the session cannot give a test - the one reply it always was.
            String response = firstReply(system, user,
                new AgentCall(repoRoot, task, design, protectedDir,
                    ArchitectClient.acceptanceWriteDir(protectedDir), List.of(),
                    BrowserOnlyCode.Survey.NONE, false).followingUpWith(testSentBack(
                        "Your test failed inside its own code before it could exercise the "
                            + "candidate: " + errorText + ". Fix the test's setup or assertion; do "
                            + "not change what it proves." + TouchedProjectTypes.section(projectSignatures),
                        Map.of(existingPath, existingContent == null ? "" : existingContent))));
            LlmTestFiles parsed;
            try {
                parsed = LlmJson.parse(mapper, response, LlmTestFiles.class);
            } catch (IOException parseFailure) {
                String description = LlmReplyBlobs.describeFailure(
                    blobs, "the test author's repair", response, parseFailure.getMessage());
                log.warn("Test repair failed for task '{}': {}", task.title(), description);
                return Authored.failed(description);
            }
            if (parsed.files == null || parsed.files.isEmpty()) {
                String description = "the test author's repair reply named no file";
                log.warn("Test repair failed for task '{}': {}", task.title(), description);
                return Authored.failed(description);
            }
            List<String> written = write(repoRoot, protectedDir, parsed);
            if (written.isEmpty()) {
                String description = "the test author's repair wrote outside the protected "
                    + "directory " + protectedDir;
                log.warn("Test repair failed for task '{}': {}", task.title(), description);
                return Authored.failed(description);
            }
            log.info("Test author repaired {} for task '{}'", written, task.title());
            return Authored.of(List.copyOf(written), List.of());
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            log.warn("Test repair failed for task '{}': {}", task.title(), e.getMessage());
            return Authored.failed("the test author's repair call failed: " + e.getMessage());
        }
    }

    /**
     * What the test author answered about a suspect test.
     *
     * @param answered    false when the call failed or its reply could not be used; the caller
     *                    then carries on as if nobody had been asked
     * @param testIsWrong true when the author says the test, not the candidates, is wrong
     * @param reason      the author's reason, in its own words; the failure when not answered
     * @param corrected   the corrected file, written into the tree given; null unless the test
     *                    is wrong
     */
    public record Reviewed(boolean answered, boolean testIsWrong, String reason,
                           Authored corrected) {

        static Reviewed unanswered(String why) {
            return new Reviewed(false, false, why, null);
        }
    }

    /**
     * Asks the author of an acceptance test which side is wrong (owner decision, 2026-10-04,
     * harness run 79): every independently written first candidate of the task compiled and
     * failed the same test method(s) with the same assertion. Not a repair call - the author may
     * answer that the test is right, and its reason is then what the repair workers are told.
     *
     * <p>The author is shown the test, the design, what each candidate failed with and each
     * candidate's change. With a lookup session configured the review runs in one - the
     * conversation that wrote the test when it is still kept - so an author that finds its test
     * wrong can look up how to put it right and compile the correction ({@link
     * #reviewInSession}; run 85). Without one it is one reply, as it was. No retry either way:
     * an unusable answer is {@link Reviewed#unanswered}, never a reason to park.
     *
     * @param evidence {@code TestRepairNeeded.suspectEvidence()}
     */
    public Reviewed reviewSuspectTest(Path repoRoot, Task task, DesignDocument design,
                                      String existingPath, String existingContent,
                                      String evidence, String projectSignatures) {
        String protectedDir = task.acceptanceTestDir();
        try {
            String brief = "You are a test author. You wrote an acceptance test for this task. "
                + "Several workers then wrote the code independently of each other, and every "
                + "one of them compiled and failed your test in the same way. Either they all "
                + "made the same mistake, or your test expects something the design and the "
                + "existing code do not give. Decide which, from the design, the existing code's "
                + "signatures and what the candidates did - not from how many they are. Typical "
                + "ways a test is wrong: it assumes data it set up differs where the existing "
                + "code makes it equal, it expects an order or a value the design does not "
                + "state, or it checks more than the criterion asks. If the test is wrong, "
                + "correct it so that it still proves the same criterion and still fails on "
                + "code that does not implement it. If the test is right, change nothing and "
                + "say what the candidates got wrong. "
                + AcceptanceTestReflection.AUTHOR_RULE;
            String system = brief + " Respond ONLY with JSON: "
                + "{\"testIsWrong\":true|false,\"reason\":\"<two or three sentences>\","
                + "\"files\":[{\"path\":\"" + existingPath + "\",\"content\":\"<corrected java "
                + "source>\"}],\"wrote\":[]} - \"files\" is empty when the test is right.";
            String user = "Task: " + task.title() + "\n" + task.instructions()
                + (design == null ? "" : "\n\nDesign contracts:\n" + ArchitectClient.designSummary(design))
                + rulesForRepair()
                + "\n\nThe test file, " + existingPath + ":\n" + existingContent
                + TouchedProjectTypes.section(projectSignatures)
                + "\n\nWHAT HAPPENED:\n" + (evidence == null ? "" : evidence);
            // In the author's own session when there is one (run 85): a one-reply review cannot
            // look anything up, so it could say the test was wrong and not how to put it right.
            LookupAgent agent = lookupAgent;
            if (agent != null && protectedDir != null) {
                Reviewed inSession = reviewInSession(agent, repoRoot, task, design, protectedDir,
                    brief, user, existingPath, existingContent, evidence, projectSignatures);
                if (inSession != null) {
                    return inSession;
                }
            }
            String response = oneShot(system, user, LlmTestReview.class);
            LlmTestReview parsed;
            try {
                parsed = LlmJson.parse(mapper, response, LlmTestReview.class);
            } catch (IOException parseFailure) {
                return Reviewed.unanswered(LlmReplyBlobs.describeFailure(
                    blobs, "the test author's review", response, parseFailure.getMessage()));
            }
            if (parsed == null || parsed.testIsWrong == null) {
                return Reviewed.unanswered("the test author's review did not say which side is "
                    + "wrong");
            }
            String reason = parsed.reason == null || parsed.reason.isBlank()
                ? "(it gave no reason)" : parsed.reason.strip();
            if (!parsed.testIsWrong) {
                log.info("Test author reviewed the test of task '{}' and stands by it: {}",
                    task.title(), reason);
                return new Reviewed(true, false, reason, null);
            }
            if (parsed.files == null || parsed.files.isEmpty()) {
                return Reviewed.unanswered("the test author said the test is wrong (" + reason
                    + ") but handed in no corrected file");
            }
            List<String> written = write(repoRoot, protectedDir, parsed);
            if (written.isEmpty()) {
                return Reviewed.unanswered("the test author said the test is wrong (" + reason
                    + ") but its correction was outside the protected directory " + protectedDir);
            }
            log.info("Test author reviewed the test of task '{}', found it wrong and corrected "
                + "{}: {}", task.title(), written, reason);
            return new Reviewed(true, true, reason, Authored.of(List.copyOf(written), List.of()));
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            log.warn("Test review failed for task '{}': {}", task.title(), e.getMessage());
            return Reviewed.unanswered("the test author's review call failed: " + e.getMessage());
        }
    }

    /** How a review is answered in a session: both endings are a tool call, never a phrase. */
    static final String REVIEW_HOW = "\n\nHOW TO ANSWER. You have your lookup tools and "
        + "compile_test. Look up whatever the question turns on before you decide - how the "
        + "harness or framework the test uses is set up in its own tests and examples, what the "
        + "existing code gives. A correction you cannot write without guessing is a lookup you "
        + "have not made yet: make it.\n"
        + "- THE TEST IS WRONG: write the corrected test (the same criterion, still failing on "
        + "code that does not implement it), give it to compile_test until it is HEALTHY, then "
        + "call report_done with two or three sentences saying what was wrong with the test.\n"
        + "- THE TEST IS RIGHT: compile nothing, and call report_done with two or three sentences "
        + "saying what the candidates got wrong; the workers repairing them are told exactly "
        + "that.";

    static final String REVIEW_FINISH_ADVICE = "Stop looking things up. If the test is wrong, "
        + "give the corrected test to compile_test and call report_done with what was wrong. If "
        + "it is right, call report_done with what the candidates got wrong.";

    /**
     * The review of a suspect test in a lookup session (run 85, 2026-10-04): the conversation
     * that wrote the test when it is still kept (section 54), a new session otherwise, with the
     * lookups and {@code compile_test} either way. What it came to is read from what the session
     * DID, not from its words: a draft given to {@code compile_test} is a correction, a hand-in
     * with nothing compiled is the author standing by its test, and a session that ended with
     * neither is no usable answer.
     *
     * @return null when no session could run at all; the caller then asks in one reply
     */
    private Reviewed reviewInSession(LookupAgent agent, Path repoRoot, Task task,
                                     DesignDocument design, String protectedDir, String brief,
                                     String user, String existingPath, String existingContent,
                                     String evidence, String projectSignatures) {
        String writeDir = ArchitectClient.acceptanceWriteDir(protectedDir);
        AgentCall call = new AgentCall(repoRoot, task, design, protectedDir, writeDir, List.of(),
            BrowserOnlyCode.Survey.NONE, false);
        DraftCompiler compiler = draftCompiler.get(); // on the caller's thread, as agentReply
        java.util.function.Function<Map<String, String>, TestAuthorTools.Verdict> check =
            files -> checkDraft(call, files, compiler);
        TestAuthorTools[] own = new TestAuthorTools[1];
        LookupAgent.Outcome outcome = null;
        try {
            KeptConversations.Held prior = kept.take(keyOf(task));
            if (prior != null && prior.tools() instanceof TestAuthorTools again) {
                again.nextRound(check);
                outcome = agent.resume(client, prior.conversation(),
                    "THIS IS THE SAME CONVERSATION, NOT A NEW TASK. Everything you looked up "
                        + "above is still true. The test you handed in is now in question. "
                        + brief + "\n\nThe test file, " + existingPath + ", as it stands:\n"
                        + (existingContent == null ? "" : existingContent)
                        + TouchedProjectTypes.section(projectSignatures)
                        + "\n\nWHAT HAPPENED:\n" + (evidence == null ? "" : evidence)
                        + REVIEW_HOW).orElse(null);
                own[0] = outcome == null ? null : again;
            } else if (prior != null) {
                prior.conversation().close();
            }
            if (outcome == null) {
                outcome = agent.run(client, new LookupAgent.Ask("test author",
                    agentSystem(brief, writeDir), user + REVIEW_HOW,
                    LookupAgent.Limits.configured(), REVIEW_FINISH_ADVICE,
                    design == null ? List.of() : design.contracts(),
                    session -> {
                        own[0] = new TestAuthorTools(session, protectedDir, writeDir, check);
                        return own[0].bindings();
                    }));
            }
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (RuntimeException e) {
            log.warn("The test author's review session for task '{}' failed ({}); asking in one "
                + "reply without tools", task.title(), e.toString());
            return null;
        }
        TestAuthorTools tools = own[0];
        if (outcome.conversation() != null) {
            if (tools != null) {
                kept.keep(keyOf(task), new KeptConversations.Held(outcome.conversation(), tools));
            } else {
                outcome.conversation().close();
            }
        }
        if (tools == null || outcome.neverRan()) {
            return null;
        }
        String said = tools.wrote().isBlank() ? oneLineOf(outcome.finalText()) : tools.wrote().strip();
        String reason = said.isBlank() ? "(it gave no reason)" : said;
        if (tools.hasDraft()) {
            List<String> written;
            try {
                written = write(repoRoot, protectedDir, filesOf(tools));
            } catch (IOException e) {
                return Reviewed.unanswered("the test author's correction could not be written: "
                    + e.getMessage());
            }
            if (written.isEmpty()) {
                return Reviewed.unanswered("the test author said the test is wrong (" + reason
                    + ") but its correction was outside the protected directory " + protectedDir);
            }
            log.info("Test author reviewed the test of task '{}' in its session ({} turn(s), {} "
                + "compile(s)), found it wrong and corrected {}: {}", task.title(),
                outcome.turns(), tools.compiles(), written, reason);
            return new Reviewed(true, true, reason, Authored.of(List.copyOf(written), List.of()));
        }
        if (tools.handedIn()) {
            log.info("Test author reviewed the test of task '{}' in its session ({} turn(s)) and "
                + "stands by it: {}", task.title(), outcome.turns(), reason);
            return new Reviewed(true, false, reason, null);
        }
        return Reviewed.unanswered("the test author's review session ended after "
            + outcome.turns() + " turn(s)" + outcome.stopped().map(k -> " (" + k.name() + ")")
                .orElse("") + " with neither a corrected test nor a hand-in; it ended on: "
            + reason);
    }

    /** The reply to {@link #reviewFailedJourney} when it is asked in one reply. */
    public static class LlmJourneyReview {
        public Boolean journeyIsWrong;
        public String reason;
        public String journey;
    }

    /**
     * What a review of a failed journey came to (section 70, after live run 95). Decided from
     * what the author handed in - the side it named, and whether {@code check_journey} kept a
     * changed journey - and never from the words of its reason: run 95's author wrote "the
     * journey was wrong", kept no journey, and was recorded as standing by it.
     */
    public enum JourneyVerdict {
        /** The author kept a changed journey; whether it is taken is decided in a browser. */
        CORRECTED,
        /** The author says the journey is right and the screen is wrong, with its reason. */
        STANDS_BY,
        /**
         * The author says the journey is wrong and, asked once more in the same conversation,
         * still handed in no corrected journey. No worker is started on such a journey.
         */
        COULD_NOT_CORRECT,
        /** The call failed, or its answer named no side and kept nothing. */
        UNANSWERED
    }

    /**
     * What the test author answered about a journey that failed in the browser.
     *
     * @param verdict   what the review came to
     * @param reason    the author's reason, in its own words, passed on and never interpreted;
     *                  the failure when not answered
     * @param corrected the corrected journey's YAML; null unless {@link JourneyVerdict#CORRECTED}.
     *                  Nothing is written here: whether it is taken is the caller's to decide,
     *                  in a browser
     */
    public record JourneyReviewed(JourneyVerdict verdict, String reason, String corrected) {

        static JourneyReviewed unanswered(String why) {
            return new JourneyReviewed(JourneyVerdict.UNANSWERED, why, null);
        }

        /** False when the call failed or its reply could not be used. */
        public boolean answered() {
            return verdict != JourneyVerdict.UNANSWERED;
        }

        /** True when the author says the journey, not the screen, is the wrong side. */
        public boolean journeyIsWrong() {
            return verdict == JourneyVerdict.CORRECTED
                || verdict == JourneyVerdict.COULD_NOT_CORRECT;
        }
    }

    /**
     * How many model calls a review of a failed journey may make, the one re-ask included.
     *
     * <p>Live run 95's review made 22 calls in about 55 minutes (446,265 input and 71,667 output
     * tokens) under the ordinary stop of 120 turns, and never called {@code check_journey}. What
     * a review needs is small: two turns of lookups (the built screen's texts), three of
     * {@code check_journey} (a draft, one objection, the one question about a text nobody
     * enters), one to answer - six. Two more for the re-ask when it calls the journey wrong and
     * hands in nothing, and two to spare: ten. The countdown tells the author so as they run
     * out. {@code swarmcoder.roles.journeyReviewTurns} replaces it.
     */
    static final int JOURNEY_REVIEW_TURNS =
        Integer.getInteger("swarmcoder.roles.journeyReviewTurns", 10);

    /**
     * The lookups one review may make before further ones are refused: about two a turn for
     * the turns that are not a draft or the answer. Run 95's made 56, fourteen of them listings
     * of the same folders. {@code swarmcoder.roles.journeyReviewLookups} replaces it.
     */
    static final int JOURNEY_REVIEW_LOOKUPS =
        Integer.getInteger("swarmcoder.roles.journeyReviewLookups", 12);

    static final String JOURNEY_REVIEW_BRIEF = "You are a test author. A journey - what a "
        + "person does in a browser - was written for this story BEFORE its screen existed, so "
        + "the names in its selectors were a guess. The story is now built and merged, the "
        + "application was started, and a real browser made the journey from the entry page. "
        + "It failed at the step shown below. ONE QUESTION is asked of you: is the journey "
        + "wrong, or is the screen wrong? Decide it from the criteria, the failing step and "
        + "what the page showed at that step - its elements by role and accessible name, its "
        + "fields' placeholders and its visible text are below, read by the browser. Nothing "
        + "else is asked: write no other test, and do not study how the code behind the screen "
        + "works. Ways a journey is wrong: it finds an element by words the criteria do not "
        + "fix, where the screen names the same element differently; it expects to see data no "
        + "step of it enters, on an application that starts with no data of its own; it leaves "
        + "out a step a person needs; it clicks an option of a drop-down list, or looks on the "
        + "page for what a field or a list holds, where the steps for that are select and "
        + "expectValue. Ways the screen is wrong: what a criterion asks for is "
        + "not on it, cannot be reached from the entry page, or does nothing. If the journey "
        + "is wrong, correct it so that it proves the same criteria: it keeps every use it "
        + "makes of the screen (no fewer fill, select, click and press steps; one select "
        + "stands for the click that opens a list and the one that chooses), it must still FAIL on "
        + "the application as it was before the story, and it must pass on the built one - "
        + "both are tried in a browser, and a correction that passes by asking for less is "
        + "refused. If the journey is right, change nothing and say what the screen got wrong: "
        + "the workers repairing it are told exactly that.";

    static final String JOURNEY_REVIEW_HOW = "\n\nHOW TO ANSWER. This is a short review: "
        + JOURNEY_REVIEW_TURNS + " turns in all, and what the question turns on is above. The "
        + "screen was built by this run. The project your lookups read is the one from BEFORE "
        + "the story, so the screen's code is not in it - never conclude from them that the "
        + "screen was not built. texts_of <Type> is the exception: in this review it answers "
        + "from the built application.\n"
        + "THE STEPS A JOURNEY HAS - all of them; nothing in the project describes them, so do "
        + "not look them up:\n" + JourneyFile.VOCABULARY + JourneyFile.CHOOSING + "\n"
        + "- THE JOURNEY IS WRONG: give the corrected journey, complete, to check_journey at "
        + "the same path until it answers VALID, then call report_done with verdict "
        + "JOURNEY_WRONG and one or two sentences saying what was wrong with it. Saying it is "
        + "wrong without a corrected journey that check_journey kept is not an answer.\n"
        + "- THE JOURNEY IS RIGHT: give check_journey nothing, and call report_done with "
        + "verdict SCREEN_WRONG and one or two sentences saying what the screen got wrong.";

    static final String JOURNEY_REVIEW_FINISH_ADVICE = "Stop looking things up. If the journey "
        + "is wrong, give the corrected journey to check_journey and call report_done with "
        + "verdict JOURNEY_WRONG. If it is right, call report_done with verdict SCREEN_WRONG.";

    /** Said once, in the same conversation, to an author that blamed its journey and kept none. */
    static String journeyCorrectionMissing(String path) {
        return "THIS IS THE SAME REVIEW, NOT A NEW TASK. Your answer did not say the screen is "
            + "wrong, and check_journey has kept no corrected journey at `" + path + "`. A "
            + "journey its author calls wrong is not given to the workers, and nothing but a "
            + "corrected file replaces it - no other kind of test does. Give the complete "
            + "corrected journey to check_journey at `" + path + "` now, until it answers "
            + "VALID, then call report_done with verdict JOURNEY_WRONG. If the journey is right "
            + "after all and the screen is wrong, call report_done with verdict SCREEN_WRONG. "
            + "Look nothing else up.";
    }

    /** As {@link #reviewFailedJourney(Path, Path, Task, DesignDocument, List, String, String, String)}, with the task's own criteria and no built tree. */
    public JourneyReviewed reviewFailedJourney(Path repoRoot, Task task, DesignDocument design,
                                               String journeyPath, String journeyContent,
                                               String whatHappened) {
        return reviewFailedJourney(repoRoot, null, task, design, null, journeyPath,
            journeyContent, whatHappened);
    }

    /**
     * Asks the author of a journey which side is wrong (owner's decision, 2026-10-08, after
     * live run 93): the journey failed in a real browser after the last merge. Before this a
     * failed journey went straight to a worker repair round, and run 93's could not be repaired
     * by any worker - it looked for a text box by a name the criteria did not fix, and expected
     * a record nobody had entered. Not a repair call: the author may answer that the journey
     * is right, and its reason is then what the repair workers are told.
     *
     * <p><b>Section 70, after live run 95</b> - the first real review. It cost 22 calls and
     * came to nothing usable, for reasons that were all in how it was asked:
     *
     * <ul>
     *   <li>its lookups read the project as it was before the story, where the screen's type
     *       did not exist, and nothing said so: the author concluded the screen had not been
     *       built. {@code texts_of} now answers from {@code builtTree}, and the opening says
     *       what the other lookups read;</li>
     *   <li>the journey was claimed by a task that writes no screen, and the opening led with
     *       that task, so the author set about writing a server test. The owner is now a task
     *       that writes the screen ({@code JourneysOfAPlan.settleOwners}), and the opening
     *       gives the story's criteria, not one task's;</li>
     *   <li>it ran under the ordinary safety stops (120 turns). It now has
     *       {@link #JOURNEY_REVIEW_TURNS} turns and {@link #JOURNEY_REVIEW_LOOKUPS} lookups;</li>
     *   <li>its answer was prose, and "nothing was checked" was read as "stands by it". The
     *       answer is now a verdict of two fixed words with what {@code check_journey} kept
     *       ({@link JourneyVerdict}), and an author that blames its journey and keeps none is
     *       asked once more in the same conversation.</li>
     * </ul>
     *
     * <p>Always a session of its own, never the kept conversation that wrote the journey: that
     * conversation's safety stops were fixed when it opened and count across all of it, so a
     * review inside it could not be bounded. Everything the review needs is in its opening.
     * Without a lookup agent, one reply - and one more when it blames the journey and gives
     * none.
     *
     * @param repoRoot      a tree of the project as it was before the story (the run's tests
     *                      worktree); only read, to know which texts the project already holds
     * @param builtTree     the merged tree the browser used, for {@code texts_of}; null when it
     *                      cannot be read
     * @param storyCriteria the criteria of the story's plan, as text; null or empty to use the
     *                      task's own
     * @param whatHappened  the failing step, what the browser said and what the page showed
     */
    public JourneyReviewed reviewFailedJourney(Path repoRoot, Path builtTree, Task task,
                                               DesignDocument design, List<String> storyCriteria,
                                               String journeyPath, String journeyContent,
                                               String whatHappened) {
        String path = journeyPath.replace((char) 92, '/');
        try {
            List<String> criteria = storyCriteria != null && !storyCriteria.isEmpty()
                ? storyCriteria
                : task.criteria() == null ? List.of()
                    : task.criteria().stream().map(criterion -> criterion.text()).toList();
            String user = "The task that owns the journey: " + task.title() + "\n"
                + task.instructions()
                + (task.writeSet() == null || task.writeSet().isEmpty() ? ""
                    : "\nIt may write: " + String.join(", ",
                        new java.util.TreeSet<>(task.writeSet())))
                + (criteria.isEmpty() ? "" : "\n\nThe story's criteria:\n"
                    + String.join("\n", criteria.stream().map(text -> "- " + text).toList()))
                + (design == null ? "" : "\n\nDesign contracts:\n"
                    + ArchitectClient.designSummary(design))
                + "\n\nThe journey, " + path + ":\n" + journeyContent
                + "\n\nWHAT HAPPENED:\n" + (whatHappened == null ? "" : whatHappened);
            LookupAgent agent = lookupAgent;
            if (agent != null) {
                JourneyReviewed inSession = reviewJourneyInSession(agent, repoRoot, builtTree,
                    task, design, path, journeyContent, user);
                if (inSession != null) {
                    return inSession;
                }
            }
            String system = JOURNEY_REVIEW_BRIEF + " Respond ONLY with JSON: "
                + "{\"journeyIsWrong\":true|false,\"reason\":\"<one or two sentences>\","
                + "\"journey\":\"<the complete corrected YAML; empty when the journey is "
                + "right>\"}";
            LlmJourneyReview parsed = journeyReviewReply(system, user);
            if (parsed == null || parsed.journeyIsWrong == null) {
                return JourneyReviewed.unanswered("the test author's review did not say which "
                    + "side is wrong");
            }
            String reason = reasonOf(parsed.reason);
            if (!parsed.journeyIsWrong) {
                log.info("Test author reviewed the journey of task '{}' and stands by it: {}",
                    task.title(), reason);
                return new JourneyReviewed(JourneyVerdict.STANDS_BY, reason, null);
            }
            if (!isACorrection(parsed.journey, journeyContent)) {
                // Said once more (section 70): the journey is disowned and none replaces it.
                log.warn("Test author called the journey of task '{}' wrong and gave no "
                    + "corrected journey; asking once more", task.title());
                LlmJourneyReview again = journeyReviewReply(system, user
                    + "\n\nYOUR FIRST REPLY said the journey is wrong (" + reason + ") and "
                    + "held no corrected journey. " + journeyCorrectionMissing(path)
                    + " Reply with the same JSON; `journey` holds the complete corrected YAML.");
                if (again != null && Boolean.FALSE.equals(again.journeyIsWrong)) {
                    return new JourneyReviewed(JourneyVerdict.STANDS_BY, reasonOf(again.reason),
                        null);
                }
                if (again == null || !isACorrection(again.journey, journeyContent)) {
                    log.warn("Test author calls the journey of task '{}' wrong and, asked "
                        + "twice, handed in no corrected journey: {}", task.title(), reason);
                    return new JourneyReviewed(JourneyVerdict.COULD_NOT_CORRECT, reason, null);
                }
                parsed = again;
            }
            log.info("Test author reviewed the journey of task '{}', found it wrong and "
                + "corrected it: {}", task.title(), reason);
            return new JourneyReviewed(JourneyVerdict.CORRECTED, reason, parsed.journey);
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            log.warn("Journey review failed for task '{}': {}", task.title(), e.getMessage());
            return JourneyReviewed.unanswered("the test author's review call failed: "
                + e.getMessage());
        }
    }

    /** One reply to a journey review, parsed; null when it could not be read. */
    private LlmJourneyReview journeyReviewReply(String system, String user) throws Exception {
        String response = oneShot(system, user, LlmJourneyReview.class);
        try {
            return LlmJson.parse(mapper, response, LlmJourneyReview.class);
        } catch (IOException parseFailure) {
            log.warn("{}", LlmReplyBlobs.describeFailure(blobs, "the test author's review of "
                + "its journey", response, parseFailure.getMessage()));
            return null;
        }
    }

    private static String reasonOf(String given) {
        String flat = oneLineOf(given);
        return flat.isBlank() ? "(it gave no reason)" : flat;
    }

    /** True when {@code journey} is a text and not the journey that failed, given again. */
    private static boolean isACorrection(String journey, String original) {
        return journey != null && !journey.isBlank() && (original == null
            || !journey.replace("\r\n", "\n").strip()
                .equals(original.replace("\r\n", "\n").strip()));
    }

    /** @return null when no session could run at all; the caller then asks in one reply */
    private JourneyReviewed reviewJourneyInSession(LookupAgent agent, Path repoRoot,
                                                   Path builtTree, Task task,
                                                   DesignDocument design, String path,
                                                   String content, String user) {
        // The directory the journey is in. The task that owns a journey need not be the one
        // whose tests it was written with, so its own acceptance directory may be another.
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/'))
            : task.acceptanceTestDir() == null ? "" : task.acceptanceTestDir();
        java.util.function.Predicate<String> held =
            com.swarmcoder.knowledge.ProjectTexts.heldIn(repoRoot);
        java.util.function.Function<String, String> built = builtTree == null ? null
            : type -> com.swarmcoder.knowledge.TreeQueries.textsOfIn(builtTree, type);
        var onStartTree = startTree.get(); // on the caller's thread
        TestAuthorTools[] own = new TestAuthorTools[1];
        LookupAgent.Outcome outcome;
        TestAuthorTools.ReviewOutcome first = null;
        String saidFirst = "";
        try {
            outcome = agent.run(client, new LookupAgent.Ask("test author",
                JOURNEY_REVIEW_BRIEF, user + JOURNEY_REVIEW_HOW,
                new LookupAgent.Limits(JOURNEY_REVIEW_TURNS, JOURNEY_REVIEW_LOOKUPS, 1, 2),
                JOURNEY_REVIEW_FINISH_ADVICE,
                design == null ? List.of() : design.contracts(),
                session -> {
                    own[0] = new TestAuthorTools(session, dir, dir,
                        files -> new TestAuthorTools.Verdict(false, ""))
                        .reviewingAJourney(path, content, built).knowingTheProjectsTexts(held)
                        .knowingTheStartPage(onStartTree);
                    return own[0].bindings();
                }));
            if (own[0] == null || outcome.neverRan()) {
                if (outcome.conversation() != null) {
                    outcome.conversation().close();
                }
                return null;
            }
            first = own[0].reviewOutcome();
            saidFirst = own[0].wrote();
            // It blamed the journey, or named no side, and kept no corrected journey: said
            // once more in the same conversation (section 70). A journey its author disowns
            // is not given to the workers, so this is the last chance to get its correction.
            if (own[0].reviewLacksACorrection() && outcome.conversation() != null) {
                log.warn("Test author ended its review of the journey of task '{}' without "
                    + "naming the screen as wrong and without a corrected journey; asking for "
                    + "the corrected file in the same conversation", task.title());
                own[0].handInAgain();
                LookupAgent.Outcome again = agent.resume(client, outcome.conversation(),
                    journeyCorrectionMissing(path)).orElse(null);
                if (again != null) {
                    outcome = again;
                }
            }
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (RuntimeException e) {
            log.warn("The test author's journey review session for task '{}' failed ({}); "
                + "asking in one reply without tools", task.title(), e.toString());
            return null;
        }
        TestAuthorTools tools = own[0];
        if (outcome.conversation() != null) {
            outcome.conversation().close(); // a review is never continued
        }
        // What it came to, from what it handed in. An answer the re-ask did not replace stands.
        TestAuthorTools.ReviewOutcome came = tools.reviewOutcome();
        if (came == TestAuthorTools.ReviewOutcome.UNANSWERED && first != null) {
            came = first;
        }
        String said = !tools.wrote().isBlank() ? tools.wrote()
            : !saidFirst.isBlank() ? saidFirst : outcome.finalText();
        String reason = reasonOf(said);
        switch (came) {
            case CORRECTED -> {
                log.info("Test author reviewed the journey of task '{}' in a session ({} "
                    + "turn(s)), found it wrong and corrected it: {}", task.title(),
                    outcome.turns(), reason);
                return new JourneyReviewed(JourneyVerdict.CORRECTED, reason, tools.correction());
            }
            case STANDS_BY -> {
                log.info("Test author reviewed the journey of task '{}' in a session ({} "
                    + "turn(s)) and stands by it - the screen is wrong: {}", task.title(),
                    outcome.turns(), reason);
                return new JourneyReviewed(JourneyVerdict.STANDS_BY, reason, null);
            }
            case COULD_NOT_CORRECT -> {
                log.warn("Test author reviewed the journey of task '{}' in a session ({} "
                    + "turn(s)), calls it wrong and handed in no corrected journey, asked "
                    + "twice: {}", task.title(), outcome.turns(), reason);
                return new JourneyReviewed(JourneyVerdict.COULD_NOT_CORRECT, reason, null);
            }
            default -> {
                return JourneyReviewed.unanswered("the test author's review session ended "
                    + "after " + outcome.turns() + " turn(s)"
                    + outcome.stopped().map(k -> " (" + k.name() + ")").orElse("")
                    + " with neither a corrected journey nor a hand-in that names which side "
                    + "is wrong; it ended on: " + reason);
            }
        }
    }

    private static String oneLineOf(String text) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 600 ? flat : flat.substring(0, 600) + "...";
    }

    /**
     * Sends a task's acceptance test file(s) back to their author because the mechanical red-check
     * found them BROKEN, not red: they do not compile for a reason no task in this plan can ever
     * fix — a type or package nobody delivers and no write set covers ({@link
     * com.swarmcoder.verify.TypeDeliverability}) — rather than because the code they need has not
     * been written yet (author decision, 2026-09-05, after harness run 26; see
     * {@link BrokenAcceptanceTest}).
     *
     * <p>One shot, no internal retries — the same bounded allowance {@link #repairFailingTest}
     * gets for a test that fails at runtime, and {@link AcceptanceTestVocabulary} gets for a test
     * naming an unagreed type: asked once, with the concrete reason and the module's own
     * classpath, and a second miss is the caller's to park on.
     *
     * @param existingFiles this task's authored test files, repo-relative path to current content,
     *                      exactly as committed on the run's tests ref
     * @param reaskMessage  {@link BrokenAcceptanceTest#reask}, naming what is undeliverable and
     *                      what this module can actually see
     * @return the corrected file(s), or {@link Authored#failed} when the call itself failed
     */
    public Authored repairBrokenTest(Path repoRoot, Task task, DesignDocument design,
                                     Map<String, String> existingFiles, String reaskMessage) {
        return repairWith(repoRoot, task, design, existingFiles, reaskMessage,
            "You are a test author. The acceptance test(s) you wrote for this task "
                + "do not compile for a reason described below — not because the code they need has "
                + "not been written yet. Fix them so they prove the SAME checks using only what this "
                + "build can actually see; do not change what a check proves, only how the test "
                + "reaches it. Respond ONLY with JSON: {\"files\":[{\"path\":\"<one of the paths "
                + "below, unchanged>\",\"content\":\"<corrected java source>\"}],\"wrote\":[]}");
    }

    /**
     * Sends a task's acceptance test file(s) back to their author because they RAN and reached code
     * that can only run in a browser (harness run 37, 2026-09-25) — found by the red-check before
     * any candidate existed, or on every candidate at verification. Same one-shot shape as
     * {@link #repairBrokenTest}; only the framing differs, because "does not compile" would be a
     * false description of a test that compiled and then hit a native method the JVM does not have.
     *
     * @param reaskMessage {@link AcceptanceTestReach#reaskForFailure}, carrying the failure itself
     */
    public Authored repairUnrunnableTest(Path repoRoot, Task task, DesignDocument design,
                                         Map<String, String> existingFiles, String reaskMessage) {
        return repairWith(repoRoot, task, design, existingFiles, reaskMessage,
            "You are a test author. The acceptance test(s) you wrote for this task "
                + "compile, but they call code that can only run in a web browser, so they can "
                + "never pass on the plain JVM they run on — the reason and the failure are below. "
                + "Fix them so they prove the SAME checks through code that runs on the JVM; do "
                + "not change what a check proves, only how the test reaches it. Respond ONLY with "
                + "JSON: {\"files\":[{\"path\":\"<one of the paths below, unchanged>\",\"content\":"
                + "\"<corrected java source>\"}],\"wrote\":[]}");
    }

    /**
     * Sends a task's acceptance test file(s) back to their author because they compile and then die
     * before any code under test runs — a server or container the test started, failing in its own
     * start-up (live harness run 51, 2026-09-30; see {@code BrokenAtStartup}). Same one-shot shape
     * as {@link #repairBrokenTest}.
     *
     * @param reaskMessage {@link AcceptanceTestStartup#reask}, carrying the failure itself
     */
    public Authored repairStartupTest(Path repoRoot, Task task, DesignDocument design,
                                      Map<String, String> existingFiles, String reaskMessage) {
        return repairWith(repoRoot, task, design, existingFiles, reaskMessage,
            "You are a test author. The acceptance test(s) you wrote for this task compile, but "
                + "they die while starting an application server or container, before any code "
                + "under test runs, so they can never pass — the failure is below. If this "
                + "project's rules name a test harness, use exactly that harness as the rules "
                + "describe; otherwise acceptance tests call the project's own classes directly "
                + "(construct the service or store, call its methods) and do not boot the "
                + "application server or a DI/CDI container. Fix them so they prove the SAME checks that way; do not change what "
                + "a check proves, only how the test reaches it. Respond ONLY with JSON: "
                + "{\"files\":[{\"path\":\"<one of the paths below, unchanged>\",\"content\":"
                + "\"<corrected java source>\"}],\"wrote\":[]}");
    }

    /**
     * Sends a task's acceptance test file(s) back to their author because their own code breaks a
     * standing rule of the project (live harness runs 56 and 58, 2026-10-01; see
     * {@link TestsVersusRules}). Same one-shot shape as {@link #repairBrokenTest}; only the
     * framing differs, because nothing here failed to compile.
     *
     * @param reaskMessage {@link TestsVersusRules#reask}: each conflict with its offending line
     *                     and the rule in full
     */
    public Authored repairRuleBreakingTest(Path repoRoot, Task task, DesignDocument design,
                                           Map<String, String> existingFiles, String reaskMessage) {
        return repairWith(repoRoot, task, design, existingFiles, reaskMessage,
            "You are a test author. The acceptance test(s) you wrote for this task break this "
                + "project's standing rules — the conflicts, with the offending lines and the "
                + "rules in full, are below. Fix them so they prove the SAME checks the way the "
                + "rules say; do not change what a check proves, only how the test reaches it. "
                + "Respond ONLY with JSON: {\"files\":[{\"path\":\"<one of the paths below, "
                + "unchanged>\",\"content\":\"<corrected java source>\"}],\"wrote\":[]}");
    }

    private Authored repairWith(Path repoRoot, Task task, DesignDocument design,
                                Map<String, String> existingFiles, String reaskMessage,
                                String system0) {
        String system = system0 + " " + AcceptanceTestReflection.AUTHOR_RULE;
        String protectedDir = task.acceptanceTestDir();
        try {
            StringBuilder user = new StringBuilder("Task: ").append(task.title()).append('\n')
                .append(task.instructions())
                .append(established(task))
                .append(design == null ? "" : "\n\nDesign contracts:\n" + ArchitectClient.designSummary(design))
                .append(rulesForRepair())
                .append(exampleFor(task, design))
                .append("\n\n").append(reaskMessage).append("\n\nThe file(s), as currently written:\n");
            for (Map.Entry<String, String> file : existingFiles.entrySet()) {
                user.append("\n--- ").append(file.getKey()).append(" ---\n").append(file.getValue())
                    .append('\n');
            }
            // In a lookup session when one is configured (2026-10-02), otherwise - and
            // whenever the session cannot give a test - the one reply it always was.
            String response = firstReply(system, user.toString(),
                new AgentCall(repoRoot, task, design, protectedDir,
                    ArchitectClient.acceptanceWriteDir(protectedDir), List.of(),
                    BrowserOnlyCode.Survey.NONE, false).followingUpWith(
                        testSentBack(reaskMessage, existingFiles)));
            LlmTestFiles parsed;
            try {
                parsed = LlmJson.parse(mapper, response, LlmTestFiles.class);
            } catch (IOException parseFailure) {
                String description = LlmReplyBlobs.describeFailure(
                    blobs, "the test author's broken-test repair", response, parseFailure.getMessage());
                log.warn("Broken-test repair failed for task '{}': {}", task.title(), description);
                return Authored.failed(description);
            }
            if (parsed.files == null || parsed.files.isEmpty()) {
                String description = "the test author's broken-test repair reply named no file";
                log.warn("Broken-test repair failed for task '{}': {}", task.title(), description);
                return Authored.failed(description);
            }
            List<String> written = write(repoRoot, protectedDir, parsed);
            if (written.isEmpty()) {
                String description = "the test author's broken-test repair wrote outside the "
                    + "protected directory " + protectedDir;
                log.warn("Broken-test repair failed for task '{}': {}", task.title(), description);
                return Authored.failed(description);
            }
            log.info("Test author repaired broken test(s) {} for task '{}'", written, task.title());
            return Authored.of(List.copyOf(written), List.of());
        } catch (EndpointOutage outage) {
            throw outage;
        } catch (Exception e) {
            log.warn("Broken-test repair failed for task '{}': {}", task.title(), e.getMessage());
            return Authored.failed("the test author's broken-test repair call failed: " + e.getMessage());
        }
    }

    /**
     * One more turn of the authoring conversation: the same system and user messages, the author's
     * own reply, and {@code ask}. Charged and outage-handled exactly as the other re-asks are.
     */
    /** The existing-types paragraph for the author's first prompt, sized by its own room. */
    private String existingTypesFor(Path repoRoot, String about) {
        if (lookupAgent != null) {
            // A session has the project map and the tree queries; the inventory is not put in its
            // prompt (section 54, CLAUDE.md section 1).
            return "";
        }
        try {
            return ExistingProjectTypes.of(repoRoot).testAuthorBrief(about,
                com.swarmcoder.inference.MaterialBudget.of(client)
                    .chars(ExistingProjectTypes.INVENTORY_CHARS));
        } catch (RuntimeException e) {
            return ""; // nothing could be read: the prompt is what it was
        }
    }

    /**
     * {@link AcceptanceTestVocabulary#check}, after correcting what needs no model to correct: a
     * name in a package where the type is not, when exactly one real type of that simple name
     * exists (live run 68, 2026-10-02: {@code server.store.HamBookRoot} for the checkout's
     * {@code server.HamBookRoot}, re-asked for eight minutes and then parked on). The file is
     * rewritten in place and the correction logged; what remains is what the author is asked about.
     */
    private static AcceptanceTestVocabulary.Check vocabularyOf(Path repoRoot, DesignDocument design,
            List<String> written, List<Task> planTasks, Task task) {
        AcceptanceTestVocabulary.Check check =
            AcceptanceTestVocabulary.check(repoRoot, design, written, planTasks);
        if (check.ok()) {
            return check;
        }
        List<String> corrected = AcceptanceTestVocabulary.correctMisplacedNames(repoRoot, check);
        if (corrected.isEmpty()) {
            return check;
        }
        log.info("Test author's tests for task '{}' named {} type(s) in a package they are not "
            + "in, each with exactly one real type of that name — corrected in the test, no "
            + "re-ask: {}", task.title(), corrected.size(), corrected);
        return AcceptanceTestVocabulary.check(repoRoot, design, written, planTasks);
    }

    private String askAgain(String system, String user, String response, String ask)
            throws Exception {
        long askTokens = CloudGate.estimateTokens(ask);
        cloudGate.charge(askTokens);
        String second;
        try {
            second = client.as("test author").chatCompletionStream(List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user),
                    Map.of("role", "assistant", "content", response),
                    Map.of("role", "user", "content", ask)),
                LlmTestFiles.class, 0.2).collect(Collectors.joining());
        } catch (Exception e) {
            EndpointOutage outage = EndpointOutage.from(client.baseUrl(), e);
            if (outage == null) {
                throw e;
            }
            cloudGate.refund(askTokens);
            throw outage;
        }
        cloudGate.charge(CloudGate.estimateTokens(second));
        return second;
    }

    /**
     * Writes one reply's files into the task's protected tree and returns what landed. Files
     * outside it are refused, silently as far as the model is concerned and loudly in the log:
     * the protected tree is what stops a worker making its own gate pass, and it is enforced
     * mechanically here rather than trusted to the prompt.
     */
    private static List<String> write(Path repoRoot, String protectedDir, LlmTestFiles parsed)
            throws IOException {
        return write(repoRoot, protectedDir, parsed, new LinkedHashMap<>());
    }

    /**
     * The same, with the journeys among the files written too and recorded in {@code journeys}
     * (path to content) instead of in the list returned: that list is the test files the build
     * compiles, and every check made of it reads Java. A journey that is not well formed, or that
     * would replace an earlier story's, is not written.
     */
    /** The key under which {@code write} records an answer given in place of a journey. */
    private static final String JOURNEY_WAIVER = ":" + JourneyFile.NO_VISIBLE_EFFECT;

    private static List<String> write(Path repoRoot, String protectedDir, LlmTestFiles parsed,
                                      Map<String, String> journeys) throws IOException {
        List<String> written = new ArrayList<>();
        Path protectedRoot = repoRoot.resolve(protectedDir).normalize();
        for (LlmTestFile file : parsed.files) {
            if (file.path == null || file.content == null) {
                continue;
            }
            Path target = repoRoot.resolve(file.path.replace('\\', '/')).normalize();
            if (!target.startsWith(protectedRoot)) {
                log.warn("Test author tried to write outside the protected dir: {} — rejected", file.path);
                continue;
            }
            if (JourneyFile.isJourney(file.path)) {
                String path = file.path.replace('\\', '/').strip();
                String waiver = JourneyFile.waiverOf(file.content);
                if (waiver != null) {
                    journeys.put(JOURNEY_WAIVER, waiver); // recorded, never a file of the project
                    continue;
                }
                JourneyFile.Read read = JourneyFile.read(path, file.content);
                String objection = read.ok()
                    ? earlierJourneyObjection(repoRoot, path, file.content) : read.objection();
                if (objection != null) {
                    log.warn("Not writing the journey {}: {}", path,
                        objection.replaceAll("\\s*\\R\\s*", " | "));
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content);
                journeys.put(path, file.content);
                continue;
            }
            String dropped = EarlierAcceptanceTests.objection(repoRoot, file.path, file.content);
            if (dropped != null) {
                // compile_test refuses such a draft in the session; this keeps the earlier test
                // whole whichever way a file reaches here (DEVELOPER_CORRECTIONS section 59).
                log.warn("Not writing {}: {}", file.path, dropped);
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content);
            written.add(file.path);
        }
        return written;
    }

    /** Takes an attempt's files back off the tree, so a superseded test cannot be committed. */
    private static void removeAll(Path repoRoot, List<String> paths) {
        for (String path : paths) {
            try {
                if (EarlierAcceptanceTests.restore(repoRoot, path)) {
                    continue; // an earlier story's test was there first: it goes back, not away
                }
                Files.deleteIfExists(repoRoot.resolve(path.replace('\\', '/')));
            } catch (IOException | RuntimeException e) {
                log.warn("Could not remove the superseded test {}: {}", path, e.toString());
            }
        }
    }

    /**
     * What the author of a task that changes a screen is told about the journey it writes too
     * (section 63). Whether a task changes a screen is not decided here and not from the
     * criteria's wording: the workflow reads it off the build and the plan's write sets.
     *
     * @param withTools true in a lookup session, where the author has {@code check_journey} and
     *                  {@code texts_of}; false for the one reply, where the journey is one more
     *                  entry of the files it answers with
     */
    static String journeyBrief(String writeDir, boolean withTools) {
        return journeyBrief(writeDir, withTools, JourneyAsk.SCREEN);
    }

    /**
     * @param ask how the journey is asked: for a task that writes a screen itself, for one whose
     *            code the graph shows browser code using, or - in a project whose contract
     *            starts an application for a browser - for every story, with the one structured
     *            answer that may be given in place of a journey (section 64)
     */
    static String journeyBrief(String writeDir, boolean withTools, JourneyAsk ask) {
        String opening = ask.mayWaive()
            ? "THIS APPLICATION IS USED IN A BROWSER, SO THIS STORY ALSO NEEDS A JOURNEY - "
                + "unless no person using it there sees or can do anything different when this "
                + "task is done (see the end of this paragraph). "
            : ask.through().isEmpty()
                ? "THIS TASK CHANGES A SCREEN, SO IT ALSO NEEDS A JOURNEY. "
                : "CODE THAT RUNS IN THE BROWSER USES WHAT THIS TASK CHANGES ("
                    + String.join("; ", ask.through().stream().limit(3).toList()) + "), SO A PERSON SEES THE CHANGE ON A "
                    + "SCREEN AND THIS TASK ALSO NEEDS A JOURNEY. ";
        String inPlace = !ask.mayWaive() ? ""
            : " IN PLACE OF A JOURNEY, and only when no person using the application in a "
                + "browser sees or can do anything different once this task's criteria are met, "
                + (withTools ? "give check_journey " : "put in \"files\" an entry ") + writeDir
                + "/none" + JourneyFile.SUFFIX + " with the single line `"
                + JourneyFile.NO_VISIBLE_EFFECT + ": <one sentence: why nothing on any screen "
                + "differs>` as its content. It is recorded with the story and shown to the "
                + "project's owner. A criterion about what a person sees, reads, clicks or types "
                + "is never such a case.";
        return opening + "Besides the JUnit test, "
            + "write ONE journey file, " + writeDir + "/<name>" + JourneyFile.SUFFIX + ": what a "
            + "person does in a browser to use what this task's criteria describe, starting on "
            + "the page the application opens on. When every task of the story is merged, the "
            + "application is started and a real browser carries the journey out. The workers "
            + "read it before they build and make the screen match it, so it is the acceptance "
            + "test of the screen, as your JUnit test is of the code behind it. The file:\n"
            + "journey: <one sentence: what the person does and what they then see>\n"
            + "steps:\n"
            + JourneyFile.VOCABULARY
            + "These are all the steps there are. " + JourneyFile.CHOOSING + "\n"
            + "RULES OF A JOURNEY. (1) No step loads an address. The journey starts on the entry "
            + "page and gets to the screen by clicking what the application shows - a screen "
            + "nothing leads to must fail it, and that is the point. (2) It must FAIL on the "
            + "application as it is today and pass when this task is done: go through what the "
            + "task adds or changes, use it, and end with expectVisible, expectHidden or expectValue of what "
            + "the person sees when it has worked (what was just typed, shown where it belongs). "
            + "The application is started with NO DATA of its own unless the project's contract "
            + "says otherwise: what the journey expects to see, one of its own steps types or "
            + "the new screen shows by itself - a record nobody entered is not there. "
            + "(3) Selectors are the browser driver's: role=button[name=\"Save\"], "
            + "role=link[name=\"Orders\"], role=textbox[name=\"Name\"], text=..., or CSS. Prefer "
            + "role and text selectors, worded as the criteria word it: a person finds things by "
            + "what they read. (4) For what the application ALREADY shows - the way from the "
            + "entry page to the screen, labels that exist - never guess"
            + (withTools ? ": look it up. texts_of <Type> gives the texts a screen or a class of "
                + "text constants holds; the project map, types_in, find_usages, "
                + "types_annotated_with and body_of show which screens exist and how one is "
                + "reached from another. Do not read whole files for this. "
                : ": use only what you were shown above. ")
            + "For what this task ADDS you choose the words, from the criteria, and they bind the "
            + "worker. (5) One action per step, `value` only with fill, select and expectValue; "
            + "no script, no waiting, "
            + "no address. "
            + (withTools ? "Give the file to check_journey; a journey it calls VALID is handed in "
                + "with your test."
                : "Put the journey in \"files\" as one more entry, its YAML as the content.")
            + inPlace;
    }

    /**
     * What the author is told when a criterion is about what a person SEES and this project has a
     * browser stage. Empty otherwise, so an author working on anything else reads exactly the
     * prompt it read before.
     *
     * <h2>The run this exists because of</h2>
     *
     * <p>2026-09-03, story "Assign and display a book rating". The criterion was "A rating can be
     * assigned to a book and is displayed alongside the book's details". The test written for it
     * proved it through the service — correctly, and that is all a JUnit test in the acceptance
     * module can do, because it cannot open a browser. Two enabler waves later the service worked,
     * so the test was green before the wave that owns the criterion even started, and the run
     * stopped and asked a person to revise a test that was doing its job.
     *
     * <p>The test is not the problem. What was missing is that nobody had ever SAID which half of
     * such a criterion a JUnit test can settle. Told that, the author writes that half properly
     * instead of either faking a screen (asserting on HTML strings, which proves nothing and passes
     * for ever) or skipping the criterion (which leaves the run with no signal at all).
     */
    static String screenSteering(List<AcceptanceCriterion> forCriteria, boolean webProject) {
        if (!webProject || !UserFacingWording.anyUserFacing(forCriteria)) {
            return "";
        }
        return "ONE OR MORE CRITERIA ABOVE ARE ABOUT WHAT A PERSON SEES ON SCREEN. Your test is a "
            + "JUnit test and it cannot open a browser, so it can prove only the SERVICE side of "
            + "such a criterion: the value can be set, it is stored, it comes back, the rule about "
            + "it holds. Write that part, for real, and write it for every such criterion — do not "
            + "skip one because it mentions a screen. Do NOT fake the screen: no asserting on HTML "
            + "strings, no pretending a page was rendered, no test that would pass whatever the "
            + "user interface does. This project starts its application and looks at it with a real "
            + "browser in a later stage, and THAT is what proves the screen half. Your job is "
            + "everything underneath it.";
    }

    /**
     * What the architect established for this task, as the task's workers are given it (section
     * 73): the test is written against the same facts the code will be. Nothing when the
     * architect kept nothing that concerns the task.
     */
    static String established(Task task) {
        String findings = task == null ? ""
            : com.swarmcoder.domain.DesignFinding.renderAll(task.architectFindings());
        return findings.isEmpty() ? "" : "\n\n" + findings;
    }
}
