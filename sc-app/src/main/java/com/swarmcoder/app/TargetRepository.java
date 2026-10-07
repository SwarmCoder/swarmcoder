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

import com.swarmcoder.domain.TestResults;
import com.swarmcoder.git.GitService;
import com.swarmcoder.verify.ContractProbe;
import com.swarmcoder.verify.ExecResult;
import com.swarmcoder.verify.JUnitXmlParser;
import com.swarmcoder.verify.ToolchainDetector;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Registering a repository nobody wrote for SwarmCoder: work out how it is built, prove it, and
 * write that down where the orchestrator will read it.</b>
 *
 * <p>Four steps, in one call: {@link ToolchainDetector#detect} proposes a contract from the build
 * files; {@link ContractProbe#probeCompile} runs the proposed compile command once, for real;
 * {@link ToolchainDetector#render} turns the proposal into the commented YAML a person reads; and
 * the file is written into the repository's own checkout and committed.
 *
 * <h2>Why the commit is part of the job and not an afterthought</h2>
 *
 * <p>Every worker gets a git worktree cut from some commit of this repository, and an uncommitted
 * file is not in one. A contract written and left in the working tree is therefore a contract no
 * worker's verification can ever see: the run loads nothing, every candidate comes back marked
 * unverified, and the winner is picked on a reading of the code alone. That failure is silent, so
 * writing and committing are one step here rather than two an operator can half-do.
 *
 * <p>The file lands in the OPERATOR'S tree and is read back from it
 * ({@link VerifySpecLoader#loadTrusted}), never from a worker's worktree — a worker that could edit
 * the commands that judge it could certify itself green.
 *
 * <h2>What this class does not do</h2>
 *
 * <p><b>It adds no logic of its own and it never decides.</b> {@link ToolchainDetector} proposes and
 * says so in its own class doc; this only sequences the proposal, the proof and the write. When the
 * probe comes back red nothing is written, for {@link OnrampCli}'s reason: a command that does not
 * build the project as it stands would make every candidate fail identically for a reason that has
 * nothing to do with the candidate. A red probe is not a verdict on the detection — a missing
 * dependency, an unset environment variable, a generator that has not been run all look identical
 * from here — so the result carries the log tail and leaves the judgement to a person.
 *
 * <p>It also <b>does not run the project's own test suite as part of registering it</b>, for
 * {@link ContractProbe}'s reason: on a large repository that is half an hour, and whether the suite
 * is green today is the operator's business, not the detector's. {@link #runSuite} exists as a
 * separate call for the caller who wants that measurement — the brownfield harness asks for it once
 * per case, before a run starts, so that a suite which was already red is never mistaken for
 * something a candidate broke.
 *
 * <h2>Where it lives</h2>
 *
 * <p>In {@code sc-app} rather than {@code sc-console}, for the seam {@link BuildContractBridge}
 * already documents: detection is in {@code sc-verify}, git is in {@code sc-git}, and
 * {@code sc-console} depends on neither. {@code sc-app} sees all three. Adding those two
 * dependencies to the console to move one class would invert a module boundary that is deliberate.
 */
public final class TargetRepository {

    private static final Logger log = LoggerFactory.getLogger(TargetRepository.class);

    /** A probe that has run this long is not going to tell the operator anything new. */
    public static final int DEFAULT_PROBE_TIMEOUT_SECONDS = 900;

    /** How much of a suite run's output to keep — enough to name the first real failure. */
    private static final int LOG_TAIL_LINES = 60;

    private TargetRepository() {
    }

    /**
     * What registering this repository found, proved and wrote.
     *
     * @param root            the repository that was registered
     * @param detection       what the build files said, with its evidence and its open questions
     * @param probe           what happened when the proposed compile command was actually run
     * @param yaml            the rendered contract — written when {@link #contractWritten} is true,
     *                        and offered for review when it is not
     * @param contractWritten true when the file was written into {@code root} and committed
     * @param contractCommit  the commit the contract landed in, or null when nothing was written
     * @param verdict         plain English: what happened and, when nothing was written, why not
     */
    public record Registration(
        Path root,
        ToolchainDetector.Detection detection,
        ContractProbe.Result probe,
        String yaml,
        boolean contractWritten,
        String contractCommit,
        String verdict
    ) {
        /** True when a contract was proposed, proved to build the project, and committed. */
        public boolean ready() {
            return contractWritten && probe != null && probe.compiles();
        }

        /** The contract itself, or null when nothing was recognised. */
        public VerifySpec spec() {
            return detection == null ? null : detection.proposed();
        }

        /** One line for a log or a chain-ledger observation. */
        public String describe() {
            return verdict;
        }
    }

    /**
     * A line of the proposed contract that a person has replaced, and why.
     *
     * <p>{@link ToolchainDetector} proposes; it never decides, and its own class doc says so. The
     * console screen exists to let an operator correct a proposed command before anything depends
     * on it ("If either line is wrong, fix it here"). This record is that correction, stated as
     * data so a caller with no screen — a harness — can make the same edit a person would, and so
     * the <b>reason</b> travels with it into the committed file rather than living in somebody's
     * head.
     *
     * <p>The reason is rendered into the contract's own comments, under the heading asking the
     * reader to decide it. A narrowed regression gate that nobody can see the reason for is how a
     * suite quietly stops covering the thing it was there to cover.
     *
     * @param existing the replacement {@code existing}-stage commands, or null to leave them alone
     * @param reason   why, in a sentence a person reading the contract will understand
     */
    public record Correction(List<String> existing, String reason) {

        /** True when there is actually something to change. */
        public boolean changesAnything() {
            return existing != null && !existing.isEmpty();
        }
    }

    /**
     * Detect, probe, render, write and commit — the whole registration, once, with nothing
     * corrected.
     *
     * @param root                  the repository checkout to register; the operator's own tree,
     *                              never a worker's worktree
     * @param probeTimeoutSeconds   cap for the compile command; 0 or less uses the spec's own
     * @param overwriteExisting     when false, a repository that already has a contract keeps it —
     *                              the on-ramp's rule, because a hand-corrected contract is the one
     *                              that has been thought about and a fresh proposal is not
     */
    public static Registration register(Path root, int probeTimeoutSeconds,
                                        boolean overwriteExisting) {
        return register(root, probeTimeoutSeconds, overwriteExisting, null);
    }

    /**
     * Detect, probe, apply the caller's correction, render, write and commit.
     *
     * @param correction what a person would have fixed on the screen before saving, or null
     */
    public static Registration register(Path root, int probeTimeoutSeconds,
                                        boolean overwriteExisting, Correction correction) {
        return register(root, probeTimeoutSeconds, overwriteExisting, correction, null);
    }

    /**
     * @param boxes where the compile probe runs: a container that sees {@code root} only. Null
     *              runs it on this PC - the operator's own project at the moment it is registered
     */
    public static Registration register(Path root, int probeTimeoutSeconds,
                                        boolean overwriteExisting, Correction correction,
                                        com.swarmcoder.verify.BuildBoxes boxes) {
        Path contract = root.resolve(VerifySpecLoader.SPEC_PATH);
        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);
        if (!detection.recognised()) {
            return new Registration(root, detection, null, null, false, null,
                "Nothing here says how this project is built, so nothing was proposed rather than "
                    + "guessed: " + detection.summary());
        }

        if (Files.isRegularFile(contract) && !overwriteExisting) {
            return new Registration(root, detection, null, null, false, null,
                "This repository already has a verification contract at "
                    + VerifySpecLoader.SPEC_PATH + " and it was left alone. A contract that has "
                    + "been corrected by hand is the one that has been thought about; a fresh "
                    + "proposal is not.");
        }

        // Probed BEFORE the correction is applied: the correction here only ever replaces the
        // existing-test stage, and what the probe proves is the compile stage. Probing the
        // detector's own proposal keeps that proof about what the detector said.
        // With boxes the build runs in a container on a throwaway copy; without them (a scripted
        // test's own fixture) it runs in the folder given.
        ContractProbe.Result probe = boxes == null
            ? ContractProbe.probeCompile(root, detection.proposed(), probeTimeoutSeconds)
            : ProbeOnACopy.probe(root, detection.proposed(), probeTimeoutSeconds, boxes);
        ToolchainDetector.Detection corrected = applyCorrection(detection, correction);
        String yaml = ToolchainDetector.render(corrected);
        if (!probe.compiles()) {
            return new Registration(root, corrected, probe, yaml, false, null,
                "The proposed compile command does not build this project as it stands, so no "
                    + "contract was written. Writing it anyway would make every candidate fail "
                    + "identically for a reason that has nothing to do with the candidate. "
                    + probe.verdict());
        }

        String commit;
        try {
            Files.createDirectories(contract.getParent());
            Files.writeString(contract, yaml);
            GitService git = new GitService(root);
            // Committed, not merely written: every worker gets a worktree cut from a commit, and an
            // uncommitted file is not in one.
            git.commitAll(root, "Verification contract detected for this repository");
            commit = headOf(git, root);
        } catch (Exception e) {
            return new Registration(root, corrected, probe, yaml, false, null,
                "The build was proved but the contract could not be saved to " + contract + ": "
                    + e.getMessage());
        }

        log.info("Registered {} as a {} project; contract committed at {}",
            root, corrected.toolchain(), commit);
        return new Registration(root, corrected, probe, yaml, true, commit,
            "This is a " + corrected.toolchain() + " project. Its compile command was run once and "
                + "it worked, taking " + probe.duration().toSeconds() + " seconds; the contract is "
                + "committed at " + shortSha(commit) + "."
                + (correction != null && correction.changesAnything()
                    ? " Its existing-test command was corrected before saving: "
                        + correction.reason()
                    : ""));
    }

    /**
     * The proposal with the caller's correction folded in, and the reason folded into the warnings
     * so it is rendered into the file's own comments.
     *
     * <p>Returns the detection unchanged when there is nothing to correct, so the common path is
     * exactly what the detector proposed.
     */
    private static ToolchainDetector.Detection applyCorrection(
            ToolchainDetector.Detection detection, Correction correction) {
        if (correction == null || !correction.changesAnything()) {
            return detection;
        }
        VerifySpec proposed = detection.proposed();
        VerifySpec fixed = new VerifySpec(proposed.toolchain(), proposed.compile(),
            proposed.acceptance(), List.copyOf(correction.existing()), proposed.lint(),
            proposed.lintReports(), proposed.testReports(), proposed.timeoutSeconds(),
            proposed.browser());
        List<String> warnings = new ArrayList<>(detection.warnings());
        warnings.add("The existing-test command below is NOT the one that was detected. It was "
            + "replaced before this file was saved, and this is the reason: "
            + (correction.reason() == null || correction.reason().isBlank()
                ? "no reason was given, which is itself worth questioning."
                : correction.reason())
            + " A narrowed regression gate that nobody can see the reason for is how a suite "
            + "quietly stops covering what it was there to cover.");
        return new ToolchainDetector.Detection(detection.toolchain(), detection.evidence(),
            warnings, fixed, detection.acceptanceTestDir(), detection.subprojects());
    }

    /**
     * What HEAD points at after the commit.
     *
     * <p>{@link GitService#resolveCommit} goes through JGit, which reads HEAD as null in a linked
     * worktree — the {@code .git} entry there is a file naming the real git directory, and that is
     * exactly the shape every case tree in the brownfield harness has. The git CLI answers
     * correctly in both shapes, so it is the fallback, and the sha is only ever used for the record
     * rather than for a decision.
     */
    private static String headOf(GitService git, Path root) {
        String resolved = git.resolveCommit("HEAD");
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(root.toFile()).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).strip();
            return process.waitFor() == 0 && !out.isEmpty() ? out : null;
        } catch (Exception e) {
            log.warn("Could not read HEAD in {}: {}", root, e.getMessage());
            return null;
        }
    }

    /**
     * What ran this build, as the tools themselves report it.
     *
     * <p>Asked before a run rather than discovered inside a candidate's build. A target whose
     * baseline is Java 8 but whose shipped jar needs a newer compiler (jsoup is exactly that: a
     * {@code multi-release} profile compiles a second source root at release 11) fails in a way
     * that reads like a candidate fault when the JDK on the path is too old, and the fix is a
     * sentence about the machine rather than anything about the product.
     *
     * @param javaVersion   what {@code java.version} reports for the JVM this runs in
     * @param javaVendor    who built it
     * @param buildTool     the first line {@code mvn -v} (or the toolchain's equivalent) printed,
     *                      or a sentence saying it could not be run
     * @param buildToolRan  true when the version command exited 0
     */
    public record Toolchain(String javaVersion, String javaVendor, String buildTool,
                            boolean buildToolRan) {

        /** Whether the JVM running this is at least the given feature release. */
        public boolean javaAtLeast(int feature) {
            return Runtime.version().feature() >= feature;
        }

        public String describe() {
            return "JDK " + javaVersion + " (" + javaVendor + "), " + buildTool;
        }
    }

    /**
     * Reads the JDK from this process and the build tool from the repository, by running it.
     *
     * @param root      the repository — the version command runs there, so a wrapper in the tree is
     *                  the one that answers
     * @param toolchain what {@link ToolchainDetector} called this project; selects the version
     *                  command
     */
    public static Toolchain toolchain(Path root, String toolchain) {
        return toolchain(root, toolchain, com.swarmcoder.verify.BuildBoxes.none());
    }

    /**
     * @param boxes where the version command runs. It is the tree's own wrapper that answers, and
     *              a wrapper is a script in a tree a model may have written to, so it runs in a
     *              container. {@code BuildBoxes.none()} refuses unless this PC was allowed by name
     */
    public static Toolchain toolchain(Path root, String toolchain,
                                      com.swarmcoder.verify.BuildBoxes boxes) {
        String versionCommand = versionCommandFor(toolchain);
        String reported;
        boolean ran = false;
        if (versionCommand == null) {
            reported = "no version command is known for a " + toolchain + " project";
        } else {
            try (com.swarmcoder.verify.BuildBoxes.Box box =
                     boxes.open(root, "The toolchain check")) {
                ExecResult result = box.target().exec(versionCommand, 120);
                ran = result.succeeded();
                reported = ran ? firstMeaningfulLine(result.output())
                    : versionCommand + " exited " + result.exitCode();
            } catch (Exception e) {
                reported = versionCommand + " could not be run: " + e.getMessage();
            }
        }
        return new Toolchain(System.getProperty("java.version", "unknown"),
            System.getProperty("java.vendor", "unknown"), reported, ran);
    }

    /**
     * What running the project's own suite cost and whether it was green.
     *
     * @param ran        true when the contract named an existing-test command at all
     * @param green      true when every command exited 0
     * @param commands   what was run, in order
     * @param exitCode   the exit code of the first command that failed, or 0
     * @param timedOut   true when a command was killed for exceeding its timeout
     * @param duration   wall time of the whole suite — the number the harness reports per case
     * @param results    what the JUnit reports say was executed, or null when none were found
     * @param logTail    the last lines of output, for naming what failed
     */
    public record SuiteRun(boolean ran, boolean green, List<String> commands, int exitCode,
                           boolean timedOut, Duration duration, TestResults results,
                           String logTail) {

        /** How many tests the reports say ran — passed, failed and errored, excluding skipped. */
        public int executed() {
            return results == null ? 0 : results.passed() + results.failed() + results.errored();
        }

        /**
         * Green AND demonstrably not empty — the only shape a caller may build on.
         *
         * <p>A build tool that selected nothing at all exits 0 and reads exactly like a suite that
         * ran and was green. That is the oldest bug in this codebase's verification, in
         * {@code Verdicts}' own words: "an acceptance stage that selected nothing at all read
         * exactly like one that ran and was green". So the reports are counted rather than the exit
         * code trusted.
         */
        public boolean greenAndNotEmpty() {
            return green && executed() > 0;
        }

        public String describe() {
            if (!ran) {
                return "the contract names no existing-test command, so nothing was run";
            }
            String outcome = green ? "green" : timedOut ? "timed out" : "exit " + exitCode;
            String counted = results == null
                ? "no JUnit reports were found, so nothing can say how many tests ran"
                : executed() + " tests executed (" + results.passed() + " passed, "
                    + results.failed() + " failed, " + results.errored() + " errored, "
                    + results.skipped() + " skipped)";
            return outcome + " in " + duration.toSeconds() + "s, " + counted;
        }
    }

    /**
     * Runs the contract's {@code existing} stage — the project's own suite — once, and times it.
     *
     * <p>Separate from {@link #register} on purpose (see the class doc). The brownfield harness
     * calls it before a run starts, so that a suite which was already red on the untouched tree is
     * never read as damage a candidate did.
     *
     * @param root           where to run; the case's own worktree
     * @param spec           the contract whose {@code existing} commands to run
     * @param timeoutSeconds cap per command; 0 or less uses the spec's own
     */
    public static SuiteRun runSuite(Path root, VerifySpec spec, int timeoutSeconds) {
        return runSuite(root, spec, timeoutSeconds, com.swarmcoder.verify.BuildBoxes.none());
    }

    /**
     * @param boxes where the suite runs: a container that sees {@code root} and nothing else of
     *              this PC. The suite executes whatever the tree holds, and after a run that is
     *              code a model wrote. {@code BuildBoxes.none()} refuses unless this PC was
     *              allowed by name. The container is removed before this returns
     */
    public static SuiteRun runSuite(Path root, VerifySpec spec, int timeoutSeconds,
                                    com.swarmcoder.verify.BuildBoxes boxes) {
        Instant start = Instant.now();
        List<String> commands = spec == null || spec.existing() == null
            ? List.of() : List.copyOf(spec.existing());
        if (commands.isEmpty()) {
            return new SuiteRun(false, false, List.of(), 0, false, Duration.ZERO, null, "");
        }
        int timeout = timeoutSeconds > 0 ? timeoutSeconds : spec.effectiveTimeoutSeconds();
        com.swarmcoder.verify.BuildBoxes.Box box;
        try {
            box = boxes.open(root, "The project's own suite");
        } catch (RuntimeException e) {
            return new SuiteRun(true, false, commands, -1, false,
                Duration.between(start, Instant.now()), null, "could not run anything in " + root
                    + ": " + e.getMessage());
        }
        try (box) {
            return runSuiteOn(box.target(), root, spec, commands, timeout, start);
        }
    }

    private static SuiteRun runSuiteOn(com.swarmcoder.verify.ExecTarget target, Path root,
                                       VerifySpec spec, List<String> commands, int timeout,
                                       Instant start) {
        // Cleared first: reports left by an earlier run would be counted as this one's, and a
        // suite that selected nothing would then look like one that ran everything.
        List<String> reportDirs = spec.existingReportDirs();
        for (String dir : reportDirs) {
            try {
                target.deleteDir(dir);
            } catch (Exception e) {
                log.warn("Could not clear stale reports in {}/{}: {}", root, dir, e.getMessage());
            }
        }
        for (String command : commands) {
            ExecResult result;
            try {
                log.info("Running the project's own suite in {}: {}", root, command);
                result = target.exec(command, timeout);
            } catch (Exception e) {
                return new SuiteRun(true, false, commands, -1, false,
                    Duration.between(start, Instant.now()), null,
                    "the command could not be started at all: " + e.getMessage());
            }
            if (!result.succeeded()) {
                return new SuiteRun(true, false, commands, result.exitCode(), result.timedOut(),
                    Duration.between(start, Instant.now()), readReports(target, reportDirs),
                    tail(result.output()));
            }
        }
        return new SuiteRun(true, true, commands, 0, false,
            Duration.between(start, Instant.now()), readReports(target, reportDirs), "");
    }

    /**
     * What the JUnit XML says ran, or null when there is none to read.
     *
     * <p>Null and "zero tests" are different findings and must not be collapsed: null means the
     * command emitted no reports at all — a wrong report directory, a runner that needs a flag to
     * write XML — while zero means reports exist and are empty. A caller can act on the first by
     * fixing the contract and on the second only by fixing the selection.
     */
    private static TestResults readReports(com.swarmcoder.verify.ExecTarget target, List<String> dirs) {
        List<String> xml = new ArrayList<>();
        for (String dir : dirs) {
            try {
                for (String file : target.listFiles(dir, ".xml")) {
                    String content = target.readFile(file, 4 * 1024 * 1024);
                    if (content != null && !content.isBlank()) {
                        xml.add(content);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read test reports in {}: {}", dir, e.getMessage());
            }
        }
        return xml.isEmpty() ? null : JUnitXmlParser.parse(xml);
    }

    private static String versionCommandFor(String toolchain) {
        if (toolchain == null) {
            return null;
        }
        return switch (toolchain) {
            case "maven" -> "mvn -v";
            case "gradle" -> "gradle -v";
            case "node" -> "npm -v";
            case "cargo" -> "cargo --version";
            case "python" -> "python --version";
            default -> null;
        };
    }

    /** The first line that carries something — build tools like to open with a blank or a warning. */
    private static String firstMeaningfulLine(String output) {
        if (output == null) {
            return "no output";
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("WARNING")) {
                return trimmed;
            }
        }
        return "no output";
    }

    private static String tail(String output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        String[] lines = output.split("\\R");
        int from = Math.max(0, lines.length - LOG_TAIL_LINES);
        return String.join("\n", List.of(lines).subList(from, lines.length));
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? String.valueOf(sha) : sha.substring(0, 8);
    }
}
