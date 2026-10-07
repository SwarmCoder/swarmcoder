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
package com.swarmcoder.lsp;

import java.nio.file.Path;
import java.util.List;

/**
 * Facade over a language server (spec §S6). The only type consumers outside {@code sc-lsp} may
 * depend on — LSP4J and the wire protocol stay behind this interface (ArchUnit boundary rule).
 *
 * <p>LSP is an <em>enhancer, never a prerequisite</em>: every method degrades to a safe empty
 * result or a {@link LspResult.Status#NOT_AVAILABLE} answer when no server is installed or the
 * server is unhealthy, and no method throws. Callers treat an empty diagnostics list as "no
 * signal", not "clean tree" — the real compile stays the authority (LSP misses annotation
 * processors, codegen, and resource pipelines).
 *
 * <p><b>Queries answer with places, never with file contents</b> (2026-10-04): a path, a line and
 * one line of text each, capped. A symbol is named the way the tree queries name it: {@code Type},
 * {@code com.example.Type}, {@code Type#member}, or {@code Type#member(String, int)} for one
 * overload.
 */
public interface LspService extends AutoCloseable {

    /**
     * Pre-compile diagnostics for a single source file (spec §S6 verifier signal). Best-effort:
     * returns an empty list when the server is unavailable, the file is outside the workspace,
     * or diagnostics do not arrive within the server's grace window.
     */
    List<LspDiagnostic> diagnostics(Path file);

    /** True when a server is running and healthy. Callers may skip work when false. */
    boolean isAvailable();

    /**
     * True when a server is installed for this workspace and has not failed - it may not have been
     * started yet (start is lazy). False means every query answers NOT_AVAILABLE.
     */
    default boolean isInstalled() {
        return isAvailable();
    }

    /** Why the server is not available, in one sentence without a full stop; empty when it is. */
    default String whyUnavailable() {
        return "none is installed on this machine";
    }

    /** Every place a type, method or field is referenced, the declaration left out. */
    default LspResult references(String symbol) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** Where a symbol is declared. */
    default LspResult definition(String symbol) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** What implements or extends a type, or overrides a method - all of them, not only direct. */
    default LspResult implementations(String symbol) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** Everything a type extends or implements, nearest first, through library jars too. */
    default LspResult supertypes(String type) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** The direct subtypes of a type. */
    default LspResult subtypes(String type) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** Every call of a method: the calling line and the method it is in. */
    default LspResult callers(String method) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** Every method a method calls, each with where it is declared. */
    default LspResult callees(String method) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** Types whose name matches a fragment or a {@code *} pattern - the project's and its jars'. */
    default LspResult workspaceSymbols(String query) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** The types and members a file declares, one line each. */
    default LspResult outline(Path file) {
        return LspResult.unavailable(whyUnavailable());
    }

    /**
     * The members of ANY type with their signatures - a project type or one that exists only in a
     * library jar - with the first sentence of the javadoc where sources are attached, and the
     * names of what it inherits.
     */
    default LspResult members(String type) {
        return LspResult.unavailable(whyUnavailable());
    }

    /**
     * The names of everything a type declares or inherits (methods without their parameter
     * lists, fields, nested types), for a mechanical "does this member exist" check. Empty when
     * the server is unavailable or does not know the type: empty is "no signal", never "no
     * members".
     */
    default List<String> memberNames(String type) {
        return List.of();
    }

    /** One symbol's signature and javadoc. */
    default LspResult hover(String symbol) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** {@link #diagnostics(Path)} as a short answer: errors first, then warnings. */
    default LspResult problems(Path file) {
        return LspResult.unavailable(whyUnavailable());
    }

    /**
     * Renames a type, method or field everywhere in the workspace and writes the change. Refused
     * on a read-only workspace and when any file it would touch lies outside the workspace.
     */
    default LspResult rename(String symbol, String newName) {
        return rename(symbol, newName, file -> null);
    }

    /**
     * {@link #rename(String, String)} under the caller's own rule about what may be written.
     *
     * @param refusal asked for every file the rename would change, by its workspace-relative
     *                path: null lets it be written, anything else is why it may not be - and then
     *                nothing at all is written and the answer says which file and why
     */
    default LspResult rename(String symbol, String newName,
                             java.util.function.Function<String, String> refusal) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** Removes unused imports and adds and sorts the needed ones in one file, and writes it. */
    default LspResult organizeImports(Path file) {
        return LspResult.unavailable(whyUnavailable());
    }

    /** Idempotent; never throws. */
    @Override
    void close();

    /**
     * The always-off implementation used when no language server is configured. Every call is a
     * clean no-op — this is the default the Verifier and workers fall back to, so their behavior
     * is byte-for-byte unchanged when LSP is not wired in.
     */
    LspService UNAVAILABLE = new LspService() {
        @Override
        public List<LspDiagnostic> diagnostics(Path file) {
            return List.of();
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    };
}
