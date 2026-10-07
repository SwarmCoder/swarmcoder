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
package com.swarmcoder.app;

import com.swarmcoder.knowledge.DocumentQueries;
import com.swarmcoder.knowledge.LanguageQueries;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.ProjectClasspath;
import com.swarmcoder.knowledge.TreeQueries;
import com.swarmcoder.lsp.LspHit;
import com.swarmcoder.lsp.LspResult;
import com.swarmcoder.lsp.LspService;
import com.swarmcoder.lsp.LspServiceFactory;
import com.swarmcoder.runtime.ApiLookup;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Everything a worker's toolbox can look up about its project, behind {@link ApiLookup}: the
 * documentation search, a worked example, the syntax tree, the project's documents by section
 * and the Java language server.
 *
 * <p><b>Why it is a class of its own (live run 82, 2026-10-04).</b> It was an anonymous class
 * inside {@link ProjectContext}, and the live harness wired its workers with
 * {@code librarian::lookupApi} instead - a method reference, which binds {@code lookup(String)}
 * and leaves every other method at its default. So in run 82 each of the workers' 11 tree
 * queries was answered "The project's syntax tree is not configured here; use read on the file."
 * (71 characters; 781 in all), and the workers read 36 files whole and ran 12 find, grep and cat
 * commands instead. The product and the harness now build the same object, so a query offered to
 * a worker cannot be wired in one and missing in the other.
 */
public final class WorkerLookups implements ApiLookup {

    private final Librarian librarian;
    private final Path projectPath;
    private final boolean languageServerInstalled;
    private final LspServiceFactory checkoutServers;
    private final Map<Path, LspService> serverOfCheckout = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> byLanguageServer = ThreadLocal.withInitial(() -> false);

    /**
     * @param languageServerInstalled whether the librarian's curator was given a language server
     *                                for the project ({@code KnowledgeCurator.languageServer})
     * @param checkoutServers         makes the server of one worker's checkout; null is none
     */
    public WorkerLookups(Librarian librarian, Path projectPath, boolean languageServerInstalled,
                         LspServiceFactory checkoutServers) {
        this.librarian = librarian;
        this.projectPath = projectPath;
        this.languageServerInstalled = languageServerInstalled && checkoutServers != null;
        this.checkoutServers = checkoutServers;
    }

    /**
     * The lookups of a project whose language server, when one is installed on this machine, is
     * started here and given to the librarian's curator - what a harness calls; the product
     * does the same in {@link ProjectContext}, where it also logs it.
     */
    public static WorkerLookups withTheInstalledServer(Librarian librarian, Path projectPath) {
        LspService projectServer = projectPath == null ? LspService.UNAVAILABLE
            : LspServiceFactory.installed(ProjectClasspath::jarsOf, false).create(projectPath);
        librarian.curator().languageServer(projectServer, projectPath);
        if (projectServer.isInstalled()) {
            Runtime.getRuntime().addShutdownHook(new Thread(projectServer::close, "jdtls-stop"));
        }
        return new WorkerLookups(librarian, projectPath, projectServer.isInstalled(),
            LspServiceFactory.forCheckouts(ProjectClasspath::jarsOf, true));
    }

    @Override
    public String lookup(String query) {
        return librarian.lookupApi(query);
    }

    // Forwarded, not left at the default: the exclusion/boost overload - "never hand this worker
    // the same section twice" - only does anything when it reaches the librarian.
    @Override
    public String lookup(String query, Set<String> alreadyGivenSections, Set<String> newTerms) {
        return librarian.lookupApi(query, alreadyGivenSections, newTerms);
    }

    @Override
    public String findExample(String what, boolean wantTest, String forWhom) {
        return librarian.findExample(what, wantTest, forWhom);
    }

    @Override
    public String tree(String query, String argument) {
        TreeQueries tree = TreeQueries.forAWorkerOf(librarian.curator(), projectPath);
        // One tool per question; which source answers is LanguageQueries' fixed rule.
        LanguageQueries language = LanguageQueries.forAWorkerOf(librarian.curator());
        byLanguageServer.set(true);
        switch (query) {
            case "shape_of": {
                String members = tree.holds(argument) ? null : language.membersOrNull(argument);
                if (members != null) {
                    return members;
                }
                break;
            }
            case "usages_of": {
                String usages = language.usagesOrNull(argument);
                if (usages != null) {
                    return usages;
                }
                break;
            }
            case "implementations_of": return language.implementationsOf(argument);
            case "supertypes_of": return language.supertypesOf(argument);
            case "callers_of": return language.callersOf(argument);
            case "find_symbol": return language.findSymbol(argument);
            case "doc_of": return language.docOf(argument);
            default: break;
        }
        byLanguageServer.set(false);
        return switch (query) {
            case "shape_of" -> tree.shapeOf(argument);
            case "body_of" -> tree.bodyOf(argument);
            case "types_in" -> tree.typesIn(argument);
            case "build_of" -> tree.buildOf(argument);
            case "resources_of" -> tree.resourcesOf(argument);
            case "usages_of" -> tree.usagesOf(argument);
            case "doc_outline" -> documents().outlineOf(argument);
            case "doc_section" -> documents().sectionOf(argument);
            case "doc_search" -> documents().search(argument);
            default -> "No such tree query: " + query;
        };
    }

    /**
     * The file as it is in the project the tree and the project's language server read; null
     * when the project has no such file - a type an earlier task of the run added.
     */
    @Override
    public String sourceInTree(String relativePath) {
        if (projectPath == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path root = projectPath.toAbsolutePath().normalize();
            Path file = root.resolve(relativePath.replace('\\', '/')).normalize();
            return file.startsWith(root) && java.nio.file.Files.isRegularFile(file)
                ? java.nio.file.Files.readString(file) : null;
        } catch (java.io.IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private DocumentQueries documents() {
        return DocumentQueries.forAWorkerOf(librarian.curator(), projectPath);
    }

    @Override
    public boolean answeredByLanguageServer() {
        return byLanguageServer.get();
    }

    @Override
    public boolean languageServer() {
        return languageServerInstalled;
    }

    @Override
    public InCheckout inCheckout(Path checkout, String action, String argument, String second,
                                 Function<String, String> refusal) {
        if (!languageServerInstalled || checkout == null) {
            return ApiLookup.super.inCheckout(checkout, action, argument, second, refusal);
        }
        LspService server = serverOfCheckout.computeIfAbsent(
            checkout.toAbsolutePath().normalize(), checkoutServers::create);
        LanguageQueries own = LanguageQueries.over(server);
        if ("problems".equals(action)) {
            return new InCheckout(own.problemsIn(argument), List.of());
        }
        LspResult done = "rename".equals(action)
            ? own.rename(argument, second, refusal) : own.organizeImports(argument);
        return new InCheckout(done.render(), !done.answered() ? List.of()
            : done.hits().stream().map(LspHit::file).toList());
    }

    @Override
    public void leaveCheckout(Path checkout) {
        LspService server = checkout == null ? null
            : serverOfCheckout.remove(checkout.toAbsolutePath().normalize());
        if (server != null) {
            server.close();
        }
    }
}
