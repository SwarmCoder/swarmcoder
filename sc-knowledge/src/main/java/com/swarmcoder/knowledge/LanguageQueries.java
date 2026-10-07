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

import com.swarmcoder.lsp.LspHit;
import com.swarmcoder.lsp.LspResult;
import com.swarmcoder.lsp.LspService;

import java.nio.file.Path;
import java.util.List;

/**
 * The questions about a project that the Java language server answers, with no model: every
 * reference to a symbol, what a type extends and what extends it, who calls a method and what it
 * calls, a type by part of its name, a file's outline, a symbol's documentation - and the members
 * of a type that exists only in a library jar, which the syntax tree cannot list (owner's
 * decision, 2026-10-04).
 *
 * <p>The companion of {@link TreeQueries}: one class, so every role and every worker is given the
 * same answer, with addresses written the way the asker's own read tool takes them.
 *
 * <h2>One tool per question, and which source answers it</h2>
 *
 * <p>Where the tree and the language server can both answer, a role still has ONE tool, and this
 * class decides the source by a fixed rule, so the two can never be shown side by side saying
 * different things:
 *
 * <ul>
 *   <li><b>A type's members</b> ({@code public_shape}, a worker's {@code shape_of}): the tree
 *       whenever it holds the type - it reads the project's and the reference checkouts' own
 *       source. The language server only for a type the tree cannot list, which is one that
 *       lives in a jar.</li>
 *   <li><b>Usages and implementations</b>: the language server when the symbol is declared in the
 *       project's own source - it resolves every binding, so an overload, an override and a
 *       method reference are told apart, and it sees the project as it is on disk now. The tree
 *       for everything else: a symbol of a library is used in the reference checkouts too, which
 *       the server does not read, and those uses are the worked examples a role is looking
 *       for.</li>
 *   <li>Everything the tree has no query for is the language server's alone.</li>
 * </ul>
 *
 * <p>Every method answers with a sentence when the server is not installed or not running; none
 * throws.
 */
public final class LanguageQueries {

    private final LspService server;
    /** What a project file's path is prefixed with for this asker: "project/" for a role. */
    private final String prefix;

    private LanguageQueries(LspService server, String prefix) {
        this.server = server == null ? LspService.UNAVAILABLE : server;
        this.prefix = prefix;
    }

    /** For a planning role or the expert: a project file is {@code <root label>/<path>}. */
    public static LanguageQueries of(KnowledgeCurator curator) {
        if (curator == null) {
            return new LanguageQueries(null, "");
        }
        String label = curator.languageServerRootLabel();
        return new LanguageQueries(curator.languageServer(), label.isBlank() ? "" : label + "/");
    }

    /** For a worker: a project file is its path in the repository, which is what {@code read} takes. */
    public static LanguageQueries forAWorkerOf(KnowledgeCurator curator) {
        return new LanguageQueries(curator == null ? null : curator.languageServer(), "");
    }

    /** For one checkout with a server of its own - a worker's refactorings and checks. */
    public static LanguageQueries over(LspService server) {
        return new LanguageQueries(server, "");
    }

    /** True when a language server is installed for this project; it may not have started yet. */
    public boolean installed() {
        return server.isInstalled();
    }

    /** What to tell a role that needed the server and has none. */
    public String notAvailable() {
        return LspResult.unavailable(server.whyUnavailable()).note();
    }

    // ----------------------------------------------------------- shared with the syntax tree

    /**
     * A type's members from the language server, for a type the tree cannot list; null when the
     * server cannot say either, and the caller then answers as it did before.
     */
    public String membersOrNull(String type) {
        if (!installed()) {
            return null;
        }
        LspResult members = server.members(type);
        return members.answered() || members.status() == LspResult.Status.AMBIGUOUS
            ? render(members) : null;
    }

    /**
     * Every reference to a symbol declared in the project's own source; null when the symbol is
     * not one (a library's, an unknown name) or the server is not available - the tree answers
     * then.
     */
    public String usagesOrNull(String what) {
        return declaredInTheProject(what) ? render(server.references(what)) : null;
    }

    /** As {@link #usagesOrNull}, for what implements, extends or overrides a symbol. */
    public String implementationsOrNull(String what) {
        return declaredInTheProject(what) ? render(server.implementations(what)) : null;
    }

    private boolean declaredInTheProject(String what) {
        if (!installed() || what == null || what.isBlank()) {
            return false;
        }
        LspResult declared = server.definition(what);
        if (declared.status() == LspResult.Status.AMBIGUOUS) {
            // Several project types of that name: the server's list of them is the answer.
            return declared.hits().stream().anyMatch(hit -> !inAJar(hit));
        }
        return declared.answered() && !declared.hits().isEmpty() && !inAJar(declared.hits().get(0));
    }

    private static boolean inAJar(LspHit hit) {
        return hit.file() != null && hit.file().startsWith("[");
    }

    // ------------------------------------------------------------- the language server's own

    public String implementationsOf(String what) {
        return render(server.implementations(what));
    }

    public String supertypesOf(String type) {
        return render(server.supertypes(type));
    }

    public String callersOf(String method) {
        return render(server.callers(method));
    }

    public String calleesOf(String method) {
        return render(server.callees(method));
    }

    public String findSymbol(String name) {
        return render(server.workspaceSymbols(name));
    }

    /**
     * {@code find_symbol}, with the tree asked when the server names nothing (run 86, section
     * 58): the server answered "0 type(s) match `ZeroZDbNode`." seven times in one session for a
     * library type the tree knew by its full name from the code that uses it. One question, one
     * answer: what the tree knows is said in the answer that would otherwise say "none".
     *
     * @param whenNone what the tree knows of the name; null or blank when it knows nothing
     */
    public String findSymbol(String name, java.util.function.Supplier<String> whenNone) {
        LspResult result = server.workspaceSymbols(name);
        if (!result.hits().isEmpty()) {
            return render(result);
        }
        String known = whenNone == null ? null : whenNone.get();
        return known == null || known.isBlank() ? render(result) : known;
    }

    public String docOf(String symbol) {
        return render(server.hover(symbol));
    }

    /** @param address a project file as this asker addresses it */
    public String outlineOf(String address) {
        Path file = fileOf(address);
        return file == null ? "outline_of reads a Java file of the project itself; give its path "
            + "as the other tools print it." : render(server.outline(file));
    }

    /** @param address a project file as this asker addresses it */
    public String problemsIn(String address) {
        Path file = fileOf(address);
        return file == null ? "Give the path of a Java file of this checkout."
            : render(server.problems(file));
    }

    /** The answer as it was given, for a caller that needs the files a change touched. */
    public LspResult rename(String symbol, String newName,
                            java.util.function.Function<String, String> refusal) {
        return server.rename(symbol, newName, refusal);
    }

    public LspResult organizeImports(String address) {
        Path file = fileOf(address);
        return file == null ? LspResult.notFound("Give the path of a Java file of this checkout.")
            : server.organizeImports(file);
    }

    private Path fileOf(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String path = address.strip().replace('\\', '/');
        int colon = path.lastIndexOf(':');
        if (colon > 1 && path.substring(colon + 1).matches("[0-9]+(-[0-9]+)?")) {
            path = path.substring(0, colon); // an address with a line, as the other tools print it
        }
        if (!prefix.isEmpty()) {
            if (!path.startsWith(prefix)) {
                return null;
            }
            path = path.substring(prefix.length());
        }
        try {
            return path.isEmpty() ? null : Path.of(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The answer as text, each project file addressed the way this asker reads files. */
    public String render(LspResult result) {
        if (prefix.isEmpty()) {
            return result.render();
        }
        List<LspHit> addressed = result.hits().stream()
            .map(hit -> hit.file() == null || hit.file().isBlank() || inAJar(hit) ? hit
                : new LspHit(prefix + hit.file(), hit.line(), hit.text()))
            .toList();
        return new LspResult(result.status(), result.note(), addressed, result.total()).render();
    }
}
