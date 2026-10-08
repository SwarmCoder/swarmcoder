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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.knowledge.ReachableCode;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
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
 * Final integration over a real git repository: a run whose merged tree is green but holds a
 * class it added and nothing reaches is not delivered (the seven accepted stories whose screens
 * no user could open, 2026-10-05). The same run with the class used from the code the
 * application enters by is delivered, and so is the first with the check switched off.
 */
@ModelCodeOnThisPc
class ARunThatAddsCodeNothingReachesIsNotDeliveredTest {

    private static final String SRC = "app/src/main/java/com/desk/";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    private Task task;
    private String base;
    private String diff;
    /** What stands before the added class: nothing, or an annotation. */
    private String mark = "";
    /** What final integration sent to the run's own record. */
    private final List<String> record = new java.util.ArrayList<>();

    @AfterEach
    void switchBackOn() {
        System.clearProperty(ReachableCode.SWITCH);
    }

    private void aWinnerThatAddsAReport(boolean usedFromTheDesk) throws Exception {
        git("init -q");
        write(".swarmcoder/verify.yaml",
            "toolchain: gradle\ncompile:\n  - \"echo integration-ok\"\ntimeoutSeconds: 60\n");
        write(SRC + "Desk.java", """
            package com.desk;
            public class Desk {
                public static void main(String[] args) { System.out.println(new Menu().text()); }
            }
            """);
        write(SRC + "Menu.java", """
            package com.desk;
            public class Menu {
                public String text() { return "menu"; }
            }
            """);
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = gitOut("rev-parse HEAD").strip();

        task = new Task(UUID.randomUUID(), 1, "report", "add the report",
            Set.of(SRC + "Report.java", SRC + "Menu.java"), Set.of(), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
        git("checkout -q -b swarm/" + task.id() + "/0");
        write(SRC + "Report.java", """
            package com.desk;
            %spublic class Report {
                public String text() { return "report"; }
            }
            """.formatted(mark));
        diff = "--- /dev/null\n+++ b/" + SRC + "Report.java\n@@ -0,0 +1 @@\n+x\n";
        if (usedFromTheDesk) {
            write(SRC + "Menu.java", """
                package com.desk;
                public class Menu {
                    public String text() { return "menu " + new Report().text(); }
                }
                """);
            diff += "--- a/" + SRC + "Menu.java\n+++ b/" + SRC + "Menu.java\n@@ -1 +1 @@\n-x\n+y\n";
        }
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m report");
        git("checkout -q master");
    }

    private FinalIntegrator.Result integrate() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(task), List.of());
            Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD,
                RunState.FINAL_INTEGRATION, null, null, null, graph.id(), null, Instant.now(), null);
            run.setBaseCommit(base);
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                store.root().candidateArchives.put(UUID.randomUUID(), Lazy.Reference(
                    new CandidateSolution(UUID.randomUUID(), task.id(), 0,
                        "swarm/" + task.id() + "/0", new SamplingConfig("m", 0.2, 0, "p", "s"),
                        diff, null, null, null, CandidateState.SELECTED, null)));
                return null;
            }).get();
            return new FinalIntegrator(new GitService(repo), store)
                .tellingTheRun(record::add).integrate(run);
        }
    }

    @Test
    void theOrphanIsNamedAndTheRunDoesNotPass() throws Exception {
        aWinnerThatAddsAReport(false);

        FinalIntegrator.Result result = integrate();

        assertThat(result.ok()).isFalse();
        assertThat(result.failure())
            .startsWith("This run adds production code that nothing in the application can "
                + "reach: Report (" + SRC + "Report.java)")
            .contains("Every acceptance test passed")
            .contains("-Dswarmcoder.verify.unreachableAddedCode=off");
    }

    @Test
    void usedFromTheCodeTheApplicationEntersByItIsDelivered() throws Exception {
        aWinnerThatAddsAReport(true);

        FinalIntegrator.Result result = integrate();

        assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
    }

    /**
     * Live run 93 (2026-10-08): the first type of a project that a framework finds by its
     * annotation. Nothing that was there before shows how types are found, so it is not
     * refused - and the run's record says what was not established.
     */
    @Test
    void theFirstAnnotatedTypeNothingUsesIsDeliveredAndTheRunSaysWhatItDidNotEstablish()
            throws Exception {
        mark = "@fw.Scheduled ";
        aWinnerThatAddsAReport(false);

        FinalIntegrator.Result result = integrate();

        assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
        assertThat(record).singleElement().asString()
            .startsWith("FINAL_INTEGRATION: Not established: whether the application reaches "
                + "Report (@Scheduled, " + SRC + "Report.java)")
            .contains("taken as found by the framework and not refused");
    }

    @Test
    void theSwitchTurnsTheCheckOff() throws Exception {
        aWinnerThatAddsAReport(false);
        System.setProperty(ReachableCode.SWITCH, "off");

        FinalIntegrator.Result result = integrate();

        assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
    }

    private void write(String path, String text) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void git(String args) throws Exception {
        gitOut(args);
    }

    private String gitOut(String args) throws Exception {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command.addAll(List.of("cmd.exe", "/c", "git " + args));
        } else {
            command.addAll(List.of("sh", "-c", "git " + args));
        }
        Process p = new ProcessBuilder(command).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
