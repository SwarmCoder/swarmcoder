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

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.domain.WorkflowKind;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

import com.swarmcoder.app.config.SwarmConfig;
import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.RolesConfig;
import com.swarmcoder.app.config.CloudConfig;
import com.swarmcoder.app.config.CloudEndpoint;
import com.swarmcoder.app.config.AgentModelConfig;
import com.zeroz4j.server.Zeroz4jServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // The on-ramp runs BEFORE anything is built. It answers "how do I build this repository",
        // which has to be answerable without a store, a project, a model endpoint or a console —
        // it is the first thing anybody does with a codebase SwarmCoder has never seen.
        if (args.length > 0 && "onramp".equals(args[0])) {
            System.exit(OnrampCli.run(args));
            return;
        }
        try {
            // stdout and stderr are NOT redirected any more. They were, to keep third-party
            // chatter off the Lanterna TUI's terminal — but the TUI is gone, the Console replaced
            // it, and the redirect survived it. The cost was severe: the process could die during
            // startup and print nothing whatsoever, because logback's console appender follows
            // System.out and had been pointed at a file the operator did not know existed.
            Path logs = Path.of(System.getProperty("user.home"), ".swarmcoder", "logs");
            Files.createDirectories(logs);

            log.info("Starting SwarmCoder... (full log: {})", logs.resolve("swarmcoder.log"));

            // A machine with no settings file is a FIRST RUN, not a failure. The loader inside
            // writes a starter file and says so, so everything below happens on a clean install
            // too — which is the only way the window can come up and explain what is still needed.
            DependencyGraph graph = new DependencyGraph();
            EnvironmentChecks.logWarnings(graph.config);

            // Embedded Console (docs/OBSERVABILITY_DESIGN.md) — the primary interface.
            Zeroz4jServer console = graph.startConsole();
            if (console != null) {
                log.info("SwarmCoder is at http://localhost:{}/ — open that in your browser.",
                    console.port());
            }

            // The MCP server, when the operator has turned it on (settings: mcpApi.enabled). It
            // lets an outside agent — Claude Code, an IDE, CI — ask what a run is doing, which
            // until now could only be answered by reading the log file by hand. Off by default,
            // and after the console because it adapts the console's own services.
            String mcpUrl = graph.startMcpServer();
            if (mcpUrl != null) {
                log.info("An agent on THIS MACHINE can watch SwarmCoder at {} — connect it with: "
                    + "claude mcp add --transport http swarmcoder {}", mcpUrl, mcpUrl);
            }

            // The last word at startup: what is still missing and where to do it. Last on purpose.
            // It is what a first-time reader is left looking at, and on a machine with nothing set
            // up it is the only instruction they have. Silent when nothing is outstanding.
            EnvironmentChecks.logSetupSummary(graph.config,
                console == null ? null : console.port(), graph.sandboxBlocksRuns());

            graph.setEventLogger(log::info);

            // Crash-resume (rule R3): continue any run that was mid-flight when the last process
            // died — including one that was waiting out a model-endpoint outage, which picks the
            // wait up again from the stage it had reached. Each run goes to ITS OWN project's
            // engine: this used to drive every unfinished run in the store through the default
            // project's engine, which meant a second project's run was continued against the first
            // project's repository, git service and locked modules.
            //
            // Stories left saying "building now" by the stopped process are freed inside, AFTER the
            // resume, so a story whose run was genuinely revived is left alone.
            graph.resumeUnfinishedRuns();

            // The watcher that lets the operator plan a set of stories, press go and go to bed.
            // Started unconditionally: while unattended running is switched off it does nothing on
            // every check, and starting it only when the setting is on would mean the setting could
            // not be turned on without restarting.
            graph.startUnattendedPilot();

            // Keep main thread alive
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
        } catch (Throwable e) {
            // Throwable, not Exception: an incompatible store schema surfaces as an Error from
            // EclipseStore, and catching only Exception let it kill the process with a stack trace
            // going nowhere. A start-up failure has to say something the operator can act on.
            log.error("SwarmCoder failed to start: {}", e.toString(), e);
            System.exit(1);
        }
    }
}