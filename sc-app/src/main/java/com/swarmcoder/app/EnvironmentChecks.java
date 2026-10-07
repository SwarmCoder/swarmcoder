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

import com.swarmcoder.app.config.AgentModelConfig;
import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.RolesConfig;
import com.swarmcoder.app.config.SwarmConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What this machine is missing, said in words the person reading them can act on.
 *
 * <p>Two jobs, and they are different. {@link #logWarnings} reports things that are true of the
 * machine and will not change while it runs. {@link #logSetupSummary} is the last thing printed at
 * startup: the short list of what is still outstanding before SwarmCoder can build anything, and
 * where to do each one.
 *
 * <h2>Why the wording changed</h2>
 *
 * <p>These lines used to be written for whoever had just made the decision they describe. They
 * quoted document section numbers ("DEVELOPER_CORRECTIONS.md §7 Q1"), used the settings file's own
 * key names as nouns, and named a filesystem mount by its protocol. A first-time reader has none of
 * that. Startup output is operator-facing text and follows the same rule as the screens
 * (CONSOLE_UX_V3.md §5 rule 3): short sentences, everyday words, no internal names.
 *
 * <p>The summary lists only what is MISSING. A list that always prints something is a list nobody
 * reads; on a fully configured machine it says nothing at all.
 */
public final class EnvironmentChecks {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentChecks.class);

    private EnvironmentChecks() {}

    public static void logWarnings(SwarmConfig config) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            log.warn("SwarmCoder is running on Windows. It works, but builds are much slower here "
                + "than on Linux. If they feel too slow, move your code — and SwarmCoder itself — "
                + "inside the Linux system that Windows can run for you.");
        }
        String repoPath = config == null ? null : config.repoPath();
        if (repoPath != null && (repoPath.startsWith("/mnt/") || repoPath.matches("^[A-Za-z]:.*"))) {
            log.warn("Your code is on a Windows drive ({}). Builds run several times slower from "
                + "there. Keeping it on the Linux side makes them fast again.", repoPath);
        }
        if (config != null && config.overnight() != null && config.overnight().enabled()) {
            log.warn("Unattended running is ON. While it is on, SwarmCoder will accept a story by "
                + "itself when every check that story promised was proved by a test that really "
                + "ran, put its code with the rest of the project, and start the next story that "
                + "was waiting for it - up to {} at a time. Anything that is not clearly finished "
                + "is left exactly as it is for you to look at, and every story it accepts is "
                + "marked as accepted without anyone looking at it.",
                config.overnight().concurrencyOrDefault());
        }
        // Settings whose features are not built yet - say so instead of silently ignoring them.
        if (config != null && config.telemetry() != null && config.telemetry().otlpEndpoint() != null
                && !config.telemetry().otlpEndpoint().isBlank()) {
            log.warn("A monitoring address is set but nothing is sent to it: SwarmCoder does not "
                + "report measurements to other tools yet. What it does is all visible in the "
                + "window.");
        }
    }

    /**
     * What the agents on this project can actually read, said once when the project opens.
     *
     * <p>Failing quietly per lookup is right — one dead documentation call must never kill a run.
     * Failing quietly at STARTUP is not, and that is what this exists to stop. A run once cost
     * ten workers their whole budget, about 180,000 tokens, and produced nothing: no documentation
     * server had ever been configured and nothing was listening where the built-in address
     * pointed, so every question a worker asked came back "no documentation found, inspect the
     * code directly" — and ten workers dutifully spent the run taking a compiled library apart.
     * Nothing anywhere said the documentation was missing.
     *
     * @param projectName      the project being opened, for the message
     * @param referenceFolders the read-only reference folders configured for it
     * @param documentCount    how many documents were actually found in them
     * @param posture          what the documentation server is doing: answering, refusing the
     *                         key, missing a key, or not there
     * @param serverAddress    the address being tried — safe to print, it never carries the key
     * @return the lines logged, so a test can assert the exact words a person is given
     */
    public static List<String> reportKnowledgeSources(
            String projectName,
            List<java.nio.file.Path> referenceFolders,
            int documentCount,
            com.swarmcoder.knowledge.Context7Client.Posture posture,
            String serverAddress) {
        List<String> lines = new ArrayList<>();
        boolean hasFolders = referenceFolders != null && !referenceFolders.isEmpty();
        boolean localDocs = hasFolders && documentCount > 0;
        if (localDocs) {
            lines.add("Project '" + projectName + "': the agents can read " + documentCount
                + " documents from the reference folders you gave it. Those are searched first, "
                + "before anything published elsewhere — your own copy is the newer one.");
        } else if (hasFolders) {
            lines.add("Project '" + projectName + "': the reference folders you gave it contain NO "
                + "documentation the agents can read. They will have to work the code out for "
                + "themselves, which is slow and often wrong. Put written documentation in a "
                + "'docs' folder inside one of them, or next to its README.");
        }
        // The three not-working states are three different problems with three different fixes,
        // and an operator must be able to tell them apart without reading code.
        String cost = localDocs
            ? " Your own reference folders still work, so this only affects questions about other "
              + "people's libraries."
            : " That leaves the agents with no documentation at all. Expect them to spend the run "
              + "taking compiled libraries apart instead of writing code.";
        switch (posture) {
            case READY -> { }
            case NO_KEY -> lines.add("Project '" + projectName + "': published library "
                + "documentation is switched off because no key is set for " + serverAddress
                + ". Set the environment variable CONTEXT7_API_KEY, then restart SwarmCoder — the "
                + "key is read once at startup, so a window that was already open will not see a "
                + "key set after it started." + cost);
            case KEY_REJECTED -> lines.add("Project '" + projectName + "': the documentation "
                + "server at " + serverAddress + " REFUSED the key in CONTEXT7_API_KEY. The key is "
                + "set but not accepted — check it is the right one and has not expired." + cost);
            case UNREACHABLE -> lines.add("Project '" + projectName + "': nothing answered at the "
                + "documentation server address " + serverAddress + ". Check the address under "
                + "'mcpServers' in your settings, or that the machine can reach it." + cost);
        }
        for (String line : lines) {
            if (line.contains("NO documentation") || line.contains("REFUSED")
                || line.contains("nothing answered")) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }
        return lines;
    }

    /**
     * The last word at startup: what is still needed, and where to do it.
     *
     * @param config the settings that were loaded
     * @param consolePort the port the window opened on, or null when no window started
     * @param sandboxBlocked whether a build would stop because Docker is not answering
     */
    public static void logSetupSummary(SwarmConfig config, Integer consolePort,
                                       boolean sandboxBlocked) {
        List<String> todo = outstandingSetup(config, consolePort, sandboxBlocked);
        if (todo.isEmpty()) {
            return;
        }
        log.warn("=================================================================");
        log.warn("Before SwarmCoder can build anything, {}:", todo.size() == 1
            ? "one thing is still needed" : todo.size() + " things are still needed");
        for (int i = 0; i < todo.size(); i++) {
            log.warn("  {}. {}", i + 1, todo.get(i));
        }
        if (consolePort != null) {
            log.warn("");
            log.warn("The window at http://localhost:{}/ says the same thing, one step at a time.",
                consolePort);
        }
        log.warn("=================================================================");
    }

    /**
     * What is still needed before a build is possible, worst first, or empty when nothing is.
     *
     * <p>Returned rather than only logged so a test can read the exact sentences a newcomer is
     * given. Text a person depends on is the product; asserting it means asserting the words.
     *
     * @param config the settings that were loaded
     * @param consolePort the port the window opened on, or null when no window started
     * @param sandboxBlocked whether a build would stop because Docker is not answering
     */
    public static List<String> outstandingSetup(SwarmConfig config, Integer consolePort,
                                                boolean sandboxBlocked) {
        List<String> todo = new ArrayList<>();

        if (consolePort == null) {
            // No window means there is no other place to be told any of this, so it goes first.
            todo.add("Open a window to work in. SwarmCoder is running with nothing to look at, "
                + "because no port is set for it. Add a line saying  consolePort: "
                + ConfigLoader.DEFAULT_CONSOLE_PORT + "  to " + ConfigLoader.configPath()
                + " and start SwarmCoder again.");
        }
        String repoPath = config == null ? null : config.repoPath();
        if (repoPath == null || repoPath.isBlank()) {
            todo.add("Point SwarmCoder at your code. It has no folder to work in yet, so it can "
                + "read and plan but cannot build. In the window: Setup, then \"Project settings\".");
        }
        if (!hasAnyModel(config)) {
            todo.add("Tell SwarmCoder which model to think with. It has no model server address "
                + "yet. In the window: Setup, then \"Models & budgets\".");
        }
        if (sandboxBlocked) {
            // Last, because it is the only one with a fix outside SwarmCoder, and the block printed
            // higher up already gives the two commands. This is the reminder, not the instructions.
            todo.add("Install Docker Desktop and start it. SwarmCoder runs the models' commands "
                + "inside a safety box, Docker is what makes one, and it is not answering. The "
                + "two commands that set it up are printed further up this log.");
        }
        return todo;
    }

    /**
     * Whether any model endpoint is configured at all.
     *
     * <p>Any one counts. The roles fall back to each other and to the process-wide default client,
     * so a single configured endpoint is a usable machine. What this check is for is the difference
     * between "some models" and "none" — not between "all" and "some", which is the job of the
     * per-role warnings at startup.
     */
    private static boolean hasAnyModel(SwarmConfig config) {
        RolesConfig roles = config == null ? null : config.roles();
        if (roles == null) {
            return false;
        }
        if (roles.workerFamilies() != null) {
            for (AgentModelConfig worker : roles.workerFamilies()) {
                if (configured(worker)) {
                    return true;
                }
            }
        }
        return configured(roles.architect()) || configured(roles.utility()) || configured(roles.chat())
            || configured(roles.testAuthor()) || configured(roles.judge())
            || configured(roles.requirementsAnalyst()) || configured(roles.storyPlanner());
    }

    private static boolean configured(AgentModelConfig role) {
        return role != null && role.baseUrl() != null && !role.baseUrl().isBlank()
            && role.modelName() != null && !role.modelName().isBlank();
    }
}
