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
import com.swarmcoder.lsp.LspSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LspPrecheckTest {

    /** Fake server returning canned diagnostics per file; available unless constructed empty. */
    private static LspService fake(Map<String, List<LspDiagnostic>> byName, boolean available) {
        return new LspService() {
            @Override public List<LspDiagnostic> diagnostics(Path file) {
                return byName.getOrDefault(file.getFileName().toString(), List.of());
            }
            @Override public boolean isAvailable() { return available; }
            @Override public void close() { }
        };
    }

    @Test
    void skippedWhenServiceUnavailable() {
        LspPrecheck.Result r = new LspPrecheck(LspService.UNAVAILABLE).check(List.of(Path.of("A.java")));
        assertThat(r.ran()).isFalse();
        assertThat(r.hasErrors()).isFalse();
        assertThat(r.renderForLog()).isEmpty();
    }

    @Test
    void skippedWhenNoFiles() {
        LspService lsp = fake(Map.of(), true);
        assertThat(new LspPrecheck(lsp).check(List.of()).ran()).isFalse();
    }

    @Test
    void aggregatesErrorsFirstThenWarningsAndCountsAll() {
        LspDiagnostic err = new LspDiagnostic("A.java", 3, 7, LspSeverity.ERROR, "1610", "cannot resolve 'Foo'");
        LspDiagnostic warn = new LspDiagnostic("A.java", 5, 1, LspSeverity.WARNING, null, "unused import");
        LspDiagnostic hint = new LspDiagnostic("A.java", 9, 1, LspSeverity.HINT, null, "could be final");
        LspService lsp = fake(Map.of("A.java", List.of(warn, err, hint)), true);

        LspPrecheck.Result r = new LspPrecheck(lsp).check(List.of(Path.of("src/A.java")));

        assertThat(r.ran()).isTrue();
        assertThat(r.errorCount()).isEqualTo(1);
        assertThat(r.diagnosticCount()).isEqualTo(3);
        assertThat(r.hasErrors()).isTrue();
        // errors render before warnings; hints are counted but not rendered
        assertThat(r.rendered()).containsExactly(
            "ERROR A.java:3:7 cannot resolve 'Foo' [1610]",
            "WARNING A.java:5:1 unused import");
        assertThat(r.renderForLog())
            .contains("[lsp] pre-compile diagnostics (advisory): 1 error(s), 3 total")
            .contains("[lsp]   ERROR A.java:3:7 cannot resolve 'Foo' [1610]");
    }

    @Test
    void cleanFilesProduceRanResultWithNoErrors() {
        LspService lsp = fake(Map.of("A.java", List.of()), true);
        LspPrecheck.Result r = new LspPrecheck(lsp).check(List.of(Path.of("A.java")));
        assertThat(r.ran()).isTrue();
        assertThat(r.hasErrors()).isFalse();
        assertThat(r.diagnosticCount()).isZero();
    }
}
