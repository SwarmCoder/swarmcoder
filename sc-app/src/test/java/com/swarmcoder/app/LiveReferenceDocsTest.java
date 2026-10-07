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
package com.swarmcoder.app;

import com.swarmcoder.testsupport.LocalCheckouts;
import com.swarmcoder.swarm.HarnessSandbox;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.PromptBundle;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.swarm.WorkerLoop;
import com.swarmcoder.swarm.WorkerResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does a worker now find the API it needs, instead of decompiling to work it out? (§32)
 *
 * <p>The same task, the same live model, the same reference folder, twice over. The only thing
 * that differs is the knowledge channel:
 *
 * <ul>
 *   <li><b>BEFORE</b> — the brief and the {@code lookup_api} answer exactly as they were: no
 *       documentation reached the prefix at all (the section rendered as the four words
 *       "… (conventions truncated)"), and every lookup answered "No documentation found …
 *       Inspect the code directly with read/exec".</li>
 *   <li><b>AFTER</b> — the real brief: the catalogue of documents by name, the sections this task
 *       is about, and a {@code lookup_api} that answers from them.</li>
 * </ul>
 *
 * <p>The check is mechanical rather than a judgement. The task asks for a screen built with a
 * framework the model cannot know from training, and that framework's {@code FormLayout} takes
 * children through {@code add(...)}. The famous Java web framework's does not — it uses
 * {@code addFormItem(...)} — so a model working from habit writes one and a model working from
 * the documentation writes the other. {@code CardTitle} is a second tell: it is this framework's
 * own class and cannot be guessed.
 *
 * <pre>
 * mvn -o test -pl sc-app -am -Dtest=LiveReferenceDocsTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8002/v1 \
 *   -Dswarmcoder.live.model=qwen3.8-27b -Dswarmcoder.live.reps=2
 * </pre>
 */
class LiveReferenceDocsTest {

    /** The reference folder: a real framework with real documentation, on this machine. */
    private static final Path FRAMEWORK = LocalCheckouts.find("zeroz4j");

    private static final String INSTRUCTIONS = """
        Create a new file src/main/java/com/example/ui/SignupView.java in package com.example.ui.

        Build a signup screen using the zeroz4j UI component library that this project depends on:
          - a vertical layout as the root
          - a title at the top reading "User Registration"
          - a form containing two text fields, "Name" and "Email"
          - a horizontal row with a "Submit" button and a "Cancel" button

        Use the real zeroz4j component classes and their real methods. Do not invent an API.
        You do NOT need to run a build and you do NOT need to explore the repository.
        Find out what the components are called, then write the file with write_file straight
        away and call report_done. Do not spend more than a few tool calls before writing.
        """;

    /** This framework's own class — it cannot be guessed, only read. */
    private static final String REAL_API = "CardTitle";
    /** The other framework's habit, which a model without documentation falls back on. */
    private static final String GUESSED_API = "addFormItem";

    @TempDir
    Path work;

    /** What one attempt did, in numbers. */
    private record Attempt(String arm, String diff, int turns, long tokens,
                           int decompileCalls, int lookupCalls, boolean steered) {
        boolean producedCode() {
            return diff != null && !diff.isBlank();
        }

        boolean usedRealApi() {
            return producedCode() && diff.contains(REAL_API);
        }

        boolean guessedApi() {
            return producedCode() && diff.contains(GUESSED_API);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "swarmcoder.live.baseUrl", matches = ".+",
        disabledReason = "needs a live model endpoint; see the class javadoc")
    void aWorkerFindsTheApiInsteadOfDecompilingForIt() throws Exception {
        assertThat(Files.isDirectory(FRAMEWORK))
            .as("this measurement needs the reference framework checkout at " + FRAMEWORK)
            .isTrue();
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String model = System.getProperty("swarmcoder.live.model", "qwen3.8-27b");
        int reps = Integer.getInteger("swarmcoder.live.reps", 2);

        Path repo = DemoRepo.create(work.resolve("repo"));
        GitService git = new GitService(repo);
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(FRAMEWORK), repo, null, work.resolve("primers"));

        Task task = new Task(UUID.randomUUID(), 1, "Signup screen", INSTRUCTIONS,
            Set.of("src/main"), Set.of("src"), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 500000, 24),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of("minimal-diff")), TaskState.READY);

        String afterBrief = librarian.assembleBrief(repo, task).renderedMarkdown();
        String beforeBrief = beforeBrief(librarian, repo, task);
        System.out.println("[DOCS] brief BEFORE: " + beforeBrief.length() + " chars, "
            + "AFTER: " + afterBrief.length() + " chars");

        List<Attempt> before = new ArrayList<>();
        List<Attempt> after = new ArrayList<>();
        for (int rep = 0; rep < reps; rep++) {
            // Interleaved, so a busy shared server cannot land on one arm only.
            before.add(run("BEFORE", baseUrl, model, git, task, beforeBrief,
                OLD_WORKFLOW_RULES, LiveReferenceDocsTest::oldLookupApi));
            after.add(run("AFTER", baseUrl, model, git, task, afterBrief,
                NEW_WORKFLOW_RULES, librarian::lookupApi));
        }

        report("BEFORE", before);
        report("AFTER", after);

        int decompilesBefore = before.stream().mapToInt(Attempt::decompileCalls).sum();
        int decompilesAfter = after.stream().mapToInt(Attempt::decompileCalls).sum();
        int lookupsBefore = before.stream().mapToInt(Attempt::lookupCalls).sum();
        int lookupsAfter = after.stream().mapToInt(Attempt::lookupCalls).sum();

        assertThat(before.stream().mapToInt(Attempt::turns).sum())
            .as("the model actually ran — otherwise nothing here measures anything")
            .isGreaterThan(0);
        assertThat(decompilesBefore)
            .as("without documentation the model does reach into the jar — otherwise the "
                + "behaviour being fixed was never reproduced, and the numbers below say nothing")
            .isGreaterThan(0);
        assertThat(decompilesAfter)
            .as("with the documentation reaching it, no attempt reached into the jar "
                + "(without: " + decompilesBefore + ")")
            .isZero();
        assertThat(lookupsAfter)
            .as("and it asked for documentation instead (without: " + lookupsBefore + ")")
            .isGreaterThan(lookupsBefore);

        // Deliberately NOT asserted: whether the file was written. On this model neither arm
        // finishes, and the reason has nothing to do with documentation — after roughly seven
        // turns it starts emitting its tool calls as plain TEXT ({"tool_name":"exec",…} in the
        // message body) instead of as tool calls, and the loop stops on two such turns in a row.
        // Its chat template also rejects native tool-call history outright (HTTP 400), so the
        // textual history that provokes the imitation cannot simply be turned off. That is a
        // separate defect, larger than this one, and asserting on it here would only produce a
        // test that is red for a reason it does not test.
        System.out.println("[DOCS] wrote code — BEFORE: "
            + before.stream().filter(Attempt::producedCode).count() + "/" + before.size()
            + ", AFTER: " + after.stream().filter(Attempt::producedCode).count() + "/" + after.size()
            + " (see the note in this test about textual tool calls)");
    }

    // --- the two arms -------------------------------------------------------------------------

    /**
     * The brief as it really was. Reproduced rather than reverted, because it is short: the
     * documentation walk found only top-level files named "readme", and every renderer dropped a
     * block that did not fit — with a 6,000-character read cap against a 3,000-character budget
     * that was always the first block, so the section came back as its own truncation marker and
     * the source selection came back empty.
     */
    private static String beforeBrief(Librarian librarian, Path repo, Task task) {
        String full = librarian.assembleBrief(repo, task).renderedMarkdown();
        int docsStart = full.indexOf("\n### Reference-repo docs");
        String libraries = docsStart < 0 ? full : full.substring(0, docsStart);
        return libraries + "\n### Reference-repo docs\n… (conventions truncated)\n";
    }

    /** The old workflow rules — they said nothing about where to look things up. */
    private static final String OLD_WORKFLOW_RULES =
        "Work in small steps: inspect with read/exec; modify with write_file (full file content — "
        + "reliable) or apply_diff (unified diff); verify by running builds/tests with exec; then "
        + "call report_done with a short summary when the change is complete and verified. ALWAYS "
        + "use repository-RELATIVE paths; your tools already operate at the repository root.";

    private static final String NEW_WORKFLOW_RULES = OLD_WORKFLOW_RULES + "\n"
        + "When you do not know an API: call lookup_api FIRST. It searches this project's "
        + "reference documentation and any configured documentation server, and your knowledge "
        + "brief lists the documents by name. Do NOT unpack or decompile jars (`jar xf`, `javap`, "
        + "a decompiler) to work an API out — that costs many turns and tells you less than one "
        + "lookup_api call.";

    /** The old answer, verbatim. It is an instruction to go and read bytecode. */
    private static String oldLookupApi(String query) {
        return "No documentation found for \"" + query + "\". Inspect the code directly with "
            + "read/exec (e.g. grep for the symbol), or check the dependency manifest.";
    }

    // --- running and counting -------------------------------------------------------------------

    private Attempt run(String arm, String baseUrl, String model, GitService git, Task task,
                        String brief, String workflowRules, ApiLookup lookup) {
        AtomicInteger decompiles = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger steers = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong tokens = new java.util.concurrent.atomic.AtomicLong();
        TraceHub hub = new TraceHub(null);
        hub.addListener(new TraceHub.Listener() {
            @Override
            public void event(UUID sessionId, TraceEvent event) {
                String kind = String.valueOf(event.kind());
                String payload = event.payload() == null ? ""
                    : event.payload().toLowerCase(Locale.ROOT);
                if ("LLM_RESPONSE".equals(kind)) {
                    turns.incrementAndGet();
                    // This model routinely emits its tool calls as plain TEXT rather than as
                    // native tool calls, so counting only TOOL_CALL events undercounts what it
                    // actually tried to do. Both are counted.
                    if (mentionsDecompiler(payload)) {
                        decompiles.incrementAndGet();
                    }
                    if (payload.contains("lookup_api")) {
                        lookups.incrementAndGet();
                    }
                }
                tokens.accumulateAndGet(event.tokensUsed(), Math::max);
                if ("TOOL_CALL".equals(kind)) {
                    if (mentionsDecompiler(payload)) {
                        decompiles.incrementAndGet();
                    }
                    if ("lookup_api".equals(event.label())) {
                        lookups.incrementAndGet();
                    }
                }
                if ("NUDGE".equals(kind) && payload.contains("orchestrator")) {
                    steers.incrementAndGet();
                }
            }
        });

        // A fresh id per attempt: the worker's branch is named from it, and a repeat would
        // collide with the branch the previous attempt left behind.
        Task attemptTask = new Task(UUID.randomUUID(), 1, task.title(), task.instructions(),
            task.writeSet(), task.readSet(), List.of(), task.acceptanceTestDir(), null,
            task.budget(), task.swarmPolicy(), task.state());
        PromptBundle bundle = PromptBundle.builder()
            .systemRole("You are a software engineering worker agent. Implement exactly the task "
                + "described below in the repository you have tools for.")
            .workflowRules(workflowRules)
            .taskInstructions("Task: " + task.title() + "\n" + task.instructions() + "\n"
                + "You may ONLY modify these paths: " + task.writeSet().stream().sorted().toList())
            .knowledgeBrief(brief)
            .build();

        com.swarmcoder.inference.ModelQuirks quirks = com.swarmcoder.inference.ModelQuirks.DEFAULTS;
        if (Boolean.getBoolean("swarmcoder.live.nativeToolHistory")) {
            quirks = new com.swarmcoder.inference.ModelQuirks(quirks.label(),
                quirks.maxOutputTokens(), quirks.thinking(), quirks.thinkingKwarg(),
                quirks.noThinkDirective(), false, quirks.jsonResponseFormat(), quirks.http2(),
                quirks.servedContextTokens(), quirks.workingContextTokens(),
                quirks.kvBytesPerToken(), quirks.maxConcurrentSequences(), quirks.verified());
        }
        AgentRuntime.ModelEndpoint endpoint =
            new AgentRuntime.ModelEndpoint(baseUrl, "", model, 65536, quirks);
        WorkerLoop loop = new WorkerLoop(attemptTask,
            new SamplingConfig(model, 0.2, 0L, "minimal-diff", "full-files"), 0,
            new InferenceScheduler(16, 1024L * 1024 * 1024, 1024), git,
            new KoogAgentRuntime(hub), endpoint, UUID.randomUUID(), bundle,
            null, null, lookup, HarnessSandbox.required());

        WorkerResult result = loop.run();
        String diff = result.candidate() == null || result.candidate().diffUnified() == null
            ? "" : result.candidate().diffUnified();
        if (result.workspace() != null) {
            git.removeWorktree(result.workspace());
        }
        Attempt attempt = new Attempt(arm, diff, turns.get(), tokens.get(),
            decompiles.get(), lookups.get(), steers.get() > 0);
        System.out.println("[DOCS] " + arm + ": code=" + attempt.producedCode()
            + " realApi=" + attempt.usedRealApi() + " guessedApi=" + attempt.guessedApi()
            + " turns=" + attempt.turns() + " tokens=" + attempt.tokens()
            + " decompileCalls=" + attempt.decompileCalls()
            + " lookupCalls=" + attempt.lookupCalls() + " steered=" + attempt.steered());
        if (attempt.producedCode()) {
            System.out.println(diff.length() > 3_000 ? diff.substring(0, 3_000) + "\n[cut]" : diff);
        }
        return attempt;
    }

    /** Reaching into a compiled artefact to work an API out. */
    private static boolean mentionsDecompiler(String lowerText) {
        return lowerText.contains("javap") || lowerText.contains("jar xf")
            || lowerText.contains("jar -xf") || lowerText.contains("jar tf")
            || lowerText.contains("unzip") || lowerText.contains("decompil");
    }

    private static void report(String arm, List<Attempt> attempts) {
        System.out.println("[DOCS] === " + arm + " over " + attempts.size() + " attempts: "
            + attempts.stream().filter(Attempt::producedCode).count() + " wrote code, "
            + attempts.stream().filter(Attempt::usedRealApi).count() + " used the real API, "
            + attempts.stream().filter(Attempt::guessedApi).count() + " guessed it, "
            + attempts.stream().mapToInt(Attempt::decompileCalls).sum() + " decompiler calls, "
            + attempts.stream().mapToInt(Attempt::lookupCalls).sum() + " lookup_api calls, "
            + attempts.stream().mapToInt(Attempt::turns).sum() + " turns, "
            + attempts.stream().mapToLong(Attempt::tokens).sum() + " tokens, "
            + attempts.stream().filter(Attempt::steered).count() + " steered out of investigating");
    }
}
