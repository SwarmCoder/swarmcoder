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
 * The questions the roles of one run have had answered by a lookup, each as a 64-bit hash of
 * the tool and its argument, in the order they were first asked (stored in
 * {@link RunUsageRecord}). It is what lets a lookup be counted as a repeat - the same question
 * already answered earlier in this run, to this role or another - after the program was
 * restarted in the middle of a run as well.
 *
 * <p>Kept in pages like {@link CallLog}: a full page is never changed and is written once.
 *
 * <p>A mutable POJO because EclipseStore cannot persist records.
 */
public class AskedQuestions {

    /** Questions in one page. */
    public static final int PAGE = 512;

    private List<long[]> pages = new ArrayList<>();
    private int count;

    public AskedQuestions() {}

    public int size() {
        return count;
    }

    /** The hash a question is kept as (FNV-1a over its characters). */
    public static long hashOf(String question) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < question.length(); i++) {
            hash ^= question.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** Adds a question first asked now. Only for the one the ledger is filling. */
    public void add(long hash) {
        int at = count % PAGE;
        if (at == 0) {
            pages.add(new long[PAGE]);
        }
        pages.get(pages.size() - 1)[at] = hash;
        count++;
    }

    /** Every hash, in the order first asked. */
    public long[] all() {
        long[] all = new long[count];
        for (int n = 0; n < count; n++) {
            all[n] = pages.get(n / PAGE)[n % PAGE];
        }
        return all;
    }

    /** A copy to store: full pages shared, the page being filled copied and cut to its use. */
    public AskedQuestions stored() {
        return copy(false);
    }

    /** One to go on filling from a stored one. */
    public AskedQuestions continued() {
        return copy(true);
    }

    private AskedQuestions copy(boolean toFill) {
        AskedQuestions copy = new AskedQuestions();
        copy.count = count;
        int open = count % PAGE;
        List<long[]> from = pages == null ? List.of() : pages;
        for (int n = 0; n < from.size(); n++) {
            boolean last = n == from.size() - 1;
            copy.pages.add(last && open != 0 ? Arrays.copyOf(from.get(n), toFill ? PAGE : open)
                : from.get(n));
        }
        return copy;
    }
}
