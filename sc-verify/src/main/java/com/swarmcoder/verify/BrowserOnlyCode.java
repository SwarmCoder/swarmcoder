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

import com.swarmcoder.domain.TestFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which modules of a build hold code that can only run in a browser — and whether a test failure
 * shows that a JUnit test reached such code anyway.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 37, 2026-09-25, DeepSeek V4 Flash, on {@code dev/bookshelf-demo}: a ZeroZ Stack
 * app whose {@code bookshelf-demo-client} module is Java compiled to JavaScript by TeaVM. Its
 * classes call {@code org.teavm.jso.*} methods that are declared {@code native} and exist only once
 * TeaVM has turned them into JavaScript in a page. The agreed check was "after restarting the
 * browser, previously added books and their ratings are still present". The planner — told, as it
 * always is, that a check about what a user sees or does belongs to the client task — gave it to
 * "Create the client BookStore singleton". The test author, told only that the server module's
 * classpath holds {@code bookshelf-demo-client} (it does: the server packages the compiled bundle,
 * at provided scope), wrote a JUnit test in the server module that called
 * {@code BookStore.getInstance()}. Both workers wrote a BookStore that compiled; both failed with
 * {@code UnsatisfiedLinkError: org.teavm.jso.browser.Window.current()} (Native Method). No code
 * could ever have passed that test, and nothing before the workers could see it:
 *
 * <ul>
 *   <li>the red-check at TEST_AUTHORING and at the wave gate saw only "BookStore does not
 *       compile" — a healthy red, because the client task was about to deliver it. The
 *       UnsatisfiedLinkError needs the class to EXIST, so it is first visible on a candidate;</li>
 *   <li>the test-repair gate only fires when every candidate's failure never left the test's own
 *       class, and this trace ran through the candidate's BookStore, so the run went to an
 *       ordinary repair round and spent two more workers.</li>
 * </ul>
 *
 * <h2>What this class answers</h2>
 *
 * <p><b>Build knowledge</b> ({@link #survey}): a module is browser-only when its OWN build file
 * declares a browser runtime — TeaVM ({@code org.teavm}), ZeroZ Stack's browser client
 * ({@code com.zeroz4j:zerozstack-client} / {@code zerozstack-ui-components}, both TeaVM underneath),
 * GWT, Elemental2 or J2CL — as a dependency (any scope but test) or as a build plugin. Being browser-
 * only is NOT inherited: {@code bookshelf-demo-server} depends on the client module only to package
 * its bundle, and its own code runs on the JVM. The survey also records every module's packages, so
 * a name a test uses can be traced to the module that owns it — including a package that does not
 * exist yet but sits under one that does ({@code ...client.store} under {@code ...client}).
 *
 * <p><b>Failure reading</b> ({@link #reachedIn}): an {@link UnsatisfiedLinkError} anywhere in the
 * trace, a native method of a browser runtime as the frame that threw, or a class of a browser
 * runtime that could not be loaded. That is not an assertion that did not hold and not a bug a
 * candidate made: it is a test asking this JVM to run code that only exists in a browser.
 *
 * <h2>What it will not do</h2>
 *
 * <p>Guess. An unreadable build file marks nothing; a {@code NoClassDefFoundError} for an ordinary
 * class is a missing dependency a candidate may well be able to add, and is left alone; and a
 * package present in both a browser-only module and a JVM module (a split package) is not called
 * browser-only, because a test naming it may be naming the JVM half.
 */
public final class BrowserOnlyCode {

    private static final Logger log = LoggerFactory.getLogger(BrowserOnlyCode.class);

    /** Enough for any real module; a guard against walking a vendored tree for ever. */
    private static final int MAX_SOURCE_FILES_PER_MODULE = 20_000;

    /**
     * Package prefixes whose classes exist only inside a browser runtime. A test that imports one
     * of these, or a failure naming one, is about code a plain JVM cannot run.
     */
    static final List<String> BROWSER_RUNTIME_PACKAGES = List.of(
        "org.teavm.", "com.zeroz4j.client.", "com.zeroz4j.ui.", "com.google.gwt.",
        "org.gwtproject.", "elemental2.");

    /**
     * One marker on a build file that makes its module browser-only.
     *
     * @param group    the groupId, matched exactly
     * @param artifact the artifactId, or null for "any artifact of that group"
     * @param runtime  what that marker means, in plain words, for the sentence a person reads
     */
    private record Marker(String group, String artifact, String runtime) {
        boolean matches(String groupId, String artifactId) {
            return group.equals(groupId) && (artifact == null || artifact.equals(artifactId));
        }
    }

    private static final List<Marker> MARKERS = List.of(
        new Marker("org.teavm", null, "TeaVM, which compiles Java to JavaScript"),
        new Marker("com.zeroz4j", "zerozstack-client",
            "ZeroZ Stack's browser client, which TeaVM compiles to JavaScript"),
        new Marker("com.zeroz4j", "zerozstack-ui-components",
            "ZeroZ Stack's browser UI components, which TeaVM compiles to JavaScript"),
        new Marker("com.google.gwt", null, "GWT, which compiles Java to JavaScript"),
        new Marker("org.gwtproject", null, "GWT, which compiles Java to JavaScript"),
        new Marker("com.google.elemental2", null, "Elemental2, the browser's own DOM API"),
        new Marker("com.vertispan.j2cl", null, "J2CL, which compiles Java to JavaScript"));

    /** The same markers as text, for a Gradle script this class does not parse. */
    private static final Pattern GRADLE_MARKER = Pattern.compile(
        "org\\.teavm|zerozstack-client|zerozstack-ui-components|com\\.google\\.gwt|org\\.gwtproject"
            + "|com\\.google\\.elemental2|com\\.vertispan\\.j2cl");

    private BrowserOnlyCode() {}

    /**
     * One module whose code runs only in a browser.
     *
     * @param dir      the module directory, repo-relative; {@code ""} is the repository root
     * @param evidence what its build file says, e.g. {@code "declares org.teavm:teavm-classlib"}
     * @param runtime  what that means, e.g. {@code "TeaVM, which compiles Java to JavaScript"}
     * @param packages the packages its main sources hold today, sorted
     */
    public record Module(String dir, String evidence, String runtime, List<String> packages) {

        /** {@code bookshelf-demo-client}, or "the repository root". */
        public String name() {
            return dir == null || dir.isEmpty() ? "the repository root" : dir;
        }
    }

    /**
     * What a build says about where its code can run.
     *
     * @param browserOnly    the modules whose code runs only in a browser, in reactor order
     * @param jvmModules     every other compiling module, in reactor order
     * @param packageOwners  package name to the directories of every module whose main sources
     *                       hold it — browser-only or not — which is what makes a split package
     *                       visible
     */
    public record Survey(List<Module> browserOnly, List<String> jvmModules,
                         Map<String, List<String>> packageOwners) {

        public static final Survey NONE = new Survey(List.of(), List.of(), Map.of());

        /** True when at least one module of this build cannot run on the JVM. */
        public boolean any() {
            return !browserOnly.isEmpty();
        }

        /**
         * The browser-only module that owns this qualified type or package name, or null when it
         * is owned by a JVM module, by several modules at once, or by nothing this survey knows.
         *
         * <p>Longest known package prefix wins, on a whole-segment boundary, so a type that does
         * not exist yet in a sub-package of the client ({@code com.acme.client.store.BookStore})
         * is still traced to the client, while {@code com.acme.clientele.X} is not.
         */
        public Module moduleOwning(String qualifiedName) {
            if (qualifiedName == null || qualifiedName.isBlank() || browserOnly.isEmpty()) {
                return null;
            }
            String name = qualifiedName.strip();
            String best = null;
            for (String pkg : packageOwners.keySet()) {
                if ((name.equals(pkg) || name.startsWith(pkg + "."))
                        && (best == null || pkg.length() > best.length())) {
                    best = pkg;
                }
            }
            if (best == null) {
                return null;
            }
            List<String> owners = packageOwners.get(best);
            Module only = null;
            for (String owner : owners) {
                Module module = browserOnlyAt(owner);
                if (module == null) {
                    return null; // a JVM module holds this package too: not provably browser-only
                }
                only = module;
            }
            return only;
        }

        /** The browser-only module at this directory, or null. */
        public Module browserOnlyAt(String dir) {
            for (Module module : browserOnly) {
                if (module.dir().equals(dir)) {
                    return module;
                }
            }
            return null;
        }

        /** The directories of the browser-only modules, for a sentence or a lookup. */
        public List<String> browserOnlyDirs() {
            return browserOnly.stream().map(Module::dir).toList();
        }
    }

    /**
     * True when {@code qualifiedName} is a type or package of a browser runtime itself — TeaVM's
     * JSO, ZeroZ Stack's browser client, GWT, Elemental2 — whatever module happens to have it on
     * its classpath.
     */
    public static boolean isBrowserRuntime(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }
        String name = qualifiedName.strip().replace('/', '.');
        for (String prefix : BROWSER_RUNTIME_PACKAGES) {
            if (name.startsWith(prefix) || name.equals(prefix.substring(0, prefix.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads every compiling module's own build file and main sources.
     *
     * <p>Never throws and never guesses: an undetermined layout, an unreadable build file or an
     * unwalkable source root costs that module's answer and nothing else, and {@link Survey#NONE}
     * comes back when nothing could be read at all — which every caller treats as "no module is
     * known to be browser-only", exactly the behaviour before this class existed.
     */
    public static Survey survey(Path repoRoot, BuildLayout.Layout layout) {
        if (repoRoot == null || layout == null || !layout.determined()
                || layout.compilingModules().isEmpty()) {
            return Survey.NONE;
        }
        List<Module> browserOnly = new ArrayList<>();
        List<String> jvm = new ArrayList<>();
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (String dir : layout.compilingModules()) {
            List<String> packages = packagesOf(repoRoot, layout, dir);
            for (String pkg : packages) {
                owners.computeIfAbsent(pkg, k -> new ArrayList<>()).add(dir);
            }
            String[] marker = markerOf(repoRoot, layout.toolchain(), dir);
            if (marker != null) {
                browserOnly.add(new Module(dir, marker[0], marker[1], packages));
            } else {
                jvm.add(dir);
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        owners.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new Survey(List.copyOf(browserOnly), List.copyOf(jvm), frozen);
    }

    /** {@code [evidence, runtime]} when this module's own build file declares a browser runtime. */
    private static String[] markerOf(Path repoRoot, String toolchain, String dir) {
        Path moduleDir = dir.isEmpty() ? repoRoot : repoRoot.resolve(dir);
        if ("gradle".equals(toolchain)) {
            for (String name : List.of("build.gradle", "build.gradle.kts")) {
                String script = read(moduleDir.resolve(name));
                if (script == null) {
                    continue;
                }
                Matcher matcher = GRADLE_MARKER.matcher(stripLineComments(script));
                if (matcher.find()) {
                    String found = matcher.group();
                    return new String[] {"its build script names " + found, runtimeFor(found)};
                }
            }
            return null;
        }
        String pom = read(moduleDir.resolve("pom.xml"));
        Document doc = pom == null ? null : BuildLayout.parse(pom);
        if (doc == null) {
            return null;
        }
        Element project = doc.getDocumentElement();
        for (Element dependency : children(BuildLayout.child(project, "dependencies"), "dependency")) {
            String scope = text(dependency, "scope");
            if ("test".equalsIgnoreCase(scope)) {
                continue; // a browser test runner in test scope does not make main code browser-only
            }
            String group = text(dependency, "groupId");
            String artifact = text(dependency, "artifactId");
            for (Marker marker : MARKERS) {
                if (marker.matches(group, artifact)) {
                    return new String[] {"declares " + group + ":" + artifact, marker.runtime()};
                }
            }
        }
        Element build = BuildLayout.child(project, "build");
        for (Element plugin : children(BuildLayout.child(build, "plugins"), "plugin")) {
            String group = text(plugin, "groupId");
            String artifact = text(plugin, "artifactId");
            for (Marker marker : MARKERS) {
                if (marker.matches(group, artifact)) {
                    return new String[] {"builds with the " + artifact + " plugin", marker.runtime()};
                }
            }
            if ("gwt-maven-plugin".equals(artifact)) {
                return new String[] {"builds with the gwt-maven-plugin",
                    "GWT, which compiles Java to JavaScript"};
            }
        }
        return null;
    }

    private static String runtimeFor(String found) {
        String lower = found.toLowerCase(Locale.ROOT);
        if (lower.contains("teavm")) {
            return "TeaVM, which compiles Java to JavaScript";
        }
        if (lower.contains("zerozstack")) {
            return "ZeroZ Stack's browser client, which TeaVM compiles to JavaScript";
        }
        if (lower.contains("elemental2")) {
            return "Elemental2, the browser's own DOM API";
        }
        if (lower.contains("j2cl")) {
            return "J2CL, which compiles Java to JavaScript";
        }
        return "GWT, which compiles Java to JavaScript";
    }

    /**
     * The packages this module's main sources hold today, read from the layout's own source
     * roots under the module — never its test tree, its resources or its web directory.
     */
    private static List<String> packagesOf(Path repoRoot, BuildLayout.Layout layout, String dir) {
        Set<String> packages = new LinkedHashSet<>();
        for (String root : layout.sourceRoots()) {
            String relative;
            if (dir.isEmpty()) {
                relative = root;
            } else if (root.startsWith(dir + "/")) {
                relative = root.substring(dir.length() + 1);
            } else {
                continue;
            }
            if (!dir.isEmpty() || belongsToRootModule(layout, root)) {
                if (isMainCodeRoot(relative)) {
                    collectPackages(repoRoot.resolve(root), packages);
                }
            }
        }
        List<String> sorted = new ArrayList<>(packages);
        sorted.sort(null);
        return List.copyOf(sorted);
    }

    /** A root at the repository root belongs to the root module only if no other module owns it. */
    private static boolean belongsToRootModule(BuildLayout.Layout layout, String root) {
        for (String module : layout.compilingModules()) {
            if (!module.isEmpty() && root.startsWith(module + "/")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMainCodeRoot(String relative) {
        String r = relative.replace('\\', '/');
        if (r.endsWith("resources") || r.endsWith("webapp")) {
            return false;
        }
        for (String segment : r.split("/")) {
            if ("test".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static void collectPackages(Path sourceRoot, Set<String> into) {
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sourceRoot, 24)) {
            files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".java") || name.endsWith(".kt");
                })
                .limit(MAX_SOURCE_FILES_PER_MODULE)
                .forEach(file -> {
                    Path parent = sourceRoot.relativize(file.getParent());
                    String pkg = parent.toString().replace('\\', '/').replace('/', '.');
                    if (!pkg.isEmpty()) {
                        into.add(pkg);
                    }
                });
        } catch (IOException | RuntimeException e) {
            log.debug("Browser-only survey: could not walk {}: {}", sourceRoot, e.toString());
        }
    }

    // ------------------------------------------------------------------- failure reading

    /** A throwable type on a trace's header or "Caused by:" line, and its message. */
    private static final Pattern THROWABLE_LINE = Pattern.compile(
        "^(?:Caused by:\\s*)?([\\w$.]+(?:Error|Exception|Throwable))(?::\\s*(.*))?$");
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+([\\w.$]+)\\.([\\w$<>]+)\\((.*)\\)\\s*$");

    /**
     * When this failure shows the test reached code that can only run in a browser, a plain
     * sentence saying so; otherwise null.
     *
     * <p>Three shapes, any of which is enough:
     * <ol>
     *   <li>an {@link UnsatisfiedLinkError} on the header or any "Caused by:" line — a native
     *       method this JVM does not have (run 37's exact failure);</li>
     *   <li>the first frame is a {@code (Native Method)} of a browser runtime package, whatever was
     *       thrown;</li>
     *   <li>a {@code NoClassDefFoundError}, {@code ClassNotFoundException} or
     *       {@code ExceptionInInitializerError} whose text names a browser runtime package — the
     *       runtime's classes were never on this classpath at all.</li>
     * </ol>
     *
     * <p>An assertion failure is never this, and neither is an ordinary missing class: a candidate
     * can add a dependency, and it cannot give a JVM a browser.
     */
    public static String reachedIn(TestFailure failure) {
        if (failure == null) {
            return null;
        }
        String trace = failure.truncatedTrace() == null ? "" : failure.truncatedTrace();
        String message = failure.message() == null ? "" : failure.message();
        String firstFrameClass = null;
        String firstFrameWhere = null;
        String unsatisfied = null;
        String unloadable = null;
        for (String raw : trace.split("\r?\n")) {
            String line = raw.strip();
            Matcher frame = FRAME.matcher(line);
            if (frame.matches()) {
                if (firstFrameClass == null) {
                    firstFrameClass = frame.group(1);
                    firstFrameWhere = frame.group(3);
                }
                continue;
            }
            Matcher thrown = THROWABLE_LINE.matcher(line);
            if (!thrown.matches()) {
                continue;
            }
            String type = thrown.group(1);
            String text = thrown.group(2) == null ? "" : thrown.group(2);
            if (type.endsWith("UnsatisfiedLinkError") && unsatisfied == null) {
                unsatisfied = text.isBlank() ? message : text;
            } else if ((type.endsWith("NoClassDefFoundError") || type.endsWith("ClassNotFoundException")
                    || type.endsWith("ExceptionInInitializerError"))
                    && namesBrowserRuntime(text + " " + message) && unloadable == null) {
                unloadable = type.substring(type.lastIndexOf('.') + 1) + ": "
                    + (text.isBlank() ? message : text);
            }
        }
        // A trace with no header line at all still carries the runner's message.
        if (unsatisfied == null && trace.isBlank() && message.contains("UnsatisfiedLinkError")) {
            unsatisfied = message;
        }
        boolean nativeInRuntime = firstFrameClass != null && "Native Method".equals(firstFrameWhere)
            && isBrowserRuntime(firstFrameClass);
        if (unsatisfied == null && unloadable == null && !nativeInRuntime) {
            return null;
        }
        StringBuilder sentence = new StringBuilder("the test reached code that can only run in a "
            + "browser: ");
        if (unsatisfied != null) {
            sentence.append("UnsatisfiedLinkError").append(unsatisfied.isBlank() ? "" : ": " + unsatisfied.strip())
                .append(" — a native method this JVM does not have");
        } else if (unloadable != null) {
            sentence.append(unloadable.strip()).append(" — a browser runtime class this JVM cannot load");
        } else {
            sentence.append("the call into ").append(firstFrameClass)
                .append(" is a native method that exists only once it is compiled to JavaScript");
        }
        if (firstFrameClass != null && isBrowserRuntime(firstFrameClass)) {
            sentence.append(" (").append(firstFrameClass).append(" belongs to a browser runtime)");
        }
        return sentence.toString();
    }

    private static boolean namesBrowserRuntime(String text) {
        String dotted = text.replace('/', '.');
        for (String prefix : BROWSER_RUNTIME_PACKAGES) {
            if (dotted.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- utility

    private static List<Element> children(Element parent, String tag) {
        if (parent == null) {
            return List.of();
        }
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && tag.equals(element.getTagName())) {
                out.add(element);
            }
        }
        return out;
    }

    private static String text(Element parent, String name) {
        String value = BuildLayout.childText(parent, name);
        return value == null ? "" : value.strip();
    }

    private static String stripLineComments(String script) {
        StringBuilder out = new StringBuilder(script.length());
        for (String line : script.split("\r?\n")) {
            int comment = line.indexOf("//");
            out.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return out.toString();
    }

    private static String read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 512 * 1024) {
                return null;
            }
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            log.debug("Browser-only survey: could not read {}: {}", file, e.toString());
            return null;
        }
    }
}
