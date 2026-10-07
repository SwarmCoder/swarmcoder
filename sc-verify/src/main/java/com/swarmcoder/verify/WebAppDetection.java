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
package com.swarmcoder.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Works out whether a repository serves a web application, and — when it does — proposes the
 * browser block of its verification contract.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Nothing in SwarmCoder had ever run the product. A candidate compiled, its unit tests passed,
 * and it was declared delivered — while every acceptance criterion an operator actually writes is
 * about a running application: "adding a book with a title, author, year and status makes it appear
 * in the list", "my books are still there when I come back tomorrow". None of those can be settled
 * by a compiler.
 *
 * <p>The instrument for settling them already existed and was switched off. {@link BrowserVerifier}
 * starts an application from a serve command, waits on a readiness probe, drives headless Chromium
 * at it, asserts elements are visible, collects console errors and files screenshots. It runs as the
 * pipeline's final stage. And it ran for nobody, because {@link ToolchainDetector} never proposed a
 * browser block for any toolchain and no contract in the project carried one by hand. The machinery
 * was complete; the wire from detection to it was missing. This class is that wire.
 *
 * <h2>It proposes; it never decides</h2>
 *
 * <p>Same rule as the rest of the detector. Every proposal carries its evidence and the warnings the
 * operator has to rule on, and where no runnable serve command can be worked out this returns
 * <b>no block and a warning saying exactly what to write</b> rather than a guess. A guessed serve
 * command is worse than none: it fails every candidate for a reason that has nothing to do with the
 * candidate, which is the most expensive way this project has ever lost a day.
 */
final class WebAppDetection {

    private static final Logger log = LoggerFactory.getLogger(WebAppDetection.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How deep to look for a module's web root or its main class. Reaches a normal monorepo. */
    private static final int SCAN_DEPTH = 4;

    /** Never descend into these when looking for web assets or a main class. */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".mvn", ".venv", "venv",
        "node_modules", "target", "build", "dist", "out", "__pycache__", ".swarmcoder");

    /**
     * How long to allow for "the application answered its first request", when the serve command
     * has to build the application first. A cold Maven package plus a CDI container's boot is
     * comfortably a minute on a laptop; the readiness poll costs nothing while it waits, and a
     * timeout that is too short reports "the application did not start" about a machine that was
     * merely slow — the single worst mistake this stage can make.
     */
    private static final int BUILD_AND_BOOT_TIMEOUT_SECONDS = 300;

    /** Relative locations, under any module, where a Java web application keeps its front page. */
    private static final List<String> JAVA_WEB_ROOTS = List.of(
        "src/main/webapp",
        "src/main/resources/META-INF/resources",
        "src/main/resources/static",
        "src/main/resources/public");

    private WebAppDetection() {}

    /**
     * A proposed browser block, or an explanation of why there is none.
     *
     * @param browser  the block to put in the contract, or null
     * @param evidence what made this look like a web application, in plain English
     * @param warnings what the operator must rule on before trusting a run
     */
    record Proposal(VerifySpec.BrowserSpec browser, List<String> evidence, List<String> warnings) {

        static Proposal none() {
            return new Proposal(null, List.of(), List.of());
        }

        static Proposal none(String warning) {
            return new Proposal(null, List.of(), List.of(warning));
        }

        boolean proposed() {
            return browser != null;
        }
    }

    // --- Java (maven and gradle) -----------------------------------------------------------------

    /**
     * Proposes a browser block for a Maven or Gradle repository.
     *
     * @param root      the repository root
     * @param toolchain "maven" or "gradle"
     * @param launcher  the build launcher already chosen by the detector (mvnw.cmd, ./gradlew, …)
     * @param modules   module directories relative to the root, empty for a single-module build
     * @param windows   whether the contract is being written on Windows — the classpath separator
     *                  and the wrapper name both depend on it, as they already do elsewhere here
     */
    static Proposal forJava(Path root, String toolchain, String launcher, List<String> modules,
                            boolean windows) {
        List<String> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String webRoot = findJavaWebRoot(root, modules);
        String framework = javaServerFramework(root, modules);
        if (webRoot == null && framework == null) {
            return Proposal.none(); // not a web application; nothing to say
        }
        if (webRoot != null) {
            evidence.add("a front page served to a browser at " + webRoot
                + ", so this project has a user interface a browser can be pointed at");
        }
        if (framework != null) {
            evidence.add(framework + " in a build file, so this project runs an HTTP server");
        }

        // The framework runners take the port as an argument, so the harness can pick a free one and
        // two verifications never collide. Prefer them wherever one applies.
        if ("spring-boot".equals(framework)) {
            evidence.add("Spring Boot, whose own runner takes the port, so the harness can pick a "
                + "free one and two verifications on this machine cannot collide");
            String serve = "gradle".equals(toolchain)
                ? launcher + " bootRun --args='--server.port={PORT}'"
                : launcher + " -B -q spring-boot:run "
                    + "-Dspring-boot.run.jvmArguments=\"-Dserver.port={PORT}\"";
            return new Proposal(block(serve, 0, BUILD_AND_BOOT_TIMEOUT_SECONDS), evidence, warnings);
        }
        if ("quarkus".equals(framework)) {
            evidence.add("Quarkus, whose dev mode takes the port, so the harness can pick a free one");
            String serve = "gradle".equals(toolchain)
                ? launcher + " quarkusDev -Dquarkus.http.port={PORT}"
                : launcher + " -B -q quarkus:dev -Dquarkus.http.port={PORT}";
            return new Proposal(block(serve, 0, BUILD_AND_BOOT_TIMEOUT_SECONDS), evidence, warnings);
        }

        // No framework runner. The application is launched the way its own Dockerfile or README
        // launches it: a main class on a classpath. Maven only, because a Gradle project's runtime
        // classpath is not something that can be named from the outside without running Gradle.
        if (!"maven".equals(toolchain)) {
            return new Proposal(null, evidence, List.of(
                "This project serves a web page, but nothing here could work out one command that "
                + "starts it. That means the browser check — the only thing that proves a candidate's "
                + "application actually runs — is off, and a candidate can be declared delivered "
                + "having never been started. Write the block by hand: `browser:` with `serve:` (the "
                + "command that starts the app, using {PORT} if it can be told which port to use, or "
                + "`port:` naming the one it hardcodes), `readyProbe:`, and at least one entry under "
                + "`checks:` naming a URL and a selector that must be visible."));
        }

        ServerEntryPoint entry = findServerEntryPoint(root, modules, webRoot);
        if (entry == null) {
            return new Proposal(null, evidence, List.of(
                "This project serves a web page from " + (webRoot == null ? "this repository" : webRoot)
                + ", but no class with a `public static void main` was found to start it, so no "
                + "browser check is proposed. Until one is written by hand, nothing in verification "
                + "ever starts this application — a candidate that compiles and cannot run will be "
                + "declared delivered."));
        }
        String module = entry.module();
        MainClass main = entry.main();
        evidence.add(main.className() + " in "
            + (module.isEmpty() ? "the repository root" : module)
            + " has a main method that starts a server, so that is what the browser check runs");

        boolean copiesDependencies = mavenCopiesDependencies(root, module);
        if (!copiesDependencies) {
            return new Proposal(null, evidence, List.of(
                "This project serves a web page and " + main.className() + " starts it, but the "
                + "build does not put the runtime dependencies anywhere a plain `java -cp` can name "
                + "them (no maven-dependency-plugin copy-dependencies), so no start command could be "
                + "proposed with any confidence. Write the `browser:` block by hand — a guessed "
                + "classpath would fail every candidate for a reason that has nothing to do with the "
                + "candidate."));
        }

        String sep = windows ? ";" : ":";
        String prefix = module.isEmpty() ? "" : module + "/";
        // package, not compile: copy-dependencies runs in the package phase, and the compile stage
        // of this contract stops short of it — so target/libs would not exist yet when the browser
        // stage runs. -am because the module needs its siblings.
        //
        // maven.test.skip, NOT skipTests, and the difference decides whether this stage works at
        // all. -DskipTests still COMPILES the tests, and by the time the browser stage runs the
        // repository is full of acceptance tests that were written to fail — that is the red check,
        // the point of the whole method. One of them naming a type the candidate has not written
        // yet is a test-compile error, the build command dies, and the stage reports "the
        // application did not start" about an application it never got as far as launching. Killing
        // candidates for the state their tests are deliberately in is the worst outcome available
        // here. Measured against dev/bookshelf-demo on 2026-08-31, where the committed acceptance
        // test names five types that do not exist. The tests have their own stages; this one only
        // has to get the application running.
        String build = launcher + " -o -q -B" + (module.isEmpty() ? "" : " -pl " + module + " -am")
            + " package -Dmaven.test.skip=true";
        String run = "java -cp \"" + prefix + "target/classes" + sep + prefix + "target/libs/*\" "
            + main.className();
        String serve = build + " && " + run;

        int port = main.port();
        if (port > 0) {
            evidence.add("that main method starts the server on port " + port + ", which it "
                + "hardcodes, so the contract names that port rather than pretending the harness "
                + "can choose one");
            warnings.add("The browser check starts this application on port " + port + ", because "
                + main.className() + " hardcodes it and takes no port argument. Two verifications "
                + "on this machine cannot both have it, and neither can you while your own copy of "
                + "the app is running: whichever loses the race records \"could not be attempted\" "
                + "and does not fail the candidate. If you want browser checks running in parallel, "
                + "make the application read its port from a system property or an environment "
                + "variable, then put {PORT} in `serve` and delete `port`.");
        } else {
            warnings.add("No port could be read out of " + main.className()
                + ", so the browser check assumes the application can be told which port to use "
                + "through {PORT}. If it cannot, the check will point a browser at the wrong port "
                + "and report that every page failed to load. Set `port:` to the port this "
                + "application actually binds.");
        }
        warnings.add("The start command below uses '" + sep + "' as its classpath separator, which "
            + "is right for " + (windows ? "Windows" : "Linux and macOS") + " and wrong for the "
            + "other. This contract is read wherever verification runs, so change it if that is not "
            + "this machine.");
        warnings.add("The browser check is the only stage that proves the candidate's application "
            + "actually starts, and it needs a headless browser on the machine running verification "
            + "(`playwright install chromium`). Without one the stage records that it could not be "
            + "attempted and fails nobody — which is safe, and also means nothing is being proved. "
            + "Where it runs, and where it does not: a candidate's sandbox container can now start "
            + "this application (the sandbox exec channel runs background services), but the "
            + "browser this stage drives runs in the orchestrator's own process, and a container "
            + "with sandbox.network=none publishes no ports for it to connect to. So a 'serve' "
            + "check against a sandboxed candidate is declined outright rather than failed, and "
            + "the check happens at integration, on the workstation. Running the browser inside "
            + "the container instead is possible and proven - see the swarmcoder-worker-ui image - "
            + "and is not switched on.");

        return new Proposal(block(serve, port, BUILD_AND_BOOT_TIMEOUT_SECONDS), evidence, warnings);
    }

    // --- Node -------------------------------------------------------------------------------------

    /**
     * Proposes a browser block for a node repository.
     *
     * <p>A built front end is served with {@code static:<dir>} in preference to running a dev
     * server: it needs no port, no background process and no readiness poll, and it is the one
     * browser mode that works against every execution target — a sandboxed candidate included,
     * because the files are read through the target and served by the harness itself.
     */
    static Proposal forNode(Path root, String packageManager) {
        List<String> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Set<String> scripts = packageScripts(root);
        boolean hasPage = Files.isRegularFile(root.resolve("index.html"))
            || Files.isRegularFile(root.resolve("public/index.html"))
            || Files.isRegularFile(root.resolve("src/index.html"));
        String bundler = nodeBundler(root);
        if (!hasPage && bundler == null) {
            return Proposal.none(); // a library or a CLI, not something a browser opens
        }
        if (hasPage) {
            evidence.add("an index.html, so this project is opened in a browser");
        }
        if (bundler != null) {
            evidence.add(bundler + " in the dependencies, so it is a browser application");
        }

        String outDir = nodeOutputDir(root, bundler);
        if (scripts.contains("build") && outDir != null) {
            evidence.add("a \"build\" script writing " + outDir + ", so the built page is served "
                + "directly and no server has to be started at all — the one browser mode that "
                + "works against every execution target, sandboxes included");
            warnings.add("The browser check serves " + outDir + " as plain files. That covers a "
                + "front end with no back end. If this application talks to a server of its own, "
                + "replace `serve: \"static:" + outDir + "\"` with the command that starts it and "
                + "add a `readyProbe`, or the page will load with its data calls failing.");
            return new Proposal(
                new VerifySpec.BrowserSpec("static:" + outDir, null, 60, defaultChecks(), 0),
                evidence, warnings);
        }

        String start = scripts.contains("preview") ? "preview"
            : scripts.contains("start") ? "start"
            : scripts.contains("dev") ? "dev" : null;
        if (start == null) {
            return new Proposal(null, evidence, List.of(
                "This project is opened in a browser, but it has no \"build\" script and no "
                + "\"start\"/\"dev\"/\"preview\" script, so no way to serve it could be worked out. "
                + "Nothing in verification will start this application: write the `browser:` block "
                + "by hand with a `serve:` command, a `readyProbe:` and at least one `checks:` "
                + "entry."));
        }
        String run = "yarn".equals(packageManager)
            ? "yarn " + start + " --port {PORT}"
            : packageManager + " run " + start + " -- --port {PORT}";
        evidence.add("a \"" + start + "\" script, used to serve the application");
        warnings.add("The start command passes `--port {PORT}` so the harness can pick a free port. "
            + "Vite and most dev servers take that; if this one wants an environment variable "
            + "instead (create-react-app wants PORT=), change the command — otherwise the "
            + "application listens somewhere else and every page will look like it failed to load.");
        return new Proposal(block(run, 0, 120), evidence, warnings);
    }

    // --- shared ------------------------------------------------------------------------------------

    /**
     * The default navigation: the front page, no console errors, one screenshot.
     *
     * <p>Deliberately thin. The detector cannot know a selector that means "the books are listed" —
     * that is the operator's to add, and the rendered contract says so. What it CAN assert is the
     * thing nothing was asserting at all: the application starts, serves its front page, and the
     * browser finds nothing broken on it.
     */
    private static List<VerifySpec.PageCheckSpec> defaultChecks() {
        return List.of(new VerifySpec.PageCheckSpec("/", true, List.of("body"), true));
    }

    private static VerifySpec.BrowserSpec block(String serve, int port, int readyTimeoutSeconds) {
        return new VerifySpec.BrowserSpec(serve, "http://localhost:{PORT}/", readyTimeoutSeconds,
            defaultChecks(), port);
    }

    /** The first module (or the root) holding a directory a browser is served from. */
    private static String findJavaWebRoot(Path root, List<String> modules) {
        List<String> places = new ArrayList<>();
        places.add("");
        places.addAll(modules);
        for (String module : places) {
            for (String webRoot : JAVA_WEB_ROOTS) {
                String relative = module.isEmpty() ? webRoot : module + "/" + webRoot;
                if (Files.isRegularFile(root.resolve(relative).resolve("index.html"))) {
                    return relative;
                }
            }
        }
        // A monorepo whose module list could not be read still has its web root on disk.
        return scanForWebRoot(root, root, 0);
    }

    private static String scanForWebRoot(Path root, Path dir, int depth) {
        if (depth > SCAN_DEPTH) {
            return null;
        }
        for (String webRoot : JAVA_WEB_ROOTS) {
            if (Files.isRegularFile(dir.resolve(webRoot).resolve("index.html"))) {
                return relative(root, dir.resolve(webRoot));
            }
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path child : entries.toList()) {
                if (Files.isDirectory(child) && !SKIP_DIRS.contains(child.getFileName().toString())) {
                    String found = scanForWebRoot(root, child, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Could not scan {} for a web root: {}", dir, e.getMessage());
        }
        return null;
    }

    /** A module and the class in it that starts the server. */
    private record ServerEntryPoint(String module, MainClass main) {}

    /**
     * The module that STARTS the server, and its main class.
     *
     * <p>Deliberately not "the module holding the web page". The first real repository this was run
     * against is a three-module ZeroZ Stack build whose front page lives in the <em>client</em>
     * module — the browser code, compiled to JavaScript — while the thing that listens on a socket
     * is a different module entirely. Taking the web root's module gave a main class that compiles a
     * browser bundle and never opens a port, and a pom with none of the packaging the run command
     * needs, so the whole proposal collapsed. A main method that names a port is direct evidence of
     * a server; a web root is only evidence that a browser is involved somewhere.
     */
    private static ServerEntryPoint findServerEntryPoint(Path root, List<String> modules,
                                                         String webRoot) {
        List<String> places = new ArrayList<>();
        places.addAll(modules);
        places.add("");
        String webModule = moduleOf(webRoot, modules);
        if (!webModule.isEmpty() && !places.contains(webModule)) {
            places.add(webModule);
        }
        ServerEntryPoint fallback = null;
        for (String module : places) {
            MainClass main = findMainClass(root, module);
            if (main == null) {
                continue;
            }
            if (main.port() > 0) {
                return new ServerEntryPoint(module, main); // it binds a port: it is the server
            }
            if (fallback == null) {
                fallback = new ServerEntryPoint(module, main);
            }
        }
        return fallback;
    }

    /** The module directory a web root sits in, or "" for the repository root. */
    private static String moduleOf(String webRoot, List<String> modules) {
        if (webRoot == null) {
            return "";
        }
        String best = "";
        for (String module : modules) {
            if (webRoot.startsWith(module + "/") && module.length() > best.length()) {
                best = module;
            }
        }
        if (!best.isEmpty()) {
            return best;
        }
        for (String suffix : JAVA_WEB_ROOTS) {
            if (webRoot.endsWith(suffix)) {
                String prefix = webRoot.substring(0, webRoot.length() - suffix.length());
                return prefix.replaceAll("/+$", "");
            }
        }
        return "";
    }

    /** The HTTP server framework this repository's build files name, or null. */
    private static String javaServerFramework(Path root, List<String> modules) {
        List<String> files = new ArrayList<>(List.of("pom.xml", "build.gradle", "build.gradle.kts"));
        for (String module : modules) {
            files.add(module + "/pom.xml");
            files.add(module + "/build.gradle");
            files.add(module + "/build.gradle.kts");
        }
        for (String file : files) {
            String text = readCapped(root.resolve(file), 512 * 1024);
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("spring-boot")) return "spring-boot";
            if (lower.contains("quarkus")) return "quarkus";
            if (lower.contains("helidon")) return "helidon";
            if (lower.contains("micronaut")) return "micronaut";
            if (lower.contains("javalin")) return "javalin";
            if (lower.contains("vertx-web") || lower.contains("vertx-core")) return "vert.x";
            if (lower.contains("ktor-server")) return "ktor";
            if (lower.contains("jetty-server") || lower.contains("undertow-core")) return "an embedded servlet container";
        }
        return null;
    }

    /** Whether this Maven module's build copies its dependencies somewhere `java -cp` can name. */
    private static boolean mavenCopiesDependencies(Path root, String module) {
        for (String file : List.of(module.isEmpty() ? "pom.xml" : module + "/pom.xml", "pom.xml")) {
            String text = readCapped(root.resolve(file), 512 * 1024);
            if (text != null && text.contains("copy-dependencies")) {
                return true;
            }
        }
        return false;
    }

    /** A class with a {@code public static void main}, and the port it starts a server on. */
    record MainClass(String className, int port) {}

    private static final Pattern MAIN_METHOD =
        Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    /** {@code Server.start(8080, …)}, {@code .port(8080)}, {@code PORT = 8080} — the common spellings. */
    private static final Pattern LITERAL_PORT = Pattern.compile(
        "\\.start\\s*\\(\\s*(\\d{2,5})\\s*[,)]"
        + "|\\.port\\s*\\(\\s*(\\d{2,5})\\s*\\)"
        + "|(?i:port)\\s*=\\s*(\\d{2,5})\\b");

    /**
     * The main class of a module, preferring the one whose file looks like a server.
     *
     * <p>Read from the source with a regex rather than by loading classes: this runs against a
     * repository nobody has vetted, on a machine that has not built it, and all that is wanted is a
     * fully-qualified name to put in a proposal the operator reads.
     */
    private static MainClass findMainClass(Path root, String module) {
        Path source = root.resolve(module.isEmpty() ? "src/main/java" : module + "/src/main/java");
        if (!Files.isDirectory(source)) {
            return null;
        }
        List<MainClass> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String text = readCapped(file, 256 * 1024);
                if (text == null || !MAIN_METHOD.matcher(text).find()) {
                    continue;
                }
                Matcher pkg = PACKAGE.matcher(text);
                String simple = file.getFileName().toString().replace(".java", "");
                String className = pkg.find() ? pkg.group(1) + "." + simple : simple;
                found.add(new MainClass(className, literalPort(text)));
            }
        } catch (IOException e) {
            log.debug("Could not scan {} for a main class: {}", source, e.getMessage());
            return null;
        }
        if (found.isEmpty()) {
            return null;
        }
        // One that names a port is starting a server; anything else with a main is a tool.
        for (MainClass candidate : found) {
            if (candidate.port() > 0) {
                return candidate;
            }
        }
        return found.get(0);
    }

    private static int literalPort(String text) {
        Matcher m = LITERAL_PORT.matcher(text);
        while (m.find()) {
            for (int group = 1; group <= m.groupCount(); group++) {
                if (m.group(group) != null) {
                    int port = Integer.parseInt(m.group(group));
                    if (port > 0 && port < 65536) {
                        return port;
                    }
                }
            }
        }
        return 0;
    }

    private static Set<String> packageScripts(Path root) {
        String json = readCapped(root.resolve("package.json"), 512 * 1024);
        if (json == null) {
            return Set.of();
        }
        try {
            JsonNode scripts = JSON.readTree(json).path("scripts");
            Set<String> names = new LinkedHashSet<>();
            scripts.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static String nodeBundler(Path root) {
        String json = readCapped(root.resolve("package.json"), 512 * 1024);
        if (json == null) {
            return null;
        }
        try {
            JsonNode pkg = JSON.readTree(json);
            for (String section : List.of("devDependencies", "dependencies")) {
                JsonNode deps = pkg.path(section);
                for (String name : List.of("vite", "next", "react-scripts", "@angular/cli",
                        "webpack", "parcel", "svelte")) {
                    if (deps.has(name)) {
                        return name;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Unreadable package.json in {}: {}", root, e.getMessage());
        }
        return null;
    }

    /** Where the build script writes the page: an existing directory first, else the convention. */
    private static String nodeOutputDir(Path root, String bundler) {
        for (String dir : List.of("dist", "build", "out")) {
            if (Files.isRegularFile(root.resolve(dir).resolve("index.html"))) {
                return dir;
            }
        }
        if (bundler == null) {
            return null;
        }
        return switch (bundler) {
            case "vite", "parcel", "svelte" -> "dist";
            case "react-scripts", "@angular/cli" -> "build";
            case "next" -> "out";
            default -> null;
        };
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String readCapped(Path file, int maxBytes) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return new String(bytes, 0, Math.min(bytes.length, maxBytes), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Unreadable {}: {}", file, e.getMessage());
            return null;
        }
    }
}
