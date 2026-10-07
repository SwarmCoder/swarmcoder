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

import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.console.api.BuildContractDto;
import com.swarmcoder.verify.BuildBoxes;
import com.swarmcoder.verify.ContractProbe;
import com.swarmcoder.verify.ToolchainDetector;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The console's on-ramp, wired to the real detector.
 *
 * <p>It lives here rather than in {@code sc-console} for the ordinary reason: detection is in
 * {@code sc-verify} and the console does not depend on it. {@code sc-app} sees both, so this is the
 * seam, exactly like {@link ConfigFormsBridge}.
 */
final class BuildContractBridge implements ConsoleContext.BuildContracts {

    private static final Logger log = LoggerFactory.getLogger(BuildContractBridge.class);

    /** A probe that has run for this long is not going to tell the operator anything new. */
    private static final int PROBE_TIMEOUT_SECONDS = 900;

    private final BuildBoxes boxes;

    /**
     * @param boxes where the compile probe runs: a container, on a throwaway copy of the folder.
     *              Never the operator's working folder.
     */
    BuildContractBridge(BuildBoxes boxes) {
        this.boxes = boxes == null ? BuildBoxes.none() : boxes;
    }

    @Override
    public BuildContractDto detect(String path, boolean probe) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path root = Path.of(path.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        ToolchainDetector.Detection detection = ToolchainDetector.detect(root);
        BuildContractDto dto = new BuildContractDto();
        dto.setAlreadyHasContract(Files.isRegularFile(root.resolve(VerifySpecLoader.SPEC_PATH)));
        dto.setEvidence(String.join("\n", detection.evidence()));
        dto.setWarnings(String.join("\n", detection.warnings()));

        if (!detection.recognised()) {
            // Nothing is proposed, and the screen must say why rather than offering a guess. A
            // guessed command that exits 0 for the wrong reason certifies a candidate nobody
            // checked, which is strictly worse than having no contract at all.
            dto.setToolchain("");
            dto.setCommands("");
            dto.setYaml("");
            dto.setProbeVerdict(unrecognised(detection));
            return dto;
        }

        VerifySpec spec = detection.proposed();
        dto.setToolchain(detection.toolchain());
        dto.setAcceptanceTestDir(detection.acceptanceTestDir());
        dto.setCommands(commands(spec));
        dto.setYaml(ToolchainDetector.render(detection));

        if (!probe) {
            dto.setProbed(false);
            dto.setProbeVerdict("The build has not been run yet. Until it has, these commands are "
                + "a proposal — check them before a run depends on them.");
            return dto;
        }
        ContractProbe.Result result = ProbeOnACopy.probe(root, spec, PROBE_TIMEOUT_SECONDS, boxes);
        dto.setProbed(result.ran());
        dto.setCompiles(result.compiles());
        dto.setProbeVerdict(result.verdict());
        dto.setProbeLog(result.logTail());
        dto.setProbeSeconds((int) result.duration().toSeconds());
        return dto;
    }

    @Override
    public String save(String path, String yaml, boolean overwrite) {
        if (path == null || path.isBlank()) {
            return "error: no project folder was given.";
        }
        if (yaml == null || yaml.isBlank()) {
            return "error: there is nothing to save.";
        }
        Path root = Path.of(path.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return "error: " + root + " is not a folder.";
        }
        // Read it back the way the orchestrator will, before it is written. A file that does not
        // parse means verification is skipped and every candidate comes back unverified — and
        // nothing would say so until a run had already been spent.
        try {
            VerifySpec parsed = VerifySpecLoader.parse(yaml);
            if (parsed.compile() == null || parsed.compile().isEmpty()) {
                return "error: this contract has no compile command, so nothing would tell a "
                    + "candidate that does not build from one that does. Add one before saving.";
            }
        } catch (Exception e) {
            return "error: this is not valid YAML for a verification contract — "
                + e.getMessage().split("\n")[0];
        }
        Path target = root.resolve(VerifySpecLoader.SPEC_PATH);
        if (Files.exists(target) && !overwrite) {
            return "error: " + root + " already has a verification contract. Tick 'replace what is "
                + "there' if you really mean to discard it.";
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, yaml);
        } catch (Exception e) {
            return "error: could not write " + target + " — " + e.getMessage();
        }
        log.info("Wrote a verification contract to {}", target);
        return "";
    }

    private static String unrecognised(ToolchainDetector.Detection detection) {
        StringBuilder out = new StringBuilder("SwarmCoder does not recognise how to build this "
            + "folder, so it is proposing nothing rather than guessing.");
        if (!detection.subprojects().isEmpty()) {
            out.append(" There is no build file at the top of it, but these folders inside it do "
                + "have one — point the project at whichever one holds the code you want changed: ")
               .append(String.join(", ", detection.subprojects())).append('.');
        }
        out.append("\n\nYou can still start runs. What you lose is the check: with no contract, "
            + "nothing builds or tests the attempts the swarm is choosing between, so every one "
            + "comes back marked unverified and the winner is picked on a reading of the code "
            + "alone. Writing .swarmcoder/verify.yaml by hand fixes that.");
        return out.toString();
    }

    private static String commands(VerifySpec spec) {
        StringBuilder out = new StringBuilder();
        stage(out, "compile", spec.compile(), "nothing — a broken build would not be caught here");
        stage(out, "acceptance", spec.acceptance(), "nothing — no test can gate this work");
        stage(out, "existing", spec.existing(),
            "nothing — nothing stops a candidate breaking what already worked");
        stage(out, "lint", spec.lint(), "nothing");
        return out.toString();
    }

    private static void stage(StringBuilder out, String name, List<String> commands,
                              String whenEmpty) {
        if (commands == null || commands.isEmpty()) {
            out.append(name).append(": ").append(whenEmpty).append('\n');
            return;
        }
        for (String command : commands) {
            out.append(name).append(": ").append(command).append('\n');
        }
    }
}
