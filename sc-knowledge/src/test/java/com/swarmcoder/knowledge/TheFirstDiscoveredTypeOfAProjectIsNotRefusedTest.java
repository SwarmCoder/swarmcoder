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
package com.swarmcoder.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project that starts as a skeleton is not refused its first framework-found type (live run
 * 93, 2026-10-08).
 *
 * <p>The run built a search over three modules that held one class each. The server's service
 * and its store provider are found by the framework through an annotation; no source file names
 * them, and the server module held nothing but a {@code main} handing over to a library, so
 * nothing there could teach the reachability check what discovery looks like. It learned one
 * annotation from the shared module and refused the run for the two server types, after a
 * repair round spent on connecting code that was connected.
 *
 * <p>An invented shelf on an invented library ({@code fw}, which is not on any classpath here, as
 * a framework's jar is for the tree reader), so nothing passes because of a name the
 * implementation knows. No model is called and nothing outside this test's {@code @TempDir} is
 * written.
 */
class TheFirstDiscoveredTypeOfAProjectIsNotRefusedTest {

    private static final String SHARED = "shelf-shared/src/main/java/com/shelf/shared/";
    private static final String SERVER = "shelf-server/src/main/java/com/shelf/server/";
    private static final String CLIENT = "shelf-client/src/main/java/com/shelf/client/";

    @TempDir
    Path tree;

    private final Set<String> added = new LinkedHashSet<>();

    @BeforeEach
    void theSkeletonTheRunStartsFrom() throws Exception {
        write("pom.xml", "<project><modules><module>shelf-shared</module>"
            + "<module>shelf-server</module><module>shelf-client</module></modules></project>");
        for (String module : List.of("shelf-shared", "shelf-server", "shelf-client")) {
            write(module + "/pom.xml", "<project><artifactId>" + module + "</artifactId></project>");
        }
        write(SHARED + "Greeting.java", """
            package com.shelf.shared;
            import fw.Stored;
            @Stored
            public class Greeting { public String text; }
            """);
        write(SERVER + "ServerMain.java", """
            package com.shelf.server;
            public class ServerMain {
                public static void main(String[] args) { fw.Server.start(8080); }
            }
            """);
        write(CLIENT + "ClientMain.java", """
            package com.shelf.client;
            public class ClientMain {
                public static void main(String[] args) { }
            }
            """);
    }

    /** What run 93 added, in this shelf's names. */
    private void theRunAddsASearch() throws Exception {
        add(SHARED + "ShelfService.java", """
            package com.shelf.shared;
            public interface ShelfService { java.util.List<String> search(String term); }
            """);
        write(CLIENT + "ClientMain.java", """
            package com.shelf.client;
            public class ClientMain {
                public static void main(String[] args) { new ShelfScreen().build(); }
            }
            """);
        add(CLIENT + "ShelfScreen.java", """
            package com.shelf.client;
            import com.shelf.shared.ShelfService;
            public class ShelfScreen {
                ShelfService service;
                public void build() { }
            }
            """);
        add(SERVER + "ShelfServiceImpl.java", """
            package com.shelf.server;
            import com.shelf.server.store.Root;
            import com.shelf.shared.ShelfService;
            import fw.Managed;
            @Managed
            public class ShelfServiceImpl implements ShelfService {
                public static final class ListAll {
                    public java.util.List<String> on(Object root) { return ((Root) root).titles; }
                }
                public java.util.List<String> search(String term) { return new ListAll().on(null); }
            }
            """);
        add(SERVER + "store/Root.java", """
            package com.shelf.server.store;
            public class Root { public java.util.List<String> titles = new java.util.ArrayList<>(); }
            """);
        add(SERVER + "store/RootProvider.java", """
            package com.shelf.server.store;
            import fw.Managed;
            @Managed
            public class RootProvider implements fw.Provider {
                public Object create() { return new Root(); }
            }
            """);
    }

    @Test
    void theServiceAndItsProviderAreTakenAsFoundAndTheRunSaysSo() throws Exception {
        theRunAddsASearch();

        ReachableCode.Graph graph = ReachableCode.of(tree);
        ReachableCode.Finding finding = graph.judge(added::contains);

        assertThat(graph.determined()).as(graph.note()).isTrue();
        assertThat(finding.discovered()).as("all the skeleton could teach").containsExactly("@Stored");
        assertThat(finding.status()).as(String.valueOf(finding.orphans()))
            .isEqualTo(ReachableCode.Status.ALL_REACHABLE);
        assertThat(finding.takenAsFound()).extracting(ReachableCode.Presumed::file)
            .containsExactly(SERVER + "ShelfServiceImpl.java", SERVER + "store/RootProvider.java");
        assertThat(finding.takenAsFound().get(0).types())
            .as("the outer type names the file, not the first nested one (run 93 said ListAll)")
            .startsWith("ShelfServiceImpl");
        assertThat(finding.takenAsFound().get(0).annotations()).containsExactly("Managed");
        assertThat(ReachableCode.objection(finding, "This run adds")).isNull();
        assertThat(ReachableCode.takenAsFoundNote(finding))
            .startsWith("Not established: whether the application reaches ShelfServiceImpl "
                + "(@Managed, " + SERVER + "ShelfServiceImpl.java), RootProvider (@Managed, ")
            .contains("taken as found by the framework and not refused");
    }

    @Test
    void anAddedClassWithNoUserAndNoAnnotationIsStillRefusedThere() throws Exception {
        theRunAddsASearch();
        add(SERVER + "Leftover.java", """
            package com.shelf.server;
            public class Leftover { }
            """);
        // An annotation the run made up for its own class is nothing a framework looks for.
        add(SERVER + "Mine.java", """
            package com.shelf.server;
            public @interface Mine { }
            """);
        add(SERVER + "Marked.java", """
            package com.shelf.server;
            @Mine
            public class Marked { }
            """);

        ReachableCode.Finding finding = ReachableCode.of(tree).judge(added::contains);

        assertThat(finding.status()).isEqualTo(ReachableCode.Status.UNREACHABLE);
        assertThat(finding.orphans()).extracting(ReachableCode.Orphan::file)
            .containsExactly(SERVER + "Leftover.java", SERVER + "Marked.java",
                SERVER + "Mine.java");
        assertThat(ReachableCode.objection(finding, "This run adds"))
            .startsWith("This run adds production code that nothing in the application can "
                + "reach: Leftover (" + SERVER + "Leftover.java)");
        assertThat(finding.takenAsFound()).hasSize(2);
    }

    @Test
    void whereEarlierCodeShowsHowTypesAreFoundAnotherAnnotationIsNotBelieved() throws Exception {
        // The server already has a type the framework finds: discovery there is known.
        write(SERVER + "Clock.java", """
            package com.shelf.server;
            import fw.Managed;
            @Managed
            public class Clock { }
            """);
        theRunAddsASearch();
        add(SERVER + "Export.java", """
            package com.shelf.server;
            import fw.Described;
            @Described
            public class Export { }
            """);

        ReachableCode.Finding finding = ReachableCode.of(tree).judge(added::contains);

        assertThat(finding.discovered()).contains("@Managed", "@Stored");
        assertThat(finding.orphans()).extracting(ReachableCode.Orphan::file)
            .as("the service passes by what was learned; the other annotation teaches nothing")
            .containsExactly(SERVER + "Export.java");
        assertThat(finding.takenAsFound()).isEmpty();
        assertThat(ReachableCode.takenAsFoundNote(finding)).isEmpty();
    }

    @Test
    void aPlanForTheFirstServiceNeedNotTouchTheEntryPoint() {
        ReachableCode.Graph graph = ReachableCode.of(tree);
        java.util.function.Predicate<String> exists =
            path -> Files.isRegularFile(tree.resolve(path));
        assertThat(graph.showsNoDiscoveryIn("shelf-server/src/main/java/")).isTrue();
        assertThat(graph.showsNoDiscoveryIn("shelf-shared/src/main/java/")).isFalse();

        ReachableCode.PlannedTask named = new ReachableCode.PlannedTask("The service",
            Set.of(SERVER + "ShelfServiceImpl.java", "shelf-server/pom.xml"),
            "Add ShelfServiceImpl, a @Managed implementation of ShelfService.");
        assertThat(ReachableCode.planObjection(graph, List.of(named), exists))
            .as("run 93's plan put the server's main in the write set with nothing to change "
                + "there, and the worker changed its whitespace").isNull();

        ReachableCode.PlannedTask unnamed = new ReachableCode.PlannedTask("The service",
            Set.of(SERVER + "ShelfServiceImpl.java", "shelf-server/pom.xml"),
            "Add ShelfServiceImpl.\n@param term what to search for");
        assertThat(ReachableCode.planObjection(graph, List.of(unnamed), exists))
            .startsWith("the plan adds new production source files (" + SERVER
                + "ShelfServiceImpl.java)");
    }

    private void add(String path, String text) throws Exception {
        write(path, text);
        added.add(path);
    }

    private void write(String path, String text) throws Exception {
        Path file = tree.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
