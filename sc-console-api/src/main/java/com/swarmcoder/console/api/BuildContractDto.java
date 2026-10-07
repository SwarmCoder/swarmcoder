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
package com.swarmcoder.console.api;

import com.zeroz4j.api.BinaryPackable;
import com.zeroz4j.api.BinarySerializer;
import com.zeroz4j.api.DataModel;

import java.nio.ByteBuffer;

/**
 * What SwarmCoder worked out about how to build a folder of code, for the operator to correct
 * before anything runs against it.
 *
 * <p>Everything here is a PROPOSAL. It names the commands the orchestrator will run to decide
 * whether a candidate passed, so a wrong one costs a whole run — which is why the screen shows the
 * evidence, the open questions and the result of really running the build, and why the YAML is
 * editable rather than merely displayed.
 */
@DataModel
public class BuildContractDto implements BinaryPackable {

    /** maven | gradle | node | cargo | python, or blank when nothing was recognised. */
    private String toolchain;
    /** Why it concluded that, one plain-English line per file it found, newline separated. */
    private String evidence;
    /** What the operator has to rule on, one per line. Never empty for a good reason. */
    private String warnings;
    /** The four stages as they will be run, ready to read: "compile: mvn …" one per line. */
    private String commands;
    /** The proposed .swarmcoder/verify.yaml, editable, exactly as it would be saved. */
    private String yaml;
    /** Where TEST_AUTHORING would write acceptance tests. */
    private String acceptanceTestDir;
    /** True when the compile command was executed and exited 0. */
    private boolean compiles;
    /** True when the compile command was executed at all. */
    private boolean probed;
    /** Plain English: what the probe result means and what to do about it. */
    private String probeVerdict;
    /** The tail of a failing command's output, so a red probe can be acted on, not just seen. */
    private String probeLog;
    /** Seconds the probe took — the floor on how long every candidate's verification costs. */
    private int probeSeconds;
    /** True when a contract already exists there; saving would overwrite the operator's own. */
    private boolean alreadyHasContract;

    public BuildContractDto() { }

    public String getToolchain() { return toolchain; }
    public void setToolchain(String toolchain) { this.toolchain = toolchain; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public String getWarnings() { return warnings; }
    public void setWarnings(String warnings) { this.warnings = warnings; }
    public String getCommands() { return commands; }
    public void setCommands(String commands) { this.commands = commands; }
    public String getYaml() { return yaml; }
    public void setYaml(String yaml) { this.yaml = yaml; }
    public String getAcceptanceTestDir() { return acceptanceTestDir; }
    public void setAcceptanceTestDir(String acceptanceTestDir) {
        this.acceptanceTestDir = acceptanceTestDir;
    }
    public boolean isCompiles() { return compiles; }
    public void setCompiles(boolean compiles) { this.compiles = compiles; }
    public boolean isProbed() { return probed; }
    public void setProbed(boolean probed) { this.probed = probed; }
    public String getProbeVerdict() { return probeVerdict; }
    public void setProbeVerdict(String probeVerdict) { this.probeVerdict = probeVerdict; }
    public String getProbeLog() { return probeLog; }
    public void setProbeLog(String probeLog) { this.probeLog = probeLog; }
    public int getProbeSeconds() { return probeSeconds; }
    public void setProbeSeconds(int probeSeconds) { this.probeSeconds = probeSeconds; }
    public boolean isAlreadyHasContract() { return alreadyHasContract; }
    public void setAlreadyHasContract(boolean alreadyHasContract) {
        this.alreadyHasContract = alreadyHasContract;
    }

    /** True when a toolchain was recognised and there is something to save. */
    public boolean isRecognised() {
        return yaml != null && !yaml.isBlank();
    }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer,
                              com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, toolchain);
        BinarySerializer.writeString(buffer, evidence);
        BinarySerializer.writeString(buffer, warnings);
        BinarySerializer.writeString(buffer, commands);
        BinarySerializer.writeString(buffer, yaml);
        BinarySerializer.writeString(buffer, acceptanceTestDir);
        BinarySerializer.writeValue(buffer, compiles ? 1 : 0, mapper);
        BinarySerializer.writeValue(buffer, probed ? 1 : 0, mapper);
        BinarySerializer.writeString(buffer, probeVerdict);
        BinarySerializer.writeString(buffer, probeLog);
        BinarySerializer.writeValue(buffer, probeSeconds, mapper);
        BinarySerializer.writeValue(buffer, alreadyHasContract ? 1 : 0, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.toolchain = BinarySerializer.readString(buffer);
        this.evidence = BinarySerializer.readString(buffer);
        this.warnings = BinarySerializer.readString(buffer);
        this.commands = BinarySerializer.readString(buffer);
        this.yaml = BinarySerializer.readString(buffer);
        this.acceptanceTestDir = BinarySerializer.readString(buffer);
        this.compiles = SessionSummaryDto.readInt(buffer, mapper) != 0;
        this.probed = SessionSummaryDto.readInt(buffer, mapper) != 0;
        this.probeVerdict = BinarySerializer.readString(buffer);
        this.probeLog = BinarySerializer.readString(buffer);
        this.probeSeconds = SessionSummaryDto.readInt(buffer, mapper);
        this.alreadyHasContract = SessionSummaryDto.readInt(buffer, mapper) != 0;
    }
}
