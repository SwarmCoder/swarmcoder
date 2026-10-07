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
 * One place a language-server query answered with: where, and one line of text. Never a file's
 * contents - a query answers with places (owner's rule, CLAUDE.md section 1).
 *
 * @param file workspace-relative path with forward slashes; for a declaration that lives in a
 *             library jar, {@code [jar] fully.qualified.Type}
 * @param line 1-based line, or 0 when there is none to give
 * @param text one line: the source line, a signature, or what the place is
 */
public record LspHit(String file, int line, String text) {

    /** {@code path:line  text}. */
    public String render() {
        String where = file == null || file.isBlank() ? (line > 0 ? "line " + line : "")
            : file + (line > 0 ? ":" + line : "");
        String said = text == null ? "" : text;
        return where.isEmpty() ? said : said.isBlank() ? where : where + "  " + said;
    }
}
