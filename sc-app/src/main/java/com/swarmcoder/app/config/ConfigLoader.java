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
package com.swarmcoder.app.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads — and, on a brand-new machine, writes — {@code ~/.swarmcoder/config.yaml}.
 *
 * <h2>A missing file is a first run, not an error</h2>
 *
 * <p>This used to throw {@code Config file not found at …}. Nothing wrote a starter file, so a
 * person who built the project and started it got two log lines, a three-frame stack trace and an
 * exit — with no hint that a file was wanted, where it goes, or what belongs in it. Everyone who
 * had ever run SwarmCoder already had a config from an earlier era, so nobody met this.
 *
 * <p>The rule now is the framework's own: <b>a newcomer must not have to think.</b> A missing file
 * is written from {@link #starterConfigText()} and the application carries on, which puts the
 * console on screen — and the console's setup surface is the thing whose entire job is naming the
 * one step that is still outstanding.
 *
 * <h2>What the starter file decides, and what it refuses to</h2>
 *
 * <p>It sets {@code consolePort}, because without a port there is no window and therefore no way to
 * be told anything at all. That is the only value here with a safe default.
 *
 * <p>It deliberately leaves {@code repoPath} empty. SwarmCoder <em>writes to</em> the repository it
 * is pointed at — branches, worktrees, commits — so a guessed default is not a convenience, it is
 * damage to a folder nobody chose. Same for the model endpoints: an invented address buys a
 * connection failure five minutes later instead of a plain sentence now.
 */
public class ConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The port the starter file opens the console on.
     *
     * <p>Any free port would do; this one is what the user manual prints, so the address in the
     * documentation is the address a fresh install actually answers on.
     */
    public static final int DEFAULT_CONSOLE_PORT = 9090;

    /** Where the settings file lives. Resolved per call, so an isolated home is respected. */
    public static Path configPath() {
        return Paths.get(System.getProperty("user.home"), ".swarmcoder", "config.yaml");
    }

    /**
     * Loads the settings, writing a starter file first when there is none.
     *
     * @return the settings; never null
     * @throws IOException only when a file that exists cannot be read or parsed, or when the
     *     starter file cannot be written — both real faults an operator has to fix
     */
    public static SwarmConfig loadDefaultConfig() throws IOException {
        Path configPath = configPath();
        if (!Files.exists(configPath)) {
            writeStarterConfig(configPath);
        }
        SwarmConfig config = YAML_MAPPER.readValue(configPath.toFile(), SwarmConfig.class);
        // Told here, where the settings are first read, so everything that starts a Java
        // language server afterwards finds the same one.
        com.swarmcoder.lsp.JdtLsInstall.configure(config == null ? null : config.jdtLsHome());
        return config;
    }

    /**
     * Writes the starter settings file and says so, in words a newcomer can act on.
     *
     * <p>Public so the first-run message and the tests name the same file this created.
     *
     * @return true when a file was written, false when one was already there
     */
    public static boolean writeStarterConfig(Path configPath) throws IOException {
        if (Files.exists(configPath)) {
            return false;
        }
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, starterConfigText());
        log.info("=================================================================");
        log.info("Welcome to SwarmCoder.");
        log.info("");
        log.info("This is the first time it has run here, so a settings file has");
        log.info("been made for you:");
        log.info("    {}", configPath);
        log.info("");
        log.info("Nothing is set up yet, and that is fine. When SwarmCoder has");
        log.info("finished starting, open this address in your browser:");
        log.info("    http://localhost:{}/", DEFAULT_CONSOLE_PORT);
        log.info("The window there tells you the next thing to do.");
        log.info("=================================================================");
        return true;
    }

    /**
     * The contents of the starter file: plain-language comments, one setting turned on.
     *
     * <p>Written as text rather than serialised from the record so the comments survive. A settings
     * file a person is expected to open is documentation as much as it is data, and Jackson writes
     * neither comments nor blank lines.
     */
    public static String starterConfigText() {
        return """
            # SwarmCoder settings.
            #
            # This file was made for you the first time SwarmCoder started. You can edit it
            # here, or change the same things in the window SwarmCoder opens in your browser.
            # Either way, SwarmCoder only reads this when it starts, so restart it afterwards.

            # The window. Once SwarmCoder is running, open http://localhost:%d/ in a browser.
            # Change the number if something else on this machine already uses it.
            consolePort: %d

            # The folder holding the code you want SwarmCoder to work on.
            #
            # It is empty on purpose. SwarmCoder makes branches and writes files in this folder,
            # so it has to be a folder you chose, never one that was guessed for you. Set it in
            # the window (Setup, then "Project settings"), or write the full path here:
            #
            #   repoPath: C:/code/my-project
            repoPath: null

            # The thinking. Each entry is the address of a model server and the name of a model
            # on it. The easiest way to fill these in is the window (Setup, then "Models &
            # budgets"); the shape below is what it writes.
            #
            # roles:
            #   architect:
            #     baseUrl: "https://api.example.com"
            #     apiKey: "your-key-here"
            #     modelName: "the-model-name"
            #   workerFamilies:
            #     - baseUrl: "http://localhost:8000/v1"
            #       apiKey: ""
            #       modelName: "the-model-name"
            #
            # One of these is worth filling in even if you fill in nothing else. "vision" is the
            # model that can look at a picture. Without it, dropping a screenshot or a photo of a
            # whiteboard into SwarmCoder is refused outright, and it is the same missing setting
            # that stops anything looking at a picture of your own running application. It has to
            # be a model that genuinely reads images: an ordinary text model will happily describe
            # a picture it never received, and nothing in the answer says which happened.
            #
            # roles:
            #   vision:
            #     baseUrl: "http://localhost:8000/v1"
            #     apiKey: ""
            #     modelName: "the-model-name"
            #     shape: "vision"

            # How much SwarmCoder does at once.
            #
            # Leave the whole block out and each piece of work gets a single attempt. Put the
            # block in and you get the numbers below, which you can change one at a time.
            #
            #   nPerTask                 how many attempts each piece of work gets. Four. More
            #                            attempts find better answers, up to a point: with eight,
            #                            six or seven of them were passing, so the last four were
            #                            proving what the first four had already proved.
            #   maxConcurrentTaskGroups  the most pieces of work attempted side by side. Leave it
            #                            out: every piece of work that is ready is then started,
            #                            as many at once as the model server has places for.
            #                            Write a number only to hold it below that.
            #   maxConcurrentWorkers     the most attempts running at the same moment on this
            #                            computer, whatever the two numbers above add up to.
            #                            Eight. Anything over it waits for a free place instead
            #                            of being skipped, so nothing is lost — it just takes
            #                            its turn.
            #
            # The model server answers only so many requests at once. Those places go to
            # different pieces of work first: the first attempt at every ready piece is started
            # before any second attempt, and a second attempt only gets a place nothing else is
            # waiting for. A piece of work that already has an attempt that passed does not start
            # another one just to have two.
            #
            # A single project can ask for a different number of attempts in its own settings, and
            # a single story can ask for a different number again on its own card. The most
            # specific one wins, and the build log always says which one it used.
            #
            # swarm:
            #   nPerTask: 4
            #   maxConcurrentWorkers: 8

            # How long one attempt is allowed to keep going.
            #
            #   maxToolTurnsPerWorker  how many steps one attempt may take before it is stopped.
            #                          A step is one thing the model does: read a file, change a
            #                          file, run the build. 120 unless you say otherwise.
            #
            # Why 120. An attempt spends about eight steps finding its feet, then repeats the same
            # three steps over and over: run the build, read what broke, fix it. 120 steps is
            # forty of those repair rounds. It used to be 30, which is ten rounds, and on a project
            # where the build takes a minute and a half that was not enough to finish anything —
            # attempts were being stopped while still working, having used less than three percent
            # of the words they were allowed.
            #
            # This is a runaway stop, not a spending limit. What costs money is words, and
            # maxLocalTokensPerTask already limits those. If attempts are being stopped here while
            # still making progress, raise it.
            #
            # A single project can ask for a different number in its own settings, and a single
            # story can ask for a different number again on its own card. The most specific one
            # wins, and the build log always says which one it used.
            #
            # budgets:
            #   maxToolTurnsPerWorker: 120

            # Letting a coding assistant watch SwarmCoder work.
            #
            # Turn this on and SwarmCoder opens a second door, for THIS MACHINE ONLY, that an
            # assistant such as Claude Code can knock on to ask what a build is doing and why it
            # stopped. It is off until you turn it on, because opening a door is your decision.
            # Nothing outside this computer can reach it, and there is no password on it, so leave
            # it off on a machine other people can use.
            #
            # Once it is on, restart SwarmCoder and run this once in a terminal:
            #   claude mcp add --transport http swarmcoder http://127.0.0.1:%d/mcp
            #
            # Add readOnly: true if you want the assistant to be able to look but not touch.
            #
            # mcpApi:
            #   enabled: true
            #   port: %d
            """.formatted(DEFAULT_CONSOLE_PORT, DEFAULT_CONSOLE_PORT,
                McpApiConfig.DEFAULT_PORT, McpApiConfig.DEFAULT_PORT);
    }

    public static void saveConfig(SwarmConfig config) throws IOException {
        Path configPath = configPath();
        if (!configPath.getParent().toFile().exists()) {
            configPath.getParent().toFile().mkdirs();
        }
        YAML_MAPPER.writeValue(configPath.toFile(), config);
    }
}
