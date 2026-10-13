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
package com.swarmcoder.knowledge;

import java.util.function.Supplier;

/**
 * How a file of the project's material is written for the one who reads the answer, so that one
 * computed answer serves every reader (section 101).
 *
 * <p><b>Why.</b> A role addresses a file as {@code <root>/<path>}; a worker addresses a file of
 * its own checkout by its path in the repository and a reference file as
 * {@code /reference/<root>/<path>}. Section 100 therefore kept a role's answer and a worker's
 * answer to the same question apart, and each was computed once. The fact under both is the
 * same; only the addresses differ.
 *
 * <p><b>How.</b> A lookup that is computed for every reader writes each address as
 * {@code <root>/<path>} between two marks ({@link #mark}); the kept text is turned into a
 * reader's own by {@link #render}, which rewrites the marked addresses and nothing else. No
 * text is searched for something that looks like a path: only what the lookup itself marked is
 * touched. A computation whose text holds a mark that is not whole, or one around something it
 * did not mark - a quoted source line that holds one of the two characters, an address cut in
 * the middle - is not for every reader ({@link NotForEveryone}) and is computed for its asker
 * as before.
 */
public final class Addresses {

    /** A role or the expert: {@code <root>/<path>}. */
    public static final Addresses OF_A_ROLE = new Addresses(null);

    private static final char OPEN = '\uE000';
    private static final char CLOSE = '\uE001';

    /** The addresses the computation on this thread has marked; null outside one. */
    private static final ThreadLocal<java.util.Set<String>> MARKED = new ThreadLocal<>();

    /** Thrown inside a computation for every reader that cannot be one. */
    static final class NotForEveryone extends RuntimeException {
        NotForEveryone(String why) {
            super(why, null, false, false);
        }
    }

    /** Why the computation on this thread cannot be for every reader; null while it can. */
    private static final ThreadLocal<String> REFUSED = new ThreadLocal<>();

    /**
     * Says that the computation on this thread cannot be one text for every reader, and gives
     * the exception to throw. It is also remembered: a lookup that catches what goes wrong
     * inside it and answers something else must not have that something else kept.
     */
    static NotForEveryone notForEveryone(String why) {
        REFUSED.set(why);
        return new NotForEveryone(why);
    }

    /** The label of the root a worker's plain paths are relative to; null for a role. */
    private final String ownRoot;

    private Addresses(String ownRoot) {
        this.ownRoot = ownRoot;
    }

    /** A worker in a checkout of the root labelled {@code ownRoot}; "" when it is none of them. */
    public static Addresses ofAWorker(String ownRoot) {
        return new Addresses(ownRoot == null ? "" : ownRoot);
    }

    /** The label of the root {@code project} is, or "" when it is none of the curator's. */
    public static String rootLabelOf(KnowledgeCurator curator, java.nio.file.Path project) {
        String label = "";
        if (curator != null && project != null) {
            java.nio.file.Path wanted = project.toAbsolutePath().normalize();
            for (KnowledgeCurator.Root root : curator.roots()) {
                if (root.path() != null && root.path().toAbsolutePath().normalize().equals(wanted)) {
                    label = root.label();
                }
            }
        }
        return label;
    }

    boolean ofAWorker() {
        return ownRoot != null;
    }

    String ownRoot() {
        return ownRoot;
    }

    /**
     * True when this reader can be given what was computed for every reader. A role always. A
     * worker when its checkout is of the root the queries take for the project's own, and the
     * language server - whose places a worker reads as plain paths - reads that root or none.
     */
    boolean sharesWith(KnowledgeCurator curator) {
        if (ownRoot == null) {
            return true;
        }
        if (curator == null || !"project".equals(ownRoot)) {
            return false;
        }
        String served = curator.languageServerRootLabel();
        return served.isBlank() || served.equals(ownRoot);
    }

    /** {@code <root>/<path>} as this reader's read tool takes it. */
    String shown(String canonical) {
        if (ownRoot == null) {
            return canonical;
        }
        if (canonical.equals(ownRoot)) {
            return "";
        }
        return canonical.startsWith(ownRoot + "/") ? canonical.substring(ownRoot.length() + 1)
            : "/reference/" + canonical;
    }

    /** An address for every reader: {@code <root>/<path>} between the two marks. */
    static String mark(String canonical) {
        if (canonical == null || canonical.isEmpty() || canonical.indexOf(OPEN) >= 0
                || canonical.indexOf(CLOSE) >= 0) {
            throw notForEveryone("an address that cannot be marked");
        }
        java.util.Set<String> marked = MARKED.get();
        if (marked != null) {
            marked.add(canonical);
        }
        return OPEN + canonical + CLOSE;
    }

    /**
     * Runs a computation that marks its addresses and returns its text, which may be null.
     *
     * @throws NotForEveryone when a mark in the text is not whole, or is around something the
     *                        computation did not mark
     */
    static String forEveryone(Supplier<String> compute) {
        java.util.Set<String> outer = MARKED.get();
        java.util.Set<String> marked = new java.util.HashSet<>();
        MARKED.set(marked);
        REFUSED.remove();
        String text;
        String refused;
        try {
            text = compute.get();
        } finally {
            refused = REFUSED.get();
            REFUSED.remove();
            if (outer == null) {
                MARKED.remove();
            } else {
                MARKED.set(outer);
            }
        }
        if (refused != null) {
            throw new NotForEveryone(refused);
        }
        if (text == null) {
            return null;
        }
        int open = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == OPEN) {
                if (open >= 0) {
                    throw new NotForEveryone("an address inside an address");
                }
                open = i;
            } else if (c == CLOSE) {
                if (open < 0 || !marked.contains(text.substring(open + 1, i))) {
                    throw new NotForEveryone("a mark around what was not marked");
                }
                open = -1;
            }
        }
        if (open >= 0) {
            throw new NotForEveryone("an address cut before its end");
        }
        return text;
    }

    /** A text computed for every reader, with each marked address written for this one. */
    public String render(String text) {
        if (text == null || text.indexOf(OPEN) < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int from = 0;
        while (true) {
            int open = text.indexOf(OPEN, from);
            int close = open < 0 ? -1 : text.indexOf(CLOSE, open + 1);
            if (close < 0) {
                return sb.append(text, from, text.length()).toString();
            }
            sb.append(text, from, open).append(shown(text.substring(open + 1, close)));
            from = close + 1;
        }
    }

    /** The text with every marked address as {@code <root>/<path>}: what a role reads. */
    static String plain(String text) {
        return OF_A_ROLE.render(text);
    }
}
