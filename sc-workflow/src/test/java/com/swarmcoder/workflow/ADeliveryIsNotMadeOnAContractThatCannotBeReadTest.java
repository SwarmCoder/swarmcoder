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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.git.GitService;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.verify.VerifySpecLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A story is not put on the project's branch on a contract that cannot be read, or on no
 * contract when it has something to prove</b> (section 107; the rule of section 104 at the
 * delivery).
 *
 * <p>{@link StoryDelivery} read "missing or unparseable" as "no contract" and moved the branch
 * with nothing built. One finished commit waits to be delivered onto {@code master} here; what
 * changes between the cases is the contract in the project's own folder and what the story
 * claims.
 */
@ModelCodeOnThisPc
class ADeliveryIsNotMadeOnAContractThatCannotBeReadTest {

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    private String base;
    private String finished;

    @Test
    void aContractThatCannotBeReadStopsTheDeliveryWhateverTheStoryClaims() throws Exception {
        repository();
        Files.createDirectories(repo.resolve(".swarmcoder"));
        Files.writeString(repo.resolve(VerifySpecLoader.SPEC_PATH), "compile: [\"unclosed\n");

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            StoryDelivery.Result result = new StoryDelivery(new GitService(repo), store)
                .deliver(story(), finished, "master");

            assertThat(result.ok()).isFalse();
            assertThat(result.failure()).contains("was not put on master")
                .contains("is there and cannot be read").contains("accept the story again");
            assertThat(git("rev-parse master").strip()).as("the branch was not moved")
                .isEqualTo(base);
        }
    }

    @Test
    void noContractStopsAStoryThatClaimsACheckAndPassesOneThatClaimsNone() throws Exception {
        repository();

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Story claiming = story();
            Task task = new Task(UUID.randomUUID(), 1, "Build the thing", "build it",
                Set.of("src/main"), Set.of(),
                List.of(new AcceptanceCriterion(UUID.randomUUID(), "the thing works",
                    "swarm.accept.ThingTest#works")),
                "src/test/java/swarm", null, null,
                new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
            task.setStoryId(claiming.id());
            store.saveTask(task);

            StoryDelivery.Result refused = new StoryDelivery(new GitService(repo), store)
                .deliver(claiming, finished, "master");

            assertThat(refused.ok()).isFalse();
            assertThat(refused.failure()).contains("There is no verification contract")
                .contains("1 check(s) are claimed");
            assertThat(git("rev-parse master").strip()).isEqualTo(base);

            StoryDelivery.Result allowed = new StoryDelivery(new GitService(repo), store)
                .deliver(story(), finished, "master");

            assertThat(allowed.ok()).as("no contract anywhere and nothing owed: the allowance "
                + "of milestone 1, " + allowed.failure()).isTrue();
            assertThat(git("rev-parse master").strip()).isNotEqualTo(base);
        }
    }

    private static Story story() {
        return new Story(UUID.randomUUID(), UUID.randomUUID(), "S1", StoryKind.DELIVERY,
            "Deliver the thing", null, StoryState.RUNNING, new ArrayList<>(), new ArrayList<>(),
            null, 0, StoryOrigin.BACKLOG, null, null, "human", new ArrayList<>(), null, null,
            null, null, Instant.now(), Instant.now());
    }

    /** {@code master} with one commit, and one finished commit beside it. */
    private void repository() throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = git("rev-parse HEAD").strip();
        git("checkout -q -b finished");
        Files.writeString(repo.resolve("thing.txt"), "the thing\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m finished");
        finished = git("rev-parse HEAD").strip();
        git("checkout -q master");
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
