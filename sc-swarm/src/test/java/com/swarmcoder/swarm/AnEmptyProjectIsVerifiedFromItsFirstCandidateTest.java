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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.verify.ContractInUse;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.Verifier;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A project that starts as an empty folder is verified from its first candidate, and a run
 * that owes proof never starts a candidate with nothing to verify it by</b> (section 107, live
 * local build of 2026-10-14).
 *
 * <p>That build made a project over an empty folder. The folder never had a verification
 * contract, 23 candidates were logged "UNVERIFIED" and winners were chosen with nothing
 * compiled. Here the project's repository holds one empty commit. The verifier is a recorder:
 * what is asserted is which contract each candidate was verified by and whether it was
 * verified at all, not what a build tool says.
 */
@ModelCodeOnThisPc
class AnEmptyProjectIsVerifiedFromItsFirstCandidateTest {

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;

    /** What the engine handed the verifier, and whether the contract was in the project's folder then. */
    private final List<VerifySpec> verifiedBy = new CopyOnWriteArrayList<>();
    private final List<Boolean> contractWasThere = new CopyOnWriteArrayList<>();

    @Test
    void aPlanThatNamesMavenModulesGetsAContractBeforeItsFirstCandidateIsVerified()
            throws Exception {
        emptyProject();
        Task build = task("Create the build and the first module",
            Set.of("pom.xml", "core/pom.xml", "app/pom.xml", "core/src/main/java"), true);

        Outcome outcome = execute(build, conversation -> writeThenDone(conversation, "pom.xml",
            "<project><modelVersion>4.0.0</modelVersion></project>\n"));

        ContractInUse contract = VerifySpecLoader.inUse(repoDir, null);
        assertThat(contract.origin()).as("nobody wrote one; the product did")
            .isEqualTo(ContractInUse.GENERATED);
        assertThat(contract.basis()).contains("the plan names").contains("core/pom.xml");
        assertThat(verifiedBy).as("the candidate went to verification").isNotEmpty()
            .allSatisfy(spec -> {
                assertThat(spec.toolchain()).isEqualTo("maven");
                assertThat(spec.compile()).containsExactly("mvn -o -B -q test-compile");
                assertThat(spec.acceptance()).singleElement().asString()
                    .contains("-Dtest=swarm/accept/**");
            });
        assertThat(contractWasThere).as("it was in the project's folder before the first "
            + "candidate was verified").doesNotContain(false);
        assertThat(outcome.candidates).isNotEmpty().allSatisfy(candidate ->
            assertThat(candidate.verification())
                .as("no candidate is passed on without a verdict").isNotNull());
        assertThat(outcome.run.verificationContract())
            .as("the run's record says which contract and where it came from")
            .contains("generated by SwarmCoder").contains("mvn -o -B -q test-compile");
    }

    @Test
    void aRunThatOwesProofAndCanGetNoContractStopsBeforeAnyCandidateIsStarted()
            throws Exception {
        emptyProject();
        Task noBuildFile = task("Write the first class", Set.of("src/main/java/com/x"), true);

        Outcome outcome = execute(noBuildFile, conversation -> writeThenDone(conversation,
            "src/main/java/com/x/A.java", "package com.x;\nclass A {}\n"));

        assertThat(outcome.thrown).isInstanceOf(RunMustPark.class);
        assertThat(((RunMustPark) outcome.thrown).brief())
            .contains("NOTHING CAN BE VERIFIED").contains("There is no verification contract")
            .contains(VerifySpecLoader.SPEC_PATH).contains("1 check(s) are claimed");
        assertThat(outcome.modelCalls).as("no worker was started").isZero();
        assertThat(outcome.candidates).isEmpty();
        assertThat(verifiedBy).isEmpty();
        assertThat(outcome.run.verificationContract()).contains("NONE");
    }

    @Test
    void anUnreadableContractStopsTheRunBeforeAnyCandidateWhateverIsOwed() throws Exception {
        emptyProject();
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        Files.writeString(repoDir.resolve(VerifySpecLoader.SPEC_PATH), "compile: [\"unclosed\n");
        Task owesNothing = task("Write the first class", Set.of("src/main/java/com/x"), false);

        Outcome outcome = execute(owesNothing, conversation -> writeThenDone(conversation,
            "src/main/java/com/x/A.java", "package com.x;\nclass A {}\n"));

        assertThat(outcome.thrown).isInstanceOf(RunMustPark.class);
        assertThat(((RunMustPark) outcome.thrown).brief()).contains("is there and cannot be read");
        assertThat(outcome.modelCalls).isZero();
    }

    @Test
    void aContractThatStopsReadingWhileACandidateWorksFailsThatCandidate() throws Exception {
        emptyProject();
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        Path file = repoDir.resolve(VerifySpecLoader.SPEC_PATH);
        Files.writeString(file, "toolchain: gradle\ncompile:\n  - \"echo ok\"\ntimeoutSeconds: 60\n");
        Task owesNothing = task("Write the first class", Set.of("src/main/java/com/x"), false);

        Outcome outcome = execute(owesNothing, conversation -> {
            try {
                Files.writeString(file, "compile: [\"unclosed\n");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return writeThenDone(conversation, "src/main/java/com/x/A.java",
                "package com.x;\nclass A {}\n");
        });

        assertThat(verifiedBy).as("there was nothing to verify it by").isEmpty();
        assertThat(outcome.candidates).as("nothing was selected").isNotEmpty()
            .noneMatch(candidate -> candidate.state() == CandidateState.SELECTED
                || candidate.state() == CandidateState.SURVIVED);
        // The first worker is the one that changed something; it is the one verification saw.
        assertThat(outcome.candidates).filteredOn(candidate -> candidate.workerIndex() == 0)
            .isNotEmpty().allSatisfy(candidate -> {
            assertThat(candidate.state()).as("not passed on unverified")
                .isEqualTo(CandidateState.FAILED);
            assertThat(candidate.verification().logTail())
                .contains("is there and cannot be read");
        });
    }

    // --- the run ---------------------------------------------------------------------------

    private record Outcome(Throwable thrown, List<CandidateSolution> candidates, Run run,
                           int modelCalls) {}

    private Outcome execute(Task task, Function<String, FakeVllm.Reply> worker) throws Exception {
        UUID runId = UUID.randomUUID();
        try (FakeVllm fake = new FakeVllm(conversation ->
                conversation.contains("code-review judge")
                    ? FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}")
                    : worker.apply(conversation));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);
            run.setBaseCommit(base);
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                store.root().runs.put(runId, run);
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
            engine.setVerifier(new Recorder());
            Throwable thrown = null;
            try {
                engine.executeRun(run);
            } catch (RuntimeException e) {
                thrown = e; // a task with nothing that passed ends its run; a stop is asserted
            }
            List<CandidateSolution> archived = new ArrayList<>();
            store.root().candidateArchives.values().forEach(lazy -> {
                if (Lazy.get(lazy) instanceof CandidateSolution candidate) {
                    archived.add(candidate);
                }
            });
            return new Outcome(thrown, archived, run, fake.requests.size());
        }
    }

    /** Compiles, runs no test: what is recorded is the contract it was called with. */
    private final class Recorder implements Verifier {
        @Override
        public VerificationReport verify(ExecTarget target, Task task, VerifySpec spec,
                                         List<GuidelineCheck> guidelineChecks,
                                         Set<String> changedFiles) {
            verifiedBy.add(spec);
            contractWasThere.add(Files.isRegularFile(repoDir.resolve(VerifySpecLoader.SPEC_PATH)));
            return new VerificationReport(UUID.randomUUID(), true, true, null, null, null, null,
                Duration.ZERO, "", null);
        }
    }

    private static FakeVllm.Reply writeThenDone(String conversation, String path, String content) {
        if (conversation.contains("wrote " + path)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"written\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", path).put("content", content).toString());
    }

    private static Task task(String title, Set<String> writeSet, boolean claimsACheck) {
        Task task = new Task(UUID.randomUUID(), 1, title, "Do it.", writeSet, Set.of(),
            claimsACheck ? List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "the project builds", "swarm.accept.BuildTest#builds")) : List.of(),
            "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 3),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(List.of());
        return task;
    }

    /** A repository with one empty commit: the folder a new project is made over. */
    private void emptyProject() throws Exception {
        git("init -q");
        git("-c user.email=t@t -c user.name=t commit -q --allow-empty -m empty");
        base = git("rev-parse HEAD").strip();
    }

    private String git(String args) throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + args) : List.of("sh", "-c", "git " + args);
        Process p = new ProcessBuilder(command).directory(repoDir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
