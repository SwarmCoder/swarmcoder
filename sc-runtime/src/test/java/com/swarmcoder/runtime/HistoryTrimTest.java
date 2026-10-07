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
package com.swarmcoder.runtime;

import ai.koog.prompt.message.Message;
import ai.koog.prompt.message.MessagePart;
import ai.koog.prompt.message.RequestMetaInfo;
import ai.koog.prompt.message.ResponseMetaInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ceiling on how long a worker can run.
 *
 * <p><b>The first defect this exists to keep dead.</b> Nothing trimmed a worker's conversation.
 * Every file read and every build log stayed in the prompt for every later turn, about two thousand
 * tokens a turn. Workers that finished did so in 8 to 21 turns; workers that ran to 62, 72, 74 and
 * 99 turns were all killed by the client's fifteen-minute request timeout while the model server was
 * answering other workers in six tenths of a second. The prompt had grown past what the box can
 * prefill in the time allowed. The old hardcoded cap of 30 turns had been hiding it.
 *
 * <p><b>The second, which the first fix caused.</b> Emptying tool outputs and nothing else cannot
 * bound a conversation, because everything else a turn adds is permanent. Measured on the owner's
 * runs: 1298 tokens reclaimed per compaction against 2067 added per turn, so 769 tokens lost every
 * turn; a conversation at 105717 tokens against a working context of 51200; compaction firing on
 * every single turn and reclaiming 79 tokens. {@link #aLongRunIsBoundedInsteadOfDoublingItsBudget}
 * is that arithmetic as a test, and it is the one that would have failed.
 */
class HistoryTrimTest {

    private static final String SYSTEM = "You are worker 3. Your task: add a line to README.md.";

    /** One tool result, the size {@code WorkerToolbox} caps a tool output at. */
    private static String output(int index) {
        return ("file contents block " + index + " ").repeat(400);
    }

    private static Message.User user(String text) {
        return new Message.User(List.of(new MessagePart.Text(text, null)),
            RequestMetaInfo.Companion.getEmpty(), null);
    }

    private static Message.Assistant assistantCalling(String callId, String tool, String args) {
        return new Message.Assistant(List.of(new MessagePart.Tool.Call(callId, tool, args)),
            ResponseMetaInfo.Companion.getEmpty(), null, null, null);
    }

    private static Message.User toolResult(String callId, String tool, String out) {
        return new Message.User(List.of(new MessagePart.Tool.Result(callId, tool, out, false)),
            RequestMetaInfo.Companion.getEmpty(), null);
    }

    /** A worker's history after {@code turns} tool turns, shaped exactly as Koog builds it. */
    private static List<Message> conversation(int turns) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.System(List.of(new MessagePart.Text(SYSTEM, null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        messages.add(user("Begin now. Implement the task from your instructions."));
        for (int i = 0; i < turns; i++) {
            messages.add(assistantCalling("call-" + i, "read_file",
                "{\"path\":\"src/main/java/Thing" + i + ".java\"}"));
            messages.add(toolResult("call-" + i, "read_file", output(i)));
        }
        return messages;
    }

    private static HistoryTrim.Budget budget() {
        return HistoryTrim.Budget.forWorkingContext(32768);
    }

    @Test
    void aShortConversationIsLeftExactlyAloneAndCostsNothing() {
        List<Message> history = conversation(3);
        HistoryTrim.Result result = HistoryTrim.trim(history, budget());

        assertThat(result.changed()).isFalse();
        assertThat(result.dropped()).isZero();
        assertThat(result.messages()).isSameAs(history);
    }

    @Test
    void aLongConversationIsCutFromAboveTheHighWaterMarkToBelowTheLowWaterMark() {
        List<Message> history = conversation(40);
        HistoryTrim.Budget budget = budget();

        assertThat(HistoryTrim.estimateTokens(history))
            .describedAs("40 turns of capped tool output must exceed the working context")
            .isGreaterThan(budget.highWaterTokens());

        HistoryTrim.Result result = HistoryTrim.trim(history, budget);

        assertThat(result.dropped()).isGreaterThan(0);
        assertThat(result.tokensAfter()).isLessThanOrEqualTo(budget.lowWaterTokens());
        assertThat(HistoryTrim.estimateTokens(result.messages())).isEqualTo(result.tokensAfter());
    }

    /**
     * The task is in the system prompt. A worker that lost it would write plausible code for the
     * wrong job, and nothing anywhere would show that it had happened.
     */
    @Test
    void theSystemPromptAndTheOpeningInstructionAreNeverTouched() {
        HistoryTrim.Result result = HistoryTrim.trim(conversation(40), budget());

        assertThat(result.messages().get(0).textContent()).isEqualTo(SYSTEM);
        assertThat(result.messages().get(1).textContent())
            .isEqualTo("Begin now. Implement the task from your instructions.");
    }

    /**
     * While emptying outputs is enough, nothing is removed — so no tool call can lose the result
     * that answered it. When it stops being enough, whole exchanges go, and
     * {@link #removingOldTurnsNeverOrphansACallOrAResult} is the safety argument for that.
     */
    @Test
    void everyMessageAndEveryToolCallSurvivesIntact() {
        List<Message> history = conversation(40);
        HistoryTrim.Result result = HistoryTrim.trim(history, budget());

        assertThat(result.messages()).hasSameSizeAs(history);
        for (int i = 0; i < history.size(); i++) {
            assertThat(result.messages().get(i).getRole()).isEqualTo(history.get(i).getRole());
        }
        assertThat(toolCallIds(result.messages())).isEqualTo(toolCallIds(history));
        assertThat(toolResultIds(result.messages())).isEqualTo(toolResultIds(history));
    }

    @Test
    void theMostRecentTurnsAreKeptWholeBecauseThatIsWhatItIsWorkingFrom() {
        List<Message> history = conversation(40);
        HistoryTrim.Result result = HistoryTrim.trim(history, budget());

        for (int i = history.size() - HistoryTrim.PROTECTED_TAIL_MESSAGES; i < history.size(); i++) {
            assertThat(outputAt(result.messages(), i))
                .describedAs("message %d is inside the protected tail", i)
                .isEqualTo(outputAt(history, i));
        }
    }

    /** The oldest read is the one most likely already superseded by a later read or an edit. */
    @Test
    void theOldestOutputGoesFirst() {
        HistoryTrim.Result result = HistoryTrim.trim(conversation(40), budget());

        assertThat(outputAt(result.messages(), 3)).startsWith(HistoryTrim.ELIDED_MARK);
        assertThat(outputAt(result.messages(), result.messages().size() - 1))
            .doesNotStartWith(HistoryTrim.ELIDED_MARK);
    }

    /**
     * How the worker learns something is gone. The line is in the place the content was, names the
     * call that produced it, and tells the worker to make that call again.
     */
    @Test
    void whatIsLeftBehindTellsTheWorkerExactlyWhatToReRead() {
        HistoryTrim.Result result = HistoryTrim.trim(conversation(40), budget());

        assertThat(outputAt(result.messages(), 3))
            .startsWith(HistoryTrim.ELIDED_MARK)
            .contains("read_file")
            .contains("src/main/java/Thing0.java")
            .contains("Run the tool again");
    }

    /** A second compaction must not re-drop what is already a stub, or it would grow the history. */
    @Test
    void compactingTwiceDropsNothingTwice() {
        HistoryTrim.Result once = HistoryTrim.trim(conversation(40), budget());
        HistoryTrim.Result twice = HistoryTrim.trim(once.messages(), budget());

        assertThat(twice.dropped()).isZero();
        assertThat(twice.tokensAfter()).isEqualTo(once.tokensAfter());
    }

    /**
     * The budget is the model's own {@code workingContextTokens} — the setting that has always
     * declared "the context SwarmCoder will let one session occupy" and that nothing enforced.
     */
    @Test
    void theBudgetComesFromTheModelsOwnWorkingContext() {
        HistoryTrim.Budget budget = HistoryTrim.Budget.forWorkingContext(32768);

        assertThat(budget.highWaterTokens()).isEqualTo(32768 * HistoryTrim.HIGH_WATER_PERCENT / 100);
        assertThat(budget.lowWaterTokens()).isEqualTo(32768 * HistoryTrim.LOW_WATER_PERCENT / 100);
        assertThat(budget.lowWaterTokens()).isLessThan(budget.highWaterTokens());
    }

    // ---------------------------------------------------------------------------------------
    // The arithmetic. Everything above tests one compaction; these test a hundred and twenty-one.
    // ---------------------------------------------------------------------------------------

    /** The owner's Spark: one running request's fair share of the reported key/value cache. */
    private static final int SPARK_WORKING_CONTEXT = 51200;

    /** What one long run did to the conversation, measured turn by turn. */
    private record LongRun(int addedPerTurn, int compactions, int averageReclaim, int peak,
                           int finalSize, int floorAtEnd, int longestConsecutiveRun,
                           List<Message> history) {}

    /**
     * A worker that reads three files and writes one, over and over, with its own reasoning on every
     * turn — the shape of the real thing, and the shape the broken scheme could not survive. Every
     * turn is handed to the compaction exactly as the worker loop hands it over.
     */
    private static LongRun longRun(int turns, int workingContext) {
        List<Message> history = new ArrayList<>(List.of(
            new Message.System(List.of(new MessagePart.Text("You are worker 3. ".repeat(120), null)),
                RequestMetaInfo.Companion.getEmpty(), null),
            user("Begin now. Implement the task from your instructions.")));
        HistoryTrim.Budget budget = HistoryTrim.Budget.forWorkingContext(workingContext);

        int compactions = 0;
        int reclaimed = 0;
        int peak = 0;
        int addedPerTurn = 0;
        int consecutive = 0;
        int longestConsecutive = 0;
        for (int turn = 0; turn < turns; turn++) {
            boolean writes = turn % 4 == 3;
            String id = "call-" + turn;
            String args = writes
                ? "{\"path\":\"src/main/java/Thing" + turn + ".java\",\"content\":\""
                    + "y".repeat(6000) + "\"}"
                : "{\"path\":\"src/main/java/Thing" + turn + ".java\"}";
            int sizeBefore = HistoryTrim.estimateTokens(history);
            history.add(new Message.Assistant(List.of(
                new MessagePart.Text("I need to see how this is wired before I change it. ".repeat(8), null),
                new MessagePart.Tool.Call(id, writes ? "write_file" : "read", args)),
                ResponseMetaInfo.Companion.getEmpty(), null, null, null));
            history.add(toolResult(id, writes ? "write_file" : "read",
                writes ? "wrote 6000 chars" : output(turn)));
            if (turn == 10) {
                addedPerTurn = HistoryTrim.estimateTokens(history) - sizeBefore;
            }

            HistoryTrim.Result result = HistoryTrim.trim(history, budget);
            peak = Math.max(peak, result.tokensBefore());
            if (result.changed()) {
                compactions++;
                reclaimed += result.tokensBefore() - result.tokensAfter();
                consecutive++;
                longestConsecutive = Math.max(longestConsecutive, consecutive);
            } else {
                consecutive = 0;
            }
            history = new ArrayList<>(result.messages());
        }
        return new LongRun(addedPerTurn, compactions,
            compactions == 0 ? 0 : reclaimed / compactions, peak,
            HistoryTrim.estimateTokens(history), HistoryTrim.floorTokens(history),
            longestConsecutive, history);
    }

    /**
     * <b>The measurement the whole change exists for.</b> A compaction must reclaim more than a turn
     * adds, or the conversation grows for ever no matter how often compaction runs.
     *
     * <p>The scheme it replaced reclaimed 1298 tokens per compaction against 2067 added per turn and
     * reached 105717 tokens on a 51200 budget. Reproduced here, the old scheme peaked at 74712 on a
     * 38400 high-water mark and compacted on 57 of 121 turns — every turn once the floor had passed
     * the mark. The printed line is the number behind the claim.
     */
    @Test
    void aLongRunIsBoundedInsteadOfDoublingItsBudget() {
        LongRun run = longRun(121, SPARK_WORKING_CONTEXT);
        System.out.println("[compaction] 121 turns: added/turn=" + run.addedPerTurn()
            + " compactions=" + run.compactions() + " avg reclaim=" + run.averageReclaim()
            + " peak=" + run.peak() + " final=" + run.finalSize() + " floor=" + run.floorAtEnd());

        assertThat(run.averageReclaim())
            .describedAs("a compaction must reclaim more than a turn adds (%s), or nothing is bounded",
                run.addedPerTurn())
            .isGreaterThan(run.addedPerTurn() * 4);
        assertThat(run.peak())
            .describedAs("no conversation may exceed the context one session is allowed")
            .isLessThanOrEqualTo(SPARK_WORKING_CONTEXT);
    }

    /**
     * Compaction rewrites the head of the conversation, so the server has to prefill everything
     * after it again. Doing that on every turn is worse than the growth it fixes — and it is what
     * the broken scheme ended up doing, 532 times in a day. One sweep must buy several turns.
     */
    @Test
    void compactionStaysRareRatherThanFiringEveryTurn() {
        LongRun run = longRun(121, SPARK_WORKING_CONTEXT);

        assertThat(run.compactions())
            .describedAs("%s compactions in 121 turns", run.compactions())
            .isGreaterThan(0)
            .isLessThanOrEqualTo(121 / 6);
        assertThat(run.longestConsecutiveRun())
            .describedAs("two compactions in a row means one of them reclaimed nothing worth having")
            .isLessThanOrEqualTo(1);
    }

    /**
     * The ceiling, and why it is one. What compaction cannot touch — the system prompt, the opening
     * instruction with its block of removed calls, and the last four turns — must not grow with the
     * number of turns, or the conversation is only bounded until it is not.
     */
    @Test
    void theFloorDoesNotGrowWithTheNumberOfTurns() {
        int at60 = longRun(60, SPARK_WORKING_CONTEXT).floorAtEnd();
        int at240 = longRun(240, SPARK_WORKING_CONTEXT).floorAtEnd();

        assertThat(at240)
            .describedAs("floor after 60 turns %s, after 240 turns %s", at60, at240)
            .isLessThan(at60 * 2);
        assertThat(at240)
            .describedAs("the floor has to be under the low-water mark or every turn compacts")
            .isLessThan(HistoryTrim.Budget.forWorkingContext(SPARK_WORKING_CONTEXT).lowWaterTokens());
    }

    /**
     * The safety argument for removing messages rather than only emptying them. A {@code role: tool}
     * result whose call is gone — or a call whose result is gone — is a malformed request that most
     * servers reject outright, and that is what killing whole turns risks.
     */
    @Test
    void removingOldTurnsNeverOrphansACallOrAResult() {
        List<Message> history = longRun(121, SPARK_WORKING_CONTEXT).history();

        List<String> calls = new ArrayList<>();
        List<String> results = new ArrayList<>();
        for (Message message : history) {
            for (MessagePart part : message.getParts()) {
                if (part instanceof MessagePart.Tool.Call call) {
                    calls.add(String.valueOf(call.getId()));
                } else if (part instanceof MessagePart.Tool.Result result) {
                    results.add(String.valueOf(result.getId()));
                }
            }
        }
        assertThat(calls).containsExactlyInAnyOrderElementsOf(results);
    }

    /**
     * The task is in the system message, and the shared prefix every worker in the group is prefilled
     * from is that message and nothing else. Removing whole turns must not reach it.
     */
    @Test
    void theSystemMessageIsUntouchedNoMatterHowMuchIsRemoved() {
        List<Message> history = longRun(121, SPARK_WORKING_CONTEXT).history();

        assertThat(history.getFirst()).isInstanceOf(Message.System.class);
        assertThat(history.getFirst().textContent()).isEqualTo("You are worker 3. ".repeat(120));
    }

    /**
     * How a worker learns that whole turns are gone, and how it gets them back. The block sits in the
     * one message it is guaranteed to read, says plainly that nothing from before it survives, and
     * names the calls it would have to make again.
     */
    @Test
    void whatIsLeftOfARemovedTurnNamesTheCallSoItCanBeMadeAgain() {
        List<Message> history = longRun(121, SPARK_WORKING_CONTEXT).history();
        String opening = history.get(1).textContent();

        assertThat(opening)
            .startsWith("Begin now. Implement the task from your instructions.")
            .contains(HistoryTrim.DIGEST_OPEN)
            .contains("run the tool again")
            .contains("- read {\"path\":\"src/main/java/Thing")
            .contains(HistoryTrim.DIGEST_CLOSE);
    }

    /**
     * The block is what keeps the floor flat, so its own size must not depend on how much has been
     * removed. It names the most recent calls and stops; it does not accumulate one line per turn,
     * which would be the same defect again with a gentler slope.
     */
    @Test
    void theBlockOfRemovedCallsHasAFixedSize() {
        String shortRun = longRun(121, SPARK_WORKING_CONTEXT).history().get(1).textContent();
        String longRun = longRun(240, SPARK_WORKING_CONTEXT).history().get(1).textContent();

        assertThat(namedCalls(longRun)).isEqualTo(namedCalls(shortRun))
            .isLessThanOrEqualTo(HistoryTrim.MAX_DIGEST_CALLS);
    }

    private static int namedCalls(String digest) {
        return (int) digest.lines().filter(line -> line.startsWith("- ")).count();
    }

    private static String outputAt(List<Message> messages, int index) {
        for (MessagePart part : messages.get(index).getParts()) {
            if (part instanceof MessagePart.Tool.Result result) {
                return result.getOutput();
            }
        }
        return null;
    }

    private static List<String> toolCallIds(List<Message> messages) {
        List<String> ids = new ArrayList<>();
        for (Message message : messages) {
            for (MessagePart part : message.getParts()) {
                if (part instanceof MessagePart.Tool.Call call) {
                    ids.add(call.getId() + "/" + call.getTool() + "/" + call.getArgs());
                }
            }
        }
        return ids;
    }

    private static List<String> toolResultIds(List<Message> messages) {
        List<String> ids = new ArrayList<>();
        for (Message message : messages) {
            for (MessagePart part : message.getParts()) {
                if (part instanceof MessagePart.Tool.Result result) {
                    ids.add(result.getId() + "/" + result.getTool());
                }
            }
        }
        return ids;
    }
}
