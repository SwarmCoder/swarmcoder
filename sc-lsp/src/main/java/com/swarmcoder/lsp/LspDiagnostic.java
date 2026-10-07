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

/**
 * A single language-server diagnostic, reduced to the facade-clean fields consumers need.
 * Lines and columns are 1-based (LSP is 0-based; the facade converts) so rendered output
 * matches what a human sees in an editor and what compilers report.
 *
 * @param file     workspace-relative path (forward slashes), or the raw URI if not relativizable
 * @param line     1-based line number of the diagnostic's start
 * @param column   1-based column of the diagnostic's start
 * @param severity facade-clean severity
 * @param code     server-specific rule/error code, or {@code null}
 * @param message  the human-readable message (single line; newlines collapsed)
 */
public record LspDiagnostic(
    String file,
    int line,
    int column,
    LspSeverity severity,
    String code,
    String message) {

    public boolean isError() {
        return severity == LspSeverity.ERROR;
    }

    /** Compact one-line rendering, e.g. {@code ERROR src/App.java:12:5 cannot resolve 'Foo' [1610]}. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity).append(' ').append(file).append(':').append(line).append(':').append(column)
            .append(' ').append(message);
        if (code != null && !code.isBlank()) {
            sb.append(" [").append(code).append(']');
        }
        return sb.toString();
    }
}
