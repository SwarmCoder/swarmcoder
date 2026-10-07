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

import com.swarmcoder.verify.ContractProbe;
import com.swarmcoder.verify.ToolchainDetector;
import com.swarmcoder.verify.VerifySpecLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The on-ramp: point SwarmCoder at a repository it has never seen and get a verification contract
 * out of it, having actually run the build once.
 *
 * <p><b>Why a command and not only a screen.</b> This is the first thing anybody does with an
 * existing project, and it has to work before the console is running, before a project exists in
 * the store, and while the answer is still being argued about. It prints what it found, what it
 * would run, what it could not decide, and the result of really running the compile stage — then
 * writes nothing unless asked.
 *
 * <p><b>It writes into the project's own checkout, never a worktree</b> (§13.1). The contract names
 * commands the orchestrator runs on the host; a worker able to edit it could certify itself green.
 * {@code VerifySpecLoader.loadTrusted} reads it from the operator's tree, so that is where it goes.
 *
 * <p><b>It refuses to overwrite.</b> An existing contract is the operator's decision, possibly hand
 * corrected, and silently replacing it with a fresh guess is exactly the kind of quiet damage that
 * is only noticed a run later.
 *
 * <pre>
 *   swarmcoder onramp &lt;path-to-repo&gt;            detect, probe, print — writes nothing
 *   swarmcoder onramp &lt;path-to-repo&gt; --write    also write .swarmcoder/verify.yaml
 *   swarmcoder onramp &lt;path-to-repo&gt; --no-probe skip running the build (fast, less honest)
 * </pre>
 */
public final class OnrampCli {

    private OnrampCli() {}

    /** Runs the on-ramp; returns the process exit code. */
    public static int run(String[] args) {
        // A Windows console defaults to cp1252, and every em-dash in the explanations below then
        // arrives as a question mark. The whole value of this command is that a person reads it.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
            true, java.nio.charset.StandardCharsets.UTF_8));
        if (args.length < 2) {
            System.out.println("""
                usage: swarmcoder onramp <path-to-repo> [--write] [--no-probe]

                  Detects how to build and test a repository and proposes the verification
                  contract SwarmCoder needs before it can tell a good candidate from a bad one.

                  --write     write .swarmcoder/verify.yaml into that repository (refuses to
                              overwrite an existing one)
                  --no-probe  do not actually run the compile command first""");
            return 2;
        }
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        boolean write = has(args, "--write");
        boolean probe = !has(args, "--no-probe");

        if (!Files.isDirectory(root)) {
            System.out.println("No such directory: " + root);
            return 1;
        }

        System.out.println("Repository: " + root);
        long detectStart = System.nanoTime();
        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);
        long detectMs = (System.nanoTime() - detectStart) / 1_000_000;
        System.out.println("Detection took " + detectMs + " ms.");
        System.out.println();

        if (!detection.recognised()) {
            System.out.println("Nothing recognised: " + detection.summary());
            if (!detection.subprojects().isEmpty()) {
                System.out.println();
                System.out.println("""
                    SwarmCoder works on one repository root at a time, and this one has no build
                    file of its own. Point it at whichever directory below is the project you want
                    changed — or, if the root really is the project, write .swarmcoder/verify.yaml
                    by hand.""");
                for (String sub : detection.subprojects()) {
                    System.out.println("    " + sub);
                }
            }
            System.out.println();
            System.out.println("""
                Without a contract, verification is SKIPPED and every candidate comes back
                unverified. The swarm still runs, but nothing has tested the attempts it is
                choosing between, so the winner is picked on the judge's reading alone.""");
            return 1;
        }

        System.out.println("Toolchain: " + detection.toolchain());
        System.out.println("Detected from:");
        for (String line : detection.evidence()) {
            System.out.println("  - " + line);
        }
        System.out.println();
        System.out.println("Will run:");
        printStage("compile   ", detection.proposed().compile());
        printStage("acceptance", detection.proposed().acceptance());
        printStage("existing  ", detection.proposed().existing());
        printStage("lint      ", detection.proposed().lint());
        System.out.println();
        System.out.println("Acceptance tests will be written to: " + detection.acceptanceTestDir());
        // Summarised, never listed. A 58-module reactor needs 117 report directories, and printing
        // them buries the four commands that are the only thing worth reading here. The full list
        // belongs in the file, where it is configuration rather than prose.
        List<String> reportDirs = detection.proposed().existingReportDirs();
        System.out.println("Test reports read from " + reportDirs.size() + " director"
            + (reportDirs.size() == 1 ? "y" : "ies") + ", starting "
            + String.join(", ", reportDirs.subList(0, Math.min(3, reportDirs.size())))
            + (reportDirs.size() > 3 ? ", … (all of them are in the file below)" : ""));

        if (!detection.warnings().isEmpty()) {
            System.out.println();
            System.out.println("Decide these before trusting a run:");
            for (String warning : detection.warnings()) {
                System.out.println("  - " + warning);
            }
        }

        if (probe) {
            System.out.println();
            System.out.println("Running the compile command now, once, so this is not a guess…");
            System.out.println("(In a container, on a throwaway copy of the folder; your working "
                + "folder is not touched.)");
            ContractProbe.Result result =
                ProbeOnACopy.probe(root, detection.proposed(), 0, CliBoxes.fromSettings());
            System.out.println();
            System.out.println(result.compiles() ? "COMPILES" : "DOES NOT COMPILE");
            System.out.println(result.verdict());
            if (!result.compiles() && !result.logTail().isBlank()) {
                System.out.println();
                System.out.println("--- last output -------------------------------------------");
                System.out.println(result.logTail());
                System.out.println("-----------------------------------------------------------");
            }
            if (!result.compiles() && write) {
                System.out.println();
                System.out.println("""
                    Not writing the contract: the command it names does not build this project as
                    it stands. Writing it anyway would make every candidate fail identically for a
                    reason that has nothing to do with the candidate. Fix the command or the
                    project, then run this again.""");
                return 1;
            }
        }

        String yaml = ToolchainDetector.render(detection);
        Path target = root.resolve(VerifySpecLoader.SPEC_PATH);
        System.out.println();
        if (!write) {
            System.out.println("--- " + VerifySpecLoader.SPEC_PATH
                + " (proposed; nothing was written) ---");
            System.out.println(yaml);
            System.out.println("Re-run with --write to save it.");
            return 0;
        }
        if (Files.exists(target)) {
            System.out.println("Refusing to overwrite the contract already at " + target + ".");
            System.out.println("Delete it first if you really want a fresh proposal.");
            return 1;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, yaml);
        } catch (Exception e) {
            System.out.println("Could not write " + target + ": " + e.getMessage());
            return 1;
        }
        System.out.println("Wrote " + target);
        System.out.println("""

            Commit it. It lives in the project's own checkout on purpose: workers get worktrees,
            and a worker that could edit this file could rewrite the commands that judge it.""");
        return 0;
    }

    private static void printStage(String name, List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            System.out.println("  " + name + "  (nothing — this stage is skipped)");
            return;
        }
        for (String command : commands) {
            System.out.println("  " + name + "  " + command);
        }
    }

    private static boolean has(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
