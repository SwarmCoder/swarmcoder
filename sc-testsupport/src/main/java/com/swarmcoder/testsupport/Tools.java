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
package com.swarmcoder.testsupport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Answers "is this tool on this machine?" once per JVM, cheaply, and errs towards running the test.
 *
 * <h2>Every probe fails towards running</h2>
 *
 * <p>A probe that is not sure says the tool is there. That is deliberate and it is the opposite of
 * how these checks are usually written. If the probe is wrong in that direction the test runs and
 * fails with the real error — {@code Chromium executable not found}, {@code Cannot connect to the
 * Docker daemon} — which is loud, accurate, and fixable. If it were wrong in the other direction
 * the test would disappear from the build and read as passing, which is the exact fault this
 * whole change exists to remove. A guess must never be able to cost coverage.
 */
public final class Tools {

    /** One answer per JVM. Surefire forks a JVM per test class here, so this is per class. */
    private static final Map<String, Optional<String>> ANSWERS = new ConcurrentHashMap<>();

    private Tools() {
    }

    /** The reason Playwright's Chromium cannot be used, or empty when it can. */
    public static Optional<String> chromiumMissing() {
        return ANSWERS.computeIfAbsent("chromium", key -> {
            String configured = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
            if (configured != null && !configured.isBlank() && !configured.equals("0")) {
                return chromiumUnder(Path.of(configured));
            }
            if ("0".equals(configured)) {
                // "0" means Playwright unpacks the browsers next to its own driver jar, wherever
                // that ends up on the classpath. Not worth locating: run, and let Playwright
                // itself say so if it is wrong.
                return Optional.empty();
            }
            return chromiumUnder(defaultBrowserStore());
        });
    }

    /** The reason Docker cannot be used for a test needing {@code image}, or empty when it can. */
    public static Optional<String> dockerMissing(String image) {
        return ANSWERS.computeIfAbsent("docker:" + image, key -> {
            if (run("docker", "version", "--format", "{{.Server.Version}}") != 0) {
                return Optional.of("no Docker daemon answered. These tests prove that a worker's "
                    + "commands really are confined — non-root, no network, read-only root "
                    + "filesystem — which can only be checked against a real daemon. Start "
                    + "Docker and they run by themselves.");
            }
            if (run("docker", "image", "inspect", image) != 0) {
                // The UI image is a different build entirely: it is FROM the worker image and adds
                // a browser, so naming the worker image's build command for it would send the
                // reader to a Dockerfile that produces something else.
                String build = image.contains("-ui")
                    ? "mvn -o -pl sc-sandbox-action-server -am package && docker build -t "
                        + "swarmcoder-worker:latest sc-sandbox-action-server && docker build -t "
                        + image + " sc-sandbox/images/sc-java-ui"
                    : "mvn -o -pl sc-sandbox-action-server -am package && docker build -t "
                        + image + " sc-sandbox-action-server";
                return Optional.of("Docker is running but the " + image + " image is not built. "
                    + "Build it with: " + build);
            }
            return Optional.empty();
        });
    }

    /** The reason Maven cannot be shelled out to, or empty when it can. */
    public static Optional<String> mavenMissing() {
        return ANSWERS.computeIfAbsent("maven", key -> onPath("mvn")
            ? Optional.empty()
            : Optional.of("Maven is not on the PATH. This test shells out to a real build to see "
                + "what the test runner actually spells its test ids, which is the match the "
                + "product's whole traceability claim rests on."));
    }

    /** The reason a live model endpoint is unavailable, or empty when one was named. */
    public static Optional<String> liveModelMissing() {
        String url = System.getProperty("swarmcoder.live.baseUrl");
        if (url != null && !url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of("no live model endpoint was named, and this stays off by default on "
            + "purpose: the model server is shared with other work and a build must not depend on "
            + "it. Run it deliberately with -Dswarmcoder.live.baseUrl=<url>.");
    }

    /** The reason the paid-cloud journey is off, or empty when it was explicitly switched on. */
    public static Optional<String> paidCloudModelsMissing() {
        if ("true".equals(System.getenv("SWARMCODER_CONFIG_E2E"))) {
            return Optional.empty();
        }
        return Optional.of("this run spends real money on the operator's own cloud model accounts "
            + "and is off by default for that reason alone. Switch it on by hand, knowing the "
            + "cost, with SWARMCODER_CONFIG_E2E=true.");
    }

    /** The reason there is no target repository to verify, or empty when one was named. */
    public static Optional<String> demoRepoMissing() {
        String repo = System.getProperty("swarmcoder.demo.repo");
        if (repo != null && !repo.isBlank() && Files.isDirectory(Path.of(repo))) {
            return Optional.empty();
        }
        return Optional.of("no target repository was named. The dev/demo-repo this wants is not "
            + "in this checkout, so there is nothing to point it at unless you have your own "
            + "copy: -Dswarmcoder.demo.repo=<path>.");
    }

    // --- the probes themselves -----------------------------------------------------------------

    private static Optional<String> chromiumUnder(Path store) {
        if (!Files.isDirectory(store)) {
            // Cannot tell. Run the test; Playwright will say so far better than a guess would.
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(store)) {
            boolean present = entries.anyMatch(p -> Files.isDirectory(p)
                && p.getFileName().toString().startsWith("chromium"));
            return present ? Optional.empty() : Optional.of(
                "Playwright's Chromium is not installed under " + store + ". These are the only "
                + "tests that drive the operator interface at all. Install it once with: mvn -o "
                + "-pl sc-console exec:java -Dexec.mainClass=com.microsoft.playwright.CLI "
                + "-Dexec.classpathScope=test -Dexec.args=\"install chromium\"");
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Path defaultBrowserStore() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Path.of(home, "AppData", "Local", "ms-playwright");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Caches", "ms-playwright");
        }
        return Path.of(home, ".cache", "ms-playwright");
    }

    private static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return true;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] names = windows
            ? new String[] {command + ".cmd", command + ".bat", command + ".exe", command}
            : new String[] {command};
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    if (Files.isRegularFile(Path.of(directory, name))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // An unparseable PATH entry is not evidence about the tool.
                }
            }
        }
        return false;
    }

    /** Runs a short command and returns its exit code; anything gone wrong is a non-zero code. */
    private static int run(String... argv) {
        try {
            Process process = new ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
