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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A class a server container finds on the classpath is reached although no code names it, and a
 * class nothing finds is still refused (DEVELOPER_CORRECTIONS section 103; live local build of
 * 2026-10-14, stopped twice for a WebSocket deployment configuration).
 *
 * <p>An invented server module. Its older code is found by an annotation the project declares
 * itself, as the module of the live build was, so the added classes are judged in a source root
 * that already shows a kind of discovery - the case that had no rule. No model is called and
 * nothing outside this test's {@code @TempDir} is written.
 */
class ATypeAContainerFindsIsReachedTest {

    private static final String SERVER = "app-server/src/main/java/com/app/server/";

    @TempDir
    Path tree;

    @TempDir
    Path elsewhere;

    @BeforeEach
    void theServerAsItWasBeforeTheRun() throws Exception {
        write(tree, "pom.xml", "<project><modules><module>app-server</module></modules></project>");
        write(tree, "app-server/pom.xml", "<project><artifactId>app-server</artifactId></project>");
        write(tree, "app-server/src/main/resources/META-INF/beans.xml", "<beans/>");
        write(tree, SERVER + "Launcher.java", """
            package com.app.server;
            public class Launcher {
                public static void main(String[] args) { }
            }
            """);
        write(tree, SERVER + "Managed.java", """
            package com.app.server;
            public @interface Managed { }
            """);
        write(tree, SERVER + "GreetingService.java", """
            package com.app.server;
            @Managed
            public class GreetingService {
                public String greet() { return "hello"; }
            }
            """);
    }

    /** The class of the live build, with the engine it deploys, and three classes nothing finds. */
    private Set<String> theRunAdds(String configSource) throws Exception {
        write(tree, SERVER + "WebSocketConfig.java", configSource);
        write(tree, SERVER + "EngineEndpoint.java", """
            package com.app.server;
            public class EngineEndpoint {
                public String open() { return "open"; }
            }
            """);
        write(tree, SERVER + "Hook.java", """
            package com.app.server;
            public interface Hook { void fire(); }
            """);
        write(tree, SERVER + "AuditHook.java", """
            package com.app.server;
            public class AuditHook implements Hook {
                public void fire() { }
            }
            """);
        write(tree, SERVER + "Leftover.java", """
            package com.app.server;
            public class Leftover {
                public String text() { return "nobody asks"; }
            }
            """);
        return Set.of(SERVER + "WebSocketConfig.java", SERVER + "EngineEndpoint.java",
            SERVER + "Hook.java", SERVER + "AuditHook.java", SERVER + "Leftover.java");
    }

    private static final String CONFIG_AS_THE_LIVE_BUILD_WROTE_IT = """
        package com.app.server;
        import jakarta.websocket.Endpoint;
        import jakarta.websocket.server.ServerEndpointConfig;
        import java.util.Collections;
        import java.util.Set;
        public class WebSocketConfig implements jakarta.websocket.server.ServerApplicationConfig {
            public Set<ServerEndpointConfig> getEndpointConfigs(Set<Class<? extends Endpoint>> c) {
                return Collections.emptySet();
            }
            public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> scanned) {
                return Collections.singleton(EngineEndpoint.class);
            }
        }
        """;

    @Test
    void aWebSocketDeploymentConfigurationIsReachedAndWhatNothingFindsIsNot() throws Exception {
        Set<String> added = theRunAdds(CONFIG_AS_THE_LIVE_BUILD_WROTE_IT);

        ReachableCode.Graph graph = ReachableCode.of(tree);
        ReachableCode.Finding finding = graph.judge(added::contains);

        assertThat(graph.determined()).as(graph.note()).isTrue();
        assertThat(finding.orphans()).extracting(ReachableCode.Orphan::file)
            .as("the configuration the container scans for, and the endpoint class it returns, "
                + "are reached; an implementation of the project's own interface that nothing "
                + "uses, that interface, and a plain unused class are not")
            .containsExactly(SERVER + "AuditHook.java", SERVER + "Hook.java",
                SERVER + "Leftover.java");
        assertThat(finding.status()).isEqualTo(ReachableCode.Status.UNREACHABLE);
        assertThat(finding.takenAsFound()).as("established, not believed").isEmpty();
        assertThat(graph.whyReached(SERVER + "WebSocketConfig.java"))
            .startsWith("reached: ").contains("found by the container (ServerApplicationConfig)");
        assertThat(graph.whyReached(SERVER + "EngineEndpoint.java"))
            .isEqualTo("reached: used by " + SERVER + "WebSocketConfig.java");
        assertThat(ReachableCode.objection(finding, "This run adds"))
            .doesNotContain("WebSocketConfig (")
            .contains("found by the framework (@Managed, ServerApplicationConfig)");
    }

    @Test
    void theSameWithTheInterfaceImportedAndInTheOlderNamespace() throws Exception {
        Set<String> added = theRunAdds("""
            package com.app.server;
            import javax.websocket.server.ServerApplicationConfig;
            import java.util.Collections;
            import java.util.Set;
            public class WebSocketConfig implements ServerApplicationConfig {
                public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> scanned) {
                    return Collections.singleton(EngineEndpoint.class);
                }
            }
            """);

        ReachableCode.Finding finding = ReachableCode.of(tree).judge(added::contains);

        assertThat(finding.orphans()).extracting(ReachableCode.Orphan::file)
            .containsExactly(SERVER + "AuditHook.java", SERVER + "Hook.java",
                SERVER + "Leftover.java");
    }

    @Test
    void anInterfaceOfTheSameSimpleNameThatIsNotThePlatformsExplainsNothing() throws Exception {
        Set<String> added = new java.util.HashSet<>(theRunAdds("""
            package com.app.server;
            public class WebSocketConfig implements ServerApplicationConfig {
                public Class<?> endpoint() { return EngineEndpoint.class; }
            }
            """));
        write(tree, SERVER + "ServerApplicationConfig.java", """
            package com.app.server;
            public interface ServerApplicationConfig { Class<?> endpoint(); }
            """);
        added.add(SERVER + "ServerApplicationConfig.java");

        ReachableCode.Finding finding = ReachableCode.of(tree).judge(added::contains);

        assertThat(finding.orphans()).extracting(ReachableCode.Orphan::file)
            .as("decided by the type's full name, not by a word in it")
            .contains(SERVER + "WebSocketConfig.java", SERVER + "EngineEndpoint.java");
    }

    // -------------------------------------------------------------------------------------------

    /** A library that asks the container for every class that is one of its {@code Module}s. */
    private Path aLibraryThatAsksTheContainerForItsModules() throws Exception {
        Path sources = elsewhere.resolve("lib-src");
        write(sources, "jakarta/servlet/annotation/HandlesTypes.java", """
            package jakarta.servlet.annotation;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface HandlesTypes { Class<?>[] value(); }
            """);
        write(sources, "jakarta/websocket/server/ServerEndpoint.java", """
            package jakarta.websocket.server;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface ServerEndpoint { String value(); }
            """);
        write(sources, "lib/spi/Module.java", """
            package lib.spi;
            public interface Module { void start(); }
            """);
        write(sources, "lib/spi/Unasked.java", """
            package lib.spi;
            public interface Unasked { }
            """);
        write(sources, "lib/boot/Initializer.java", """
            package lib.boot;
            @jakarta.servlet.annotation.HandlesTypes({lib.spi.Module.class})
            public class Initializer { }
            """);
        Path classes = elsewhere.resolve("lib-classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("these tests need a JDK").isNotNull();
        List<String> arguments = new ArrayList<>(List.of("-d", classes.toString()));
        try (Stream<Path> walk = Files.walk(sources)) {
            walk.filter(Files::isRegularFile).forEach(file -> arguments.add(file.toString()));
        }
        assertThat(compiler.run(null, null, null, arguments.toArray(String[]::new))).isZero();
        write(classes, "META-INF/services/jakarta.servlet.ServletContainerInitializer",
            "# what the container loads\nlib.boot.Initializer\n");
        return classes;
    }

    @Test
    void whatALibraryAsksTheContainerForIsReadFromItsJarAndFromAClassFolder() throws Exception {
        Path classes = aLibraryThatAsksTheContainerForItsModules();
        Path jar = elsewhere.resolve("lib.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(classes.relativize(file).toString()
                    .replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }

        assertThat(ContainerDiscovery.handledTypes(List.of(jar))).containsExactly("lib.spi.Module");
        assertThat(ContainerDiscovery.handledTypes(List.of(classes)))
            .containsExactly("lib.spi.Module");
        assertThat(ContainerDiscovery.handledTypes(List.of(elsewhere.resolve("no-such.jar"),
            tree.resolve("pom.xml")))).as("unreadable entries contribute nothing").isEmpty();
    }

    @Test
    void aClassOfATypeALibraryAsksTheContainerForIsReached() throws Exception {
        // The parser's classpath holds every target/classes of the tree: the library stands
        // there as a built module of which the tree has no source.
        Path classes = aLibraryThatAsksTheContainerForItsModules();
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path to = tree.resolve("lib/target/classes").resolve(classes.relativize(file));
                Files.createDirectories(to.getParent());
                Files.copy(file, to);
            }
        }
        write(tree, SERVER + "ReportsModule.java", """
            package com.app.server;
            public class ReportsModule implements lib.spi.Module {
                public void start() { }
            }
            """);
        write(tree, SERVER + "Bystander.java", """
            package com.app.server;
            public class Bystander implements lib.spi.Unasked { }
            """);
        write(tree, SERVER + "ChatSocket.java", """
            package com.app.server;
            @jakarta.websocket.server.ServerEndpoint("/chat")
            public class ChatSocket { }
            """);
        Set<String> added = Set.of(SERVER + "ReportsModule.java", SERVER + "Bystander.java",
            SERVER + "ChatSocket.java");

        ReachableCode.Graph graph = ReachableCode.of(tree);
        ReachableCode.Finding finding = graph.judge(added::contains);

        assertThat(graph.determined()).as(graph.note()).isTrue();
        assertThat(finding.orphans()).extracting(ReachableCode.Orphan::file)
            .as("a library type no initializer asks for explains nothing")
            .containsExactly(SERVER + "Bystander.java");
        assertThat(graph.whyReached(SERVER + "ReportsModule.java"))
            .startsWith("reached: ").contains("found by the container (Module)");
        assertThat(graph.whyReached(SERVER + "ChatSocket.java"))
            .startsWith("reached: ").contains("found by the container (@ServerEndpoint)");
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
