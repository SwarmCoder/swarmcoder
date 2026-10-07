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
package com.swarmcoder.app;

import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.SwarmConfig;
import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.ReadinessSignals;
import com.zeroz4j.server.Zeroz4jServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A genuinely cold start: no settings file, an empty home, nothing installed.
 *
 * <h2>What this used to do</h2>
 *
 * <p>Exit. {@code ConfigLoader} threw {@code Config file not found at …}, {@code Main} logged the
 * exception and called {@code System.exit(1)}. The whole of a first run was two log lines and a
 * three-frame stack trace, and there was no path from a clean checkout to a running console at all.
 * Nobody had met it because everyone who had ever run SwarmCoder already had a settings file from
 * an earlier era.
 *
 * <h2>Why it is written this way</h2>
 *
 * <p>Not a unit test of the loader with a null argument. It builds the real {@link DependencyGraph}
 * — the composition root {@code Main} builds — from a home directory that is genuinely empty, and
 * then installs the real console context, which is the step that decides what the operator is told.
 *
 * <p>Two things it will not do. It never touches the real {@code ~/.swarmcoder}: the home is a
 * temporary folder and {@link #assertGenuinelyCold} fails if it is not, so this cannot quietly pass
 * by finding the developer's own configuration. And it does not bind the configured port — other
 * people are running SwarmCoder on the machine this is developed on, and taking their port to prove
 * a point is not worth it. {@link #theWindowActuallyComesUpFromACleanStart()} proves the server
 * side separately, on a port the operating system chooses.
 */
class FirstRunColdStartTest {

    /**
     * The one that matters: nothing on the machine, and the application comes up anyway.
     */
    @Test
    void aMachineWithNothingOnItStartsAndSaysWhatItNeeds(@TempDir Path home) throws Exception {
        assertGenuinelyCold(home);

        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        DependencyGraph graph = null;
        try {
            // The real startup path, from nothing. This used to throw.
            graph = new DependencyGraph();

            // 1. A settings file was made, where the documentation says it lives.
            Path config = home.resolve(".swarmcoder/config.yaml");
            assertThat(config)
                .describedAs("a first run writes a starter settings file rather than refusing")
                .exists();
            String written = Files.readString(config);
            assertThat(written)
                .describedAs("the file explains itself: a person is expected to open it")
                .contains("# SwarmCoder settings.");

            // 2. It opens a window. Without a port there is no interface at all, and therefore no
            //    way for the operator to be told anything — this is the one value with a safe
            //    default, so the starter file sets it.
            assertThat(graph.config.consolePort())
                .describedAs("a first run has somewhere to look")
                .isEqualTo(ConfigLoader.DEFAULT_CONSOLE_PORT);

            // 3. It does NOT guess a repository. SwarmCoder makes branches and writes files in the
            //    folder it is pointed at, so a default here is damage to a folder nobody chose.
            assertThat(graph.config.repoPath())
                .describedAs("no folder is guessed for the operator")
                .isNull();

            // 4. What the operator is told at the end of startup, word for word. Docker is asked
            //    about for real: a machine that has none must be told so, and one that has it must
            //    not be sent chasing a problem it does not have.
            List<String> outstanding = EnvironmentChecks.outstandingSetup(
                graph.config, graph.config.consolePort(), graph.sandboxBlocksRuns());
            assertThat(outstanding.get(0)).isEqualTo(
                "Point SwarmCoder at your code. It has no folder to work in yet, so it can read "
                    + "and plan but cannot build. In the window: Setup, then \"Project settings\".");
            assertThat(outstanding.get(1)).isEqualTo(
                "Tell SwarmCoder which model to think with. It has no model server address yet. "
                    + "In the window: Setup, then \"Models & budgets\".");
            assertThat(outstanding).hasSize(graph.sandboxBlocksRuns() ? 3 : 2);
            if (graph.sandboxBlocksRuns()) {
                assertThat(outstanding.get(2)).startsWith("Install Docker Desktop and start it.");
            }

            // Every sentence is written for someone who has never seen the inside of this program:
            // no setting names, no class names, no document references.
            for (String step : outstanding) {
                assertThat(step)
                    .describedAs("first-run instructions carry no internal names")
                    .doesNotContain("repoPath").doesNotContain("roles.")
                    .doesNotContain("sandbox.required").doesNotContain(".md");
            }

            // 5. And what the window itself says, from the console's own reading of the machine.
            //    Installing the context is what publishes readiness; the port only decides where it
            //    is read. Every sentence here is rendered verbatim by the setup surface.
            graph.installConsoleContext();
            ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
            assertThat(readiness).isNotNull();
            assertThat(readiness.canRun())
                .describedAs("nothing can be built yet, and the window knows it")
                .isFalse();
            assertThat(readiness.runBlocker())
                .describedAs("the reason is named, not merely implied")
                .isNotBlank();
            assertThat(readiness.nextStep())
                .describedAs("one concrete next step, which is the whole point of the surface")
                .isNotBlank();
        } finally {
            if (graph != null) {
                graph.close();
            }
            System.setProperty("user.home", realHome);
        }
    }

    /**
     * The second start finds the file it wrote and leaves it alone.
     *
     * <p>Rewriting it would be worse than the original crash: the operator's first act after a cold
     * start is to fill this file in, and a starter file that reappears eats that work silently.
     */
    @Test
    void theSecondStartKeepsWhatTheOperatorWroteInTheFirst(@TempDir Path home) throws Exception {
        assertGenuinelyCold(home);

        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            ConfigLoader.loadDefaultConfig();
            Path config = home.resolve(".swarmcoder/config.yaml");
            Files.writeString(config, "consolePort: 9091\nrepoPath: " + slash(home) + "/code\n");

            SwarmConfig second = ConfigLoader.loadDefaultConfig();
            assertThat(second.consolePort()).isEqualTo(9091);
            assertThat(second.repoPath()).isEqualTo(home.toString().replace('\\', '/') + "/code");
            assertThat(ConfigLoader.writeStarterConfig(config))
                .describedAs("an existing file is never overwritten")
                .isFalse();
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    /**
     * The window really does bind and serve, starting from a home with nothing in it.
     *
     * <p>On a port the operating system picks, because this machine has other people's SwarmCoder
     * on the configured one. Everything else is the cold path: no settings file, the starter file
     * written by the loader, the real graph, the real {@code startConsole()}.
     */
    @Test
    void theWindowActuallyComesUpFromACleanStart(@TempDir Path home) throws Exception {
        assertGenuinelyCold(home);

        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        DependencyGraph graph = null;
        Zeroz4jServer console = null;
        try {
            ConfigLoader.loadDefaultConfig();
            // The one edit a newcomer whose port is already taken would make by hand. 0 asks the
            // operating system for a free one, which is the only safe choice on a shared machine.
            Path config = home.resolve(".swarmcoder/config.yaml");
            Files.writeString(config, Files.readString(config)
                .replace("consolePort: " + ConfigLoader.DEFAULT_CONSOLE_PORT, "consolePort: 0"));

            graph = new DependencyGraph();
            console = graph.startConsole();
            assertThat(console)
                .describedAs("the console starts on a machine where nothing else is set up")
                .isNotNull();
            assertThat(console.port()).isPositive();
        } finally {
            if (console != null) {
                console.close();
            }
            if (graph != null) {
                graph.close();
            }
            System.setProperty("user.home", realHome);
        }
    }

    /**
     * A settings file with no port left the process running invisibly and saying nothing.
     *
     * <p>The starter file always sets one, so this is only reachable by hand-editing — but that is
     * exactly the person who most needs telling, because with no window there is nowhere else the
     * message could appear.
     */
    @Test
    void aSettingsFileWithNoWindowSaysSoFirst() {
        List<String> outstanding = EnvironmentChecks.outstandingSetup(
            new SwarmConfig(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null),
            null, false);
        assertThat(outstanding.get(0))
            .describedAs("no window means no other place to be told anything, so it comes first")
            .startsWith("Open a window to work in.")
            .contains("consolePort: " + ConfigLoader.DEFAULT_CONSOLE_PORT);
    }

    /** The starter file is valid settings, not just prose. */
    @Test
    void theStarterFileParsesAndDecidesOnlyTheWindow(@TempDir Path home) throws Exception {
        assertGenuinelyCold(home);
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            SwarmConfig config = ConfigLoader.loadDefaultConfig();
            assertThat(config.consolePort()).isEqualTo(ConfigLoader.DEFAULT_CONSOLE_PORT);
            assertThat(config.repoPath()).isNull();
            assertThat(config.roles()).describedAs("no model endpoint is invented").isNull();
            assertThat(config.contextPaths()).isEmpty();
            assertThat(ConfigLoader.starterConfigText())
                .describedAs("the address in the file is the address the window answers on")
                .contains("http://localhost:" + ConfigLoader.DEFAULT_CONSOLE_PORT + "/");
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    /**
     * Fails unless this really is a machine with nothing on it.
     *
     * <p>Without this the whole class passes on any developer's workstation by finding the settings
     * they already have — which is exactly how a defect this size survived for months.
     */
    private static void assertGenuinelyCold(Path home) throws Exception {
        assertThat(home.toString())
            .describedAs("the real home is never read or written by this test")
            .isNotEqualTo(System.getProperty("user.home"));
        try (Stream<Path> entries = Files.list(home)) {
            assertThat(entries.toList())
                .describedAs("a cold start means an EMPTY home directory")
                .isEmpty();
        }
        assertThat(home.resolve(".swarmcoder/config.yaml")).doesNotExist();
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
