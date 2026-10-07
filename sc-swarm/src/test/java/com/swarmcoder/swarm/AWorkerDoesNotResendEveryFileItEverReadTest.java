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

import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's old tool results are cut to their first lines, and what stays at the front of its
 * conversation stays exactly as it was (2026-10-02).
 *
 * <p>Harness run 66: 219 worker calls sent 5,419,009 prompt tokens for 102,036 written - every
 * turn resends every file an earlier turn read. Here a real worker, through the real worker
 * stack, reads twelve large files one after another against a scripted model server, with room
 * to spare (so the compaction at the real room never fires). The request for its last turn must
 * not carry all twelve again, the earliest must be there as their first lines with a note on how
 * to get them back, and every request must begin with the same bytes - a server that keeps a
 * conversation's prefix warm must still be able to.
 */
@ModelCodeOnThisPc
class AWorkerDoesNotResendEveryFileItEverReadTest {

    private static final int FILES = 12;

    @TempDir
    Path repoDir;

    @Test
    void oldReadsBecomeTheirFirstLinesAndTheFrontOfTheConversationNeverChanges() throws Exception {
        exec("git init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        for (int i = 0; i < FILES; i++) {
            // Different from one another: reading the same content twice counts as a wasted call.
            Files.writeString(repoDir.resolve("Big" + i + ".java"),
                "// file " + i + "\npackage big" + i + ";\n" + ("line of file " + i + "\n").repeat(600));
        }
        exec("git add -A");
        exec("git -c user.email=t@t -c user.name=t commit -q -m init");

        AtomicInteger turn = new AtomicInteger();
        try (FakeVllm fake = new FakeVllm(conversation -> {
            int n = turn.getAndIncrement();
            return n < FILES
                ? FakeVllm.Reply.toolCall("read", "{\"path\":\"Big" + n + ".java\"}")
                : FakeVllm.Reply.toolCall("report_done", "{\"summary\":\"read them all\"}");
        })) {
            // Room for everything: nothing here may be explained by the conversation not fitting.
            ModelQuirks quirks = ModelQuirks.DEFAULTS.withWorkingContextTokens(400_000);
            SwarmDispatcher dispatcher = new SwarmDispatcher(
                InferenceScheduler.forWorkerModel("fake", quirks), new GitService(repoDir),
                new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm",
                        quirks.servedContextTokens(), quirks),
                    ModelProfile.Kind.WORKER, quirks, 0))));
            Task task = new Task(UUID.randomUUID(), 1, "Look around", "Read the big files.",
                Set.of("README.md"), Set.of(), List.of(), null, null, null,
                new SwarmPolicy(1, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);

            List<WorkerResult> results = dispatcher.dispatch(task, UUID.randomUUID());
            try {
                assertThat(results).hasSize(1);
                assertThat(results.get(0).candidate().killReason())
                    .as("it was not stopped: it had room, and it finished").isNull();
                assertThat(results.get(0).candidate().state()).isNotEqualTo(CandidateState.KILLED);
            } finally {
                for (WorkerResult result : results) {
                    if (result.workspace() != null) {
                        new GitService(repoDir).removeWorktree(result.workspace());
                    }
                }
            }

            List<String> requests = List.copyOf(fake.requests);
            assertThat(requests).hasSize(FILES + 1);
            String last = requests.get(FILES);
            assertThat(last)
                .as("the earliest reads are there as their first lines, and say how to get the "
                    + "rest back")
                .contains("[dropped to save room] You read this earlier with read")
                .contains("Big0.java").contains("package big0;")
                .contains("Make the same call again");
            assertThat(count(last, "line of file 0"))
                .as("the body of the first file is no longer resent").isBetween(1, 60);
            assertThat(count(last, "line of file " + (FILES - 1)))
                .as("the file it has just read is whole (as much of it as one read returns)")
                .isGreaterThan(300);
            assertThat(last.length())
                .as("the last request is smaller than the one before the old reads were cut")
                .isLessThan(requests.stream().mapToInt(String::length).max().orElseThrow());

            // The front of the conversation: the system prompt and the opening instruction.
            String first = requests.get(0);
            for (String request : requests) {
                assertThat(request)
                    .as("every request begins with exactly what the first one was")
                    .startsWith(first);
            }
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private void exec(String command) throws Exception {
        List<String> cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", command) : List.of("sh", "-c", command);
        Process p = new ProcessBuilder(cmd).directory(repoDir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(command + " failed: " + out);
        }
    }
}
