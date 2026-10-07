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
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 89 (DEVELOPER_CORRECTIONS section 64): a story about what a screen shows was planned
 * as a change to one server file, because the screen is drawn in the browser from a descriptor
 * the server's service returns. The write set named no screen and no journey was asked for.
 *
 * <p>The same shape, invented: a client module that draws a list from a descriptor it gets
 * through a service interface of a shared module; the server implements the interface. The
 * object graph says the client uses what the server file is. No model, no names known to the
 * implementation.
 */
class BrowserCodeUsingWhatATaskWritesIsFoundOnTheGraphTest {

    private static final String SHARED = "depot-shared/src/main/java/com/depot/";
    private static final String CLIENT = "depot-client/src/main/java/com/depot/client/";
    private static final String SERVER = "depot-server/src/main/java/com/depot/server/";

    private static final Predicate<String> IN_THE_BROWSER = file -> file.startsWith("depot-client/");

    @TempDir
    Path tree;

    @BeforeEach
    void theDepot() throws Exception {
        write("pom.xml", "<project><modules><module>depot-shared</module>"
            + "<module>depot-client</module><module>depot-server</module></modules></project>");
        write("depot-shared/pom.xml", "<project><artifactId>depot-shared</artifactId></project>");
        write("depot-client/pom.xml", "<project><artifactId>depot-client</artifactId></project>");
        write("depot-server/pom.xml", "<project><artifactId>depot-server</artifactId></project>");
        write(SHARED + "ShelfDescriptor.java", """
            package com.depot;
            import java.util.List;
            public class ShelfDescriptor {
                public List<String> boxes;
            }
            """);
        write(SHARED + "ShelfService.java", """
            package com.depot;
            public interface ShelfService {
                ShelfDescriptor describeShelf();
            }
            """);
        write(CLIENT + "ShelfScreen.java", """
            package com.depot.client;
            import com.depot.ShelfDescriptor;
            import com.depot.ShelfService;
            public class ShelfScreen {
                private final ShelfService service;
                public ShelfScreen(ShelfService service) { this.service = service; }
                public String draw() {
                    ShelfDescriptor shelf = service.describeShelf();
                    return String.join(" | ", shelf.boxes);
                }
            }
            """);
        write(CLIENT + "ClientMain.java", """
            package com.depot.client;
            public class ClientMain {
                public static void main(String[] args) { new ShelfScreen(null).draw(); }
            }
            """);
        write(SERVER + "ShelfServiceImpl.java", """
            package com.depot.server;
            import com.depot.ShelfDescriptor;
            import com.depot.ShelfService;
            import java.util.List;
            public class ShelfServiceImpl implements ShelfService {
                private final Ledger ledger = new Ledger();
                public ShelfDescriptor describeShelf() {
                    ShelfDescriptor shelf = new ShelfDescriptor();
                    shelf.boxes = List.of("a", "b", String.valueOf(ledger.count()));
                    return shelf;
                }
            }
            """);
        write(SERVER + "Ledger.java", """
            package com.depot.server;
            public class Ledger {
                public int count() { return 2; }
            }
            """);
        write(SERVER + "ServerMain.java", """
            package com.depot.server;
            public class ServerMain {
                public static void main(String[] args) { new ShelfServiceImpl().describeShelf(); }
            }
            """);
    }

    @Test
    void theServersImplementationIsUsedByTheClientThroughTheSharedInterface() {
        ReachableCode.Graph graph = ReachableCode.of(tree);
        assertThat(graph.determined()).as(graph.note()).isTrue();

        assertThat(graph.usersThroughItsTypes(SERVER + "ShelfServiceImpl.java", IN_THE_BROWSER))
            .as("the one file run 89's plan wrote: no client file names it, the client calls it")
            .containsExactly(CLIENT + "ShelfScreen.java uses ShelfService, which "
                + "ShelfServiceImpl is");
    }

    @Test
    void aSharedTypeTheClientNamesIsUsedDirectlyAndServerInternalsAreNot() {
        ReachableCode.Graph graph = ReachableCode.of(tree);

        assertThat(graph.usersThroughItsTypes(SHARED + "ShelfDescriptor.java", IN_THE_BROWSER))
            .containsExactly(CLIENT + "ShelfScreen.java uses ShelfDescriptor");
        assertThat(graph.usersThroughItsTypes(SERVER + "Ledger.java", IN_THE_BROWSER))
            .as("behind the service: the graph does not say a screen shows it").isEmpty();
        assertThat(graph.usersThroughItsTypes(SERVER + "ServerMain.java", IN_THE_BROWSER))
            .isEmpty();
        assertThat(graph.usersThroughItsTypes(SERVER + "NotThereYet.java", IN_THE_BROWSER))
            .as("a file the plan is about to add is not in the start tree").isEmpty();
        assertThat(graph.usersThroughItsTypes(CLIENT + "ShelfScreen.java",
            file -> file.startsWith("depot-server/")))
            .as("the other way round nothing uses the screen").isEmpty();
    }

    private void write(String relative, String content) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
