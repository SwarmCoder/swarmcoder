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

import com.swarmcoder.domain.KillReason;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real ceiling on how long a worker can run, measured against a real server.
 *
 * <p><b>What was measured on the owner's box on 2026-09-01.</b> Workers split into two populations.
 * Eight finished, in 8 to 21 turns, using 16k to 42k tokens. Four died — at 62, 72, 74 and 99 turns
 * — every one of them killed by {@code HttpRequestTimeoutException} after the full fifteen minutes,
 * while the same endpoint answered {@code /v1/models} in six milliseconds and a small completion in
 * six tenths of a second. Nothing trimmed the conversation, so it grew by one capped tool output
 * (8000 characters, about 2000 tokens) per turn until the prompt could not be prefilled inside the
 * timeout.
 *
 * <p>This drives the REAL stack — the Koog client over HTTP, the worker loop, the compaction —
 * against a scripted {@link FakeVllm} that hands back a full-size tool output every turn, and
 * measures the actual request bodies. It is the number behind the turn-allowance advice, and it
 * costs no model calls.
 */
class WorkerContextGrowthTest {

    /** One tool output at the size {@code WorkerToolbox} caps them at. */
    private static final int TOOL_OUTPUT_CHARS = 8000;

    /** Roughly four characters per token, the same crude figure the compaction uses. */
    private static final int CHARS_PER_TOKEN = 4;

    /** Past the point where the owner's four workers died. */
    private static final int TURNS = 70;

    /**
     * What the owner's Spark now reports: 462103 key/value cache tokens shared 8 ways, less
     * headroom. Discovered at startup rather than declared — see {@code ServerCapabilities}.
     */
    private static final int SPARK_WORKING_CONTEXT = 51200;

    /** The unchanged figure for a server that discovers nothing — the generic shape. */
    private static final int GENERIC_WORKING_CONTEXT = 32768;

    /** The tools the scripted model calls. */
    public static final class BigReader {
        public String read(String path) {
            String head = path + ":";  // distinct per file, so never an unchanged repeat
            return head + "x".repeat(TOOL_OUTPUT_CHARS - head.length());
        }

        /** A write: almost no output, and the whole file in the tool call's ARGUMENTS. */
        public String write(String path, String content) {
            return "wrote " + content.length() + " chars to " + path;
        }

        public String reportDone(String summary) {
            return summary;
        }
    }

    private static List<AgentRuntime.ToolBinding> tools() throws Exception {
        BigReader target = new BigReader();
        Method read = BigReader.class.getMethod("read", String.class);
        Method write = BigReader.class.getMethod("write", String.class, String.class);
        Method done = BigReader.class.getMethod("reportDone", String.class);
        return List.of(
            new AgentRuntime.ToolBinding("read", "Read a file by path.", target, read),
            new AgentRuntime.ToolBinding("write", "Write a file.", target, write),
            new AgentRuntime.ToolBinding(KoogAgentRuntime.DONE_TOOL,
                "Call when the task is complete.", target, done));
    }

    /** Never finishes on purpose: the point is what the conversation looks like at turn N. */
    private static java.util.function.Function<String, FakeVllm.Reply> keepReading() {
        // A different file every turn: an identical repeat is answered with a one-line "unchanged"
        // note (section 59), which would stop the conversation growing and prove nothing.
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
        return conversation -> FakeVllm.Reply.toolCall("read",
            "{\"path\":\"src/main/java/Thing" + turn.getAndIncrement() + ".java\"}");
    }

    /**
     * A worker that also WRITES — and the reason this harness used to report a bound that reality
     * did not have.
     *
     * <p>{@link #keepReading} only ever reads, so every assistant turn in that script is a tool call
     * with a twenty-character argument, and a scheme that empties tool output alone bounds it
     * easily. A real worker writes files, and a write puts the whole file in the tool call's
     * ARGUMENTS, which the first version of {@code HistoryTrim} was not allowed to touch. That is
     * the content that made the owner's conversations grow 769 tokens a turn while compaction
     * reported success on every one of 532 attempts. One write every fourth turn here, which is
     * roughly the rate the workers that finished were writing at.
     */
    private static java.util.function.Function<String, FakeVllm.Reply> readAndWrite() {
        // Counted here rather than read back out of the conversation, which is what the other
        // scripts do: the conversation is exactly what compaction rewrites, so a script that counts
        // its own past calls in it would change behaviour every time it was compacted, and the
        // measurement would be of the script rather than of the scheme. One worker, so a counter is
        // as deterministic as routing on text.
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
        return conversation -> {
            int n = turn.getAndIncrement();
            if (n % 4 == 3) {
                return FakeVllm.Reply.toolCall("write",
                    "{\"path\":\"src/main/java/Thing" + n + ".java\",\"content\":\""
                        + "y".repeat(6000) + "\"}");
            }
            return FakeVllm.Reply.toolCall("read",
                "{\"path\":\"src/main/java/Thing" + n + ".java\"}");
        };
    }

    private static AgentRuntime.SessionResult runWorker(FakeVllm fake, int workingContextTokens)
            throws Exception {
        return runWorker(fake, workingContextTokens, TURNS);
    }

    private static AgentRuntime.SessionResult runWorker(FakeVllm fake, int workingContextTokens,
                                                         int maxTurns) throws Exception {
        ModelQuirks quirks = new ModelQuirks("test model", 4096, false, null, null,
            true, true, false, 262144, workingContextTokens, 1024, 8, false);
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec(
            "worker-0",
            "You are a worker. Read files and change the code.",
            new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 262144, quirks),
            0.2, maxTurns, tools(), info -> Optional.empty());
        try (AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
            return session.run("Begin now.");
        }
    }

    /**
     * Estimated prompt size, in tokens, of every request the server actually received.
     *
     * <p>Measured from the request BODY, not from {@code FakeVllm.requests}: that field is a lossy
     * reconstruction built for routing scripted replies, and it undercounts a textified tool history
     * badly. Measuring the wrong thing here would produce a turn-count recommendation with nothing
     * behind it.
     */
    private static List<Integer> promptTokensPerTurn(FakeVllm fake) {
        synchronized (fake.requests) {
            return fake.requestBytes.stream().map(bytes -> bytes / CHARS_PER_TOKEN).toList();
        }
    }

    /** How many turns in a row the prompt got smaller — one compaction each. */
    private static int compactions(List<Integer> perTurn) {
        int count = 0;
        for (int i = 1; i < perTurn.size(); i++) {
            if (perTurn.get(i) < perTurn.get(i - 1)) {
                count++;
            }
        }
        return count;
    }

    /** The measurement itself, printed so the number behind the advice is visible in the log. */
    private static void report(String label, List<Integer> perTurn) {
        StringBuilder sb = new StringBuilder("[context-growth] " + label + ": ");
        for (int turn = 0; turn < perTurn.size(); turn += 10) {
            sb.append('t').append(turn + 1).append('=').append(perTurn.get(turn)).append("  ");
        }
        sb.append("t").append(perTurn.size()).append('=').append(perTurn.getLast())
            .append("  peak=").append(perTurn.stream().mapToInt(Integer::intValue).max().orElse(0))
            .append("  compactions=").append(compactions(perTurn));
        System.out.println(sb);
    }

    /**
     * The defect, reproduced: with no budget to enforce, the conversation grows by a tool output a
     * turn for ever. This is what the owner's four dead workers were doing.
     */
    @Test
    void withoutABudgetTheConversationGrowsWithoutBound() throws Exception {
        try (FakeVllm fake = new FakeVllm(keepReading())) {
            // A working context larger than anything 70 turns can reach: nothing ever compacts, so
            // whatever kills this worker is the turn cap, not room — TURN_CAP, never BUDGET_EXCEEDED.
            AgentRuntime.SessionResult result = runWorker(fake, 5_000_000);
            assertThat(result.killReason()).contains(KillReason.TURN_CAP);

            List<Integer> perTurn = promptTokensPerTurn(fake);
            report("untrimmed", perTurn);
            int growthPerTurn = (perTurn.getLast() - perTurn.getFirst()) / (perTurn.size() - 1);

            assertThat(compactions(perTurn)).isZero();
            assertThat(growthPerTurn)
                .describedAs("one capped tool output per turn, in tokens")
                .isBetween(1500, 3000);
            assertThat(perTurn.getLast())
                .describedAs("%s turns untrimmed reaches %s tokens", TURNS, perTurn.getLast())
                .isGreaterThan(120_000);
        }
    }

    /**
     * The exact regression this whole change exists for, isolated from the context-growth
     * measurement above: a worker that used up its turn allowance and never once needed a
     * compaction must be killed {@code TURN_CAP}, and never {@code BUDGET_EXCEEDED} — the
     * constant that means "its history no longer fits its room". Harness run 11 reported two
     * workers dead at exactly turn 25 as {@code BUDGET_EXCEEDED} with no compaction line anywhere
     * in either log; this is that failure, reproduced at five turns and proven fixed.
     */
    @Test
    void aWorkerThatExceedsItsTurnCapIsKilledTurnCapNotBudgetExceeded() throws Exception {
        try (FakeVllm fake = new FakeVllm(keepReading())) {
            // 5,000,000 tokens of room: nothing five turns of "read" can produce ever comes close
            // to needing a compaction, so this ending can only be the turn cap.
            AgentRuntime.SessionResult result = runWorker(fake, 5_000_000, 5);

            assertThat(result.killReason())
                .describedAs("the two are different facts and must never be reported as the same one")
                .isEqualTo(Optional.of(KillReason.TURN_CAP));
            assertThat(result.turns())
                .describedAs("one turn past the five-turn allowance is what fires the cap")
                .isEqualTo(6);
            assertThat(compactions(promptTokensPerTurn(fake)))
                .describedAs("proof this death is not about room: nothing here ever compacted")
                .isZero();
        }
    }

    /**
     * The fix on the owner's own box: the working budget the server's key/value cache actually
     * supports, applied to a running conversation for the first time.
     */
    @Test
    void withTheSparksDiscoveredWorkingContextTheConversationStaysInsideIt() throws Exception {
        try (FakeVllm fake = new FakeVllm(keepReading())) {
            AgentRuntime.SessionResult result = runWorker(fake, SPARK_WORKING_CONTEXT);
            // Compaction keeps working at this budget (see compactionIsRareEnoughNotToCostThePrefixCacheEveryTurn),
            // so this worker never fails to fit its room — it runs out its 70-turn allowance instead.
            // TURN_CAP, not BUDGET_EXCEEDED: nothing about this ending is a room failure.
            assertThat(result.killReason()).contains(KillReason.TURN_CAP);

            List<Integer> perTurn = promptTokensPerTurn(fake);
            report("spark-51200", perTurn);

            assertThat(perTurn.stream().mapToInt(Integer::intValue).max().orElseThrow())
                .describedAs("no request may exceed what one session is allowed to occupy")
                .isLessThanOrEqualTo(SPARK_WORKING_CONTEXT);
        }
    }

    /** The same, for a server that discovers nothing and keeps the generic shape's figure. */
    @Test
    void withTheGenericWorkingContextTheConversationStaysInsideItToo() throws Exception {
        try (FakeVllm fake = new FakeVllm(keepReading())) {
            runWorker(fake, GENERIC_WORKING_CONTEXT);

            List<Integer> perTurn = promptTokensPerTurn(fake);
            report("generic-32768", perTurn);

            assertThat(perTurn.stream().mapToInt(Integer::intValue).max().orElseThrow())
                .isLessThanOrEqualTo(GENERIC_WORKING_CONTEXT);
        }
    }

    /** What was dropped is replaced by a line that tells the worker how to get it back. */
    @Test
    void theWorkerIsToldWhatWasDroppedAndHowToGetItBack() throws Exception {
        try (FakeVllm fake = new FakeVllm(keepReading())) {
            runWorker(fake, SPARK_WORKING_CONTEXT);
            String lastConversation;
            synchronized (fake.requests) {
                lastConversation = fake.requests.getLast();
            }
            assertThat(lastConversation)
                .contains("dropped to save room")
                .contains("Run the tool again");
        }
    }

    /**
     * The prefix-cache invariant. The server reuses a prompt prefix it has already prefilled, and a
     * scheme that rewrote the head of the conversation every turn would throw that away every turn —
     * worse than the growth it fixes. Compaction fires at a high-water mark and cuts to a low-water
     * mark, so it costs a cold prefill roughly once every ten turns instead of once every turn.
     */
    @Test
    void compactionIsRareEnoughNotToCostThePrefixCacheEveryTurn() throws Exception {
        try (FakeVllm fake = new FakeVllm(keepReading())) {
            runWorker(fake, SPARK_WORKING_CONTEXT);
            List<Integer> perTurn = promptTokensPerTurn(fake);
            int compactions = compactions(perTurn);

            assertThat(compactions)
                .describedAs("%s compactions across %s turns", compactions, perTurn.size())
                .isGreaterThan(0)
                .isLessThanOrEqualTo(perTurn.size() / 6);
        }
    }

    /**
     * <b>The measurement the second round of this work exists for</b>, through the real stack: a
     * worker that writes as well as reads, so the conversation grows by content the first version of
     * the compaction was not allowed to touch.
     *
     * <p>Against that worker the first version lost ground every turn — 1298 tokens reclaimed per
     * compaction against 2067 added per turn — reached 105717 tokens on a 51200 budget, and fired on
     * every single turn in the late stages. Both numbers are asserted here: the conversation stays
     * inside the budget, and one compaction buys several turns rather than none.
     */
    @Test
    void aWorkerThatWritesAsWellAsReadsIsBoundedToo() throws Exception {
        try (FakeVllm fake = new FakeVllm(readAndWrite())) {
            runWorker(fake, SPARK_WORKING_CONTEXT);

            List<Integer> perTurn = promptTokensPerTurn(fake);
            report("spark-51200-reads-and-writes", perTurn);
            int compactions = compactions(perTurn);

            String lastConversation;
            synchronized (fake.requests) {
                lastConversation = fake.requests.getLast();
            }
            assertThat(lastConversation)
                .describedAs("the script must actually have written files, or this measures nothing")
                .contains("write");

            assertThat(perTurn.stream().mapToInt(Integer::intValue).max().orElseThrow())
                .describedAs("no request may exceed what one session is allowed to occupy")
                .isLessThanOrEqualTo(SPARK_WORKING_CONTEXT);
            assertThat(compactions)
                .describedAs("%s compactions across %s turns: each one is a cold prefill of the "
                    + "whole conversation, so this is the wall-clock cost, not the token cost",
                    compactions, perTurn.size())
                .isGreaterThan(0)
                .isLessThanOrEqualTo(perTurn.size() / 6);
        }
    }

    /**
     * What happens when the conversation cannot be brought inside the budget at all.
     *
     * <p>The system prompt, the opening instruction and the four most recent turns may never be
     * dropped. Give a worker a budget smaller than those, and no amount of compaction can help it:
     * the old behaviour was to compact anyway, succeed at reclaiming nothing, and try again next
     * turn — 532 times in one day, each one rewriting the history and costing the server a full
     * re-read. Here the worker is stopped instead, with {@code BUDGET_EXCEEDED}, whose sentence to
     * the operator already reads "its conversation outgrew what it is allowed, so it was stopped".
     *
     * <p>The proof that it stopped rather than ground on is the request count: a handful of turns,
     * not the seventy it was allowed.
     */
    @Test
    void aWorkerThatCannotBeBroughtInsideItsBudgetIsStoppedRatherThanGroundOn() throws Exception {
        try (FakeVllm fake = new FakeVllm(keepReading())) {
            // 8192 working context: the high-water mark is 6144 tokens and four turns of capped tool
            // output alone are about 8000, so the untouchable part is over the line by itself.
            AgentRuntime.SessionResult result = runWorker(fake, 8192);

            assertThat(result.killReason()).contains(KillReason.BUDGET_EXCEEDED);
            List<Integer> perTurn = promptTokensPerTurn(fake);
            report("floor-above-budget-8192", perTurn);
            assertThat(perTurn.size())
                .describedAs("stopped after %s turns instead of grinding through all %s",
                    perTurn.size(), TURNS)
                .isLessThan(TURNS / 2);
        }
    }
}
