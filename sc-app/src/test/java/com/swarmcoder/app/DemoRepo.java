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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A throwaway copy of the demo target repository — a real Maven project with a real git history,
 * for a run to actually build in.
 *
 * <p><b>Never the original.</b> A run writes commits, branches and worktrees into whatever repo it
 * is pointed at, so every test gets its own copy and deletes nothing it did not create.
 *
 * <p>Two sources, in this order:
 * <ol>
 *   <li>{@code dev/demo-repo} in the developer's checkout, cloned with git, when
 *       {@code -Dswarmcoder.demo.repo=&lt;path&gt;} points at it or it is found by walking up from the
 *       working directory. This is the real thing, warts and all.</li>
 *   <li>Otherwise the copy of it checked in under {@code src/test/resources/demo-repo}. It has to
 *       exist: {@code dev/demo-repo} is its own git repository and is gitignored by this one, so it
 *       is absent from every fresh clone and every worktree, and a test that needed it would simply
 *       not run where it matters.</li>
 * </ol>
 * {@link #describeSource()} says which was used, so a green run never leaves that ambiguous.
 */
final class DemoRepo {

    /** Every file of the template, as classpath resource path → repo-relative path. */
    private static final List<String[]> TEMPLATE = List.of(
        new String[] {"/demo-repo/pom.xml", "pom.xml"},
        new String[] {"/demo-repo/.swarmcoder/verify.yaml", ".swarmcoder/verify.yaml"},
        new String[] {"/demo-repo/src/main/java/com/example/calc/Calculator.java",
            "src/main/java/com/example/calc/Calculator.java"},
        new String[] {"/demo-repo/src/test/java/com/example/calc/CalculatorTest.java",
            "src/test/java/com/example/calc/CalculatorTest.java"},
        // Checked in as gitignore.txt: Maven's resource filtering excludes files named .gitignore.
        new String[] {"/demo-repo/gitignore.txt", ".gitignore"});

    private static String source = "not yet created";

    private DemoRepo() {}

    static String describeSource() {
        return source;
    }

    /**
     * Materialises a working demo repository at {@code target} and returns it.
     *
     * <p>The verification contract is rewritten to build OFFLINE ({@code mvn -o}). Same commands,
     * same stages — but a verification stage that reaches for the network turns a deterministic
     * test into one that fails when the machine is offline or a repository is slow, and the whole
     * point of this harness is that nothing but the model is simulated.
     */
    static Path create(Path target) throws Exception {
        Path origin = locateDeveloperCopy();
        if (origin != null) {
            source = "a git clone of " + origin;
            git(target.getParent(), "clone -q \"" + origin.toString().replace('\\', '/') + "\" \""
                + target.getFileName() + "\"");
        } else {
            source = "the checked-in copy in src/test/resources/demo-repo "
                + "(dev/demo-repo was not found)";
            Files.createDirectories(target);
            for (String[] entry : TEMPLATE) {
                Path file = target.resolve(entry[1]);
                Files.createDirectories(file.getParent());
                Files.writeString(file, resource(entry[0]));
            }
            git(target, "init -q");
            git(target, "add -A");
            git(target, "-c user.email=t@t -c user.name=t commit -q -m \"demo target repo\"");
        }

        // Offline verification, committed so every worker worktree inherits it.
        Files.writeString(target.resolve(".swarmcoder/verify.yaml"), """
            # Same stages as dev/demo-repo, pinned offline for a deterministic test run.
            toolchain: maven
            compile:
              - "mvn -o -q -B compile test-compile"
            acceptance:
              - "mvn -o -q -B test -Dtest=swarm/accept/** -Dsurefire.failIfNoSpecifiedTests=false"
            existing:
              - "mvn -o -q -B test"
            timeoutSeconds: 900
            """);
        git(target, "add -A");
        git(target, "-c user.email=t@t -c user.name=t commit -q -m \"verify offline\"");
        return target;
    }

    /** The developer's dev/demo-repo, or null when this checkout has none. */
    private static Path locateDeveloperCopy() {
        String declared = System.getProperty("swarmcoder.demo.repo");
        if (declared != null && !declared.isBlank()) {
            Path path = Path.of(declared);
            return Files.isDirectory(path.resolve(".git")) ? path : null;
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("dev/demo-repo");
            if (Files.isDirectory(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return null;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = DemoRepo.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing demo-repo test resource " + path
                    + " — the checked-in copy is what makes this test runnable in a worktree");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void git(Path dir, String args) throws Exception {
        List<String> argv = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            argv.addAll(List.of("cmd.exe", "/c", "git " + args));
        } else {
            argv.addAll(List.of("sh", "-c", "git " + args));
        }
        Process process = new ProcessBuilder(argv).directory(dir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed in " + dir + ":\n" + out);
        }
    }
}
