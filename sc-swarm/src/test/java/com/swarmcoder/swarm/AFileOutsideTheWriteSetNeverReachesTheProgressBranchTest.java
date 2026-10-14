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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.git.GitService;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 112 (live local build, 2026-10-14). A worker of the shared module's task wrote the
 * server module's build file, a stub with no dependencies, because the root build file it wrote
 * lists that module. The file was in the write set of the server task, which the plan ran later.
 * Build files were nobody's in the reservations, left out of the verification check and kept by
 * the merge, so the stub reached the progress branch and the server task's test could not
 * compile. A change outside a task's write set that another task holds now never reaches a
 * winner or the progress branch, whatever wrote it; output the build generates under an ignored
 * folder is not a change.
 */
@ModelCodeOnThisPc
class AFileOutsideTheWriteSetNeverReachesTheProgressBranchTest {

    private static final String ROOT_POM = "pom.xml";
    private static final String SHARED_POM = "shared/pom.xml";
    private static final String SERVER_POM = "server/pom.xml";
    private static final String CLIENT_POM = "client/pom.xml";
    private static final String GENERATED = "target/classes/Generated.class";

    private final Task shared = task("Shared module", ROOT_POM, SHARED_POM, "shared/src/main/java");
    private final Task server = task("Server module", SERVER_POM, "server/src/main/java");
    private final Task client = task("Client module", CLIENT_POM, "client/src/main/java");
    /** The shared module first; the server and the client after it. */
    private final List<List<Task>> waves = List.of(List.of(shared), List.of(server, client));

    @TempDir
    Path repo;

    // --- at write time -------------------------------------------------------------------------

    @Test
    void aShellCommandWritingALaterTasksBuildFileIsPutBackAndTheTasksOwnFileStays()
            throws Exception {
        initRepo();
        WorkerToolbox toolbox = new WorkerToolbox(repo, shared, ApiLookup.UNAVAILABLE, null, null,
            List.of());
        toolbox.setOtherTasks(ReservationBook.of(waves).forWorker(shared, "0"));

        String stub = toolbox.exec(shellWrite(SERVER_POM, "stub"));
        assertThat(stub).contains("[write policy]").contains("put back")
            .contains("'Server module'");
        assertThat(Files.exists(repo.resolve(SERVER_POM))).as("never left in the checkout")
            .isFalse();

        toolbox.exec(shellWrite(SHARED_POM, "own"));
        assertThat(Files.exists(repo.resolve(SHARED_POM))).as("its own file stays").isTrue();

        toolbox.exec(shellWrite(GENERATED, "bytes"));
        assertThat(Files.exists(repo.resolve(GENERATED)))
            .as("build output under an ignored folder is not touched").isTrue();
        assertThat(toolbox.outOfWriteSetPaths()).as("nor recorded").isEmpty();
        assertThat(toolbox.blockingViolations()).isZero();
    }

    @Test
    void aFileThatReachedTheCheckoutPastEveryToolIsPutBackBeforeTheChangeIsTaken()
            throws Exception {
        initRepo();
        WorkerToolbox toolbox = new WorkerToolbox(repo, shared, ApiLookup.UNAVAILABLE, null, null,
            List.of());
        toolbox.setOtherTasks(ReservationBook.of(waves).forWorker(shared, "0"));
        // Written with no tool and no command of the worker: whatever wrote it.
        write(SERVER_POM, "<project/>");
        write(CLIENT_POM, "<project>changed</project>");
        write(SHARED_POM, "<project>shared</project>");
        write(GENERATED, "bytes");

        String settled = toolbox.settleBeforeTheChangeIsTaken();

        assertThat(settled).contains("'Server module'").contains("'Client module'");
        assertThat(Files.exists(repo.resolve(SERVER_POM))).as("created: removed").isFalse();
        assertThat(Files.readString(repo.resolve(CLIENT_POM)))
            .as("changed: as it was committed").contains("original");
        assertThat(Files.readString(repo.resolve(SHARED_POM))).contains("shared");
        assertThat(Files.exists(repo.resolve(GENERATED))).isTrue();
    }

    // --- before verification: the candidate's whole diff -------------------------------------

    @Test
    void theWholeDiffIsCheckedSoAChangeNoToolRecordedStillFailsTheCandidate() {
        ReservationBook book = ReservationBook.of(waves);
        CandidateSolution stubbed = candidate(shared, header(ROOT_POM) + header(SHARED_POM)
            + header(SERVER_POM) + header(GENERATED));

        assertThat(stubbed.outOfWriteSetPaths()).as("nothing was recorded by a tool").isEmpty();
        assertThat(SourceOutsideWriteSet.objection(shared, stubbed, book))
            .contains(SERVER_POM).contains("'Server module', which the plan runs later")
            .doesNotContain(GENERATED).doesNotContain(SHARED_POM);

        CandidateSolution own = candidate(shared, header(ROOT_POM) + header(SHARED_POM)
            + header(GENERATED));
        assertThat(SourceOutsideWriteSet.objection(shared, own, book))
            .as("its own files and generated output pass").isNull();
        assertThat(SourceOutsideWriteSet.paths(shared, own)).isEmpty();

        CandidateSolution deleted = candidate(shared, "diff --git a/" + CLIENT_POM + " b/"
            + CLIENT_POM + "\ndeleted file mode 100644\n--- a/" + CLIENT_POM
            + "\n+++ /dev/null\n@@ -1 +0,0 @@\n-old\n");
        assertThat(SourceOutsideWriteSet.objection(shared, deleted, book))
            .as("deleting another task's file is a change to it").contains(CLIENT_POM);
    }

    // --- at the merge onto the progress branch -----------------------------------------------

    @Test
    void aFileOutsideTheWinnersWriteSetIsPutBackOnTheProgressBranchAndItsOwnFileLands()
            throws Exception {
        initRepo();
        write(".swarmcoder/verify.yaml",
            "toolchain: gradle\ncompile:\n  - \"echo wave-compile-ok\"\ntimeoutSeconds: 60");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m contract");
        String base = git("rev-parse HEAD").strip();

        String branch = "swarm/" + shared.id() + "/0";
        git("checkout -q -b " + branch);
        write(SHARED_POM, "<project>shared</project>");
        write(SERVER_POM, "<project/>");
        write(CLIENT_POM, "<project>changed</project>");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m winner");
        git("checkout -q " + base);

        CandidateSolution winner = new CandidateSolution(UUID.randomUUID(), shared.id(), 0, branch,
            new SamplingConfig("fake", 0.2, 0L, "minimal-diff", "full-files"),
            header(SHARED_POM) + header(SERVER_POM) + header(CLIENT_POM), null, null, null,
            CandidateState.SELECTED, null);

        WaveIntegrator.Result result = new WaveIntegrator(new GitService(repo)).integrateWave(
            UUID.randomUUID(), base, List.of(new WaveIntegrator.Winner(shared, winner)),
            new LinkedHashMap<>());

        assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
        assertThat(git("show " + result.commit() + ":" + SHARED_POM)).contains("shared");
        assertThatThrownBy(() -> git("show " + result.commit() + ":" + SERVER_POM))
            .as("the later task's build file is not created early")
            .isInstanceOf(IllegalStateException.class);
        assertThat(git("show " + result.commit() + ":" + CLIENT_POM))
            .as("another task's existing file is as it was").contains("original");
    }

    // --- fixtures ------------------------------------------------------------------------------

    private void initRepo() throws Exception {
        git("init -q");
        write(".gitignore", "target/");
        write(ROOT_POM, "<project>root</project>");
        write(CLIENT_POM, "<project>original</project>");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
    }

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content + "\n");
    }

    private static CandidateSolution candidate(Task task, String diff) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), 0, "b",
            new SamplingConfig("fake", 0.2, 0L, "minimal-diff", "full-files"), diff, null, null,
            null, CandidateState.SURVIVED, null);
    }

    private static String header(String path) {
        return "diff --git a/" + path + " b/" + path + "\n--- a/" + path + "\n+++ b/" + path
            + "\n@@ -1 +1 @@\n-old\n+new\n";
    }

    private static Task task(String title, String... writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of(writeSet), Set.of(),
            List.of(), "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }

    /** A shell command that writes one file, creating its folder, on this PC's shell. */
    private static String shellWrite(String path, String content) {
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? null : path.substring(0, slash);
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            String mkdir = parent == null ? "" : "mkdir " + parent.replace('/', '\\') + " 2>nul & ";
            return mkdir + "echo " + content + "> " + path.replace('/', '\\');
        }
        String mkdir = parent == null ? "" : "mkdir -p " + parent + " && ";
        return mkdir + "printf '%s' '" + content + "' > " + path;
    }

    private String git(String args) throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + args) : List.of("sh", "-c", "git " + args);
        Process p = new ProcessBuilder(command).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
