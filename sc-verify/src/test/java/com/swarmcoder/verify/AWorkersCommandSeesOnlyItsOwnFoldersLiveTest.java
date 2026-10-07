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

import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's rule, measured in a real container: no command a model writes can read or write the
 * workstation's drives. Inside the container a worker has its own checkout (read-write), the
 * reference folder (read-only), the Maven repository (read-only) - and nothing else.
 *
 * <p>Runs wherever Docker and {@code swarmcoder-worker:latest} are present, and is announced as
 * skipped where they are not. The container is removed in a {@code finally}.
 */
@RunsWhen(Need.DOCKER)
class AWorkersCommandSeesOnlyItsOwnFoldersLiveTest {

    @Test
    void aCommandInTheContainerCannotReachTheMachineOutsideItsMounts(@TempDir Path tmp)
            throws Exception {
        // The workstation, in miniature: a checkout, a reference folder, and a file beside them
        // that belongs to neither - standing in for everything else on the operator's disk.
        String token = "sc-secret-" + UUID.randomUUID();
        Path checkout = tmp.resolve("wt");
        Path reference = tmp.resolve("reference-lib");
        Files.createDirectories(checkout.resolve("src"));
        Files.createDirectories(reference.resolve("docs"));
        Files.writeString(checkout.resolve("src/App.java"), "class App {}\n");
        Files.writeString(checkout.resolve(".git"), "gitdir: /nowhere/.git/worktrees/wt\n");
        Files.writeString(reference.resolve("docs/guide.md"), "# the guide\n");
        Path outside = tmp.resolve(token + ".txt");
        Files.writeString(outside, token + "\n");

        DockerSandboxManager sandbox = new DockerSandboxManager("swarmcoder-worker:latest", 2, 4,
            null, Path.of(System.getProperty("user.home"), ".m2").toString());
        assertThat(sandbox.whyUnusable()).isEmpty();
        assertThat(sandbox.network()).isEqualTo(DockerSandboxManager.NetworkPolicy.NONE);

        DockerSandboxManager.SandboxHandle handle = sandbox.launch(
            checkout.toAbsolutePath().toString(), "src",
            List.of(new DockerSandboxManager.ReadOnlyMount(reference.toAbsolutePath().toString(),
                DockerSandboxManager.referenceMountPath("reference-lib"))));
        System.out.println("[CONTAINED] started container " + handle.containerId());
        try {
            SandboxExecTarget shell = new SandboxExecTarget(sandbox, handle.containerId()).preferBash();

            // --- what it has ------------------------------------------------------------------
            assertThat(shell.exec("pwd && cat src/App.java", 30).output())
                .contains("/workspace").contains("class App");
            assertThat(shell.exec("echo made > src/New.java", 30).exitCode()).isZero();
            assertThat(Files.readString(checkout.resolve("src/New.java")))
                .as("the checkout is the worker's own, and what it writes there is on the host")
                .contains("made");
            assertThat(shell.exec("cat /reference/reference-lib/docs/guide.md", 30).output())
                .contains("the guide");
            assertThat(shell.exec("id -u", 30).output().strip()).isNotEqualTo("0");
            assertThat(shell.exec("[[ -n \"$BASH_VERSION\" ]] && echo is-bash", 30).output())
                .as("a model's commands get bash, not dash").contains("is-bash");

            // --- what it has not --------------------------------------------------------------
            // The reference folder cannot be written.
            assertThat(shell.exec("echo x > /reference/reference-lib/docs/planted.md", 30).exitCode())
                .isNotZero();
            assertThat(Files.exists(reference.resolve("docs/planted.md"))).isFalse();

            // The file beside the checkout is nowhere in the container - not by walking up, not
            // by searching the whole of it. (The Maven repository and /proc are left out of the
            // search only because they are huge; neither can hold a file from that folder.)
            assertThat(shell.exec("ls -a /workspace/..", 30).output()).doesNotContain(token);
            ExecResult search = shell.exec("find / -name '" + token + "*' "
                + "-not -path '/proc/*' -not -path '/sys/*' -not -path '/opt/m2-ro/*' "
                + "-not -path '/opt/m2-cache/*' 2>/dev/null; echo searched", 120);
            assertThat(search.output()).contains("searched").doesNotContain(token + ".txt");

            // No drive of the workstation is mounted under any of the names Docker gives one.
            ExecResult drives = shell.exec("ls -d /mnt/c /mnt/host /host_mnt /run/desktop/mnt/host "
                + "/c /C: 2>/dev/null; echo listed", 30);
            assertThat(drives.output().strip()).isEqualTo("listed");

            // Every host folder it can see is one it was given: nothing else is mounted from
            // outside the container. Paths that are the container's own (tmpfs, proc, the image)
            // carry no host folder.
            String mounts = shell.exec("awk '{print $2}' /proc/mounts | sort", 30).output();
            assertThat(mounts).contains("/workspace").contains("/reference/reference-lib")
                .contains("/opt/m2-ro/repository");
            for (String mountPoint : mounts.strip().split("\\R")) {
                assertThat(mountPoint).as("mounted in the container: " + mountPoint)
                    .matches("/|/proc(/.*)?|/dev(/.*)?|/sys(/.*)?|/tmp|/home/sandbox"
                        + "|/etc/(resolv\\.conf|hostname|hosts)|(/usr)?/sbin/docker-init|/run/\\.containerenv"
                        + "|/workspace|/workspace/\\.git|/reference/reference-lib"
                        + "|/opt/m2-ro/repository|/opt/m2-cache");
            }

            // The Maven repository is the repository folder only: the settings file beside it on
            // the workstation, where repository passwords live, is not there, and it is read-only.
            assertThat(shell.exec("ls -A /opt/m2-ro", 30).output().strip()).isEqualTo("repository");
            assertThat(shell.exec("touch /opt/m2-ro/repository/sc-probe", 30).exitCode()).isNotZero();

            // The checkout's git pointer cannot be rewritten to aim the host's git elsewhere.
            assertThat(shell.exec("echo 'gitdir: /somewhere/else' > .git", 30).exitCode()).isNotZero();
            assertThat(shell.exec("rm -f .git && echo removed", 30).output()).doesNotContain("removed");
            assertThat(Files.readString(checkout.resolve(".git"))).contains("/nowhere/.git");

            // No network.
            assertThat(shell.exec("curl -s --max-time 5 -o /dev/null http://1.1.1.1/", 30).exitCode())
                .isNotZero();

            // --- and it can still build ------------------------------------------------------
            ExecResult maven = shell.exec("mvn -o -q -v", 120);
            assertThat(maven.exitCode()).as(maven.output()).isZero();
            assertThat(shell.exec("echo \"$MAVEN_ARGS\"", 30).output())
                .as("a model's bare `mvn test` is offline without being told").contains(" -o");

            // --- a command that overruns is stopped, not left running ------------------------
            ExecResult overrun = shell.exec("sleep 300", 3);
            assertThat(overrun.timedOut()).isTrue();
            assertThat(shell.exec("ps -eo args | grep -c '^sleep 300' || true", 30).output().strip())
                .as("the overrunning command is gone from the container").isEqualTo("0");
        } finally {
            sandbox.kill(handle);
            System.out.println("[CONTAINED] removed container " + handle.containerId());
        }
        assertThat(Files.readString(outside)).isEqualTo(token + "\n");
    }
}
