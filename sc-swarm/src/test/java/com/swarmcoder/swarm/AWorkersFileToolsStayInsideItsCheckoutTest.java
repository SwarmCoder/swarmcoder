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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.runtime.ApiLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A worker's {@code read}, {@code write_file} and {@code apply_diff} run in the orchestrator's own
 * process - on the workstation - whatever its shell is confined to. So the path check is all that
 * keeps a model's path off the operator's disk: the checkout, the reference folders, nothing else.
 */
class AWorkersFileToolsStayInsideItsCheckoutTest {

    @TempDir
    Path tmp;

    private Path checkout;
    private Path reference;
    private Path secret;

    @BeforeEach
    void layOutTheMachine() throws IOException {
        checkout = tmp.resolve("wt");
        reference = tmp.resolve("zeroz4j");
        Files.createDirectories(checkout.resolve("src"));
        Files.createDirectories(reference.resolve("docs"));
        Files.writeString(checkout.resolve("src/App.java"), "class App {}\n");
        Files.writeString(reference.resolve("docs/guide.md"), "# the guide\n");
        secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "THE-OPERATORS-SECRET\n");
    }

    private WorkerToolbox toolbox() {
        Task task = new Task(UUID.randomUUID(), 1, "t", "instructions", Set.of("src"), Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
        WorkerToolbox toolbox = new WorkerToolbox(checkout, task, ApiLookup.UNAVAILABLE, null);
        toolbox.setReferenceRoots(Map.of("zeroz4j", reference));
        return toolbox;
    }

    @Test
    void readTakesTheCheckoutByRelativePathAndByItsNameInsideTheContainer() {
        WorkerToolbox toolbox = toolbox();
        assertThat(toolbox.read("src/App.java")).contains("class App");
        assertThat(toolbox.read("/workspace/src/App.java")).contains("class App");
        assertThat(toolbox.read(checkout.toString() + "/src/App.java")).contains("class App");
    }

    @Test
    void readTakesAReferenceDocumentAtTheAddressTheContainerMountsItAt() {
        WorkerToolbox toolbox = toolbox();
        assertThat(toolbox.read("/reference/zeroz4j/docs/guide.md")).contains("the guide");
        // ...and cannot be walked out of, or pointed at a folder nobody mounted.
        assertThat(toolbox.read("/reference/zeroz4j/../secret.txt"))
            .startsWith("error: Refused").doesNotContain("THE-OPERATORS-SECRET");
        assertThat(toolbox.read("/reference/other/docs/guide.md")).startsWith("error: Refused");
    }

    @Test
    void readRefusesAnythingElseOnTheMachineAndSaysWhatIsAllowed() {
        WorkerToolbox toolbox = toolbox();
        for (String path : new String[] {secret.toString(), "../secret.txt",
            "src/../../secret.txt", "C:\\Windows\\win.ini", "c:/Users", "/etc/passwd",
            "~/.ssh/id_rsa", "\\\\server\\share\\x"}) {
            String answer = toolbox.read(path);
            assertThat(answer).as(path).startsWith("error: Refused")
                .doesNotContain("THE-OPERATORS-SECRET");
        }
        assertThat(toolbox.read("C:\\Windows\\win.ini"))
            .contains("not inside your checkout")
            .contains("/reference/zeroz4j/<path>")
            .contains("Nothing else on this machine is reachable");
    }

    @Test
    void readDoesNotFollowALinkOutOfTheCheckout() {
        assumeTrue(LinkForTests.directoryLink(checkout.resolve("way-out"), tmp),
            "this machine lets the test create neither a symbolic link nor a junction");
        String answer = toolbox().read("way-out/secret.txt");
        assertThat(answer).startsWith("error: Refused").contains("symbolic link")
            .doesNotContain("THE-OPERATORS-SECRET");
    }

    @Test
    void writeFileRefusesAnAbsolutePathOutsideTheCheckoutInsteadOfReRootingIt() throws IOException {
        WorkerToolbox toolbox = toolbox();
        String answer = toolbox.writeFile("C:/Windows/hosts.txt", "x");
        assertThat(answer).startsWith("error: Refused").contains("not inside your checkout");
        // It used to be stripped to `Windows/hosts.txt` and written into the checkout.
        assertThat(Files.exists(checkout.resolve("Windows"))).isFalse();
        assertThat(toolbox.writeFile("/etc/hosts", "x")).startsWith("error: Refused");
        assertThat(Files.exists(checkout.resolve("etc"))).isFalse();

        assertThat(toolbox.writeFile("../escaped.txt", "x")).startsWith("error:");
        assertThat(Files.exists(tmp.resolve("escaped.txt"))).isFalse();

        // The container's own name for the checkout is the checkout.
        assertThat(toolbox.writeFile("/workspace/src/New.java", "class New {}"))
            .startsWith("wrote src/New.java");
        assertThat(Files.readString(checkout.resolve("src/New.java"))).contains("class New");
    }

    @Test
    void writeFileDoesNotWriteThroughALinkOutOfTheCheckout() {
        assumeTrue(LinkForTests.directoryLink(checkout.resolve("src/way-out"), tmp),
            "this machine lets the test create neither a symbolic link nor a junction");
        String answer = toolbox().writeFile("src/way-out/planted.txt", "x");
        assertThat(answer).startsWith("error:");
        assertThat(Files.exists(tmp.resolve("planted.txt"))).isFalse();
    }

    @Test
    void aDiffNamingAnAbsolutePathOutsideTheCheckoutIsRefused() {
        String diff = """
            --- /dev/null
            +++ C:/Windows/planted.txt
            @@ -0,0 +1 @@
            +x
            """;
        assertThat(toolbox().applyDiff(diff)).startsWith("error:");
        assertThat(Files.exists(checkout.resolve("Windows"))).isFalse();
    }

    @Test
    void aContainedWorkerIsToldItHasALinuxShellAndWhatItCanSee() {
        String note = SwarmDispatcher.containerShellNote(List.of("zeroz4j"));
        assertThat(note).contains("Linux container").contains("bash").contains("/workspace")
            .contains("`/reference/zeroz4j`").contains("no network").contains("offline")
            .doesNotContain("cmd.exe will reject");
        // The task prompt carries that note, and not the workstation's shell, when it is given.
        Task task = new Task(UUID.randomUUID(), 1, "t", "instructions", Set.of("src"), Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
        String rules = SwarmDispatcher.buildBundle(task, null, null, null, List.of(), note)
            .forWorker("", null).toString();
        assertThat(rules).contains("Linux container").doesNotContain("Windows' cmd.exe");
    }
}
