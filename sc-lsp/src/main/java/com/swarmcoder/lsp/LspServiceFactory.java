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

/**
 * Builds an {@link LspService} bound to a specific workspace root. A language server instance is
 * tied to one checkout, so the server cannot be a singleton across candidate worktrees — the
 * consumer that owns a workspace (e.g. the final-integration worktree, spec §S6) asks the factory
 * for a server scoped to it, uses it, and closes it.
 *
 * <p>{@link #NONE} yields {@link LspService#UNAVAILABLE} for every root — the default, so LSP is
 * entirely off unless a server home is configured.
 */
@FunctionalInterface
public interface LspServiceFactory {

    /** Never returns null; returns {@link LspService#UNAVAILABLE} when no server is available. */
    LspService create(Path workspaceRoot);

    LspServiceFactory NONE = workspaceRoot -> LspService.UNAVAILABLE;

    /**
     * A factory that launches Eclipse JDT LS from {@code jdtLsHome} for each requested workspace.
     * Returns {@link #NONE} when the home is null or blank — so configuring the feature is opt-in
     * and its absence is a clean no-op (spec §S6: LSP is an enhancer, never a prerequisite).
     */
    static LspServiceFactory fromJdtLsHome(String jdtLsHome) {
        if (jdtLsHome == null || jdtLsHome.isBlank()) {
            return NONE;
        }
        Path home = Path.of(jdtLsHome.trim());
        return workspaceRoot -> new JdtLanguageServer(home, workspaceRoot);
    }

    /**
     * A factory for the JDT LS product installed on this machine, found by {@link JdtLsInstall}
     * (system property, then the {@code tools.jdtLsHome} setting, then the default folder).
     * {@link #NONE} when none is installed.
     *
     * @param libraries  the jars a workspace compiles against, resolved offline by the caller;
     *                   null is "none"
     * @param writable   whether refactorings may write into the workspace
     */
    static LspServiceFactory installed(
            java.util.function.Function<Path, java.util.List<Path>> libraries, boolean writable) {
        Path home = JdtLsInstall.locate();
        if (home == null) {
            return NONE;
        }
        return workspaceRoot -> new JdtLanguageServer(home, workspaceRoot, null,
            () -> libraries == null ? java.util.List.of() : libraries.apply(workspaceRoot),
            writable, false);
    }

    /** True when {@link #installed} would find a product on this machine. */
    static boolean isInstalled() {
        return JdtLsInstall.locate() != null;
    }

    /**
     * As {@link #installed}, for a checkout that is thrown away when its work is done (a
     * worker's, the integration worktree): closing the server deletes its store, kept under
     * {@code ~/.swarmcoder/jdtls-data/checkouts}, so nothing piles up run after run.
     */
    static LspServiceFactory forCheckouts(
            java.util.function.Function<Path, java.util.List<Path>> libraries, boolean writable) {
        Path home = JdtLsInstall.locate();
        if (home == null) {
            return NONE;
        }
        return checkout -> {
            Path root = checkout.toAbsolutePath().normalize();
            Path data = JdtLanguageServer.defaultDataDir(root).resolveSibling("checkouts")
                .resolve(JdtLanguageServer.defaultDataDir(root).getFileName());
            return new JdtLanguageServer(home, root, data,
                () -> libraries == null ? java.util.List.of() : libraries.apply(root),
                writable, true);
        };
    }
}
