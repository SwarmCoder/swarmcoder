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
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.verify.JourneyFile;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final integration over a real git repository (section 63): the journeys the merged tree holds
 * are made after the last merge, and one that cannot be made is not passed over - here the
 * project's contract has no way to start the application. No browser is started in this test.
 */
@ModelCodeOnThisPc
class AJourneyThatCannotBeMadeStopsTheRunTest {

    private static final String SRC = "app/src/main/java/com/desk/";
    private static final String DIR = "app/src/test/java/swarm";
    private static final String JOURNEY = DIR + "/accept/open-report.journey.yaml";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    private Task task;
    private String base;

    @AfterEach
    void switchBackOn() {
        System.clearProperty(JourneyFile.SWITCH);
    }

    private void aGreenRun(boolean withAJourney) throws Exception {
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
        if (withAJourney) {
            write(JOURNEY, """
                journey: The report is opened from the menu
                steps:
                  - click: "role=link[name=\\"Report\\"]"
                  - expectVisible: "text=Monthly report"
                """);
        }
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = gitOut("rev-parse HEAD").strip();

        task = new Task(UUID.randomUUID(), 1, "report", "show the report",
            Set.of(SRC + "Menu.java"), Set.of(), List.of(), DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
        if (withAJourney) {
            task.setJourneyPaths(List.of(JOURNEY));
        }
        git("checkout -q -b swarm/" + task.id() + "/0");
        write(SRC + "Menu.java", """
            package com.desk;
            public class Menu {
                public String text() { return "menu and report"; }
            }
            """);
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
                        "--- a/" + SRC + "Menu.java\n+++ b/" + SRC + "Menu.java\n@@ -1 +1 @@\n-x\n+y\n",
                        null, null, null, CandidateState.SELECTED, null)));
                return null;
            }).get();
            return new FinalIntegrator(new GitService(repo), store).integrate(run);
        }
    }

    @Test
    void theRunIsNotDeliveredAndTheReasonIsPlain() throws Exception {
        aGreenRun(true);

        FinalIntegrator.Result result = integrate();

        assertThat(result.ok()).isFalse();
        assertThat(result.failure())
            .startsWith("Every acceptance test passed on the merged tree, and the 1 journey(s) "
                + "of this project could not be made")
            .contains("no `browser.serve` line")
            .contains(JOURNEY)
            .contains("-Dswarmcoder.verify.journeys=off");
        assertThat(result.journeyFailure())
            .as("nothing a worker could repair: it is the contract that is missing a line")
            .isNull();
    }

    @Test
    void aRunWhoseTreeHoldsNoJourneyIsDeliveredAsBefore() throws Exception {
        aGreenRun(false);

        FinalIntegrator.Result result = integrate();

        assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
    }

    @Test
    void switchedOffTheJourneyIsNotMadeAndTheRunIsDelivered() throws Exception {
        aGreenRun(true);
        System.setProperty(JourneyFile.SWITCH, "off");

        FinalIntegrator.Result result = integrate();

        assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
    }

    @Test
    void theJourneysOfTheTreeAreFoundUnderTheProtectedDirectoryOnly() throws Exception {
        aGreenRun(true);
        write("docs/notes.journey.yaml", "journey: not a test\n");
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(task), List.of());

        assertThat(FinalIntegrator.journeysIn(repo, graph)).containsOnlyKeys(JOURNEY);
    }

    // -------------------------------------------------------------------------------------------

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void git(String args) throws Exception {
        gitOut(args);
    }

    private String gitOut(String args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args.split(" ")));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }
}
