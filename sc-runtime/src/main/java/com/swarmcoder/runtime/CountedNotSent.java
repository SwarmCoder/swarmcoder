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

/**
 * What a model server counted as input on one session's calls and the session did not send
 * (section 94), and whether the next request should therefore begin a new user message.
 *
 * <p>Some servers keep what the model thought before each tool call and put it back into every
 * later request of the same user message, counted as input. The session never sends it and
 * cannot remove it; the server lets it go when a new user message begins. This class sees only
 * what a session can observe: for each call, the size of the conversation that was sent
 * (estimated) and the input tokens the server reported. Nothing here knows a model or a vendor.
 *
 * <p>The first call of a session can hold no earlier thinking, so it fixes what the server
 * counts on top of the conversation (the tool descriptions, the template): reported minus sent.
 * On every later call, reported minus sent minus that is the excess. A new user message is due
 * when the excess is above the tolerance and either old results are being tidied on this turn
 * anyway (that call's prompt cache is lost in any case) or the excess alone has passed the mark
 * at which old results are tidied.
 *
 * <p>The call that begins a new user message is judged by the server's count itself: it fell,
 * against what the conversation's growth alone would have made it, by at least a third of the
 * excess - then the excess is measured afresh from that call - or this server does not let go
 * of it that way and no further message is begun in this session.
 *
 * <p>Measured on the paid server on 2026-10-13 (docs/DEVELOPER_CORRECTIONS.md, section 94):
 * each call's input was the call before's input, plus all of that call's output with its
 * thinking, plus the new results; a request ending on a new user message was counted without
 * any of the session's earlier thinking, and lost the prompt cache for that one call.
 *
 * <p>One per session; a session makes one call at a time.
 */
final class CountedNotSent {

    /** Switches the whole behaviour off: {@code -Dswarmcoder.countedNotSent=false}. */
    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("swarmcoder.countedNotSent"));
    }

    /** An excess below this many tokens is never acted on. */
    static int leastTokens() {
        return Math.max(0, Integer.getInteger("swarmcoder.countedNotSent.leastTokens", 1_000));
    }

    /** An excess below this share of what was sent is never acted on, in percent. */
    static int leastPercent() {
        return Math.max(0, Integer.getInteger("swarmcoder.countedNotSent.leastPercent", 10));
    }

    private final boolean on;
    private final int leastTokens;
    private final int leastPercent;

    private boolean based;
    private long onTop;
    private long excess;
    private long sent;
    private long reported;
    /** The excess of the call before a new message was begun; -1 when none is being judged. */
    private long excessBeforeNewMessage = -1;
    private boolean noUse;
    private int begun;

    CountedNotSent() {
        this(enabled(), leastTokens(), leastPercent());
    }

    CountedNotSent(boolean on, int leastTokens, int leastPercent) {
        this.on = on;
        this.leastTokens = leastTokens;
        this.leastPercent = leastPercent;
    }

    /**
     * One call has been answered.
     *
     * @param sentTokens     the conversation as it was sent, estimated in tokens
     * @param reportedTokens the server's count of the call's input; negative when it gave none
     */
    synchronized void called(long sentTokens, long reportedTokens) {
        if (reportedTokens < 0 || sentTokens < 0) {
            return;
        }
        if (!based) {
            based = true;
            onTop = reportedTokens - sentTokens;
            excess = 0;
            sent = sentTokens;
            reported = reportedTokens;
            return;
        }
        if (excessBeforeNewMessage >= 0) {
            // The call that began a new user message. Had the server gone on as before, its
            // count would have grown by what was added to the conversation; it let go of at
            // least a third of the excess, or this is not a server a new message helps on.
            long fell = reported + (sentTokens - sent) - reportedTokens;
            if (fell * 3 < excessBeforeNewMessage) {
                noUse = true;
            } else {
                // Nothing earlier is counted on this call, as on a first call: measure from
                // here, so that what the estimate was out by does not add up over a session.
                onTop = reportedTokens - sentTokens;
            }
            excessBeforeNewMessage = -1;
        }
        sent = sentTokens;
        reported = reportedTokens;
        excess = reportedTokens - sentTokens - onTop;
    }

    /** What the latest call was counted for and did not send; 0 before the second call. */
    synchronized long excessTokens() {
        return Math.max(0, excess);
    }

    /** True when the latest call's excess is more than the tolerance. */
    synchronized boolean shown() {
        return based && excess > Math.max(leastTokens, sent * leastPercent / 100);
    }

    /** True once a new user message was begun and the server went on counting as before. */
    synchronized boolean noUse() {
        return noUse;
    }

    /** How many user messages this session has begun for this reason. */
    synchronized int begun() {
        return begun;
    }

    /**
     * Whether the request about to be sent should begin a new user message.
     *
     * @param tidiedNow true when old results were shortened on this turn
     * @param tidyMark  the mark above which the session tidies old results, in tokens; 0 or
     *                  less for a session that never tidies, which then never begins one
     */
    synchronized boolean newMessageDue(boolean tidiedNow, int tidyMark) {
        if (!on || noUse || tidyMark <= 0 || !shown()) {
            return false;
        }
        return tidiedNow || excess > tidyMark;
    }

    /** A new user message is being begun with the request about to be sent. */
    synchronized void beginsNewMessage() {
        excessBeforeNewMessage = Math.max(0, excess);
        begun++;
    }
}
