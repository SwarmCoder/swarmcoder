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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.BuildLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A task's acceptance test goes in the module its own code is written to, and a test that
 * needs a library its module does not declare is told so</b> (section 109, live local build of
 * 2026-10-14).
 *
 * <p>That build's plan had four tasks: the build files of every module, the shared module, the
 * browser client, the server. Because the first task writes every module's build file, every
 * module seemed to come after every other, the tie went to the first name, and the server
 * task's test went to the browser client, whose build declares none of the server's libraries.
 * The run stopped at the last wave saying "no task delivers" library packages.
 */
class AnAcceptanceTestGoesInTheModuleOfItsTaskTest {

    @TempDir
    Path repo;
    @TempDir
    Path m2;

    private final Task skeleton = task("Module skeleton", Set.of("pom.xml", "app-shared/pom.xml",
        "app-client/pom.xml", "app-server/pom.xml"));
    private final Task shared = task("Shared module", Set.of("app-shared/src/main/java/org/x"));
    private final Task client = task("Browser client", Set.of("app-client/src/main/java/org/x"));
    private final Task server = task("Server", Set.of("app-server/src/main/java/org/x"));
    private final TaskGraph plan = new TaskGraph(UUID.randomUUID(), 1, null,
        List.of(skeleton, shared, client, server),
        List.of(new TaskEdge(skeleton.id(), shared.id()), new TaskEdge(shared.id(), client.id()),
            new TaskEdge(client.id(), server.id())));
    private final BuildLayout.Layout nothingToRead = BuildLayout.read((Path) null, null);

    @Test
    void theServerTaskOfAFourTaskPlanHasItsTestInTheServerModule() {
        ContractFromTheStart.Places places =
            ContractFromTheStart.acceptancePlaces(nothingToRead, plan, repo);

        assertThat(places.of(server).protectedDir())
            .as("the module its own main sources are written to, not the browser client")
            .isEqualTo("app-server/src/test/java/swarm");
        assertThat(places.of(shared).protectedDir()).isEqualTo("app-shared/src/test/java/swarm");
        assertThat(places.of(client).protectedDir())
            .as("nothing on disk says yet that this module runs only in a browser")
            .isEqualTo("app-client/src/test/java/swarm");
        assertThat(places.of(skeleton).protectedDir())
            .as("a task writing only build files takes the module whose tasks come last")
            .isEqualTo("app-server/src/test/java/swarm");
        assertThat(ContractFromTheStart.acceptanceLocation(nothingToRead, plan).module())
            .isEqualTo("app-server");
    }

    @Test
    void aModuleThatRunsOnlyInABrowserReceivesNoTest() throws Exception {
        Files.createDirectories(repo.resolve("app-client"));
        Files.writeString(repo.resolve("app-client/pom.xml"), pom("""
            <dependency><groupId>org.teavm</groupId><artifactId>teavm-classlib</artifactId>
            <version>1.0</version></dependency>"""));
        // The server comes before the client here: by task order the client would win.
        TaskGraph serverFirst = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(skeleton, shared, server, client),
            List.of(new TaskEdge(skeleton.id(), shared.id()),
                new TaskEdge(shared.id(), server.id()), new TaskEdge(server.id(), client.id())));

        ContractFromTheStart.Places places =
            ContractFromTheStart.acceptancePlaces(nothingToRead, serverFirst, repo);

        assertThat(places.of(client).module()).isEqualTo("app-server");
        assertThat(places.planDefault().module()).isEqualTo("app-server");
        assertThat(places.of(server).module()).isEqualTo("app-server");
    }

    @Test
    void libraryPackagesAreNamedAsLibrariesWithTheBuildFileThatDeclaresThem() throws Exception {
        Files.createDirectories(repo.resolve("app-client"));
        Files.createDirectories(repo.resolve("app-server"));
        Files.writeString(repo.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>org.x</groupId>
            <artifactId>app</artifactId><version>1</version><packaging>pom</packaging>
            <dependencyManagement><dependencies>
            <dependency><groupId>org.example.cdi</groupId><artifactId>cdi-api</artifactId>
            <version>4.0</version></dependency>
            </dependencies></dependencyManagement></project>""");
        Files.writeString(repo.resolve("app-client/pom.xml"), pom(""));
        Files.writeString(repo.resolve("app-server/pom.xml"), pom("""
            <dependency><groupId>org.example.db</groupId><artifactId>db-net</artifactId>
            </dependency>"""));
        jar("org.example.db", "db-net", "1.2", "org/example/db/net/Node.class");
        jar("org.example.cdi", "cdi-api", "4.0", "org/example/cdi/context/Scoped.class");
        server.setAcceptanceTestDir("app-client/src/test/java/swarm");

        String message = UndeliverableType.park(server, List.of(),
            List.of("org.example.db.net", "org.example.cdi.context.Scoped"), repo, m2);

        assertThat(message)
            .contains("`org.example.db.net` is a package of the library org.example.db:db-net, "
                + "not a type a task delivers")
            .contains("declared by app-server/pom.xml, and not by app-client/pom.xml")
            .contains("`org.example.cdi.context.Scoped` is a package of the library "
                + "org.example.cdi:cdi-api")
            .contains("declared by no build file this task or an earlier one writes")
            .contains("the tests are in the wrong module")
            .doesNotContain("no task delivers");

        Task mayDeclare = task("Server", Set.of("app-client/pom.xml",
            "app-server/src/main/java/org/x"));
        mayDeclare.setAcceptanceTestDir("app-client/src/test/java/swarm");
        assertThat(UndeliverableType.park(mayDeclare, List.of(), List.of("org.example.db.net"),
            repo, m2)).as("the task may still declare the library in that build file").isNull();

        assertThat(UndeliverableType.park(server, List.of(), List.of("org.example.app.Missing"),
            repo, m2)).as("a project type stays what it was").contains("which no task delivers");
    }

    // --- fixture --------------------------------------------------------------------------------

    private static String pom(String dependencies) {
        return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>org.x</groupId>"
            + "<artifactId>app</artifactId><version>1</version></parent><artifactId>m</artifactId>"
            + "<dependencies>" + dependencies + "</dependencies></project>";
    }

    private void jar(String group, String artifact, String version, String entry) throws Exception {
        Path dir = m2.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"),
            "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId>"
                + "<artifactId>" + artifact + "</artifactId><version>" + version
                + "</version></project>");
        try (OutputStream out = Files.newOutputStream(dir.resolve(artifact + "-" + version + ".jar"));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(new byte[] {0});
            zip.closeEntry();
        }
    }

    private static Task task(String title, Set<String> writeSet) {
        Task task = new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(), List.of(),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(List.of());
        return task;
    }
}
