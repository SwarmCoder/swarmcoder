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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.lsp.LspDiagnostic;
import com.swarmcoder.lsp.LspService;
import com.swarmcoder.lsp.LspSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advisory LSP pre-compile signal (spec §S6) folds into the log without affecting survival,
 * and is a strict no-op when no server is wired (the default) or the target has no local root.
 */
class CommandPipelineVerifierLspTest {

    private static Task task(Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, "t", "instructions", writeSet, Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    private static VerifySpec compileOnlySpec() {
        return new VerifySpec("gradle", List.of("compile"), List.of(), List.of(), List.of(),
            null, null, 60, null);
    }

    private static LspService fakeReturning(LspDiagnostic... diagnostics) {
        return new LspService() {
            @Override public List<LspDiagnostic> diagnostics(Path file) { return List.of(diagnostics); }
            @Override public boolean isAvailable() { return true; }
            @Override public void close() { }
        };
    }

    @Test
    void foldsDiagnosticsIntoTheLogWithoutChangingSurvival(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("App.java"), "public class App {}\n");
        LspService lsp = fakeReturning(
            new LspDiagnostic("App.java", 3, 7, LspSeverity.ERROR, "1610", "cannot resolve 'Foo'"));
        CommandPipelineVerifier verifier = new CommandPipelineVerifier(BlobSink.NONE, lsp);
        FakeExecTarget target = new FakeExecTarget().localRoot(workspace).script("compile", 0);

        VerificationReport report = verifier.verify(target, task(Set.of("App.java")), compileOnlySpec());

        assertThat(report.compiles()).isTrue();
        assertThat(Verdicts.survived(report)).isTrue(); // advisory: LSP errors never kill
        assertThat(report.logTail())
            .contains("[lsp] pre-compile diagnostics (advisory): 1 error(s), 1 total")
            .contains("[lsp]   ERROR App.java:3:7 cannot resolve 'Foo' [1610]");
    }

    @Test
    void noOpWhenServiceUnavailable(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("App.java"), "public class App {}\n");
        CommandPipelineVerifier verifier = new CommandPipelineVerifier(); // default UNAVAILABLE
        FakeExecTarget target = new FakeExecTarget().localRoot(workspace).script("compile", 0);

        VerificationReport report = verifier.verify(target, task(Set.of("App.java")), compileOnlySpec());

        assertThat(report.logTail()).doesNotContain("[lsp]");
    }

    @Test
    void noOpWhenTargetHasNoLocalRoot() {
        LspService lsp = fakeReturning(
            new LspDiagnostic("App.java", 1, 1, LspSeverity.ERROR, null, "boom"));
        CommandPipelineVerifier verifier = new CommandPipelineVerifier(BlobSink.NONE, lsp);
        FakeExecTarget target = new FakeExecTarget().script("compile", 0); // no localRoot

        VerificationReport report = verifier.verify(target, task(Set.of("App.java")), compileOnlySpec());

        assertThat(report.logTail()).doesNotContain("[lsp]");
    }
}
