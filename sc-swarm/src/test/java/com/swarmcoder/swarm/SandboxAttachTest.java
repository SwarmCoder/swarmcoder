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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.verify.SandboxExecTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression this exists to prevent: a Docker problem used to silently downgrade a candidate
 * to running model-authored commands on the workstation. There is no host fallback any more:
 * {@code sandbox.required} is ignored, a failed launch refuses, and an engine with no sandbox
 * refuses unless {@link HostExecution} was switched on by name.
 *
 * <p>No Docker daemon is involved — {@link DockerSandboxManager} is subclassed to simulate the
 * launch outcome.
 */
class SandboxAttachTest {

    /** A manager whose launch always fails, exactly as an unreachable/broken daemon behaves. */
    private static class FailingSandbox extends DockerSandboxManager {
        final AtomicInteger launches = new AtomicInteger();

        FailingSandbox(boolean required) {
            super("swarmcoder-worker:latest", 2, 4, 512, NetworkPolicy.NONE, List.of(), required,
                null, null);
        }

        @Override
        public SandboxHandle launch(String worktreeHostPath, String writeSetEnv,
                                    java.util.List<ReadOnlyMount> readOnly) {
            launches.incrementAndGet();
            throw new SandboxException("image swarmcoder-worker:latest not found");
        }
    }

    /** A manager whose launch succeeds, without a container behind it. */
    private static class WorkingSandbox extends DockerSandboxManager {
        WorkingSandbox() {
            super("swarmcoder-worker:latest", 2, 4, 512, NetworkPolicy.NONE, List.of(), true,
                null, null);
        }

        @Override
        public SandboxHandle launch(String worktreeHostPath, String writeSetEnv,
                                    java.util.List<ReadOnlyMount> readOnly) {
            return new SandboxHandle("container-abc", null);
        }

        @Override
        public void kill(SandboxHandle handle) {
            // no container to kill
        }
    }

    @Test
    void aSandboxThatFailsToLaunchRefusesInsteadOfFallingBackToTheHost() {
        FailingSandbox sandbox = new FailingSandbox(true);

        SandboxAttach.Attachment attachment =
            SandboxAttach.attach(sandbox, "/wt/candidate-1", "src", "Worker 0");

        assertThat(sandbox.launches).hasValue(1);
        assertThat(attachment.refused()).isTrue();
        assertThat(attachment.localAllowed()).isFalse();
        assertThat(attachment.target()).isNull();
        assertThat(attachment.handle()).isNull();
        assertThat(attachment.refusal())
            .contains("the container could not be started")
            .contains("image swarmcoder-worker:latest not found");
    }

    @Test
    void sandboxRequiredFalseIsIgnoredAndALaunchFailureStillRefuses() {
        FailingSandbox sandbox = new FailingSandbox(false);

        SandboxAttach.Attachment attachment =
            SandboxAttach.attach(sandbox, "/wt/candidate-1", "src", "Worker 0");

        assertThat(sandbox.launches).hasValue(1);
        assertThat(attachment.refused()).isTrue();
        assertThat(attachment.localAllowed()).isFalse();
        assertThat(attachment.refusal()).contains("image swarmcoder-worker:latest not found");
    }

    @Test
    void noSandboxConfiguredRefusesUnlessHostExecutionWasSwitchedOnByName() {
        SandboxAttach.Attachment attachment =
            SandboxAttach.attach(null, "/wt/candidate-1", "src", "Worker 0");

        assertThat(attachment.refused()).isTrue();
        assertThat(attachment.localAllowed()).isFalse();
        assertThat(attachment.refusal()).contains("Refused to run Worker 0 on this PC");
    }

    @Test
    void noSandboxConfiguredRunsOnTheHostWhenHostExecutionIsAllowedByName() {
        HostExecution.allow("SandboxAttachTest");
        try {
            SandboxAttach.Attachment attachment =
                SandboxAttach.attach(null, "/wt/candidate-1", "src", "Worker 0");

            assertThat(attachment.refused()).isFalse();
            assertThat(attachment.localAllowed()).isTrue();
        } finally {
            HostExecution.withdraw("SandboxAttachTest");
        }
    }

    @Test
    void successfulLaunchDrivesTheContainerNotTheHost() {
        SandboxAttach.Attachment attachment =
            SandboxAttach.attach(new WorkingSandbox(), "/wt/candidate-1", "src", "Worker 0");

        assertThat(attachment.refused()).isFalse();
        assertThat(attachment.localAllowed()).isFalse();
        assertThat(attachment.handle().containerId()).isEqualTo("container-abc");
        // Engine-exec transport: the only one that works under network=none, where Docker
        // publishes no port for the action server.
        assertThat(attachment.target()).isInstanceOf(SandboxExecTarget.class);
    }
}
