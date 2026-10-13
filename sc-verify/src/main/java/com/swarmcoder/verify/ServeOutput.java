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
package com.swarmcoder.verify;

import java.nio.charset.StandardCharsets;

/**
 * What an application printed when it was started with the contract's {@code browser.serve}
 * command, kept at both ends (section 92): the beginning, where a start that is refused says
 * why, and the end, where it stopped. The middle of a long output is left out and counted.
 * Nothing in it is read for meaning here.
 *
 * @param first      the first bytes printed, at most {@link #FIRST_BYTES} by default
 * @param last       the last bytes printed that are not already in {@code first}
 * @param totalBytes everything the command printed, in bytes; more than both parts together
 *                   when a middle was left out
 */
public record ServeOutput(String first, String last, long totalBytes) {

    /** Kept from the beginning of the output. */
    public static final int FIRST_BYTES = 4 * 1024;
    /** Kept from the end of the output. */
    public static final int LAST_BYTES = 16 * 1024;

    public static final ServeOutput NONE = new ServeOutput("", "", 0);

    public ServeOutput {
        first = first == null ? "" : first;
        last = last == null ? "" : last;
    }

    /** Both ends of a text that is already here in full. */
    public static ServeOutput keep(String whole, int firstBytes, int lastBytes) {
        if (whole == null || whole.isEmpty()) {
            return NONE;
        }
        byte[] bytes = whole.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= (long) firstBytes + lastBytes) {
            return new ServeOutput(whole, "", bytes.length);
        }
        return new ServeOutput(
            new String(bytes, 0, firstBytes, StandardCharsets.UTF_8),
            new String(bytes, bytes.length - lastBytes, lastBytes, StandardCharsets.UTF_8),
            bytes.length);
    }

    public boolean isEmpty() {
        return first.isEmpty() && last.isEmpty();
    }

    /** How many bytes between the two parts are not here. */
    public long leftOut() {
        return Math.max(0, totalBytes - first.getBytes(StandardCharsets.UTF_8).length
            - last.getBytes(StandardCharsets.UTF_8).length);
    }

    /** The kept output as one text, with a line where the middle was left out. */
    public String text() {
        if (last.isEmpty()) {
            return first;
        }
        long gone = leftOut();
        return first + (first.endsWith("\n") ? "" : "\n")
            + (gone > 0 ? "[... " + gone + " bytes of output left out here ...]\n" : "") + last;
    }

    /**
     * The last whole lines of the output that fit in {@code maxChars}; of a single line longer
     * than that, its end. Lines are taken as printed and none is chosen over another.
     */
    public String lastLines(int maxChars) {
        String all = text().stripTrailing();
        if (maxChars <= 0 || all.isEmpty()) {
            return "";
        }
        if (all.length() <= maxChars) {
            return all;
        }
        String end = all.substring(all.length() - maxChars);
        int firstBreak = end.indexOf('\n');
        return firstBreak < 0 || firstBreak == end.length() - 1 ? end
            : end.substring(firstBreak + 1);
    }
}
