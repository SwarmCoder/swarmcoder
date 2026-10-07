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

import java.util.Set;

/**
 * The worker's narrow knowledge tool (spec §13/§4.8): {@code lookup_api(query)} answers an
 * API/library question from a pre-warmed local docs index, falling through to Context7 on a
 * miss. Kept as a plain functional interface so the swarm engine can carry it without
 * depending on sc-knowledge; the real implementation lives in sc-app.
 *
 * <p>Workers never browse the open web — this preserves the no-network sandbox guarantee and
 * keeps tool results small and cacheable.
 */
@FunctionalInterface
public interface ApiLookup {

    /** A human-readable answer, or a "not found — inspect the source" nudge. Never null. */
    String lookup(String query);

    /**
     * As {@link #lookup(String)}, but told which documentation sections THIS WORKER has already
     * been given (so a second, differently-worded question is not answered with the first
     * question's leading section again) and which words in {@code query} are new since its
     * earlier questions (so a refined question actually moves the ranking instead of landing on
     * the same page a broader one did).
     *
     * <p>The default ignores both and behaves exactly as {@link #lookup(String)} — a lookup
     * source that does not track sections (every lambda in this codebase's tests, and
     * {@link #UNAVAILABLE}) needs no change to keep working. Only a source that actually keeps
     * a per-worker "already given" set, such as the Librarian, overrides this.
     */
    default String lookup(String query, Set<String> alreadyGivenSections, Set<String> newTerms) {
        return lookup(query);
    }

    /**
     * {@code find_example}: one whole file of real code from this project's material that uses the
     * types named in {@code what}, chosen by which types it uses. The same librarian call the
     * planning roles have.
     *
     * @param what     the library and project types involved and a few words on what the code does
     * @param wantTest true for a test that does it, false for an implementation
     * @param forWhom  who asks, for the log
     */
    default String findExample(String what, boolean wantTest, String forWhom) {
        return "find_example is not configured; look at a similar file with read, or ask_expert.";
    }

    /** No knowledge source configured — the tool tells the worker to use read/exec instead. */
    /**
     * A question answered from the project's syntax tree and object graph, with no model
     * (owner's rule, 2026-10-04: every role learns the project from the tree; a worker should
     * not need find, grep and cat to learn where things are).
     *
     * @param query    which question: {@code shape_of}, {@code body_of}, {@code types_in},
     *                 {@code build_of} or {@code usages_of}
     * @param argument the type, {@code Type#member}, package, module or folder asked about
     */
    default String tree(String query, String argument) {
        return "The project's syntax tree is not configured here; use read on the file.";
    }

    /**
     * True when a Java language server is installed for this project, so the worker's
     * language-server tools have something behind them (2026-10-04). The read-only questions go
     * through {@link #tree}: {@code implementations_of}, {@code supertypes_of},
     * {@code callers_of}, {@code find_symbol}, {@code doc_of}.
     */
    /**
     * The source of one file of the project as the syntax tree was built from it, by its path in
     * the repository; null when the tree was built from no such file.
     *
     * <p>A worker's checkout is cut from the run's progress, so it also holds what earlier tasks
     * of the run delivered and what the worker changed itself, and the tree holds neither (live
     * run 88: {@code shape_of} of four types the first task had added answered "no such type"
     * 27 times, and the files were read whole 30 times). The toolbox compares a file of the
     * checkout with this to tell whether the tree can answer for it.
     */
    default String sourceInTree(String relativePath) {
        return null;
    }

    default boolean languageServer() {
        return false;
    }

    /**
     * True when the answer {@link #tree} gave last on this thread came from the language server
     * and not from the syntax tree - a library type's members, a project symbol's usages - so
     * the run's record of lookups counts it under the right kind.
     */
    default boolean answeredByLanguageServer() {
        return false;
    }

    /** What a refactoring or a check in a worker's own checkout answered, and what it wrote. */
    record InCheckout(String answer, java.util.List<String> filesWritten) {}

    /**
     * A deterministic refactoring or check in ONE worker's own checkout, by a language server
     * that reads that checkout: {@code rename} ({@code argument} is the symbol, {@code second}
     * the new name), {@code organize_imports} and {@code problems} ({@code argument} is a file).
     * It runs no build and executes nothing the checkout contains.
     *
     * @param checkout the worker's checkout on this machine
     * @param refusal  asked for every file a change would write, by its path in the checkout:
     *                 null lets it be written, anything else refuses the whole change
     */
    default InCheckout inCheckout(java.nio.file.Path checkout, String action, String argument,
                                  String second,
                                  java.util.function.Function<String, String> refusal) {
        return new InCheckout("The Java language server is not available here; make the change "
            + "with apply_diff or write_file, and let the build check it.", java.util.List.of());
    }

    /** The worker that used {@code checkout} is done: stop whatever was started for it. */
    default void leaveCheckout(java.nio.file.Path checkout) {
        // nothing was started
    }

    ApiLookup UNAVAILABLE = query ->
        "lookup_api is not configured; inspect the code directly with read/exec (e.g. grep for the symbol).";
}
