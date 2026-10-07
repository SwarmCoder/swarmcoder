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

import com.swarmcoder.lsp.LspDiagnostic;
import com.swarmcoder.lsp.LspService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The optional pre-compile LSP diagnostics signal (spec §S6): a cheap, advisory look at the
 * changed files via a language server BEFORE the full build. It is strictly advisory — a language
 * server misses annotation processors, code generation, and resource pipelines, so it never
 * substitutes for the real compile stage and never kills a candidate on its own here. Its value is
 * an early, human-readable "you referenced a symbol that doesn't exist" hint folded into the
 * verification log.
 *
 * <p>When no {@link LspService} is wired (the default {@link LspService#UNAVAILABLE}), {@link #check}
 * returns a skipped result and the pipeline behaves exactly as if this stage did not exist.
 */
public final class LspPrecheck {

    /** Cap on rendered lines so the signal stays small (prefix/log friendly). */
    private static final int MAX_RENDERED = 50;

    private final LspService lsp;

    public LspPrecheck(LspService lsp) {
        this.lsp = lsp == null ? LspService.UNAVAILABLE : lsp;
    }

    /**
     * Aggregates diagnostics across the given files. Errors are surfaced first, then warnings;
     * information/hint severities are counted but not rendered (too noisy for a pre-compile hint).
     */
    public Result check(Collection<Path> files) {
        if (!lsp.isAvailable() || files == null || files.isEmpty()) {
            return Result.SKIPPED;
        }
        List<LspDiagnostic> errors = new ArrayList<>();
        List<LspDiagnostic> warnings = new ArrayList<>();
        int total = 0;
        for (Path file : files) {
            for (LspDiagnostic d : lsp.diagnostics(file)) {
                total++;
                switch (d.severity()) {
                    case ERROR -> errors.add(d);
                    case WARNING -> warnings.add(d);
                    default -> { /* counted in total, not rendered */ }
                }
            }
        }
        List<String> rendered = new ArrayList<>();
        for (LspDiagnostic d : errors) {
            if (rendered.size() >= MAX_RENDERED) {
                break;
            }
            rendered.add(d.render());
        }
        for (LspDiagnostic d : warnings) {
            if (rendered.size() >= MAX_RENDERED) {
                break;
            }
            rendered.add(d.render());
        }
        return new Result(true, errors.size(), total, List.copyOf(rendered));
    }

    /** Advisory result. {@code ran()} is false when no server was available. */
    public record Result(boolean ran, int errorCount, int diagnosticCount, List<String> rendered) {

        static final Result SKIPPED = new Result(false, 0, 0, List.of());

        public boolean hasErrors() {
            return errorCount > 0;
        }

        /** One block suitable for the verification log; empty string when the stage was skipped. */
        public String renderForLog() {
            if (!ran) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[lsp] pre-compile diagnostics (advisory): ")
                .append(errorCount).append(" error(s), ")
                .append(diagnosticCount).append(" total\n");
            for (String line : rendered) {
                sb.append("[lsp]   ").append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
