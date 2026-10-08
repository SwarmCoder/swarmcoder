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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Why a worker's conversation stops growing — and why the first attempt at this did not work.
 *
 * <p><b>The original defect (2026-09-01).</b> Nothing anywhere trimmed a worker's history. Every
 * file read, every build log, every directory listing stayed in the prompt for every later turn,
 * and each turn adds roughly two thousand tokens because {@code WorkerToolbox} caps one tool output
 * at 8000 characters. Workers that finished did so in 8 to 21 turns at 16k-42k tokens. Workers that
 * ran long died at 62, 72, 74 and 99 turns, every one killed by the client's fifteen-minute request
 * timeout while the same endpoint answered a small completion in six tenths of a second.
 *
 * <p><b>The second defect (2026-09-02): the first fix could not hold the line.</b> The first version
 * of this class dropped tool OUTPUT and nothing else — never a message, never an assistant turn,
 * never a tool call. Measured over a day of real runs: 532 compactions, average reclaim 1298 tokens,
 * maximum 18698, against 2067 tokens added per turn. It lost 769 tokens every turn it ran. A
 * conversation reached 105717 tokens against a working context of 51200, compaction fired on every
 * single turn in the late stages (77283 -&gt; 77204, a reclaim of 79 tokens), and one worker ran 121
 * turns and produced nothing.
 *
 * <p>The arithmetic is forced by what the scheme was not allowed to touch. Once the old outputs are
 * stubs there is nothing left to drop but the one output that has just aged out of the protected
 * tail, so a compaction reclaims exactly one tool result — the measured 1298-token average IS one
 * average tool result. Everything else a turn adds is permanent: the assistant's own reasoning, the
 * tool CALL arguments (and {@code write_file} carries a whole file in its arguments), and the stub
 * left behind. That permanent remainder was the measured 769 tokens a turn, and it is a floor that
 * rises for ever. Reproduced in {@code HistoryTrimTest}: 121 simulated turns of the old scheme
 * reached 74712 tokens against a 38400 high-water mark, compacting on 57 of them.
 *
 * <p><b>The scheme now: two passes, oldest first, and the second one can always reach the floor.</b>
 *
 * <ul>
 *   <li><b>Pass one — empty the old tool outputs, in place.</b> Exactly what the first version did,
 *       and for the same reasons: it is free, it is instant, it keeps every message, every role and
 *       every {@code tool_call_id} pairing intact, and what it removes is the cheapest thing in the
 *       conversation to recover, because a file can simply be read again and the later read is the
 *       correct one. Short and medium runs never get past this pass.
 *   <li><b>Pass two — remove the oldest exchanges outright.</b> When pass one cannot reach the
 *       low-water mark, whole assistant/tool-result exchanges are removed from the oldest end: the
 *       assistant's reasoning, its tool calls with their arguments, and the results. This is the
 *       part the first version had no answer for, and the only reason it lost ground every turn.
 *       Removal is always in balanced spans — a tool call and the result that answered it leave
 *       together — so no result is ever orphaned and no call is ever left unanswered.
 *   <li><b>What replaces them is one block of fixed size.</b> Not one line per removed call, which
 *       would be a new floor with a gentler slope; a fixed paragraph plus at most
 *       {@link #MAX_DIGEST_CALLS} of the most recently removed calls, each capped. The block costs
 *       the same at turn 200 as at turn 40, so the conversation has a genuine ceiling: system
 *       message + opening instruction + this block + the protected tail. Nothing in that sum grows
 *       with the number of turns, and {@link #floorTokens} is that sum, measurable at any moment.
 *   <li><b>Rare, not per-turn.</b> Compaction fires above {@link #HIGH_WATER_PERCENT} of the model's
 *       working context and cuts to {@link #LOW_WATER_PERCENT}. That was right in the first version;
 *       what broke it was a reclaim too small to reach the low-water mark, so the next turn was over
 *       the high-water mark again and it compacted every turn anyway.
 * </ul>
 *
 * <p><b>Why not summarise.</b> Reconsidered against these numbers rather than dismissed out of hand.
 * A summary costs a model call over the very history that is already too big to answer quickly — on
 * the owner's box, minutes — and it would fire once every eight or nine turns, so a 120-turn worker
 * would spend a dozen of them. Removal reaches the same ceiling for nothing, and the material it
 * removes is sixty turns old and re-readable from disk in one call. The one thing a summary buys
 * that removal does not is the worker's own conclusions from those turns; the answer to that is that
 * a worker's conclusions belong in the files it has already written, not in its scrollback.
 *
 * <p><b>Never dropped.</b> The system prompt — task, project rules, write set — and the opening
 * instruction. A worker that lost its task would write plausible code for the wrong job and nothing
 * would show that it had happened, which is strictly worse than a worker that dies. Also never
 * dropped: the most recent {@link #PROTECTED_TAIL_MESSAGES} messages, which is what it is working
 * from right now.
 *
 * <p><b>When even that is too big.</b> If the system prompt plus the protected tail plus the block
 * still exceeds the budget, no amount of compaction can make this conversation answerable. There is
 * no honest way to continue, so the caller stops the worker and says so rather than letting it run
 * to 121 turns pretending. See {@code KoogAgentRuntime.compactIfNeeded}.
 *
 * <p><b>Prefix caching — two different caches, and only one of them is a real cost.</b>
 *
 * <ul>
 *   <li><b>Across the workers on a task.</b> {@link PromptBundle} keeps the shared prompt prefix
 *       byte-identical for every worker in a group so the server prefills it once for all of them.
 *       That prefix is the system message, and nothing here ever touches the system message, so the
 *       alignment is exactly as it was, before and after this change.
 *   <li><b>Within one worker.</b> SGLang's radix cache reuses the longest prefix it has already
 *       prefilled, so rewriting anything at the front invalidates everything after it: one cold
 *       prefill of the whole post-compaction prompt. That cost is real and it is paid PER
 *       COMPACTION, which is why the frequency matters more than the depth. The broken version's
 *       532 compactions were 532 cold prefills; a scheme that sweeps once every eight or nine turns
 *       pays it a handful of times per worker.
 *   <li><b>Why not protect a longer head, so more of the cache survives?</b> It would work, and it
 *       is not worth what it costs. Measured on the 121-turn run in {@code HistoryTrimTest}: the
 *       floor is 10242 tokens against a low-water mark of 20480, so there is room for about four
 *       turns of permanently protected head before the floor crosses the mark and every turn starts
 *       compacting again. Four protected turns would keep roughly half of the post-compaction
 *       prompt warm — half a prefill saved, a dozen times per worker — and would spend nearly all
 *       of the margin that keeps a worker with a larger system prompt, or bigger tool outputs, out
 *       of the grinding regime this class exists to end. Frequency is the lever with the decimal
 *       point in it: 57 sweeps down to 12. The size of the protected head is not.
 * </ul>
 */
public final class HistoryTrim {

    /** Prompt size, as a percentage of the model's working context, that starts a compaction. */
    public static final int HIGH_WATER_PERCENT = 75;

    /** What one compaction cuts down to. The gap to the high water is what buys the batching. */
    public static final int LOW_WATER_PERCENT = 40;

    /**
     * Messages at the end that are never touched — what the worker is working from right now.
     * Eight is about four turns: the current tool results, the turn that asked for them, and the
     * two before that.
     */
    public static final int PROTECTED_TAIL_MESSAGES = 8;

    /** Marks an output this class already replaced, so a second pass does not re-drop it. */
    static final String ELIDED_MARK = "[dropped to save room]";

    /** Opens the block that stands where removed exchanges used to be. */
    static final String DIGEST_OPEN = "--- EARLIER PART OF THIS CONVERSATION REMOVED ---";

    /** Closes it. Everything between the two is rewritten by the next compaction. */
    static final String DIGEST_CLOSE = "--- END OF REMOVED PART ---";

    static final String DIGEST_CALLS_HEADER = "The most recent calls that were removed:";

    /**
     * How many removed calls the block names. THE reason the conversation has a ceiling: a block
     * that named every removed call would be a floor again, just with a gentler slope.
     */
    static final int MAX_DIGEST_CALLS = 24;

    /**
     * Characters per token. Deliberately crude: the exact number is a property of the tokenizer,
     * this is used only to decide when to act, and the alternative — the server's own reported
     * usage — is not available before the request that would fail is sent.
     */
    private static final int CHARS_PER_TOKEN = 4;

    /** Per-message framing the chat template adds (role tags, separators), in tokens. */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private static final int MAX_ARGS_IN_STUB = 160;

    private static final int MAX_ARGS_IN_DIGEST = 100;

    private HistoryTrim() {}

    /**
     * What one compaction did.
     *
     * @param messages     the history to use from now on — the same list when nothing was dropped
     * @param dropped      how many tool outputs were emptied in place
     * @param removed      how many whole messages were taken out, oldest first
     * @param tokensBefore estimated prompt size before
     * @param tokensAfter  estimated prompt size after
     */
    public record Result(List<Message> messages, int dropped, int removed,
                         int tokensBefore, int tokensAfter) {

        public boolean changed() {
            return dropped > 0 || removed > 0;
        }

        /** The line the operator reads in the run log. */
        public String sentence() {
            StringBuilder sb = new StringBuilder();
            if (dropped > 0) {
                sb.append("emptied ").append(dropped).append(" old tool result")
                    .append(dropped == 1 ? "" : "s");
            }
            if (removed > 0) {
                if (!sb.isEmpty()) {
                    sb.append(" and ");
                }
                sb.append("removed ").append(removed).append(" of its oldest message")
                    .append(removed == 1 ? "" : "s").append(" outright");
            }
            if (sb.isEmpty()) {
                sb.append("changed nothing");
            }
            return sb + " from the worker's history, " + tokensBefore + " -> " + tokensAfter
                + " tokens (estimated)";
        }
    }

    /** The two marks, derived from the one setting an operator already has per model. */
    public record Budget(int highWaterTokens, int lowWaterTokens) {

        /**
         * From {@code ModelQuirks.workingContextTokens()} — "the context SwarmCoder will let one
         * session occupy". That setting existed and nothing enforced it; this is what enforces it.
         * It is per model, editable in config, and since {@code ServerCapabilities} arrived it is
         * usually DISCOVERED from the server at startup: on the 2026-08 Spark, one running request's
         * fair share of the reported key/value cache, which is 51200 rather than the 32768 that had
         * been written down. Nothing here needs a second knob of its own.
         *
         * <p>The high-water mark is three quarters of it rather than all of it because the answer
         * comes out of the same allocation as the prompt: a quarter of the budget is left for the
         * model to generate into, and for one more turn's tool output to land before the next
         * compaction fires.
         */
        public static Budget forWorkingContext(int workingContextTokens) {
            // Long arithmetic, and not for tidiness: a working context above about 28 million
            // overflows an int when multiplied by the percentage, and the wrap lands on a SMALL
            // number — so the largest configurations would have compacted hardest. Caught by the
            // measurement test, which sets a deliberately absurd budget to switch compaction off.
            long working = Math.max(workingContextTokens, 2048);
            return new Budget((int) Math.min(Integer.MAX_VALUE, working * HIGH_WATER_PERCENT / 100),
                (int) Math.min(Integer.MAX_VALUE, working * LOW_WATER_PERCENT / 100));
        }
    }

    /** Estimated size of a whole conversation, in tokens. */
    public static int estimateTokens(List<Message> messages) {
        long tokens = 0;
        for (Message message : messages) {
            tokens += estimateMessage(message);
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /** Estimated size of one message, in tokens, including the framing the template adds. */
    private static int estimateMessage(Message message) {
        long chars = 0;
        for (MessagePart part : message.getParts()) {
            chars += sizeOf(part);
        }
        return (int) (MESSAGE_OVERHEAD_TOKENS + chars / CHARS_PER_TOKEN);
    }

    private static int sizeOf(MessagePart part) {
        if (part instanceof MessagePart.Text text) {
            return text.getText().length();
        }
        if (part instanceof MessagePart.Tool.Result result) {
            return result.getOutput().length() + result.getTool().length();
        }
        if (part instanceof MessagePart.Tool.Call call) {
            return call.getArgs().length() + call.getTool().length();
        }
        // Reasoning, attachments and anything a Koog upgrade adds: counted by their text form so an
        // unknown part can never be invisible to the budget.
        return String.valueOf(part).length();
    }

    /**
     * Cuts the conversation back under the low-water mark, or returns it untouched when it is below
     * the high-water mark or is already at its floor.
     *
     * <p>Oldest first, in both passes: the oldest read of a file is the one most likely to have been
     * superseded by a later read, or by the worker's own edit to that file.
     */
    public static Result trim(List<Message> messages, Budget budget) {
        int before = estimateTokens(messages);
        if (before <= budget.highWaterTokens()) {
            return new Result(messages, 0, 0, before, before);
        }

        List<Message> out = new ArrayList<>(messages);
        int low = lowWaterAbove(openingTokens(messages), budget);
        // What the session asked for a second time is what it is working from: it goes last.
        Set<String> askedAgain = askedAgainCalls(messages);
        int dropped = emptyOldToolOutputs(out, low, before, 0, null, askedAgain);
        int running = dropped == 0 ? before : estimateTokens(out);
        if (running > low && !askedAgain.isEmpty()) {
            int more = emptyOldToolOutputs(out, low, running, 0, askedAgain, null);
            dropped += more;
            running = more == 0 ? running : estimateTokens(out);
        }
        int removed = running <= low ? 0 : removeOldestExchanges(out, low);

        if (dropped == 0 && removed == 0) {
            return new Result(messages, 0, 0, before, before);
        }
        return new Result(List.copyOf(out), dropped, removed, before, estimateTokens(out));
    }

    /**
     * The mark a compaction cuts to, given what can never be cut.
     *
     * <p>Harness run 79 (2026-10-04): the planner's system prompt, opening and last four turns
     * (the opening alone is what is passed here) were about 14,000 tokens in a room whose marks were 24,576 and 13,107. A low-water mark at
     * or under the fixed part means every compaction empties EVERY result the session holds, so
     * it read the same twelve files again after each of 14 compactions - 100 turns, 308 calls,
     * no plan. A cut must leave something to work from: where the opening comes within half
     * the span of the two marks of the low one, the cut goes half way between the opening and
     * the high mark instead. Where the opening is small the configured mark stands.
     */
    static int lowWaterAbove(int openingTokens, Budget budget) {
        int high = budget.highWaterTokens();
        int low = budget.lowWaterTokens();
        if (openingTokens >= high || openingTokens + (high - low) / 2 <= low) {
            return low;
        }
        return Math.max(low, openingTokens + (high - openingTokens) / 2);
    }

    /** The system prompt and the opening instruction: what is sent whole on every call. */
    static int openingTokens(List<Message> messages) {
        int tokens = 0;
        int start = firstExchangeIndex(messages);
        for (int i = 0; i < start; i++) {
            tokens += estimateMessage(messages.get(i));
        }
        return tokens;
    }

    /**
     * The smallest this conversation could be made — what would be left if everything removable
     * went: the system prompt, the opening instruction with its block of removed calls, and the
     * protected tail.
     *
     * <p>The one number that says whether compaction can do its job here at all. A caller that finds
     * this above the budget knows the worker cannot be made to fit and must be stopped, rather than
     * compacting fruitlessly on every turn for another eighty turns — which is exactly what the
     * broken version did.
     */
    public static int floorTokens(List<Message> messages) {
        int start = firstExchangeIndex(messages);
        int end = Math.max(start, messages.size() - PROTECTED_TAIL_MESSAGES);
        int floor = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (i < start || i >= end) {
                floor += estimateMessage(messages.get(i));
            }
        }
        return floor;
    }

    /**
     * Pass one. Replaces each old tool output with a line naming the call that produced it, until
     * the conversation is under the low-water mark or no full output is left outside the protected
     * tail.
     *
     * @return how many outputs were emptied
     */
    private static int emptyOldToolOutputs(List<Message> out, int lowWater, int running) {
        return emptyOldToolOutputs(out, lowWater, running, 0);
    }

    /**
     * Makes a conversation that looks things up small again BEFORE it is anywhere near its room
     * (2026-10-02, the expert): once the tool results outside the protected tail add up to more than
     * {@code aboveTokens}, the oldest are replaced by a digest - the tool, its arguments and the
     * first {@code digestChars} characters of what it returned - until what is left of them is at
     * most {@code toTokens}.
     *
     * <p>Why: harness run 66 sent the expert's model 989,781 prompt tokens to have it write 24,531.
     * Every turn resends every file body an earlier turn read, and a session that has read a file
     * and moved on does not need its whole text again - it needs to know that it read it, what it
     * was, and how to get it back. Nothing here is a limit and nothing here can stop a session:
     * {@link #trim} and its stops are untouched and still apply at the real room.
     *
     * <p>Two thresholds, not one, for the reason {@link #HIGH_WATER_PERCENT} and
     * {@link #LOW_WATER_PERCENT} are two: each rewrite costs the model server its cached prefix
     * from the rewritten message on, so it must buy several turns, not one.
     *
     * <p><b>Superseded results go first</b> (2026-10-02, when the workers got this too). A result
     * is superseded when the session later made the same call again, or a later call names the
     * same {@code path}: the file was read again, or written. The later result is the true one,
     * so the earlier one is the cheapest thing in the conversation to cut - cheaper than an
     * older result nothing has replaced. Only then does it go oldest first. Being superseded
     * does not start a tidy on its own: it only decides the order once the threshold is passed,
     * so the number of rewrites - and of cold prefills - is what the two thresholds make it.
     *
     * <p><b>Old write arguments too.</b> The whole file bodies and diffs a worker sent in earlier
     * {@code write_file} and {@code apply_diff} calls are resent every turn like old results, and
     * the files are on disk. They count toward the threshold and, after the superseded results,
     * go next: the call keeps its id and its path and the body becomes a line saying how much was
     * sent and how it began ({@link #digestArguments}); the last few turns stay whole.
     *
     * <p><b>First lines do not stay for ever</b> (live run 93, 2026-10-08). A digest keeps up to
     * {@code digestChars} characters and a sentence, about 200 tokens, and nothing ever shortened
     * one: after the architect's 134th lookup the first lines alone were about 20,000 tokens -
     * the mark itself - resent on every call, counted by nothing, and its second tidy could
     * take only 10,000 off a conversation of 47,668. So the first lines kept by an
     * <i>earlier</i> tidy count toward the threshold like whole results, and they go before any
     * whole result that nothing has superseded: each becomes one line that still names the
     * call ({@link #collapse}). A result is whole, then its first lines, then one line.
     */
    public static Result tidy(List<Message> messages, int aboveTokens, int toTokens,
                              int digestChars) {
        int before = estimateTokens(messages);
        if (aboveTokens <= 0) {
            return new Result(messages, 0, 0, before, before);
        }
        // A result the session asked for a second time is what it works from: a tidy leaves
        // it whole and does not count it (run 79). A compaction, at the real room, may cut it.
        Set<String> askedAgain = askedAgainCalls(messages);
        Set<String> superseded = supersededCalls(messages);
        askedAgain.removeAll(superseded);
        int droppable = 0;
        int last = messages.size() - PROTECTED_TAIL_MESSAGES;
        for (int i = firstDroppableIndex(messages); i < last; i++) {
            if (messages.get(i) instanceof Message.User user) {
                for (MessagePart part : user.getParts()) {
                    if (part instanceof MessagePart.Tool.Result result && !isAlreadyDropped(result)
                            && !askedAgain.contains(result.getId())) {
                        droppable += result.getOutput().length() / CHARS_PER_TOKEN;
                    }
                }
            }
        }
        // The whole file bodies and diffs the worker sent in earlier write_file / apply_diff calls
        // are resent every turn too, and the files are on disk now.
        droppable += trimmableArgumentTokens(messages);
        // And the first lines an earlier tidy kept of results older still (run 93).
        droppable += earlierDigestTokens(messages);
        if (droppable <= aboveTokens) {
            return new Result(messages, 0, 0, before, before);
        }
        List<Message> out = new ArrayList<>(messages);
        int target = before - (droppable - Math.max(0, toTokens));
        int dropped = emptyOldToolOutputs(out, target, before, Math.max(1, digestChars),
            superseded);
        int running = dropped == 0 ? before : estimateTokens(out);
        if (running > target) {
            // Next the arguments of old write calls: what was written is in the file, which the
            // worker can read again; the call keeps its path and a short digest of what it sent.
            int trimmed = trimOldCallArguments(out, target, running);
            dropped += trimmed;
            running = trimmed == 0 ? running : estimateTokens(out);
        }
        if (running > target) {
            // Then what is oldest of all: the first lines an earlier tidy left.
            int collapsed = collapseEarlierDigests(out, target, running);
            dropped += collapsed;
            running = collapsed == 0 ? running : estimateTokens(out);
        }
        if (running > target) {
            dropped += emptyOldToolOutputs(out, target, running, Math.max(1, digestChars), null,
                askedAgain);
        }
        if (dropped == 0) {
            return new Result(messages, 0, 0, before, before);
        }
        return new Result(List.copyOf(out), dropped, 0, before, estimateTokens(out));
    }

    /** Tools whose call arguments carry a whole file body or diff the worker sent. */
    private static final Set<String> BODY_TOOLS = Set.of("write_file", "apply_diff");

    /** An argument no longer than this is left whole: its digest would be no shorter. */
    private static final int ARGS_NOT_WORTH_IT = 600;

    /** How much of the head of a written file its digest keeps. */
    private static final int ARGS_HEAD_CHARS = 100;

    private static final java.util.regex.Pattern CONTENT_ARGUMENT =
        java.util.regex.Pattern.compile("\"content\"\\s*:\\s*\"");

    private static final java.util.regex.Pattern FIRST_KEY =
        java.util.regex.Pattern.compile("^\\s*\\{\\s*\"([^\"]+)\"");

    private static final java.util.regex.Pattern DIFF_TARGET =
        java.util.regex.Pattern.compile("\\+\\+\\+ (?:b/)?([^\\\\\\s\"]+)");

    private static boolean isTrimmableCall(MessagePart.Tool.Call call) {
        String args = call.getArgs();
        return BODY_TOOLS.contains(call.getTool()) && args != null
            && args.length() > ARGS_NOT_WORTH_IT && !args.contains(ELIDED_MARK);
    }

    /** What the arguments of old write calls outside the protected tail would give back. */
    private static int trimmableArgumentTokens(List<Message> messages) {
        int last = messages.size() - PROTECTED_TAIL_MESSAGES;
        int tokens = 0;
        for (int i = firstExchangeIndex(messages); i < last; i++) {
            if (messages.get(i) instanceof Message.Assistant assistant) {
                for (MessagePart part : assistant.getParts()) {
                    if (part instanceof MessagePart.Tool.Call call && isTrimmableCall(call)) {
                        tokens += call.getArgs().length() / CHARS_PER_TOKEN;
                    }
                }
            }
        }
        return tokens;
    }

    /**
     * Replaces the arguments of old {@code write_file} and {@code apply_diff} calls - the whole file
     * bodies and diffs the worker sent, which every later turn resends - by their path and a short
     * digest, oldest first, until the conversation is down to {@code lowWater} or nothing old is
     * left. The call, its id and its path stay: the history still says what was written where; the
     * text is in the file.
     *
     * @return how many calls were trimmed
     */
    private static int trimOldCallArguments(List<Message> out, int lowWater, int running) {
        int last = out.size() - PROTECTED_TAIL_MESSAGES;
        int trimmed = 0;
        for (int i = firstExchangeIndex(out); i < last && running > lowWater; i++) {
            if (!(out.get(i) instanceof Message.Assistant assistant)) {
                continue;
            }
            List<MessagePart.ResponsePart> parts = new ArrayList<>();
            int saved = 0;
            int here = 0;
            for (MessagePart.ResponsePart part : assistant.getParts()) {
                if (part instanceof MessagePart.Tool.Call call && isTrimmableCall(call)) {
                    String digest = digestArguments(call.getTool(), call.getArgs());
                    if (digest.length() < call.getArgs().length()) {
                        saved += call.getArgs().length() - digest.length();
                        here++;
                        parts.add(call.copy(call.getId(), call.getTool(), digest,
                            call.getCacheControl()));
                        continue;
                    }
                }
                parts.add(part);
            }
            if (here == 0) {
                continue;
            }
            out.set(i, assistant.copy(parts, assistant.getMetaInfo(), assistant.getFinishReason(),
                assistant.getRawResponse(), assistant.getId()));
            running -= saved / CHARS_PER_TOKEN;
            trimmed += here;
        }
        return trimmed;
    }

    /**
     * The arguments of a write call, kept as valid JSON with the same keys: the path as sent, and
     * in place of the body a line that says how much was sent, that it is in the file now, and how
     * it began.
     */
    static String digestArguments(String tool, String args) {
        if ("apply_diff".equals(tool)) {
            java.util.regex.Matcher key = FIRST_KEY.matcher(args);
            String name = key.find() ? key.group(1) : "unifiedDiff";
            Set<String> files = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher target = DIFF_TARGET.matcher(args);
            while (target.find() && files.size() < 6) {
                files.add(target.group(1));
            }
            return "{\"" + name + "\":\"" + ELIDED_MARK + " A diff of " + args.length()
                + " characters" + (files.isEmpty() ? "" : " touching " + String.join(", ", files))
                + " was applied here. The files are changed on disk now; read them to see how.\"}";
        }
        java.util.regex.Matcher path = PATH_ARGUMENT.matcher(args);
        String where = path.find() ? path.group(1) : "";
        java.util.regex.Matcher body = CONTENT_ARGUMENT.matcher(args);
        String head = "";
        if (body.find()) {
            int from = body.end();
            head = args.substring(from, Math.min(args.length(), from + ARGS_HEAD_CHARS));
            for (int i = 0; i < head.length(); i++) {
                if (head.charAt(i) == '\\') {
                    i++; // an escape: its next character is not a closing quote
                } else if (head.charAt(i) == '"') {
                    head = head.substring(0, i); // the content ended inside the head
                    break;
                }
            }
            int slashes = 0;
            while (head.length() > slashes && head.charAt(head.length() - 1 - slashes) == '\\') {
                slashes++;
            }
            if (slashes % 2 == 1) {
                head = head.substring(0, head.length() - 1); // never end in half an escape
            }
        }
        return "{\"path\":\"" + where + "\",\"content\":\"" + ELIDED_MARK + " The " + args.length()
            + " characters you sent here are in the file now; read it to see them."
            + (head.isEmpty() ? "" : " It began: " + head + "...") + "\"}";
    }

    /** A {@code "path": "..."} argument of a tool call, as the model wrote it. */
    private static final java.util.regex.Pattern PATH_ARGUMENT =
        java.util.regex.Pattern.compile("\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * The ids of the tool calls a later call has superseded: the same tool with the same
     * arguments again, or any later call naming the same {@code path} - the file read again, or
     * written since.
     */
    static Set<String> supersededCalls(List<Message> messages) {
        Set<String> superseded = new HashSet<>();
        Map<String, String> lastBySignature = new HashMap<>();
        Map<String, String> lastByPath = new HashMap<>();
        // A call answered "unchanged" sent nothing new: it supersedes nothing, or the earlier
        // result it points the model back to would be the first thing a tidy cut (section 59).
        Set<String> unchanged = unchangedCalls(messages);
        for (Message message : messages) {
            for (MessagePart part : message.getParts()) {
                if (!(part instanceof MessagePart.Tool.Call call) || call.getId() == null
                        || unchanged.contains(call.getId())) {
                    continue;
                }
                String args = call.getArgs() == null ? "" : call.getArgs();
                String earlier = lastBySignature.put(call.getTool() + "\n" + args, call.getId());
                if (earlier != null) {
                    superseded.add(earlier);
                }
                java.util.regex.Matcher path = PATH_ARGUMENT.matcher(args);
                if (path.find()) {
                    String before = lastByPath.put(path.group(1), call.getId());
                    if (before != null) {
                        superseded.add(before);
                    }
                }
            }
        }
        return superseded;
    }

    /**
     * The ids of the calls that repeat an earlier call of this conversation exactly: the session
     * had that result, lost it to a tidy or a compaction (or never let go of needing it) and
     * asked for it again. That is the session saying which results it works from, so they are
     * the last to be cut (run 79: the planner read twelve files four to nine times each).
     * The opposite end of {@link #supersededCalls}: there the earlier call, here the later one.
     */
    static Set<String> askedAgainCalls(List<Message> messages) {
        Set<String> again = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Set<String> unchanged = unchangedCalls(messages);
        for (Message message : messages) {
            for (MessagePart part : message.getParts()) {
                if (!(part instanceof MessagePart.Tool.Call call) || call.getId() == null
                        || unchanged.contains(call.getId())) {
                    continue;
                }
                String args = call.getArgs() == null ? "" : call.getArgs();
                if (!seen.add(call.getTool() + "\n" + args)) {
                    again.add(call.getId());
                }
            }
        }
        return again;
    }

    /**
     * What a repeated lookup is answered with when the conversation still holds the same answer
     * whole (section 59). Not a cap: the lookup was made, and anything that differs from what is
     * held is sent as it is.
     */
    static final String UNCHANGED_MARK =
        "unchanged; the full answer is already in this conversation";

    /** An answer shorter than this is sent again: the line saying so would be as long. */
    static final int UNCHANGED_MIN_CHARS = 300;

    /** The ids of the calls whose result is an "unchanged" line. */
    static Set<String> unchangedCalls(List<Message> messages) {
        Set<String> ids = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof Message.User user) {
                for (MessagePart part : user.getParts()) {
                    if (part instanceof MessagePart.Tool.Result result && result.getId() != null
                            && result.getOutput().startsWith(UNCHANGED_MARK)) {
                        ids.add(result.getId());
                    }
                }
            }
        }
        return ids;
    }

    /** The last note a tool result ends with: a blank line, then text in square brackets. */
    private static String withoutNote(String output) {
        int at = output.lastIndexOf("\n\n[");
        return at > 0 && output.endsWith("]") ? output.substring(0, at) : output;
    }

    /**
     * The line to send instead of {@code output}, or null to send it. It is a line when the
     * conversation holds an earlier call of the same tool with the same arguments whose result is
     * still whole (not tidied or compacted away, not itself an "unchanged" line) and equal to
     * {@code output}, so the file or index behind it has not changed. Any note the output ends
     * with (the turn countdown) is kept.
     *
     * @param messages the conversation as it will be sent, this turn's results not yet in it
     */
    static String unchangedRepeat(List<Message> messages, String tool, String args,
                                  String output) {
        if (output == null || output.length() < UNCHANGED_MIN_CHARS || messages == null) {
            return null;
        }
        String wanted = args == null ? "" : args;
        Map<String, Integer> callTurn = new HashMap<>();
        int assistants = 0;
        int turnsAgo = -1;
        String body = withoutNote(output);
        for (Message message : messages) {
            if (message instanceof Message.Assistant assistant) {
                assistants++;
                for (MessagePart part : assistant.getParts()) {
                    if (part instanceof MessagePart.Tool.Call call && call.getId() != null
                            && tool.equals(call.getTool())
                            && wanted.equals(call.getArgs() == null ? "" : call.getArgs())) {
                        callTurn.put(call.getId(), assistants);
                    }
                }
            } else if (message instanceof Message.User user) {
                for (MessagePart part : user.getParts()) {
                    if (part instanceof MessagePart.Tool.Result result
                            && callTurn.containsKey(result.getId())
                            && !isAlreadyDropped(result)
                            && !result.getOutput().startsWith(UNCHANGED_MARK)
                            && withoutNote(result.getOutput()).equals(body)) {
                        turnsAgo = callTurn.get(result.getId());
                    }
                }
            }
        }
        if (turnsAgo < 0) {
            return null;
        }
        int ago = Math.max(1, assistants - turnsAgo);
        String note = output.substring(body.length());
        return UNCHANGED_MARK + ", from your call " + ago + (ago == 1 ? " turn" : " turns")
            + " ago." + note;
    }

    /** @param digestChars how much of each emptied output's head its stub keeps; 0 keeps none */
    private static int emptyOldToolOutputs(List<Message> out, int lowWater, int running,
                                           int digestChars) {
        return emptyOldToolOutputs(out, lowWater, running, digestChars, null);
    }

    /**
     * @param only when not null, only the results of these call ids are touched
     */
    private static int emptyOldToolOutputs(List<Message> out, int lowWater, int running,
                                           int digestChars, Set<String> only) {
        return emptyOldToolOutputs(out, lowWater, running, digestChars, only, null);
    }

    /**
     * @param only   when not null, only the results of these call ids are touched
     * @param except when not null, the results of these call ids are left alone
     */
    private static int emptyOldToolOutputs(List<Message> out, int lowWater, int running,
                                           int digestChars, Set<String> only, Set<String> except) {
        if (only != null && only.isEmpty()) {
            return 0;
        }
        Map<String, String> argsByCallId = callArguments(out);
        int last = out.size() - PROTECTED_TAIL_MESSAGES;
        int dropped = 0;

        for (int i = firstDroppableIndex(out); i < last && running > lowWater; i++) {
            if (!(out.get(i) instanceof Message.User user)) {
                continue;
            }
            List<MessagePart.RequestPart> parts = new ArrayList<>();
            int saved = 0;
            int droppedHere = 0;
            for (MessagePart.RequestPart part : user.getParts()) {
                if (part instanceof MessagePart.Tool.Result result && !isAlreadyDropped(result)
                        && (only == null || only.contains(result.getId()))
                        && (except == null || !except.contains(result.getId()))) {
                    String stub = digestChars > 0
                        ? digest(result, argsByCallId.get(result.getId()), digestChars)
                        : stub(result, argsByCallId.get(result.getId()));
                    saved += result.getOutput().length() - stub.length();
                    droppedHere++;
                    // Koog 1.2.0: MessagePart.Tool.Result.copy() now takes the output as a
                    // List<ContentPart> (multipart tool results), not a String — but the legacy
                    // (id, tool, String, isError) constructor is kept for exactly this case, so a
                    // plain-text stub is built directly rather than through copy().
                    parts.add(new MessagePart.Tool.Result(result.getId(), result.getTool(), stub, result.isError()));
                } else {
                    parts.add(part);
                }
            }
            if (droppedHere == 0 || saved <= 0) {
                continue;
            }
            out.set(i, user.copy(parts, user.getMetaInfo(), user.getId()));
            running -= saved / CHARS_PER_TOKEN;
            dropped += droppedHere;
        }
        return dropped;
    }

    /**
     * Pass two — the one that gives the conversation a ceiling. Takes whole messages out of the
     * front, in balanced spans, until it is under the low-water mark or nothing is left between the
     * head and the protected tail.
     *
     * <p><b>Balanced spans.</b> A span may only end where every tool call inside it has been
     * answered inside it. That is the whole safety argument for removing messages rather than
     * emptying them: a {@code role: tool} result whose call is gone, or a tool call whose result is
     * gone, is a malformed request that most servers reject outright.
     *
     * @return how many messages were removed
     */
    private static int removeOldestExchanges(List<Message> out, int lowWater) {
        int start = firstExchangeIndex(out);
        int end = out.size() - PROTECTED_TAIL_MESSAGES;
        if (start >= end) {
            return 0;
        }

        int cut = start;
        int cutRunning = estimateTokens(out);
        int scan = start;
        int scanRunning = cutRunning;
        Set<String> openCalls = new HashSet<>();
        List<String> pending = new ArrayList<>();
        List<String> committed = new ArrayList<>();

        while (scan < end && cutRunning > lowWater) {
            Message message = out.get(scan);
            for (MessagePart part : message.getParts()) {
                if (part instanceof MessagePart.Tool.Call call) {
                    openCalls.add(String.valueOf(call.getId()));
                    pending.add(digestLine(call));
                } else if (part instanceof MessagePart.Tool.Result result) {
                    openCalls.remove(String.valueOf(result.getId()));
                }
            }
            scanRunning -= estimateMessage(message);
            scan++;
            if (openCalls.isEmpty()) {
                // A balanced boundary: everything asked for inside the span was answered inside it.
                cut = scan;
                cutRunning = scanRunning;
                committed.addAll(pending);
                pending.clear();
            }
        }
        if (cut == start) {
            return 0;
        }

        List<String> lines = carriedDigestLines(out, start);
        lines.addAll(committed);
        String digest = digest(lines);
        out.subList(start, cut).clear();
        writeDigest(out, start, digest);
        return cut - start;
    }

    /**
     * The first message that may be REMOVED: the first assistant turn. Everything before it is the
     * system prompt and the opening instruction, and those are the two things that must never go.
     */
    private static int firstExchangeIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.Assistant) {
                return i;
            }
        }
        return messages.size();
    }

    /** The first message whose tool output may be EMPTIED: the first one that has a tool output. */
    private static int firstDroppableIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.User user && hasToolResult(user)) {
                return i;
            }
        }
        return messages.size();
    }

    private static boolean hasToolResult(Message.User user) {
        for (MessagePart part : user.getParts()) {
            if (part instanceof MessagePart.Tool.Result) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAlreadyDropped(MessagePart.Tool.Result result) {
        return result.getOutput().startsWith(ELIDED_MARK);
    }

    /** Tool-call arguments by call id, so a dropped result can say what question it answered. */
    private static Map<String, String> callArguments(List<Message> messages) {
        Map<String, String> args = new HashMap<>();
        for (Message message : messages) {
            for (MessagePart part : message.getParts()) {
                if (part instanceof MessagePart.Tool.Call call && call.getId() != null) {
                    args.put(call.getId(), call.getArgs());
                }
            }
        }
        return args;
    }

    /**
     * What stands where an emptied output used to be. It names the tool and the exact arguments, so
     * the worker can repeat the call verbatim, and it says in plain words that the content is gone —
     * the whole point being that the worker re-reads rather than trusting a memory of a file it
     * looked at fifty turns ago and has edited since.
     */
    static String stub(MessagePart.Tool.Result result, String args) {
        String shortArgs = args == null || args.isBlank() ? ""
            : " " + (args.length() > MAX_ARGS_IN_STUB ? args.substring(0, MAX_ARGS_IN_STUB) + "..." : args);
        return ELIDED_MARK + " This was the output of " + result.getTool() + shortArgs
            + " from an earlier turn. The text is gone from your history and files may have changed"
            + " since. Run the tool again if you still need the answer.";
    }

    /** A result this little longer than its digest is left whole. */
    private static final int DIGEST_NOT_WORTH_IT = 400;

    /**
     * {@link #stub} for a session that looks things up: it also keeps the head of what the tool
     * returned - a file's package and imports, a type's declaration, a search's best hits - so the
     * session still knows what it read and can ask for it again by the same call.
     */
    static String digest(MessagePart.Tool.Result result, String args, int digestChars) {
        String output = result.getOutput();
        if (output.length() <= digestChars + DIGEST_NOT_WORTH_IT) {
            return output; // the digest would be no shorter than the thing itself
        }
        String shortArgs = args == null || args.isBlank() ? ""
            : " " + (args.length() > MAX_ARGS_IN_STUB ? args.substring(0, MAX_ARGS_IN_STUB) + "..." : args);
        int cut = output.lastIndexOf('\n', digestChars);
        String head = output.substring(0,
            Math.min(output.length(), cut > digestChars / 2 ? cut : digestChars));
        return ELIDED_MARK + " You read this earlier with " + result.getTool() + shortArgs
            + "; only its beginning is kept (" + output.length() + " characters were returned)."
            + " Make the same call again if you need the rest.\n" + head + "\n...";
    }

    /** How a digest says it kept a head; what tells a digest from a stub or a collapsed one. */
    private static final String DIGEST_KEPT = "; only its beginning is kept (";
    private static final String DIGEST_COUNTED = " characters were returned).";

    private static boolean isDigest(MessagePart.Tool.Result result) {
        String output = result.getOutput();
        return output.startsWith(ELIDED_MARK) && output.contains(DIGEST_KEPT);
    }

    /**
     * A digest without the head it kept: one line that names the call, says how much it
     * returned and that none of it is here. Null when the text is not a digest.
     */
    static String collapse(String digest) {
        int kept = digest == null ? -1 : digest.indexOf(DIGEST_KEPT);
        int counted = kept < 0 ? -1 : digest.indexOf(DIGEST_COUNTED, kept);
        if (counted < 0 || !digest.startsWith(ELIDED_MARK)) {
            return null;
        }
        return digest.substring(0, kept) + "; none of it is kept ("
            + digest.substring(kept + DIGEST_KEPT.length(), counted)
            + " characters were returned). Make the same call again if you need it.";
    }

    /** What the digests outside the protected tail hold beyond the one line each would become. */
    private static int earlierDigestTokens(List<Message> messages) {
        int tokens = 0;
        int last = messages.size() - PROTECTED_TAIL_MESSAGES;
        for (int i = firstDroppableIndex(messages); i < last; i++) {
            if (messages.get(i) instanceof Message.User user) {
                for (MessagePart part : user.getParts()) {
                    if (part instanceof MessagePart.Tool.Result result && isDigest(result)) {
                        String line = collapse(result.getOutput());
                        if (line != null) {
                            tokens += (result.getOutput().length() - line.length())
                                / CHARS_PER_TOKEN;
                        }
                    }
                }
            }
        }
        return tokens;
    }

    /**
     * Turns the digests an earlier tidy left into one line each, oldest first, until the
     * conversation is down to {@code target}.
     *
     * @return how many were shortened
     */
    private static int collapseEarlierDigests(List<Message> out, int target, int running) {
        int last = out.size() - PROTECTED_TAIL_MESSAGES;
        int collapsed = 0;
        for (int i = firstDroppableIndex(out); i < last && running > target; i++) {
            if (!(out.get(i) instanceof Message.User user)) {
                continue;
            }
            List<MessagePart.RequestPart> parts = new ArrayList<>();
            int saved = 0;
            int here = 0;
            for (MessagePart.RequestPart part : user.getParts()) {
                String line = part instanceof MessagePart.Tool.Result result && isDigest(result)
                    ? collapse(result.getOutput()) : null;
                if (line != null) {
                    MessagePart.Tool.Result result = (MessagePart.Tool.Result) part;
                    saved += result.getOutput().length() - line.length();
                    here++;
                    parts.add(new MessagePart.Tool.Result(result.getId(), result.getTool(), line,
                        result.isError()));
                } else {
                    parts.add(part);
                }
            }
            if (here == 0 || saved <= 0) {
                continue;
            }
            out.set(i, user.copy(parts, user.getMetaInfo(), user.getId()));
            running -= saved / CHARS_PER_TOKEN;
            collapsed += here;
        }
        return collapsed;
    }

    /** One removed call, named the way the worker would have to type it to make it again. */
    private static String digestLine(MessagePart.Tool.Call call) {
        String args = call.getArgs() == null ? "" : call.getArgs();
        if (args.length() > MAX_ARGS_IN_DIGEST) {
            args = args.substring(0, MAX_ARGS_IN_DIGEST) + "...";
        }
        return "- " + call.getTool() + " " + args;
    }

    /**
     * The block that stands where the removed exchanges were. Fixed prose plus at most
     * {@link #MAX_DIGEST_CALLS} lines, so its size does not depend on how much was removed — which
     * is what makes the conversation's ceiling a ceiling.
     */
    private static String digest(List<String> lines) {
        List<String> shown = lines.size() <= MAX_DIGEST_CALLS ? lines
            : lines.subList(lines.size() - MAX_DIGEST_CALLS, lines.size());
        StringBuilder sb = new StringBuilder(DIGEST_OPEN).append('\n')
            .append("Everything you did before this point has been taken out of your history to")
            .append(" make room: the files you read, the text you wrote, and your own notes about")
            .append(" them. Files may have changed since. If you still need something from back")
            .append(" then, run the tool again - do not rely on remembering it.\n")
            .append(DIGEST_CALLS_HEADER).append('\n');
        for (String line : shown) {
            sb.append(line).append('\n');
        }
        sb.append(DIGEST_CLOSE);
        return sb.toString();
    }

    /**
     * The call lines a previous compaction already wrote, so a running record of what the worker has
     * done survives one removal after another rather than restarting at each one.
     */
    private static List<String> carriedDigestLines(List<Message> messages, int start) {
        List<String> lines = new ArrayList<>();
        if (start == 0) {
            return lines;
        }
        String text = messages.get(start - 1).textContent();
        int open = text.indexOf(DIGEST_CALLS_HEADER);
        int close = text.indexOf(DIGEST_CLOSE);
        if (open < 0 || close < open) {
            return lines;
        }
        for (String line : text.substring(open + DIGEST_CALLS_HEADER.length(), close).split("\n")) {
            if (line.startsWith("- ")) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Puts the block into the opening instruction rather than inserting a message of its own.
     *
     * <p>Two reasons. It keeps the roles alternating from the head onwards, which some chat
     * templates require and none dislike; and the opening instruction is the one message the worker
     * is guaranteed to read, so a note about what is missing cannot be missed there. Keeping each
     * compaction's block as a separate appended message would preserve a thousand tokens more of
     * this session's own prefix cache, which is a twentieth of one prefill a handful of times per
     * worker — not worth a run of consecutive user messages that some chat templates reject.
     */
    private static void writeDigest(List<Message> out, int start, String digest) {
        int carrier = start - 1;
        if (carrier < 0 || !(out.get(carrier) instanceof Message.User user)) {
            out.add(Math.max(start, 0), new Message.User(List.of(new MessagePart.Text(digest, null)),
                RequestMetaInfo.Companion.getEmpty(), null));
            return;
        }
        String text = user.textContent();
        int open = text.indexOf(DIGEST_OPEN);
        String base = open < 0 ? text : text.substring(0, open).stripTrailing();
        out.set(carrier, user.copy(List.of(new MessagePart.Text(base + "\n\n" + digest, null)),
            user.getMetaInfo(), user.getId()));
    }
}
