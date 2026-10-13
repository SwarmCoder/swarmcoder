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
package com.swarmcoder.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every model call of one run, one by one, in the order they ended (stored in
 * {@link RunUsageRecord}).
 *
 * <p><b>Why it is not a list of objects.</b> A build makes thousands of calls a run and the
 * run's record is written again every few seconds while it runs. A stored object per call,
 * copied on every write, would write the whole run again each time. Here a call is
 * {@value #WIDTH} numbers in a page of {@value #PAGE} calls; a page that is full is never
 * changed again, so every later copy of the record shares it and the store writes it once.
 * Only the page being filled and the short list of names are written again.
 *
 * <p>The texts of a call (role, task, worker, model, purpose, candidate) are kept once each in
 * {@link #names} and a call holds their positions.
 *
 * <p>A mutable POJO because EclipseStore cannot persist records. Only fields may be added, and
 * the meaning of a column never changes: a new figure is a new class of page, not a wider one.
 */
public class CallLog {

    /** Calls in one page. */
    public static final int PAGE = 256;
    /** Numbers kept for one call. */
    public static final int WIDTH = 13;

    private static final int FAILED = 1;
    private static final int ESTIMATED = 2;
    private static final int PAID = 4;

    private List<int[]> pages = new ArrayList<>();
    private int count;
    private List<String> names = new ArrayList<>();
    /** When the first call was sent, in milliseconds since 1970; calls keep seconds after it. */
    private long epochMillis;

    public CallLog() {}

    /** How many calls are kept. */
    public int size() {
        return count;
    }

    /** Adds one call. Only for the log the ledger is filling, never for a stored copy. */
    public void add(CallUsageRecord call) {
        if (count == 0) {
            epochMillis = call.startedAt();
        }
        int at = count % PAGE;
        if (at == 0) {
            pages.add(new int[PAGE * WIDTH]);
        }
        int[] page = pages.get(pages.size() - 1);
        int i = at * WIDTH;
        page[i] = clamp((call.startedAt() - epochMillis) / 1000);
        page[i + 1] = clamp(call.millis());
        page[i + 2] = clamp(call.input());
        page[i + 3] = clamp(call.cachedInput());
        page[i + 4] = clamp(call.output());
        page[i + 5] = clamp(call.thinking());
        page[i + 6] = (call.failed() ? FAILED : 0) | (call.estimated() ? ESTIMATED : 0)
            | (call.paid() ? PAID : 0);
        page[i + 7] = name(call.role());
        page[i + 8] = name(call.task());
        page[i + 9] = name(call.worker());
        page[i + 10] = name(call.model());
        page[i + 11] = name(call.purpose());
        page[i + 12] = name(call.candidate());
        count++;
    }

    private static int clamp(long value) {
        return value < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, value);
    }

    private int name(String text) {
        if (text == null) {
            return -1;
        }
        int at = names.indexOf(text);
        if (at < 0) {
            names.add(text);
            at = names.size() - 1;
        }
        return at;
    }

    private String nameAt(int at) {
        return at < 0 || at >= names.size() ? null : names.get(at);
    }

    /** Page {@code n} itself, for the test that a full page is shared and never copied. */
    int[] page(int n) {
        return pages.get(n);
    }

    /** Every call, oldest first. */
    public List<CallUsageRecord> calls() {
        List<CallUsageRecord> all = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            int[] page = pages.get(n / PAGE);
            int i = (n % PAGE) * WIDTH;
            int flags = page[i + 6];
            all.add(new CallUsageRecord(nameAt(page[i + 7]), nameAt(page[i + 8]),
                nameAt(page[i + 9]), nameAt(page[i + 10]),
                (flags & PAID) != 0 ? ModelUsageRecord.CLOUD : ModelUsageRecord.LOCAL,
                nameAt(page[i + 11]), nameAt(page[i + 12]), epochMillis + page[i] * 1000L,
                page[i + 1], page[i + 2], page[i + 3], page[i + 4], page[i + 5],
                (flags & FAILED) != 0, (flags & ESTIMATED) != 0));
        }
        return all;
    }

    /**
     * A copy to store: it shares every full page with this log and holds its own copy of the
     * page being filled, cut to what is used, so nothing the ledger adds later changes it.
     */
    public CallLog stored() {
        CallLog copy = new CallLog();
        copy.count = count;
        copy.epochMillis = epochMillis;
        copy.names = new ArrayList<>(names);
        int open = count % PAGE;
        for (int n = 0; n < pages.size(); n++) {
            boolean last = n == pages.size() - 1;
            copy.pages.add(last && open != 0 ? Arrays.copyOf(pages.get(n), open * WIDTH)
                : pages.get(n));
        }
        return copy;
    }

    /** A log to go on filling from a stored one: full pages shared, the open page its own. */
    public CallLog continued() {
        CallLog copy = new CallLog();
        copy.count = count;
        copy.epochMillis = epochMillis;
        copy.names = new ArrayList<>(names == null ? List.of() : names);
        int open = count % PAGE;
        List<int[]> from = pages == null ? List.of() : pages;
        for (int n = 0; n < from.size(); n++) {
            boolean last = n == from.size() - 1;
            copy.pages.add(last && open != 0 ? Arrays.copyOf(from.get(n), PAGE * WIDTH)
                : from.get(n));
        }
        return copy;
    }
}
