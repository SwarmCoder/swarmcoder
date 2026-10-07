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

import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.BuildReachability;
import com.swarmcoder.domain.BuildReachabilityStatus;
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.GuidelineCheckResult;
import com.swarmcoder.domain.LintResults;
import com.swarmcoder.domain.PageCheck;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.lsp.LspService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The verification pipeline (spec §10.2): compile → guideline checks → acceptance tests →
 * existing tests → lint → browser checks, fail-fast and cheapest-first. Toolchain differences
 * (Gradle vs Maven vs node/cargo/python) are expressed entirely through the command lists and
 * report locations in {@link VerifySpec}; test outcomes are read structurally from JUnit XML,
 * never guessed from exit codes.
 *
 * <p>Short-circuit policy: a compile failure skips everything downstream; a broken house rule or
 * an acceptance failure skips existing tests, lint, and browser (the candidate cannot survive, so
 * the compute is saved) — the report still records exactly which stages ran.
 *
 * <p><b>Why the guideline stage sits exactly there</b> (author decision, §21). It cannot run
 * before the compile: a rule's command inspects the candidate's source, and source that does not
 * compile is not a candidate at all — failing it on a house rule would bury the real reason. It
 * must run before the tests: a grep-shaped check costs milliseconds against minutes for a suite,
 * and the pipeline's whole ordering principle is cheapest-first, so a candidate doomed by a rule
 * should not first buy a full test run. That is the same slot lint would occupy if lint decided
 * anything; the difference is that lint is advisory and a declared rule check is not.
 */
public final class CommandPipelineVerifier implements Verifier {

    private static final Logger log = LoggerFactory.getLogger(CommandPipelineVerifier.class);
    private static final int LOG_TAIL_LINES = 200;

    /** An earlier stage failed, so this one was never attempted. It says nothing about anything. */
    private static final TestResults SKIPPED =
        new TestResults(0, 0, 0, 0, List.of()).withStageOutcome(TestStageOutcome.SKIPPED);
    /** The verification contract declares no commands for this stage. */
    private static final TestResults NOT_CONFIGURED =
        new TestResults(0, 0, 0, 0, List.of()).withStageOutcome(TestStageOutcome.NOT_CONFIGURED);
    /** Cap on write-set files fed to the advisory LSP precheck — the signal stays cheap. */
    private static final int MAX_LSP_FILES = 25;

    private final BlobSink blobSink;
    private final LspService lsp;

    public CommandPipelineVerifier() {
        this(BlobSink.NONE);
    }

    public CommandPipelineVerifier(BlobSink blobSink) {
        this(blobSink, LspService.UNAVAILABLE);
    }

    /**
     * @param lsp advisory pre-compile language-server signal (spec §S6). The default
     *            {@link LspService#UNAVAILABLE} makes the precheck a no-op, so behavior is
     *            identical to the pipeline without LSP. A server is only meaningful on a
     *            single, local workspace (the integration worktree) — per-candidate LSP is
     *            prohibitive, and the precheck is skipped when the target has no local root.
     */
    public CommandPipelineVerifier(BlobSink blobSink, LspService lsp) {
        this.blobSink = blobSink == null ? BlobSink.NONE : blobSink;
        this.lsp = lsp == null ? LspService.UNAVAILABLE : lsp;
    }

    @Override
    public VerificationReport verify(ExecTarget target, Task task, VerifySpec spec,
                                     List<GuidelineCheck> guidelineChecks, Set<String> changedFiles) {
        return verify(target, task, spec, guidelineChecks, changedFiles, VerificationBaseline.NONE);
    }

    @Override
    public VerificationReport verify(ExecTarget target, Task task, VerifySpec spec,
                                     List<GuidelineCheck> guidelineChecks, Set<String> changedFiles,
                                     VerificationBaseline baseline) {
        Instant start = Instant.now();
        StringBuilder fullLog = new StringBuilder();

        // Advisory pre-compile signal (spec §S6): folded into the log, never gates survival —
        // a language server misses annotation processors, codegen, and resource pipelines, so
        // the real compile below stays the authority.
        runLspPrecheck(target, task, fullLog);

        String compileFailureOutput = runCompileStage(target, spec.compile(), spec.effectiveTimeoutSeconds(), fullLog);
        boolean compiles = compileFailureOutput == null;

        // Reachability, immediately after the compile whose verdict it corrects. It costs
        // milliseconds (it reads build files, it runs nothing), and it answers the one question the
        // exit code above cannot: were THESE files among what that command built? A candidate whose
        // code sits outside every source root is dead here, and everything downstream is skipped —
        // running a test suite against code that is not in the build proves nothing about it.
        BuildReachability reachability = runReachabilityStage(target, spec, changedFiles, fullLog);
        boolean reachable = reachability == null || !reachability.orphaned();

        // Whose fault a failed compile is, read from what the compiler printed against what the
        // candidate wrote. After reachability rather than before it only because the layout that
        // stage read is the cheapest way to turn the compiler's absolute paths back into the
        // repo-relative ones the sentence names. The exit code above cannot say whether the
        // candidate's own files failed or an acceptance test committed by an earlier run did, and
        // on 2026-09-02 that difference was the whole story of a run.
        CompileFailure compileFailure = null;
        if (!compiles) {
            compileFailure = CompileFailureAttribution.attribute(compileFailureOutput, changedFiles,
                task == null ? null : task.acceptanceTestDir(), target.localRoot().orElse(null),
                reachability == null ? List.of() : reachability.compiledRoots(),
                baseline == null ? null : baseline.startCompile());
            appendLog(fullLog, "[compile] " + compileFailure.describe());
        }

        TestResults acceptance = SKIPPED;
        TestResults existing = SKIPPED;
        LintResults lint = null;
        BrowserCheckResults browser = null;
        List<GuidelineCheckResult> guidelines = List.of();

        if (compiles && reachable) {
            guidelines = GuidelineCheckRunner.run(target, guidelineChecks, fullLog);
        }
        if (compiles && reachable && !GuidelineCheckRunner.anyBroken(guidelines)) {
            acceptance = runTestStage(target, "acceptance", spec.acceptance(),
                spec.acceptanceReportDirs(), spec.effectiveTimeoutSeconds(), fullLog);
            if (clean(acceptance)) {
                existing = runTestStage(target, "existing", spec.existing(),
                    spec.existingReportDirs(), spec.effectiveTimeoutSeconds(), fullLog);
                // A test that already failed on the tree the run started from is not this
                // candidate's regression (see VerificationBaseline).
                if (baseline != null) {
                    existing = baseline.discount(existing, fullLog);
                }
                if (clean(existing)) {
                    lint = runLintStage(target, spec, fullLog);
                    browser = runBrowserStage(target, spec, fullLog);
                }
            }
        }

        String logText = fullLog.toString();
        VerificationReport report = new VerificationReport(
            UUID.randomUUID(),
            true, // parse gating happens at write time, before verification (spec §10.2)
            compiles,
            acceptance,
            existing,
            lint,
            browser,
            Duration.between(start, Instant.now()),
            tail(logText),
            blobSink.put(logText.getBytes(StandardCharsets.UTF_8))
        );
        report.setGuidelineChecks(guidelines);
        report.setBuildReachability(reachability);
        report.setCompileFailure(compileFailure);
        return report;
    }

    /**
     * The browser stage: start the candidate's application for real and look at it with a browser.
     *
     * <p>It is last because it is the most expensive thing in the pipeline and the cheapest-first
     * ordering says so, not because it is optional. When the contract declares a browser block, this
     * stage decides survival: an application that will not start has delivered nothing, and until
     * this ran nothing in verification had ever started one. {@link Verdicts} holds that rule.
     *
     * <p>The one thing it must never do is fail a candidate because the harness could not be
     * pointed at it — no browser installed, a target with no background exec, a port in use. Those
     * come back tagged {@code COULD_NOT_TRY} and are recorded loudly here, so a run whose browser
     * checks are silently not happening reads as exactly that rather than as a green stage.
     */
    private BrowserCheckResults runBrowserStage(ExecTarget target, VerifySpec spec, StringBuilder fullLog) {
        if (spec.browser() == null) {
            appendLog(fullLog, "[browser] no browser block configured — the candidate's application "
                + "is never started, so nothing here proves it runs");
            return null;
        }
        BrowserCheckResults results = new BrowserVerifier(blobSink).run(target, spec.browser(), fullLog);
        if (results.couldNotTry()) {
            log.warn("Browser stage not attempted: {}", results.couldNotTryReason());
        } else {
            long loaded = results.checks() == null ? 0
                : results.checks().stream().filter(PageCheck::loaded).count();
            int total = results.checks() == null ? 0 : results.checks().size();
            appendLog(fullLog, "[browser] the application was started and " + loaded + "/" + total
                + " page(s) loaded");
        }
        return results;
    }

    /**
     * The build-reachability stage: for every file the candidate added or changed, is it inside a
     * source root this repository's build actually compiles or packages?
     *
     * <p>It is a stage rather than a check bolted onto the caller because {@link Verdicts} is the
     * single definition of survival, and every consumer of verification — the swarm engine, the
     * final integrator, story delivery — has to be gated by the same rule. Its whole decision is in
     * {@link BuildReachabilityCheck}; this method only runs it, logs it, and never throws: a check
     * that cannot read the layout must degrade to "nothing established", not to a failed pipeline.
     */
    private static BuildReachability runReachabilityStage(ExecTarget target, VerifySpec spec,
                                                          Set<String> changedFiles, StringBuilder fullLog) {
        BuildReachability result;
        try {
            result = BuildReachabilityCheck.check(target, spec.toolchain(), changedFiles);
        } catch (RuntimeException e) {
            log.warn("Build-reachability check failed to run: {}", e.getMessage());
            appendLog(fullLog, "[reachability] could not be established: " + e.getMessage());
            return null;
        }
        if (result.status() == BuildReachabilityStatus.ORPHANED) {
            appendLog(fullLog, "[reachability] FAILED — " + result.describe());
        } else if (result.status() == BuildReachabilityStatus.UNDETERMINED) {
            appendLog(fullLog, "[reachability] undetermined — " + result.explanation());
        } else {
            appendLog(fullLog, "[reachability] every changed file is inside a source root the build "
                + "compiles or packages");
        }
        return result;
    }

    /**
     * Advisory LSP pre-compile pass. No-op unless a server is available AND the target exposes a
     * local root (the integration worktree) AND the task declares a write set — the files to check
     * are the task's own {@code .java} write set, capped. Appends a rendered block to the log; it
     * never touches the report or survival.
     */
    private void runLspPrecheck(ExecTarget target, Task task, StringBuilder fullLog) {
        if (!lsp.isAvailable() || task == null || task.writeSet() == null || task.writeSet().isEmpty()) {
            return;
        }
        Optional<Path> root = target.localRoot();
        if (root.isEmpty()) {
            return;
        }
        List<Path> files = writeSetJavaFiles(root.get(), task);
        if (files.isEmpty()) {
            return;
        }
        LspPrecheck.Result result = new LspPrecheck(lsp).check(files);
        String block = result.renderForLog();
        if (!block.isEmpty()) {
            fullLog.append(block);
        }
    }

    /** Resolves the task's write set to existing {@code .java} files under the workspace, capped. */
    private static List<Path> writeSetJavaFiles(Path root, Task task) {
        List<Path> files = new ArrayList<>();
        for (String entry : task.writeSet()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path path = root.resolve(entry.replace('\\', '/')).normalize();
            if (!path.startsWith(root)) {
                continue; // never escape the workspace
            }
            if (Files.isRegularFile(path) && path.toString().endsWith(".java")) {
                files.add(path);
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java"))
                        .sorted()
                        .limit(Math.max(0, MAX_LSP_FILES - files.size()))
                        .forEach(files::add);
                } catch (IOException e) {
                    log.debug("LSP precheck: write-set walk failed for {}: {}", path, e.getMessage());
                }
            }
            if (files.size() >= MAX_LSP_FILES) {
                break;
            }
        }
        return files;
    }

    /**
     * Runs the compile commands; the stage passes only when every command exits 0.
     *
     * @return null when the stage passed; otherwise what the failing command printed, which is
     *         what {@link CompileFailureAttribution} reads to say whose fault the failure is. A
     *         target that could not be reached yields an empty string: a failure with no output.
     */
    private String runCompileStage(ExecTarget target, List<String> commands,
                                   int timeoutSeconds, StringBuilder fullLog) {
        if (commands == null || commands.isEmpty()) {
            appendLog(fullLog, "[compile] no commands configured — skipped");
            return null;
        }
        for (String command : commands) {
            ExecResult result = execLogged(target, "compile", command, timeoutSeconds, fullLog);
            if (result == null) {
                return "";
            }
            if (!result.succeeded()) {
                return result.output() == null ? "" : result.output();
            }
        }
        return null;
    }

    private TestResults runTestStage(ExecTarget target, String stage, List<String> commands,
                                     List<String> reportDirs, int timeoutSeconds, StringBuilder fullLog) {
        if (commands == null || commands.isEmpty()) {
            appendLog(fullLog, "[" + stage + "] no commands configured — skipped");
            return NOT_CONFIGURED;
        }
        return TestStageRunner.run(target, stage, commands, reportDirs, timeoutSeconds, fullLog).results();
    }

    /**
     * Lint is recorded but advisory. With {@code lintReports} configured, results come from
     * checkstyle-format XML; otherwise errors = count of failing lint commands.
     */
    private LintResults runLintStage(ExecTarget target, VerifySpec spec, StringBuilder fullLog) {
        List<String> commands = spec.lint();
        if (commands == null || commands.isEmpty()) {
            return new LintResults(0, 0, List.of());
        }
        boolean structured = spec.lintReports() != null && !spec.lintReports().isEmpty();
        if (structured) {
            try {
                for (String dir : spec.lintReports()) {
                    target.deleteDir(dir);
                }
            } catch (IOException e) {
                appendLog(fullLog, "[lint] failed to clear report dirs: " + e.getMessage());
            }
        }

        int failedCommands = 0;
        List<String> commandMessages = new ArrayList<>();
        for (String command : commands) {
            ExecResult result = execLogged(target, "lint", command, spec.effectiveTimeoutSeconds(), fullLog);
            if (result == null || !result.succeeded()) {
                failedCommands++;
                commandMessages.add(command + " -> "
                    + (result == null ? "exec target unreachable" : tail(result.output())));
            }
        }

        if (structured) {
            List<String> xml = new ArrayList<>();
            for (String dir : spec.lintReports()) {
                try {
                    for (String file : target.listFiles(dir, ".xml")) {
                        String content = target.readFile(file, TestStageRunner.MAX_REPORT_FILE_BYTES);
                        if (content != null) {
                            xml.add(content);
                        }
                    }
                } catch (IOException e) {
                    appendLog(fullLog, "[lint] failed reading " + dir + ": " + e.getMessage());
                }
            }
            LintResults parsed = CheckstyleXmlParser.parse(xml);
            appendLog(fullLog, "[lint] " + parsed.errors() + " errors, " + parsed.warnings() + " warnings (structured)");
            return parsed;
        }
        return new LintResults(failedCommands, 0, commandMessages);
    }

    private ExecResult execLogged(ExecTarget target, String stage, String command,
                                  int timeoutSeconds, StringBuilder fullLog) {
        appendLog(fullLog, "[" + stage + "] $ " + command);
        try {
            ExecResult result = target.exec(command, timeoutSeconds);
            if (!result.output().isBlank()) {
                fullLog.append(result.output());
                if (!result.output().endsWith("\n")) {
                    fullLog.append('\n');
                }
            }
            appendLog(fullLog, "[" + stage + "] exit=" + result.exitCode()
                + (result.timedOut() ? " (TIMED OUT)" : "") + " in " + result.duration().toSeconds() + "s");
            return result;
        } catch (IOException e) {
            log.error("Exec target unreachable running '{}': {}", command, e.getMessage());
            appendLog(fullLog, "[" + stage + "] EXEC TARGET ERROR: " + e.getMessage());
            return null;
        }
    }

    private static boolean clean(TestResults results) {
        return results.failed() == 0 && results.errored() == 0;
    }

    private static void appendLog(StringBuilder fullLog, String line) {
        fullLog.append(line).append('\n');
    }

    private static String tail(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= LOG_TAIL_LINES) {
            return text;
        }
        StringBuilder sb = new StringBuilder("[...tail of " + lines.length + " lines]\n");
        for (int i = lines.length - LOG_TAIL_LINES; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
