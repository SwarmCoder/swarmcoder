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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which source files the code half of a {@code lookup_api} answer shows, on trees this test
 * builds.
 *
 * <p>Two things went wrong on a real reference checkout on 2026-10-12, and both are pinned here
 * so they do not depend on that checkout:
 *
 * <ol>
 *   <li>A question of two words was answered with a file that matched one hump of the first word
 *       in its own short name, ahead of the file that matched both words.</li>
 *   <li>A file the ranking had chosen, an example's own implementation of an interface, was left
 *       out "for room" because about 500 characters had been reserved for a line listing other
 *       files with its name.</li>
 * </ol>
 */
class LibrarianCodeHalfOnASyntheticTreeTest {

    @TempDir
    Path repo;
    @TempDir
    Path framework;
    @TempDir
    Path cache;

    private Librarian librarian() {
        return new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(framework), repo, null, cache);
    }

    private void write(String relative, String content) throws Exception {
        Path file = framework.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Plain classes that match nothing asked here, so that a word is rare or common as in a real tree. */
    private void writeFillers(String folder, String packageName, String... names) throws Exception {
        for (String name : names) {
            write(folder + "/" + name + ".java", """
                package %s;
                /** One %s. */
                public class %s {
                    private long id;
                    public long getId() { return id; }
                }
                """.formatted(packageName, name.toLowerCase(java.util.Locale.ROOT), name));
        }
    }

    /**
     * The file that matches more of the question beats the file that matches one word of it
     * strongly.
     *
     * <p>The tree: one class of text whose short name holds one hump of the question's first
     * word, three classes whose names hold the other hump and nothing else of the question, and
     * one class that holds a hump in its name and declares the method the second word asks for.
     * Every file is under {@code src/main/java}, so every path says "main"; that must count for
     * nothing.
     */
    @Test
    void aFileThatMatchesTwoWordsOfTheQuestionBeatsOneThatMatchesOneWordStrongly() throws Exception {
        write("demo-shared/src/main/java/demo/api/AppText.java", """
            package demo.api;
            /** The words of this app, in every language the app has. */
            @Catalog(baseName = "i18n/app")
            public final class AppText {
                private AppText() { }
            }
            """);
        write("demo-server/src/main/java/demo/server/DemoServer.java", """
            package demo.server;
            /** Starts the backend and waits. */
            public final class DemoServer {
                public static void main(String[] args) {
                    Runtime.start(8080).join();
                }
            }
            """);
        write("demo-server/src/main/java/demo/server/ServerConfig.java", """
            package demo.server;
            /** What the server was configured with. */
            public final class ServerConfig {
                public int port() { return 8080; }
            }
            """);
        write("demo-server/src/main/java/demo/server/ServerBinding.java", """
            package demo.server;
            /** One way of running the server. */
            public interface ServerBinding {
                String name();
            }
            """);
        write("demo-server/src/main/java/demo/server/ServerRuntime.java", """
            package demo.server;
            /** The running server. */
            public final class ServerRuntime {
                public void close() { }
            }
            """);
        writeFillers("demo-core/src/main/java/demo/core", "demo.core",
            "Product", "Order", "Invoice", "Customer", "Shipment", "Ledger", "Basket", "Voucher",
            "Parcel", "Tariff", "Depot", "Carrier");
        Librarian librarian = librarian();

        List<String> ranked = librarian.curator().sourceHits("ServerApp main", 10).stream()
            .map(ReferenceIndex.SrcHit::address).toList();

        assertThat(ranked).isNotEmpty();
        assertThat(ranked.get(0))
            .as("the class with a hump of the name AND the method asked for comes first")
            .endsWith("demo/server/DemoServer.java");
        List<String> shown = librarian.lookupApi("ServerApp main").lines()
            .filter(line -> line.startsWith("## Source: ")).toList();
        assertThat(shown).isNotEmpty();
        assertThat(shown.get(0))
            .as("and it is the first file the answer shows")
            .endsWith("demo/server/DemoServer.java");
    }

    /**
     * A file the ranking chose is not left out to make room for a line of pointers.
     *
     * <p>The tree: four examples, each with a root class and a class that makes the root, all
     * four pairs with the same two names; the interface the maker implements, in a module of its
     * own; and a page long enough for the prose half to take its whole share. The question names
     * one example and its root class.
     */
    @Test
    void aFileTheRankingChoseIsNotLeftOutToMakeRoomForPointers() throws Exception {
        for (String example : List.of("inventory", "chat", "signup", "jobs")) {
            String folder = "examples/" + example + "/" + example
                + "-server/src/main/java/com/demo/example/server/store";
            write(folder + "/DataRoot.java", """
                package com.demo.example.server.store;

                import com.demo.example.model.Entry;

                import java.util.ArrayList;
                import java.util.List;

                public class DataRoot {
                    private List<Entry> entries = new ArrayList<>();
                    private long nextId = 1;

                    public List<Entry> getEntries() {
                        return entries;
                    }

                    public void setEntries(List<Entry> entries) {
                        this.entries = entries;
                    }

                    public long getNextId() {
                        return nextId;
                    }

                    public void setNextId(long nextId) {
                        this.nextId = nextId;
                    }

                    public long takeNextId() {
                        return nextId++;
                    }
                }
                """);
            write(folder + "/DefaultDataRootProvider.java", """
                package com.demo.example.server.store;

                import com.demo.api.store.DataRootProvider;

                /** Makes the first root object of this example. */
                public class DefaultDataRootProvider implements DataRootProvider {
                    @Override
                    public Object createDefaultRoot(String tenantId) {
                        DataRoot root = new DataRoot();
                        root.setNextId(1);
                        return root;
                    }
                }
                """);
        }
        write("api/src/main/java/com/demo/api/store/DataRootProvider.java", """
            package com.demo.api.store;

            /**
             * The interface an application implements to say what its first root object is.
             *
             * <p>Called once, when a database is first created and has no root object yet. What it
             * returns is written as the root of the object graph, and every later start reads that
             * root back instead of calling this again.</p>
             */
            public interface DataRootProvider {
                /**
                 * Creates the first root object for one tenant.
                 *
                 * @param tenantId the tenant the database belongs to
                 * @return the object to keep as the root of the graph
                 */
                Object createDefaultRoot(String tenantId);
            }
            """);
        writeFillers("core/src/main/java/com/demo/core", "com.demo.core",
            "Product", "Order", "Invoice", "Customer", "Shipment", "Ledger", "Basket", "Voucher",
            "Parcel", "Tariff", "Depot", "Carrier", "Route", "Pallet", "Docket", "Manifest");
        write("docs/store-modes.md", """
            # Store modes

            ## Writing services for an embedded store
            """
            + ("An embedded store keeps its data in a directory next to the app, and the main "
                + "class of the app says where that directory is.\n").repeat(18)
            + """

            ## Choosing the store directory
            """
            + ("The store directory is a setting of the embedded store; the app reads it when "
                + "its main class starts.\n").repeat(18));
        Librarian librarian = librarian();

        String answer = librarian.lookupApi(
            "inventory-server main app DataRoot embedded store directory");

        String store = "examples/inventory/inventory-server/src/main/java/com/demo/example/server/store/";
        List<String> shown = answer.lines().filter(line -> line.startsWith("## Source: ")).toList();
        assertThat(answer).as("the prose half took its share").contains("docs/store-modes.md");
        assertThat(shown)
            .as("the root object, from the example the question names")
            .anySatisfy(line -> assertThat(line).endsWith(store + "DataRoot.java"))
            .as("and that example's class that makes it, shown and not only named")
            .anySatisfy(line -> assertThat(line).endsWith(store + "DefaultDataRootProvider.java"));
        assertThat(answer)
            .as("the other files with the name the question gave are still pointed at")
            .contains("[Other files named DataRoot: ");
    }
}
