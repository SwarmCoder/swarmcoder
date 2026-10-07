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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionKindCapabilities;
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@link LspService} backed by Eclipse JDT Language Server over stdio (spec §S6). Launches the
 * JDT LS product from a home directory (the standard Equinox launcher jar + per-OS config) and
 * wires an LSP4J client.
 *
 * <p><b>It never runs the project's build</b> (containment, 2026-10-04). JDT LS's Maven and
 * Gradle importers are switched off, because importing a Maven project loads the build's
 * extensions and plugins into the server's own JVM and runs the plugin goals m2e maps to
 * "execute", and it resolves annotation processors and runs them in that JVM - all of it code the
 * project (or a model that edited its pom) chose, running on the host. Instead the server opens
 * a project this class writes into the server's own data folder: its source folders are links
 * to the workspace's (found by walking it and reading each file's package line), and its
 * libraries are the jars the caller hands in (the same offline classpath the syntax tree is
 * parsed with). The server parses and indexes; it executes nothing the project contains, runs
 * no annotation processor, downloads nothing, and writes nothing into the workspace.
 *
 * <p>Every failure mode — home missing, launcher not found, process death, timeout — degrades to
 * a NOT_AVAILABLE answer. The class NEVER throws to callers. Start-up is lazy: the JDT LS process
 * is only spawned on the first query.
 */
public final class JdtLanguageServer implements LspService {

    private static final Logger log = LoggerFactory.getLogger(JdtLanguageServer.class);
    private static final long DIAGNOSTICS_GRACE_MS = 8_000;
    private static final long INIT_TIMEOUT_SECONDS = 60;
    /** How long the first query waits for the server to say it has read the workspace. */
    private static final long READY_TIMEOUT_SECONDS = 180;
    private static final long REQUEST_TIMEOUT_SECONDS = 60;
    /** How many places one answer lists; the total is always given. */
    static final int MAX_HITS = 40;
    /** How long one line of text may be. */
    static final int MAX_TEXT = 160;
    private static final int MAX_SUPERTYPES = 24;
    /** {@code -Dswarmcoder.jdtLs.trace=true}: the server's own log lines on standard error. */
    private static final boolean TRACE = Boolean.getBoolean("swarmcoder.jdtLs.trace");
    private static final Set<String> SKIP_DIRS = Set.of("target", "build", "node_modules", "out",
        "bin", ".git", ".swarmcoder", ".idea", ".gradle", ".mvn");

    private final Path jdtLsHome;
    private final Path workspaceRoot;
    private final Path workspaceData;
    /** Asked once, when the server starts: resolving a classpath is not free. */
    private final java.util.function.Supplier<List<Path>> librarySource;
    private List<Path> libraries = List.of();
    private final boolean writable;
    /** True for a server whose store is thrown away with it - a worker's checkout. */
    private final boolean ownsData;

    private volatile boolean started = false;
    private volatile boolean unavailable = false;
    private volatile String why = "";
    private Process process;
    private JdtServer server;
    private Future<?> listening;
    private final Client client = new Client();
    /** Every Java file's last-modified time as the server was last told, to notice changes. */
    private final Map<Path, Long> known = new HashMap<>();
    private long coldStartMillis = -1;

    /** JDT LS's own requests beyond the protocol. */
    interface JdtServer extends LanguageServer {
        @JsonRequest("java/classFileContents")
        CompletableFuture<String> classFileContents(TextDocumentIdentifier document);
    }

    /**
     * A read-only server whose library jars are whatever JDT LS finds by itself (none): enough
     * for diagnostics of project code, as before 2026-10-04.
     */
    public JdtLanguageServer(Path jdtLsHome, Path workspaceRoot) {
        this(jdtLsHome, workspaceRoot, null, List.of(), false);
    }

    /**
     * @param jdtLsHome     directory holding the JDT LS product (a {@code plugins/} folder and a
     *                      per-OS {@code config_win} / {@code config_linux} / {@code config_mac}
     *                      folder); when null or absent the service is permanently unavailable
     * @param workspaceRoot the project checkout the server analyzes
     * @param dataDir       the server's own store ({@code -data}); null puts it under
     *                      {@code ~/.swarmcoder/jdtls-data}, never inside the workspace
     * @param libraries     the jars the project compiles against, as the caller resolved them
     *                      offline; a jar's {@code -sources.jar} beside it is attached
     * @param writable      true when refactorings may write into the workspace
     */
    public JdtLanguageServer(Path jdtLsHome, Path workspaceRoot, Path dataDir,
                             List<Path> libraries, boolean writable) {
        this(jdtLsHome, workspaceRoot, dataDir,
            () -> libraries == null ? List.<Path>of() : libraries, writable, false);
    }

    /**
     * @param librarySource the jars, resolved when the server first starts and not before
     * @param ownsData      true to delete {@code dataDir} when the server is closed - for a
     *                      checkout that is itself thrown away
     */
    public JdtLanguageServer(Path jdtLsHome, Path workspaceRoot, Path dataDir,
                             java.util.function.Supplier<List<Path>> librarySource,
                             boolean writable, boolean ownsData) {
        this.librarySource = librarySource;
        this.ownsData = ownsData && dataDir != null;
        this.jdtLsHome = jdtLsHome;
        this.workspaceRoot = workspaceRoot == null ? null : workspaceRoot.toAbsolutePath().normalize();
        this.workspaceData = this.workspaceRoot == null ? null
            : dataDir != null ? dataDir.toAbsolutePath().normalize() : defaultDataDir(this.workspaceRoot);
        this.writable = writable;
        if (jdtLsHome == null || !Files.isDirectory(jdtLsHome) || this.workspaceRoot == null) {
            this.unavailable = true;
            this.why = jdtLsHome == null ? "none is installed on this machine"
                : "its folder " + jdtLsHome + " does not exist";
            log.info("JDT LS unavailable (home={}, workspace={})", jdtLsHome, workspaceRoot);
        }
    }

    static Path defaultDataDir(Path root) {
        String name = root.getFileName() == null ? "root" : root.getFileName().toString();
        String key = name.replaceAll("[^A-Za-z0-9._-]", "_") + "-"
            + Integer.toHexString(root.toString().toLowerCase(Locale.ROOT).hashCode());
        return Path.of(System.getProperty("user.home"), ".swarmcoder", "jdtls-data", key);
    }

    /** The server process's id once started, so whoever started it can account for it; -1 before. */
    public synchronized long pid() {
        return process == null ? -1 : process.pid();
    }

    /** How long the start took, from spawning the process to the workspace being read; -1 before. */
    public long coldStartMillis() {
        return coldStartMillis;
    }

    @Override
    public boolean isInstalled() {
        return !unavailable;
    }

    @Override
    public String whyUnavailable() {
        return unavailable ? why : "";
    }

    // ------------------------------------------------------------------------------- queries

    @Override
    public synchronized List<LspDiagnostic> diagnostics(Path file) {
        if (unavailable || file == null || !ensureStarted()) {
            return List.of();
        }
        Path abs = inWorkspace(file);
        if (abs == null || !Files.isRegularFile(abs)) {
            return List.of();
        }
        String uri = abs.toUri().toString();
        String text;
        try {
            text = Files.readString(abs);
        } catch (IOException e) {
            log.debug("JDT LS: cannot read {}: {}", abs, e.getMessage());
            return List.of();
        }
        try {
            syncChanges();
            CompletableFuture<List<Diagnostic>> awaited = client.expect(uri);
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, languageId(abs), 1, text)));
            List<Diagnostic> raw;
            try {
                raw = awaited.get(DIAGNOSTICS_GRACE_MS, TimeUnit.MILLISECONDS);
            } catch (Exception timeout) {
                raw = client.latest(uri); // may be empty — treated as "no signal"
            } finally {
                server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(
                    new TextDocumentIdentifier(uri)));
                client.forget(uri);
            }
            return map(raw, abs);
        } catch (Exception e) {
            log.debug("JDT LS diagnostics failed for {}: {}", abs, e.getMessage());
            markUnavailable("it stopped answering (" + e.getMessage() + ")");
            return List.of();
        }
    }

    @Override
    public synchronized LspResult problems(Path file) {
        return answer("problems", String.valueOf(file), () -> {
            Path abs = inWorkspace(file);
            if (abs == null || !Files.isRegularFile(abs)) {
                return LspResult.notFound("There is no file " + file + " in this workspace.");
            }
            List<LspDiagnostic> all = new ArrayList<>(diagnostics(abs));
            all.removeIf(d -> d.severity() != LspSeverity.ERROR && d.severity() != LspSeverity.WARNING);
            all.sort(Comparator.comparing(LspDiagnostic::severity).thenComparingInt(LspDiagnostic::line));
            long errors = all.stream().filter(LspDiagnostic::isError).count();
            List<LspHit> hits = new ArrayList<>();
            for (LspDiagnostic d : all) {
                hits.add(new LspHit(d.file(), d.line(), d.severity() + " " + cut(d.message())));
            }
            return LspResult.ok(relative(abs) + ": " + errors + " error(s), "
                + (all.size() - errors) + " warning(s), as the language server sees the file now"
                + " (it runs no annotation processor; the build is the authority).",
                capped(hits), hits.size());
        });
    }

    @Override
    public synchronized LspResult references(String symbol) {
        return answer("references", symbol, () -> {
            List<Target> targets = resolve(symbol, true);
            Set<LspHit> hits = new LinkedHashSet<>();
            for (Target target : targets) {
                ReferenceParams params = new ReferenceParams(new TextDocumentIdentifier(target.uri),
                    target.position, new ReferenceContext(false));
                hits.addAll(hitsOf(await(server.getTextDocumentService().references(params))));
            }
            List<LspHit> sorted = sorted(hits);
            return LspResult.ok(sorted.size() + " reference(s) to `" + targets.get(0).display + "`"
                + (sorted.isEmpty() ? " in this project." : ":"), capped(sorted), sorted.size());
        });
    }

    @Override
    public synchronized LspResult definition(String symbol) {
        return answer("definition", symbol, () -> {
            List<Target> targets = resolve(symbol, true);
            List<LspHit> hits = new ArrayList<>();
            for (Target target : targets) {
                hits.add(hit(target.uri, target.position.getLine(), null));
            }
            return LspResult.ok("`" + targets.get(0).display + "` is declared at:", capped(hits),
                hits.size());
        });
    }

    @Override
    public synchronized LspResult implementations(String symbol) {
        return answer("implementations", symbol, () -> {
            Target target = resolve(symbol, false).get(0);
            ImplementationParams params = new ImplementationParams(
                new TextDocumentIdentifier(target.uri), target.position);
            List<LspHit> hits = sorted(new LinkedHashSet<>(
                hitsOf(locations(await(server.getTextDocumentService().implementation(params))))));
            return LspResult.ok(hits.size() + " implementation(s) of `" + target.display + "`"
                + (hits.isEmpty() ? " in this project or its libraries." : ":"), capped(hits),
                hits.size());
        });
    }

    @Override
    public synchronized LspResult supertypes(String type) {
        return answer("supertypes", type, () -> {
            Target target = resolve(type, false).get(0);
            List<TypeHierarchyItem> supers = supertypesOf(target);
            List<LspHit> hits = new ArrayList<>();
            for (TypeHierarchyItem item : supers) {
                hits.add(hit(item.getUri(), item.getSelectionRange().getStart().getLine(),
                    kind(item.getKind()) + " " + qualified(item.getDetail(), item.getName())));
            }
            return LspResult.ok("`" + target.display + "` extends or implements, nearest first:",
                capped(hits), hits.size());
        });
    }

    @Override
    public synchronized LspResult subtypes(String type) {
        return answer("subtypes", type, () -> {
            Target target = resolve(type, false).get(0);
            List<LspHit> hits = new ArrayList<>();
            for (TypeHierarchyItem root : prepareTypes(target)) {
                List<TypeHierarchyItem> subs = await(server.getTextDocumentService()
                    .typeHierarchySubtypes(new TypeHierarchySubtypesParams(root)));
                for (TypeHierarchyItem item : subs == null ? List.<TypeHierarchyItem>of() : subs) {
                    hits.add(hit(item.getUri(), item.getSelectionRange().getStart().getLine(),
                        kind(item.getKind()) + " " + qualified(item.getDetail(), item.getName())));
                }
            }
            return LspResult.ok(hits.size() + " direct subtype(s) of `" + target.display + "`"
                + (hits.isEmpty() ? "." : ":"), capped(hits), hits.size());
        });
    }

    @Override
    public synchronized LspResult callers(String method) {
        return answer("callers", method, () -> {
            List<Target> targets = resolve(method, true);
            Set<LspHit> hits = new LinkedHashSet<>();
            for (Target target : targets) {
                for (CallHierarchyItem item : prepareCalls(target)) {
                    List<CallHierarchyIncomingCall> calls = await(server.getTextDocumentService()
                        .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(item)));
                    for (CallHierarchyIncomingCall call
                            : calls == null ? List.<CallHierarchyIncomingCall>of() : calls) {
                        CallHierarchyItem from = call.getFrom();
                        String in = "in " + simpleOf(from.getDetail()) + "#"
                            + from.getName().split(" : ")[0];
                        List<Range> at = call.getFromRanges() == null || call.getFromRanges().isEmpty()
                            ? List.of(from.getSelectionRange()) : call.getFromRanges();
                        for (Range range : at) {
                            LspHit line = hit(from.getUri(), range.getStart().getLine(), null);
                            hits.add(new LspHit(line.file(), line.line(),
                                cut(in + ": " + line.text())));
                        }
                    }
                }
            }
            List<LspHit> sorted = sorted(hits);
            return LspResult.ok(sorted.size() + " call(s) of `" + targets.get(0).display + "`"
                + (sorted.isEmpty() ? " in this project." : ":"), capped(sorted), sorted.size());
        });
    }

    @Override
    public synchronized LspResult callees(String method) {
        return answer("callees", method, () -> {
            List<Target> targets = resolve(method, true);
            Set<LspHit> hits = new LinkedHashSet<>();
            for (Target target : targets) {
                for (CallHierarchyItem item : prepareCalls(target)) {
                    List<CallHierarchyOutgoingCall> calls = await(server.getTextDocumentService()
                        .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(item)));
                    for (CallHierarchyOutgoingCall call
                            : calls == null ? List.<CallHierarchyOutgoingCall>of() : calls) {
                        CallHierarchyItem to = call.getTo();
                        hits.add(hit(to.getUri(), to.getSelectionRange().getStart().getLine(),
                            qualified(to.getDetail(), to.getName())));
                    }
                }
            }
            List<LspHit> list = new ArrayList<>(hits);
            return LspResult.ok("`" + targets.get(0).display + "` calls " + list.size()
                + " method(s)" + (list.isEmpty() ? "." : ", each with where it is declared:"),
                capped(list), list.size());
        });
    }

    @Override
    public synchronized LspResult workspaceSymbols(String query) {
        return answer("symbols", query, () -> {
            List<Symbol> found = symbols(query == null ? "" : query.strip());
            found.sort(Comparator.comparing((Symbol s) -> !isFile(s.uri))
                .thenComparing(s -> s.name).thenComparing(s -> s.container));
            List<LspHit> hits = new ArrayList<>();
            for (Symbol s : found) {
                hits.add(hit(s.uri, s.line, kind(s.kind) + " " + qualified(s.container, s.name)));
            }
            return LspResult.ok(hits.size() + " type(s) match `" + query + "`"
                + (hits.isEmpty() ? "." : " (the project's first, then its libraries'):"),
                capped(hits), hits.size());
        });
    }

    @Override
    public synchronized LspResult outline(Path file) {
        return answer("outline", String.valueOf(file), () -> {
            Path abs = inWorkspace(file);
            if (abs == null || !Files.isRegularFile(abs)) {
                return LspResult.notFound("There is no file " + file + " in this workspace.");
            }
            String rel = relative(abs);
            List<LspHit> hits = new ArrayList<>();
            outline(documentSymbols(abs.toUri().toString()), rel, "", hits);
            return LspResult.ok(rel + " declares:", capped(hits), hits.size());
        });
    }

    private void outline(List<DocumentSymbol> symbols, String file, String indent, List<LspHit> out) {
        for (DocumentSymbol s : symbols) {
            out.add(new LspHit(file, s.getSelectionRange().getStart().getLine() + 1,
                cut(indent + kind(s.getKind()) + " " + s.getName() + nullToEmpty(s.getDetail()))));
            if (s.getChildren() != null) {
                outline(s.getChildren(), file, indent + "  ", out);
            }
        }
    }

    @Override
    public synchronized LspResult members(String type) {
        return answer("members", type, () -> {
            Target target = resolve(type, false).get(0);
            DocumentSymbol decl = target.symbol;
            if (decl == null) {
                return LspResult.failed("The language server found `" + target.display
                    + "` but could not list its members.");
            }
            List<DocumentSymbol> own = decl.getChildren() == null ? List.of() : decl.getChildren();
            // Every member's own signature and javadoc, asked for together: a hover is answered
            // from the server's model in a few milliseconds.
            List<CompletableFuture<Hover>> hovers = new ArrayList<>();
            int detailed = Math.min(own.size(), MAX_HITS);
            for (int i = 0; i < detailed; i++) {
                hovers.add(server.getTextDocumentService().hover(new HoverParams(
                    new TextDocumentIdentifier(target.uri), own.get(i).getSelectionRange().getStart())));
            }
            List<LspHit> hits = new ArrayList<>();
            boolean inProject = isFile(target.uri);
            for (int i = 0; i < detailed; i++) {
                DocumentSymbol member = own.get(i);
                String text = kind(member.getKind()) + " " + member.getName()
                    + nullToEmpty(member.getDetail());
                try {
                    String[] parts = hoverParts(hovers.get(i).get(REQUEST_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS));
                    if (!parts[0].isBlank()) {
                        text = parts[0].replace(target.display + ".", "");
                    }
                    if (!parts[1].isBlank()) {
                        text += "  // " + firstSentence(parts[1]);
                    }
                } catch (Exception e) {                                        // noqa
                    log.debug("JDT LS hover failed for {}: {}", member.getName(), e.toString());
                }
                // One type, one file: the file is named once, in the note, not on every line.
                hits.add(new LspHit("", inProject
                    ? member.getSelectionRange().getStart().getLine() + 1 : 0, cut(text)));
            }
            Set<String> unlisted = new LinkedHashSet<>();
            for (int i = detailed; i < own.size(); i++) {
                unlisted.add(bare(own.get(i).getName()));
            }
            StringBuilder note = new StringBuilder("`" + target.display + "` ("
                + (inProject ? fileOf(target.uri) : "library jar " + jarOf(target.uri))
                + ") declares " + own.size() + " member(s)");
            List<String> inherited = new ArrayList<>();
            for (TypeHierarchyItem parent : supertypesOf(target)) {
                Set<String> names = new LinkedHashSet<>();
                DocumentSymbol parentDecl = find(documentSymbols(parent.getUri()), simple(parent.getName()));
                if (parentDecl != null && parentDecl.getChildren() != null) {
                    for (DocumentSymbol member : parentDecl.getChildren()) {
                        if (member.getKind() != SymbolKind.Constructor) {
                            names.add(bare(member.getName()));
                        }
                    }
                }
                if (!names.isEmpty()) {
                    inherited.add("from " + simple(parent.getName()) + ": " + String.join(", ", names));
                }
            }
            if (!inherited.isEmpty()) {
                note.append("; it inherits ").append(cut(String.join("; ", inherited), 1_200));
            }
            if (!unlisted.isEmpty()) {
                note.append("; the last ").append(own.size() - detailed)
                    .append(" are named, not listed: ")
                    .append(cut(String.join(", ", unlisted), 1_200));
            }
            note.append(own.isEmpty() ? "." : ". Its own members"
                + (inProject ? ", each with its line:" : ":"));
            return LspResult.ok(note.toString(), hits, hits.size());
        });
    }

    @Override
    public synchronized List<String> memberNames(String type) {
        if (unavailable || !ensureStarted()) {
            return List.of();
        }
        try {
            syncChanges();
            Target target = resolve(type, false).get(0);
            if (target.symbol == null) {
                return List.of();
            }
            Set<String> names = new LinkedHashSet<>();
            addNames(target.symbol, names);
            for (TypeHierarchyItem parent : supertypesOf(target)) {
                DocumentSymbol decl = find(documentSymbols(parent.getUri()), simple(parent.getName()));
                if (decl == null) {
                    return List.of(); // a supertype that cannot be read: no signal, not "no members"
                }
                addNames(decl, names);
            }
            return List.copyOf(names);
        } catch (Exception e) {                                                // noqa
            log.debug("JDT LS memberNames({}) gave no answer: {}", type, e.toString());
            return List.of();
        }
    }

    private static void addNames(DocumentSymbol type, Set<String> names) {
        if (type.getChildren() != null) {
            for (DocumentSymbol member : type.getChildren()) {
                names.add(bare(member.getName()));
            }
        }
    }

    @Override
    public synchronized LspResult hover(String symbol) {
        return answer("hover", symbol, () -> {
            List<Target> targets = resolve(symbol, true);
            List<LspHit> hits = new ArrayList<>();
            StringBuilder note = new StringBuilder();
            for (Target target : targets.subList(0, Math.min(targets.size(), 6))) {
                String[] parts = hoverParts(await(server.getTextDocumentService().hover(
                    new HoverParams(new TextDocumentIdentifier(target.uri), target.position))));
                hits.add(hit(target.uri, target.position.getLine(),
                    parts[0].isBlank() ? null : parts[0]));
                if (!parts[1].isBlank() && note.length() == 0) {
                    note.append(cut(parts[1], 1_200));
                }
            }
            return LspResult.ok("`" + targets.get(0).display + "`"
                + (note.length() == 0 ? " (no javadoc attached):" : ": " + note), capped(hits),
                targets.size());
        });
    }

    // ---------------------------------------------------------------------------- refactoring

    @Override
    public synchronized LspResult rename(String symbol, String newName,
                                         java.util.function.Function<String, String> refusal) {
        return answer("rename", symbol + " -> " + newName, () -> {
            if (!writable) {
                return LspResult.failed("This workspace is read-only here; nothing was renamed.");
            }
            if (newName == null || !newName.strip().matches("[\\p{L}_$][\\p{L}\\p{N}_$]*")) {
                return LspResult.failed("`" + newName + "` is not a Java identifier; nothing was "
                    + "renamed. Give the new simple name only.");
            }
            List<Target> targets = resolve(symbol, true);
            if (targets.size() > 1) {
                List<LspHit> choices = new ArrayList<>();
                for (Target t : targets) {
                    choices.add(hit(t.uri, t.position.getLine(), t.display));
                }
                return new LspResult(LspResult.Status.AMBIGUOUS, "`" + symbol + "` names "
                    + targets.size() + " overloads; nothing was renamed. Name one with its "
                    + "parameter types, exactly as listed:", capped(choices), choices.size());
            }
            Target target = targets.get(0);
            if (!isFile(target.uri)) {
                return LspResult.failed("`" + target.display + "` is declared in a library jar "
                    + "and cannot be renamed.");
            }
            WorkspaceEdit edit = await(server.getTextDocumentService().rename(new RenameParams(
                new TextDocumentIdentifier(target.uri), target.position, newName.strip())));
            return apply(edit, "Renamed `" + target.display + "` to `" + newName.strip() + "`",
                refusal);
        });
    }

    @Override
    public synchronized LspResult organizeImports(Path file) {
        return answer("organize_imports", String.valueOf(file), () -> {
            if (!writable) {
                return LspResult.failed("This workspace is read-only here; nothing was changed.");
            }
            Path abs = inWorkspace(file);
            if (abs == null || !Files.isRegularFile(abs)) {
                return LspResult.notFound("There is no file " + file + " in this workspace.");
            }
            // Asked as a source action on the document: JDT LS's organize-imports COMMAND looks
            // the file up by its place inside the project folder and so misses a linked one.
            CodeActionContext context = new CodeActionContext(List.of());
            context.setOnly(List.of(CodeActionKind.SourceOrganizeImports));
            List<Either<Command, CodeAction>> actions = await(server.getTextDocumentService()
                .codeAction(new CodeActionParams(new TextDocumentIdentifier(abs.toUri().toString()),
                    new Range(new Position(0, 0), new Position(0, 0)), context)));
            WorkspaceEdit edit = null;
            for (Either<Command, CodeAction> action
                    : actions == null ? List.<Either<Command, CodeAction>>of() : actions) {
                Command command = action.isLeft() ? action.getLeft() : action.getRight().getCommand();
                if (action.isRight() && action.getRight().getEdit() != null) {
                    edit = action.getRight().getEdit();
                } else if (command != null && command.getArguments() != null
                        && !command.getArguments().isEmpty()) {
                    edit = editOf(command.getArguments().get(0));
                }
                if (edit != null) {
                    break;
                }
            }
            return apply(edit, "Organized the imports of " + relative(abs), null);
        });
    }

    /** One file's planned change: text edits, or a move. */
    private record Step(Path file, List<TextEdit> edits, Path movedTo) {}

    /**
     * Writes a workspace edit, all of it or none of it: every file it names is checked to lie
     * inside the workspace before the first byte is written.
     */
    private LspResult apply(WorkspaceEdit edit, String what,
                            java.util.function.Function<String, String> refusal) throws Exception {
        List<Step> steps = new ArrayList<>();
        if (edit != null && edit.getDocumentChanges() != null) {
            for (Either<TextDocumentEdit, ResourceOperation> change : edit.getDocumentChanges()) {
                if (change.isLeft()) {
                    steps.add(new Step(writablePath(change.getLeft().getTextDocument().getUri()),
                        change.getLeft().getEdits(), null));
                } else if (change.getRight() instanceof RenameFile move) {
                    Path to = writablePath(move.getNewUri());
                    steps.add(new Step(to == null ? null : writablePath(move.getOldUri()),
                        List.of(), to));
                } else {
                    return LspResult.failed("The language server asked for a file operation this "
                        + "tool does not perform (" + change.getRight().getKind() + "); nothing "
                        + "was changed.");
                }
            }
        } else if (edit != null && edit.getChanges() != null) {
            for (Map.Entry<String, List<TextEdit>> entry : edit.getChanges().entrySet()) {
                steps.add(new Step(writablePath(entry.getKey()), entry.getValue(), null));
            }
        }
        if (steps.isEmpty()) {
            return LspResult.ok(what + ": nothing needed changing.", List.of(), 0);
        }
        for (Step step : steps) {
            if (step.file == null) {
                return LspResult.failed("The change would touch a file outside this workspace; "
                    + "nothing was changed.");
            }
            if (!step.edits.isEmpty() && step.movedTo == null && !Files.isRegularFile(step.file)
                    && steps.stream().noneMatch(s -> step.file.equals(s.movedTo))) {
                return LspResult.failed("The change names a file that does not exist ("
                    + relative(step.file) + "); nothing was changed.");
            }
        }
        if (refusal != null) {
            for (Step step : steps) {
                for (Path file : step.movedTo == null ? List.of(step.file)
                        : List.of(step.file, step.movedTo)) {
                    String why = refusal.apply(relative(file));
                    if (why != null) {
                        return LspResult.failed("The change would write " + relative(file)
                            + ", which is refused: " + why + " Nothing was changed.");
                    }
                }
            }
        }
        List<LspHit> hits = new ArrayList<>();
        List<FileEvent> events = new ArrayList<>();
        int edits = 0;
        for (Step step : steps) {
            if (step.movedTo != null) {
                Files.createDirectories(step.movedTo.getParent());
                Files.move(step.file, step.movedTo, StandardCopyOption.REPLACE_EXISTING);
                events.add(new FileEvent(step.file.toUri().toString(), FileChangeType.Deleted));
                events.add(new FileEvent(step.movedTo.toUri().toString(), FileChangeType.Created));
                known.remove(step.file);
                known.put(step.movedTo, Files.getLastModifiedTime(step.movedTo).toMillis());
                hits.add(new LspHit(relative(step.movedTo), 0, "moved from " + relative(step.file)));
                continue;
            }
            if (step.edits.isEmpty()) {
                continue;
            }
            String before = Files.readString(step.file, StandardCharsets.UTF_8);
            Files.writeString(step.file, edited(before, step.edits), StandardCharsets.UTF_8);
            known.put(step.file, Files.getLastModifiedTime(step.file).toMillis());
            events.add(new FileEvent(step.file.toUri().toString(), FileChangeType.Changed));
            List<Integer> lines = step.edits.stream()
                .map(e -> e.getRange().getStart().getLine() + 1).distinct().sorted().toList();
            edits += step.edits.size();
            hits.add(new LspHit(relative(step.file), lines.get(0), step.edits.size()
                + " edit(s) at line(s) " + cut(lines.toString().replaceAll("[\\[\\]]", ""), 80)));
        }
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(events));
        return LspResult.ok(what + ": " + edits + " edit(s) in " + hits.size() + " file(s), written.",
            capped(hits), hits.size());
    }

    /** The file a server-given URI names, when it lies inside this workspace; null otherwise. */
    private Path writablePath(String uri) {
        if (!isFile(uri)) {
            return null;
        }
        try {
            Path path = Path.of(URI.create(uri)).toAbsolutePath().normalize();
            if (!path.startsWith(workspaceRoot)) {
                return null;
            }
            // A link inside the workspace that leads out of it is outside it.
            Path existing = path;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null
                    || !existing.toRealPath().startsWith(workspaceRoot.toRealPath())) {
                return null;
            }
            return path;
        } catch (Exception e) {                                                // noqa
            return null;
        }
    }

    /** {@code text} with every edit made, last edit first so earlier positions stay true. */
    static String edited(String text, List<TextEdit> edits) {
        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }
        List<TextEdit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((TextEdit e) -> e.getRange().getStart().getLine())
            .thenComparingInt(e -> e.getRange().getStart().getCharacter()).reversed());
        StringBuilder sb = new StringBuilder(text);
        for (TextEdit edit : ordered) {
            int from = offset(text, lineStarts, edit.getRange().getStart());
            int to = offset(text, lineStarts, edit.getRange().getEnd());
            sb.replace(from, Math.max(from, to), edit.getNewText() == null ? "" : edit.getNewText());
        }
        return sb.toString();
    }

    private static int offset(String text, List<Integer> lineStarts, Position position) {
        if (position.getLine() >= lineStarts.size()) {
            return text.length();
        }
        return Math.min(text.length(), lineStarts.get(position.getLine()) + position.getCharacter());
    }

    /**
     * A workspace edit as it arrives from an executed command: untyped, a map or a JSON tree
     * depending on how the protocol library read it. Text edits only.
     */
    private static WorkspaceEdit editOf(Object raw) {
        JsonElement tree = raw instanceof JsonElement element ? element
            : new com.google.gson.Gson().toJsonTree(raw);
        if (tree == null || !tree.isJsonObject()) {
            return null;
        }
        JsonObject json = tree.getAsJsonObject();
        Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
        if (json.has("changes") && json.get("changes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("changes").entrySet()) {
                changes.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .addAll(textEdits(entry.getValue()));
            }
        }
        if (json.has("documentChanges") && json.get("documentChanges").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("documentChanges")) {
                JsonObject change = element.getAsJsonObject();
                if (change.has("textDocument") && change.has("edits")) {
                    changes.computeIfAbsent(
                        change.getAsJsonObject("textDocument").get("uri").getAsString(),
                        k -> new ArrayList<>()).addAll(textEdits(change.get("edits")));
                }
            }
        }
        return new WorkspaceEdit(changes);
    }

    private static List<TextEdit> textEdits(JsonElement array) {
        List<TextEdit> edits = new ArrayList<>();
        if (array != null && array.isJsonArray()) {
            for (JsonElement element : (JsonArray) array) {
                JsonObject edit = element.getAsJsonObject();
                JsonObject range = edit.getAsJsonObject("range");
                edits.add(new TextEdit(new Range(position(range.getAsJsonObject("start")),
                    position(range.getAsJsonObject("end"))), edit.get("newText").getAsString()));
            }
        }
        return edits;
    }

    private static Position position(JsonObject json) {
        return new Position(json.get("line").getAsInt(), json.get("character").getAsInt());
    }

    // ------------------------------------------------------------------------ symbol finding

    /** A type the server knows by name. */
    private record Symbol(String name, String container, SymbolKind kind, String uri, int line) {}

    /** A resolved symbol: where a request about it points. */
    private record Target(String uri, Position position, String display, DocumentSymbol symbol) {}

    /** A question that has its answer already: not found, or ambiguous. */
    private static final class Answered extends Exception {
        final transient LspResult result;

        Answered(LspResult result) {
            super(result.note(), null, false, false);
            this.result = result;
        }
    }

    private List<Symbol> symbols(String query) throws Exception {
        Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> found =
            await(server.getWorkspaceService().symbol(new WorkspaceSymbolParams(query)));
        List<Symbol> out = new ArrayList<>();
        if (found == null) {
            return out;
        }
        if (found.isLeft()) {
            for (SymbolInformation s : found.getLeft()) {
                out.add(new Symbol(s.getName(), nullToEmpty(s.getContainerName()), s.getKind(),
                    s.getLocation().getUri(), s.getLocation().getRange().getStart().getLine()));
            }
        } else {
            for (WorkspaceSymbol s : found.getRight()) {
                if (s.getLocation().isLeft()) {
                    Location at = s.getLocation().getLeft();
                    out.add(new Symbol(s.getName(), nullToEmpty(s.getContainerName()), s.getKind(),
                        at.getUri(), at.getRange().getStart().getLine()));
                }
            }
        }
        return out;
    }

    /**
     * The declaration(s) a name stands for: one for a type, a field or a method named with its
     * parameter types, every overload for a bare method name when {@code overloads} is set.
     */
    private List<Target> resolve(String symbol, boolean overloads) throws Exception {
        String asked = symbol == null ? "" : symbol.strip();
        int hash = asked.indexOf('#');
        String typePart = (hash < 0 ? asked : asked.substring(0, hash)).strip();
        String member = hash < 0 ? "" : asked.substring(hash + 1).strip();
        if (typePart.isEmpty()) {
            throw new Answered(LspResult.notFound("Name a type, or Type#member."));
        }
        int dot = typePart.lastIndexOf('.');
        String simple = dot < 0 ? typePart : typePart.substring(dot + 1);
        String container = dot < 0 ? "" : typePart.substring(0, dot);
        Map<String, Symbol> byName = new LinkedHashMap<>();
        for (Symbol s : symbols(simple)) {
            if (s.name.equals(simple) && (container.isEmpty() || s.container.equals(container))) {
                Symbol earlier = byName.get(qualified(s.container, s.name));
                if (earlier == null || (!isFile(earlier.uri) && isFile(s.uri))) {
                    byName.put(qualified(s.container, s.name), s);
                }
            }
        }
        List<Symbol> candidates = new ArrayList<>(byName.values());
        if (candidates.stream().anyMatch(s -> isFile(s.uri))) {
            candidates.removeIf(s -> !isFile(s.uri)); // the project's own type wins over a jar's
        }
        if (candidates.isEmpty()) {
            throw new Answered(LspResult.notFound("The language server knows no type called `"
                + typePart + "` in this project or its library jars. Check the name; "
                + "a fragment or a * pattern finds types by part of their name."));
        }
        if (candidates.size() > 1) {
            List<LspHit> choices = new ArrayList<>();
            for (Symbol s : candidates) {
                choices.add(hit(s.uri, s.line, kind(s.kind) + " " + qualified(s.container, s.name)));
            }
            throw new Answered(new LspResult(LspResult.Status.AMBIGUOUS, "`" + typePart + "` is "
                + "the name of " + candidates.size() + " types; ask again with the full name:",
                capped(choices), choices.size()));
        }
        Symbol type = candidates.get(0);
        String full = qualified(type.container, type.name);
        DocumentSymbol decl = find(documentSymbols(type.uri), simple);
        Position at = decl == null ? new Position(type.line, 0) : decl.getSelectionRange().getStart();
        if (member.isEmpty()) {
            return List.of(new Target(type.uri, at, full, decl));
        }
        List<Target> found = new ArrayList<>();
        String wanted = member.replaceAll("\\s+", "");
        if (decl != null && decl.getChildren() != null) {
            for (DocumentSymbol child : decl.getChildren()) {
                String name = child.getName().replaceAll("\\s+", "");
                if (name.equals(wanted) || (wanted.indexOf('(') < 0 && bare(name).equals(wanted))) {
                    found.add(new Target(type.uri, child.getSelectionRange().getStart(),
                        full + "#" + child.getName(), child));
                }
            }
        }
        if (found.isEmpty()) {
            List<String> names = new ArrayList<>();
            if (decl != null && decl.getChildren() != null) {
                decl.getChildren().forEach(c -> names.add(c.getName()));
            }
            throw new Answered(LspResult.notFound("`" + full + "` declares no member called `"
                + member + "`. It declares: " + cut(String.join(", ", names), 1_200)
                + ". (Inherited members are declared on its supertypes.)"));
        }
        return overloads ? found : found.subList(0, 1);
    }

    private List<DocumentSymbol> documentSymbols(String uri) throws Exception {
        List<Either<SymbolInformation, DocumentSymbol>> raw = await(server.getTextDocumentService()
            .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri))));
        List<DocumentSymbol> out = new ArrayList<>();
        for (Either<SymbolInformation, DocumentSymbol> either
                : raw == null ? List.<Either<SymbolInformation, DocumentSymbol>>of() : raw) {
            if (either.isRight()) {
                out.add(either.getRight());
            }
        }
        return out;
    }

    private static DocumentSymbol find(List<DocumentSymbol> symbols, String simpleName) {
        for (DocumentSymbol s : symbols) {
            if (s.getName().equals(simpleName) && isType(s.getKind())) {
                return s;
            }
        }
        for (DocumentSymbol s : symbols) {
            if (s.getChildren() != null && isType(s.getKind())) {
                DocumentSymbol nested = find(s.getChildren(), simpleName);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static boolean isType(SymbolKind kind) {
        return kind == SymbolKind.Class || kind == SymbolKind.Interface || kind == SymbolKind.Enum
            || kind == SymbolKind.Struct;
    }

    private List<TypeHierarchyItem> prepareTypes(Target target) throws Exception {
        List<TypeHierarchyItem> items = await(server.getTextDocumentService().prepareTypeHierarchy(
            new TypeHierarchyPrepareParams(new TextDocumentIdentifier(target.uri), target.position)));
        return items == null ? List.of() : items;
    }

    /** Every supertype, nearest first, each once. */
    private List<TypeHierarchyItem> supertypesOf(Target target) throws Exception {
        List<TypeHierarchyItem> all = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<TypeHierarchyItem> level = prepareTypes(target);
        while (!level.isEmpty() && all.size() < MAX_SUPERTYPES) {
            List<TypeHierarchyItem> next = new ArrayList<>();
            for (TypeHierarchyItem item : level) {
                List<TypeHierarchyItem> supers = await(server.getTextDocumentService()
                    .typeHierarchySupertypes(new TypeHierarchySupertypesParams(item)));
                for (TypeHierarchyItem parent : supers == null ? List.<TypeHierarchyItem>of() : supers) {
                    if (seen.add(parent.getUri() + "#" + parent.getName())
                            && all.size() < MAX_SUPERTYPES) {
                        all.add(parent);
                        next.add(parent);
                    }
                }
            }
            level = next;
        }
        return all;
    }

    private List<CallHierarchyItem> prepareCalls(Target target) throws Exception {
        List<CallHierarchyItem> items = await(server.getTextDocumentService().prepareCallHierarchy(
            new CallHierarchyPrepareParams(new TextDocumentIdentifier(target.uri), target.position)));
        return items == null ? List.of() : items;
    }

    // ----------------------------------------------------------------------------- rendering

    private static List<? extends Location> locations(
            Either<List<? extends Location>, List<? extends LocationLink>> either) {
        if (either == null) {
            return List.of();
        }
        if (either.isLeft()) {
            return either.getLeft();
        }
        List<Location> out = new ArrayList<>();
        for (LocationLink link : either.getRight()) {
            out.add(new Location(link.getTargetUri(), link.getTargetSelectionRange()));
        }
        return out;
    }

    private List<LspHit> hitsOf(List<? extends Location> locations) {
        List<LspHit> hits = new ArrayList<>();
        for (Location location : locations == null ? List.<Location>of() : locations) {
            hits.add(hit(location.getUri(), location.getRange().getStart().getLine(), null));
        }
        return hits;
    }

    /** Lines of files already read while one answer is put together. */
    private final Map<String, List<String>> lineCache = new HashMap<>();

    /** One place: {@code text} when given, otherwise the source line there. */
    private LspHit hit(String uri, int zeroBasedLine, String text) {
        String shown = text;
        if (shown == null) {
            List<String> lines = lineCache.computeIfAbsent(uri, this::linesOf);
            shown = zeroBasedLine >= 0 && zeroBasedLine < lines.size()
                ? lines.get(zeroBasedLine).strip() : "";
        }
        return new LspHit(fileOf(uri), isFile(uri) ? zeroBasedLine + 1 : 0, cut(shown));
    }

    private List<String> linesOf(String uri) {
        try {
            if (isFile(uri)) {
                // Only the workspace's own files are quoted: a link that leads out of it is not.
                Path file = Path.of(URI.create(uri)).toRealPath();
                return file.startsWith(workspaceRoot.toRealPath())
                    ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
            }
            String contents = server.classFileContents(new TextDocumentIdentifier(uri))
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return contents == null ? List.of() : List.of(contents.split("\\R", -1));
        } catch (Exception e) {                                                // noqa
            return List.of();
        }
    }

    /** A workspace-relative path, or {@code [jar] full.Type} for a class in a library. */
    private String fileOf(String uri) {
        if (isFile(uri)) {
            try {
                return relative(Path.of(URI.create(uri)));
            } catch (Exception e) {                                            // noqa
                return uri;
            }
        }
        // jdt://contents/<jar>/<package>/<Type>.class?...
        try {
            String path = uri.substring(uri.indexOf("://") + 3);
            int query = path.indexOf('?');
            String[] parts = (query < 0 ? path : path.substring(0, query)).split("/");
            if (parts.length >= 4) {
                String type = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
                int ext = type.lastIndexOf('.');
                type = ext > 0 ? type.substring(0, ext) : type; // .class, or .java with sources
                return "[" + URLDecoder.decode(parts[1], StandardCharsets.UTF_8) + "] "
                    + qualified(URLDecoder.decode(parts[2], StandardCharsets.UTF_8), type);
            }
        } catch (Exception e) {                                                // noqa
            // fall through
        }
        return "[library]";
    }

    /** The jar a library class's address names. */
    private static String jarOf(String uri) {
        try {
            String[] parts = uri.substring(uri.indexOf("://") + 3).split("/");
            return parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
        } catch (Exception e) {                                                // noqa
            return "";
        }
    }

    private static boolean isFile(String uri) {
        return uri != null && uri.startsWith("file:");
    }

    private String relative(Path file) {
        try {
            return workspaceRoot.relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString().replace('\\', '/');
        }
    }

    private Path inWorkspace(Path file) {
        if (file == null) {
            return null;
        }
        Path abs = (file.isAbsolute() ? file : workspaceRoot.resolve(file)).toAbsolutePath().normalize();
        return abs.startsWith(workspaceRoot) ? abs : null;
    }

    private static List<LspHit> sorted(Set<LspHit> hits) {
        List<LspHit> list = new ArrayList<>(hits);
        list.sort(Comparator.comparing(LspHit::file).thenComparingInt(LspHit::line));
        return list;
    }

    private static List<LspHit> capped(List<LspHit> hits) {
        return hits.size() <= MAX_HITS ? hits : hits.subList(0, MAX_HITS);
    }

    private static String cut(String text) {
        return cut(text, MAX_TEXT);
    }

    private static String cut(String text, int max) {
        String one = text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").strip();
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String qualified(String container, String name) {
        return container == null || container.isBlank() ? name : container + "." + name;
    }

    /** A member's name without its parameter list. */
    private static String bare(String name) {
        int paren = name.indexOf('(');
        return (paren < 0 ? name : name.substring(0, paren)).strip();
    }

    /** The last segment of a qualified name. */
    private static String simpleOf(String qualified) {
        String name = nullToEmpty(qualified);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** A type's name without its type parameters. */
    private static String simple(String name) {
        int angle = name.indexOf('<');
        return (angle < 0 ? name : name.substring(0, angle)).strip();
    }

    private static String kind(SymbolKind kind) {
        return kind == null ? "" : kind.name().toLowerCase(Locale.ROOT);
    }

    /** A hover's two halves: the signature, and the documentation. */
    private static String[] hoverParts(Hover hover) {
        String signature = "";
        StringBuilder doc = new StringBuilder();
        if (hover != null && hover.getContents() != null) {
            if (hover.getContents().isLeft()) {
                for (Either<String, MarkedString> part : hover.getContents().getLeft()) {
                    if (part.isRight() && signature.isEmpty()) {
                        signature = nullToEmpty(part.getRight().getValue());
                    } else {
                        doc.append(part.isLeft() ? part.getLeft() : part.getRight().getValue())
                            .append('\n');
                    }
                }
            } else {
                doc.append(nullToEmpty(hover.getContents().getRight().getValue()));
            }
        }
        return new String[] {signature.replaceAll("\\s*\\R\\s*", " ").strip(), plain(doc.toString())};
    }

    /** Documentation without its markup: link targets, emphasis marks and the "Source" line go. */
    static String plain(String doc) {
        String text = doc.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        int source = text.indexOf("Source: *");
        if (source >= 0) {
            text = text.substring(0, source);
        }
        return text.replaceAll("(?m)^\\s*\\*\\s+", "").replace("**", "").replace("`", "")
            .replaceAll("\\s*\\R\\s*", " ").strip();
    }

    private static String firstSentence(String doc) {
        String one = doc.replaceAll("\\s*\\R\\s*", " ").strip();
        int end = one.indexOf(". ");
        return end < 0 ? one : one.substring(0, end + 1);
    }

    // ------------------------------------------------------------------------------ plumbing

    private interface Query {
        LspResult get() throws Exception;
    }

    /** One query: start if needed, tell the server what changed on disk, ask, never throw. */
    private LspResult answer(String what, String argument, Query query) {
        if (unavailable || !ensureStarted()) {
            return LspResult.unavailable(why);
        }
        long began = System.nanoTime();
        try {
            lineCache.clear();
            syncChanges();
            LspResult result = query.get();
            log.debug("JDT LS {}('{}') -> {} in {} ms", what, argument, result.status(),
                (System.nanoTime() - began) / 1_000_000);
            return result;
        } catch (Answered answered) {
            return answered.result;
        } catch (java.util.concurrent.TimeoutException e) {
            return LspResult.failed("The language server did not answer within "
                + REQUEST_TIMEOUT_SECONDS + " seconds.");
        } catch (Exception e) {                                                // noqa
            log.debug("JDT LS {}('{}') failed: {}", what, argument, e.toString());
            if (process == null || !process.isAlive()) {
                markUnavailable("its process ended");
                return LspResult.unavailable(why);
            }
            return LspResult.failed("The language server could not answer that ("
                + cut(String.valueOf(e.getMessage())) + ").");
        } finally {
            lineCache.clear();
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Tells the server which Java files appeared, changed or went since it was last told. */
    private void syncChanges() {
        Map<Path, Long> now = javaFiles();
        List<FileEvent> events = new ArrayList<>();
        for (Map.Entry<Path, Long> entry : now.entrySet()) {
            Long before = known.get(entry.getKey());
            if (before == null) {
                events.add(new FileEvent(entry.getKey().toUri().toString(), FileChangeType.Created));
            } else if (!before.equals(entry.getValue())) {
                events.add(new FileEvent(entry.getKey().toUri().toString(), FileChangeType.Changed));
            }
        }
        for (Path gone : known.keySet()) {
            if (!now.containsKey(gone)) {
                events.add(new FileEvent(gone.toUri().toString(), FileChangeType.Deleted));
            }
        }
        known.clear();
        known.putAll(now);
        if (!events.isEmpty()) {
            server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(events));
        }
    }

    private Map<Path, Long> javaFiles() {
        Map<Path, Long> files = new HashMap<>();
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(workspaceRoot) && dir.getFileName() != null
                        && SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".java")) {
                        files.put(file, attrs.lastModifiedTime().toMillis());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("JDT LS: could not walk {}: {}", workspaceRoot, e.getMessage());
        }
        return files;
    }

    /**
     * The folders Java packages start in, relative to the workspace: for each Java file, its
     * folder minus its package. Found by reading each file's package line, so a project laid out
     * in any way is read right.
     */
    static List<String> sourceFolders(Path root, Set<Path> javaFiles) {
        Set<String> folders = new java.util.TreeSet<>();
        Set<Path> settled = new java.util.HashSet<>();
        for (Path file : javaFiles) {
            Path dir = file.getParent();
            if (dir == null || !settled.add(dir)) {
                continue;
            }
            String pkg = "";
            try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                int read = 0;
                while ((line = reader.readLine()) != null && read++ < 200) {
                    String s = line.strip();
                    if (s.startsWith("package ") && s.endsWith(";")) {
                        pkg = s.substring(8, s.length() - 1).strip();
                        break;
                    }
                }
            } catch (Exception e) {                                            // noqa
                continue;
            }
            Path sourceRoot = dir;
            if (!pkg.isEmpty()) {
                String[] parts = pkg.split("\\.");
                boolean fits = true;
                for (int i = parts.length - 1; i >= 0 && fits; i--) {
                    fits = sourceRoot != null && sourceRoot.getFileName() != null
                        && sourceRoot.getFileName().toString().equals(parts[i]);
                    sourceRoot = fits ? sourceRoot.getParent() : sourceRoot;
                }
                if (!fits || sourceRoot == null) {
                    continue;
                }
            }
            if (sourceRoot.startsWith(root)) {
                String relative = root.relativize(sourceRoot).toString().replace('\\', '/');
                folders.add(relative.isEmpty() ? "." : relative);
            }
        }
        return List.copyOf(folders);
    }

    /** JDT LS's settings: no build import and no downloads. */
    private static Map<String, Object> settings() {
        Map<String, Object> java = new LinkedHashMap<>();
        java.put("import", Map.of(
            "maven", Map.of("enabled", false, "offline", Map.of("enabled", true)),
            "gradle", Map.of("enabled", false, "wrapper", Map.of("enabled", false))));
        java.put("maven", Map.of("downloadSources", false, "updateSnapshots", false));
        java.put("eclipse", Map.of("downloadSources", false));
        java.put("autobuild", Map.of("enabled", false));
        java.put("configuration", Map.of("updateBuildConfiguration", "disabled"));
        java.put("symbols", Map.of("includeSourceMethodDeclarations", false));
        java.put("references", Map.of("includeDecompiledSources", false));
        java.put("errors", Map.of("incompleteClasspath", Map.of("severity", "ignore")));
        java.put("telemetry", Map.of("enabled", false));
        return Map.of("java", java);
    }

    /**
     * Writes the project the server opens: a plain Eclipse Java project in the server's own data
     * folder whose source folders are LINKS to the workspace's source folders and whose libraries
     * are the caller's jars. Nothing is written into the workspace, and no build file is in
     * sight of the server, so there is nothing for it to import or run.
     *
     * <p>(JDT LS's own way of opening a folder without a build, its "invisible project", refuses
     * a folder that has a pom.xml above it - found live, 2026-10-04.)
     */
    private Path writeProject(List<String> sourceFolders) throws IOException {
        Path project = workspaceData.resolve("project");
        Files.createDirectories(project.resolve(".settings"));
        StringBuilder links = new StringBuilder();
        StringBuilder entries = new StringBuilder();
        int n = 0;
        for (String folder : sourceFolders) {
            if (folder.equals(".") && sourceFolders.size() > 1) {
                continue; // a source folder may not hold another
            }
            String name = "src" + n++;
            links.append("    <link><name>").append(name).append("</name><type>2</type><locationURI>")
                .append(xml(workspaceRoot.resolve(folder).normalize().toUri().toString()))
                .append("</locationURI></link>\n");
            entries.append("  <classpathentry kind=\"src\" path=\"").append(name).append("\"/>\n");
        }
        for (Path library : libraries) {
            if (!Files.isRegularFile(library)) {
                continue;
            }
            Path jar = library.toAbsolutePath().normalize();
            String file = jar.getFileName().toString();
            Path sources = file.endsWith(".jar")
                ? jar.resolveSibling(file.substring(0, file.length() - 4) + "-sources.jar") : null;
            entries.append("  <classpathentry kind=\"lib\" path=\"").append(xml(slashed(jar))).append('"');
            if (sources != null && Files.isRegularFile(sources)) {
                entries.append(" sourcepath=\"").append(xml(slashed(sources))).append('"');
            }
            entries.append("/>\n");
        }
        Files.writeString(project.resolve(".project"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<projectDescription>\n  <name>workspace</name>\n  <buildSpec><buildCommand>"
            + "<name>org.eclipse.jdt.core.javabuilder</name></buildCommand></buildSpec>\n"
            + "  <natures><nature>org.eclipse.jdt.core.javanature</nature></natures>\n"
            + "  <linkedResources>\n" + links + "  </linkedResources>\n</projectDescription>\n");
        Files.writeString(project.resolve(".classpath"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<classpath>\n" + entries
            + "  <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\"/>\n"
            + "  <classpathentry kind=\"output\" path=\"bin\"/>\n</classpath>\n");
        String level = String.valueOf(Math.min(Runtime.version().feature(), 25));
        Files.writeString(project.resolve(".settings").resolve("org.eclipse.jdt.core.prefs"),
            "eclipse.preferences.version=1\n"
            + "org.eclipse.jdt.core.compiler.compliance=" + level + "\n"
            + "org.eclipse.jdt.core.compiler.source=" + level + "\n"
            + "org.eclipse.jdt.core.compiler.codegen.targetPlatform=" + level + "\n"
            + "org.eclipse.jdt.core.compiler.processAnnotations=disabled\n");
        return project;
    }

    private static String slashed(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    @Override
    public boolean isAvailable() {
        return !unavailable && started && process != null && process.isAlive();
    }

    private synchronized boolean ensureStarted() {
        if (started) {
            if (!isAvailable() && !unavailable) {
                markUnavailable("its process ended");
            }
            return isAvailable();
        }
        if (unavailable) {
            return false;
        }
        long began = System.nanoTime();
        try {
            Path launcher = findEquinoxLauncher();
            Path config = findConfigDir();
            if (launcher == null || config == null) {
                markUnavailable("no JDT LS product was found under " + jdtLsHome);
                return false;
            }
            // The product's configuration folder is written to at start; a copy per data
            // directory keeps the installed product untouched and two servers apart.
            Path ownConfig = workspaceData.resolve("config");
            Files.createDirectories(ownConfig);
            Files.copy(config.resolve("config.ini"), ownConfig.resolve("config.ini"),
                StandardCopyOption.REPLACE_EXISTING);
            List<String> command = new ArrayList<>(List.of(
                javaExecutable(),
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dlog.level=" + (TRACE ? "ALL" : "ERROR"),
                "-Xms256m",
                "-Xmx1g",
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", launcher.toString(),
                "-configuration", ownConfig.toString(),
                "-data", workspaceData.resolve("workspace").toString()));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Launcher<JdtServer> lspLauncher = new Launcher.Builder<JdtServer>()
                .setLocalService(client)
                .setRemoteInterface(JdtServer.class)
                .setInput(process.getInputStream())
                .setOutput(process.getOutputStream())
                .create();
            this.server = lspLauncher.getRemoteProxy();
            this.listening = lspLauncher.startListening();

            try {
                List<Path> jars = librarySource == null ? null : librarySource.get();
                libraries = jars == null ? List.of() : List.copyOf(jars);
            } catch (RuntimeException e) {
                libraries = List.of();
            }
            Map<Path, Long> files = javaFiles();
            List<String> sourceFolders = sourceFolders(workspaceRoot, files.keySet());
            Path project = writeProject(sourceFolders);
            Map<String, Object> settings = settings();
            InitializeParams init = new InitializeParams();
            init.setProcessId((int) ProcessHandle.current().pid());
            init.setRootUri(project.toUri().toString());
            init.setCapabilities(capabilities());
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("settings", settings);
            options.put("extendedClientCapabilities", Map.of("classFileContentsSupport", true));
            init.setInitializationOptions(options);
            server.initialize(init).get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server.initialized(new InitializedParams());
            server.getWorkspaceService().didChangeConfiguration(
                new DidChangeConfigurationParams(settings));
            if (!client.ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.info("JDT LS did not report ready within {} s for {}; asking it anyway",
                    READY_TIMEOUT_SECONDS, workspaceRoot);
            }
            known.clear();
            known.putAll(files);
            started = true;
            coldStartMillis = (System.nanoTime() - began) / 1_000_000;
            log.info("JDT LS started for workspace {} in {} ms (pid {}, {} source folder(s), "
                + "{} library jar(s))", workspaceRoot, coldStartMillis, process.pid(),
                sourceFolders.size(), libraries.size());
            return isAvailable();
        } catch (Exception e) {
            log.info("JDT LS failed to start ({})", e.toString());
            markUnavailable("it failed to start (" + e.getMessage() + ")");
            return false;
        }
    }

    private static ClientCapabilities capabilities() {
        TextDocumentClientCapabilities text = new TextDocumentClientCapabilities();
        DocumentSymbolCapabilities symbols = new DocumentSymbolCapabilities();
        symbols.setHierarchicalDocumentSymbolSupport(true);
        text.setDocumentSymbol(symbols);
        CodeActionCapabilities actions = new CodeActionCapabilities();
        actions.setCodeActionLiteralSupport(new CodeActionLiteralSupportCapabilities(
            new CodeActionKindCapabilities(List.of(CodeActionKind.Source,
                CodeActionKind.SourceOrganizeImports, CodeActionKind.Refactor,
                CodeActionKind.QuickFix))));
        text.setCodeAction(actions);
        WorkspaceEditCapabilities edits = new WorkspaceEditCapabilities();
        edits.setDocumentChanges(true);
        // JDT LS moves a renamed type's file only for a client that names all three.
        edits.setResourceOperations(List.of(ResourceOperationKind.Create,
            ResourceOperationKind.Rename, ResourceOperationKind.Delete));
        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setWorkspaceEdit(edits);
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(text);
        capabilities.setWorkspace(workspace);
        return capabilities;
    }

    private void markUnavailable(String reason) {
        unavailable = true;
        why = reason;
        closeQuietly();
    }

    @Override
    public synchronized void close() {
        try {
            if (server != null && started && process != null && process.isAlive()) {
                server.shutdown().get(5, TimeUnit.SECONDS);
                server.exit();
            }
        } catch (Exception ignored) {
            // shutting down; nothing actionable
        } finally {
            closeQuietly();
            unavailable = true;
            why = "it was stopped";
            if (ownsData) {
                deleteData();
            }
        }
    }

    private void deleteData() {
        try (java.util.stream.Stream<Path> walk = Files.walk(workspaceData)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception e) {                                                // noqa
            log.debug("JDT LS: could not delete {}: {}", workspaceData, e.toString());
        }
    }

    private void closeQuietly() {
        if (listening != null) {
            listening.cancel(true);
        }
        if (process != null && process.isAlive()) {
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        started = false;
    }

    private List<LspDiagnostic> map(List<Diagnostic> raw, Path file) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String relative = relative(file);
        List<LspDiagnostic> out = new ArrayList<>(raw.size());
        for (Diagnostic d : raw) {
            int line = 1;
            int column = 1;
            if (d.getRange() != null && d.getRange().getStart() != null) {
                line = d.getRange().getStart().getLine() + 1;      // LSP is 0-based
                column = d.getRange().getStart().getCharacter() + 1;
            }
            out.add(new LspDiagnostic(relative, line, column,
                severity(d.getSeverity()), codeOf(d), collapse(d.getMessage())));
        }
        return out;
    }

    private static LspSeverity severity(DiagnosticSeverity s) {
        if (s == null) {
            return LspSeverity.INFORMATION;
        }
        return switch (s) {
            case Error -> LspSeverity.ERROR;
            case Warning -> LspSeverity.WARNING;
            case Information -> LspSeverity.INFORMATION;
            case Hint -> LspSeverity.HINT;
        };
    }

    private static String codeOf(Diagnostic d) {
        if (d.getCode() == null) {
            return null;
        }
        return d.getCode().isLeft() ? d.getCode().getLeft() : String.valueOf(d.getCode().getRight());
    }

    private static String collapse(String message) {
        return message == null ? "" : message.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    private static String languageId(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return "java";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "plaintext";
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String bin = "bin/java" + (windows ? ".exe" : "");
        Path candidate = Path.of(home, bin);
        return Files.isExecutable(candidate) ? candidate.toString() : "java";
    }

    private Path findEquinoxLauncher() throws IOException {
        Path plugins = jdtLsHome.resolve("plugins");
        if (!Files.isDirectory(plugins)) {
            return null;
        }
        try (DirectoryStream<Path> stream =
                 Files.newDirectoryStream(plugins, "org.eclipse.equinox.launcher_*.jar")) {
            for (Path p : stream) {
                return p; // first match is fine — there is exactly one launcher in a product
            }
        }
        return null;
    }

    private Path findConfigDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String suffix = os.contains("win") ? "config_win"
            : os.contains("mac") || os.contains("darwin") ? "config_mac"
            : "config_linux";
        Path dir = jdtLsHome.resolve(suffix);
        return Files.isDirectory(dir) ? dir : null;
    }

    /** What JDT LS says about itself: {@code language/status}. */
    public static final class StatusReport {
        private String type;
        private String message;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    /**
     * The client half: collects {@code publishDiagnostics} pushes per document URI, and learns
     * from {@code language/status} when the server has read the workspace. A caller that is about
     * to {@code didOpen} a file first {@link #expect(String)}s its URI to get a future that
     * completes on the next push for that URI; {@link #latest(String)} returns the last push seen.
     */
    private static final class Client implements LanguageClient {
        private final Map<String, List<Diagnostic>> latest = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<List<Diagnostic>>> pending = new ConcurrentHashMap<>();
        final CountDownLatch ready = new CountDownLatch(1);

        CompletableFuture<List<Diagnostic>> expect(String uri) {
            CompletableFuture<List<Diagnostic>> future = new CompletableFuture<>();
            pending.put(uri, future);
            return future;
        }

        List<Diagnostic> latest(String uri) {
            return latest.getOrDefault(uri, List.of());
        }

        void forget(String uri) {
            pending.remove(uri);
            latest.remove(uri);
        }

        @JsonNotification("language/status")
        public void languageStatus(StatusReport report) {
            if (TRACE && report != null) {
                System.err.println("JDTLS-STATUS " + report.getType() + " " + report.getMessage());
            }
            if (report != null && "ServiceReady".equals(report.getType())) {
                ready.countDown();
            }
        }

        @JsonNotification("language/eventNotification")
        public void eventNotification(Object event) {
            // ignored
        }

        @JsonNotification("language/actionableNotification")
        public void actionableNotification(Object notification) {
            // ignored
        }

        @JsonNotification("language/progressReport")
        public void progressReport(Object report) {
            // ignored
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            List<Diagnostic> list = params.getDiagnostics() == null ? List.of() : params.getDiagnostics();
            latest.put(params.getUri(), list);
            CompletableFuture<List<Diagnostic>> future = pending.get(params.getUri());
            if (future != null) {
                future.complete(list);
            }
        }

        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void telemetryEvent(Object object) {
            // ignored
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            // ignored
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            // JDT LS is chatty; shown only when someone is looking for a fault
            if (TRACE && message != null) {
                System.err.println("JDTLS-LOG " + message.getType() + " " + message.getMessage());
            }
        }
    }
}
