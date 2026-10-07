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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The files a unified diff ADDS or CHANGES, as repo-relative forward-slash paths.
 *
 * <p>Used by {@link BuildReachabilityCheck}, which has to know what the candidate actually wrote
 * rather than what its task's write set said it would. Those are different things, and the
 * difference is the point: the write set is the plan's claim, and a candidate that wandered outside
 * it must be caught by the same gate as one that followed a plan pointing at nowhere.
 *
 * <p>Deletions are excluded. A file that is gone cannot be in the wrong place.
 */
public final class UnifiedDiffPaths {

    private UnifiedDiffPaths() {}

    /**
     * Parses {@code git diff} output. Unrecognisable input yields an empty set, which the caller
     * reads as "nothing could be established" — never as "nothing was changed".
     */
    public static Set<String> addedOrChanged(String unifiedDiff) {
        Set<String> paths = new LinkedHashSet<>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return paths;
        }
        String current = null;
        boolean deleted = false;
        for (String line : unifiedDiff.split("\r?\n")) {
            if (line.startsWith("diff --git ")) {
                if (current != null && !deleted) {
                    paths.add(current);
                }
                current = postImagePath(line.substring("diff --git ".length()));
                deleted = false;
            } else if (line.startsWith("deleted file mode ")) {
                deleted = true;
            } else if (line.startsWith("+++ ")) {
                String target = line.substring(4).trim();
                if ("/dev/null".equals(target)) {
                    deleted = true;
                } else if (current == null) {
                    current = stripPrefix(unquote(target));  // a bare diff with no --git header
                }
            }
        }
        if (current != null && !deleted) {
            paths.add(current);
        }
        paths.remove(null);
        paths.remove("");
        return paths;
    }

    /**
     * The {@code b/...} half of a {@code diff --git a/x b/y} header. Renames make the two halves
     * differ, and the post-image is where the file ended up, which is the one that has to be
     * reachable.
     */
    private static String postImagePath(String header) {
        String rest = header.trim();
        if (rest.startsWith("\"")) {
            // Both halves are quoted when either contains an unusual character.
            int end = closingQuote(rest, 0);
            if (end < 0) {
                return null;
            }
            String second = rest.substring(end + 1).trim();
            return stripPrefix(unquote(second));
        }
        // Unquoted: "a/<path> b/<path>". Paths may contain spaces, so split on " b/" from the end.
        int split = rest.lastIndexOf(" b/");
        if (split < 0) {
            return null;
        }
        return stripPrefix(rest.substring(split + 1));
    }

    private static int closingQuote(String text, int from) {
        for (int i = from + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    /** Drops git's {@code a/} or {@code b/} prefix and any trailing tab-separated timestamp. */
    private static String stripPrefix(String raw) {
        String value = raw.trim();
        int tab = value.indexOf('\t');
        if (tab >= 0) {
            value = value.substring(0, tab);
        }
        if (value.startsWith("a/") || value.startsWith("b/")) {
            value = value.substring(2);
        }
        return BuildLayout.normalize(value);
    }
}
