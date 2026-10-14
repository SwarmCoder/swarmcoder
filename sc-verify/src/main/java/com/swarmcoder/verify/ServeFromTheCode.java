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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * How the application of a project with a generated contract is started for its browser
 * checks and journeys, worked out from the code (section 108). No model, nothing run.
 *
 * <p><b>Which class.</b> The entry points are the types of the production code that declare
 * {@code main(String[])}, as the object graph holds them. A main in a module that runs only in
 * a browser is never the application's: it is what a compiler to JavaScript starts from. Of
 * the rest, when there are several, the ones in the module that depends on the most other
 * modules of the project are kept - the module that assembles the application - and then, if
 * still several, the one a build file names. Anything still ambiguous is a question that names
 * the candidates; nothing is guessed. A project can pin the class with the contract's
 * {@link GeneratedContract#ENTRY_POINT_KEY} key.
 *
 * <p><b>Its runtime classpath, offline.</b> Candidates and journeys build with no network, so
 * the command may only use what the offline repository holds:
 * <ul>
 *   <li>When the module's build copies its dependencies ({@code copy-dependencies} of the
 *       dependency plugin, in a phase up to {@code package}), that folder is used: the
 *       project's own build put the jars there, so nothing more is needed.</li>
 *   <li>Otherwise the dependency plugin's {@code build-classpath} goal writes the classpath to
 *       a file, run in the same reactor build as {@code package} so that sibling modules
 *       resolve to their freshly built jars. The dependency plugin is not part of Maven's
 *       default lifecycle, so an offline repository does not have to hold it. It is named with
 *       a version the repository the sandbox mounts holds a jar of; with none, there is no
 *       command and the answer is a question saying so.</li>
 * </ul>
 * Tests are not compiled ({@code -Dmaven.test.skip=true}): the acceptance tests on the tree
 * may name types that do not exist yet, and a test-compile error would read as "the
 * application did not start".
 *
 * <p><b>Its port.</b> The command passes {@code {PORT}} as the environment variable
 * {@code PORT} and the system property {@code port}, which an application can read without
 * the product knowing anything about it; and the block is marked
 * {@link VerifySpec.BrowserSpec#findPort}, so an application that binds a port of its own is
 * found on the port it opened.
 *
 * <p>Maven only. A Gradle project's runtime classpath cannot be named without running Gradle
 * with a task or an init script of SwarmCoder's own; that is not done.
 */
public final class ServeFromTheCode {

    /** The dependency plugin's coordinates, without a version. */
    static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin";

    /** Where the generated command writes the module's runtime classpath, under the module. */
    static final String CLASSPATH_FILE = "target/swarmcoder-runtime-classpath.txt";

    /** Long enough for a cold offline package of a reactor and a server's boot. */
    static final int READY_TIMEOUT_SECONDS = 300;

    /** Phases of the default lifecycle up to and including {@code package}. */
    private static final Set<String> UP_TO_PACKAGE = Set.of("validate", "initialize",
        "generate-sources", "process-sources", "generate-resources", "process-resources",
        "compile", "process-classes", "generate-test-sources", "process-test-sources",
        "generate-test-resources", "process-test-resources", "test-compile",
        "process-test-classes", "test", "prepare-package", "package");

    private ServeFromTheCode() {}

    /**
     * A type that declares {@code main(String[])}.
     *
     * @param file              repo-relative
     * @param type              its full name
     * @param namedInABuildFile a build file names it in full
     */
    public record EntryPoint(String file, String type, boolean namedInABuildFile) {}

    /**
     * Either a browser block or a question; never both.
     *
     * @param browser  the block; null with a question
     * @param chosen   the class it starts; null with a question
     * @param module   that class's module, {@code ""} for the root
     * @param how      in words, why that class and how its classpath is made
     * @param question what a person must settle, naming the candidates; null with a block
     */
    public record Outcome(VerifySpec.BrowserSpec browser, String chosen, String module,
                          String how, String question) {

        static Outcome asking(String question) {
            return new Outcome(null, null, null, null, question);
        }
    }

    /**
     * @param tree              the project's checkout, for its build files
     * @param mains             the entry points the object graph holds
     * @param layout            the build's modules and how they depend on each other
     * @param survey            the modules that run only in a browser; may be null
     * @param pinned            the class the contract names, or null
     * @param offlineRepository the local Maven repository the sandbox mounts; may be null
     */
    public static Outcome serve(Path tree, Collection<EntryPoint> mains, BuildLayout.Layout layout,
                                BrowserOnlyCode.Survey survey, String pinned,
                                Path offlineRepository) {
        List<String> modules = layout == null ? List.of() : layout.compilingModules();
        List<EntryPoint> candidates = new ArrayList<>();
        List<String> inBrowser = new ArrayList<>();
        for (EntryPoint main : mains == null ? List.<EntryPoint>of() : mains) {
            if (ScreenChange.inBrowserOnlyModule(main.file(), survey)) {
                inBrowser.add(main.type());
            } else {
                candidates.add(main);
            }
        }
        if (pinned != null && !pinned.isBlank()) {
            List<EntryPoint> named = candidates.stream()
                .filter(main -> main.type().equals(pinned.strip())).toList();
            if (named.isEmpty()) {
                return Outcome.asking("The contract names `" + GeneratedContract.ENTRY_POINT_KEY
                    + ": " + pinned.strip() + "` as the class that starts the application, and no "
                    + "such class with a main method is in the project's code outside the "
                    + "modules that run only in a browser. " + candidatesSentence(candidates));
            }
            candidates = named;
        }
        if (candidates.isEmpty()) {
            return Outcome.asking("No class with a `main` method is in the project's code"
                + (inBrowser.isEmpty() ? "" : " outside the modules that run only in a browser ("
                    + String.join(", ", inBrowser) + " runs in the browser)")
                + ", so nothing says how to start the application for its journeys. Add the "
                + "class that starts it, or write the `browser:` block of "
                + VerifySpecLoader.SPEC_PATH + " by hand.");
        }
        String why;
        if (candidates.size() == 1) {
            why = "the one class with a main method outside the modules that run only in a "
                + "browser";
        } else {
            List<EntryPoint> assembling = inTheAssemblingModule(candidates, layout, modules);
            why = "the class with a main method in the module that depends on the most other "
                + "modules of the project";
            if (assembling.size() > 1) {
                List<EntryPoint> named = assembling.stream()
                    .filter(EntryPoint::namedInABuildFile).toList();
                if (named.size() == 1) {
                    assembling = named;
                    why = "the class with a main method that a build file names";
                }
            }
            if (assembling.size() != 1) {
                return Outcome.asking("Several classes could start this application and nothing "
                    + "in the build decides between them: " + String.join(", ",
                        candidates.stream().map(EntryPoint::type).toList()) + ". Neither the "
                    + "modules they are in (by how many other modules of the project each "
                    + "depends on) nor the build files (which name none of them, or several) "
                    + "settle it. Add the line `" + GeneratedContract.ENTRY_POINT_KEY
                    + ": <one of them>` to " + VerifySpecLoader.SPEC_PATH + " and answer, or "
                    + "write its `browser:` block by hand.");
            }
            candidates = assembling;
        }
        EntryPoint main = candidates.get(0);
        String module = moduleOf(main.file(), modules);
        String prefix = module.isEmpty() ? "" : module + "/";
        String select = module.isEmpty() ? "" : " -pl " + module + " -am";
        String build = "mvn -o -q -B -Dmaven.test.skip=true" + select + " package";
        String copied = copiedDependencies(tree, module);
        String classpath;
        String how;
        if (copied != null) {
            classpath = "target/classes:" + copied + "/*";
            how = "its runtime classpath is the folder its own build copies its dependencies to ("
                + prefix + copied + ")";
        } else {
            String version = dependencyPluginVersion(offlineRepository);
            if (version == null) {
                return Outcome.asking(main.type() + " starts this application, and its runtime "
                    + "classpath cannot be had offline: the build does not copy its dependencies "
                    + "anywhere (no copy-dependencies of the dependency plugin up to the package "
                    + "phase), and the local Maven repository the builds use offline"
                    + (offlineRepository == null ? "" : " (" + offlineRepository + ")")
                    + " holds no jar of " + DEPENDENCY_PLUGIN + ", which would write it. Run a "
                    + "build of any project that uses that plugin once on this machine, or write "
                    + "the `browser:` block of " + VerifySpecLoader.SPEC_PATH + " by hand.");
            }
            build += " " + DEPENDENCY_PLUGIN + ":" + version + ":build-classpath"
                + " -Dmdep.includeScope=runtime -Dmdep.outputFile=\"$PWD/" + prefix
                + CLASSPATH_FILE + "\"";
            classpath = "target/classes:$(cat " + CLASSPATH_FILE + ")";
            how = "its runtime classpath is written by " + DEPENDENCY_PLUGIN + " " + version
                + " (build-classpath), which the offline repository holds";
        }
        String run = (module.isEmpty() ? "" : "cd " + module + " && ")
            + "export PORT={PORT} && exec java -Dport={PORT} -cp \"" + classpath + "\" "
            + main.type();
        VerifySpec.BrowserSpec block = new VerifySpec.BrowserSpec(build + " && " + run,
            "http://127.0.0.1:{PORT}/", READY_TIMEOUT_SECONDS, List.of(), 0, true);
        return new Outcome(block, main.type(), module, main.type() + " in "
            + (module.isEmpty() ? "the root module" : "module " + module) + ", " + why + "; "
            + how, null);
    }

    private static String candidatesSentence(List<EntryPoint> candidates) {
        return candidates.isEmpty() ? "There is none."
            : "Those there are: " + String.join(", ",
                candidates.stream().map(EntryPoint::type).toList()) + ".";
    }

    /** The candidates in the module that depends, directly or not, on the most other modules. */
    private static List<EntryPoint> inTheAssemblingModule(List<EntryPoint> candidates,
                                                          BuildLayout.Layout layout,
                                                          List<String> modules) {
        int most = -1;
        List<EntryPoint> kept = new ArrayList<>();
        for (EntryPoint main : candidates) {
            String module = moduleOf(main.file(), modules);
            int reach = layout == null ? 0 : dependedOn(layout, module).size();
            if (reach > most) {
                most = reach;
                kept.clear();
            }
            if (reach == most) {
                kept.add(main);
            }
        }
        // Two modules that reach as many is not a decision; the build files may still make one.
        return kept;
    }

    /** The modules {@code module} depends on, directly or not. */
    static Set<String> dependedOn(BuildLayout.Layout layout, String module) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> next = new ArrayDeque<>(layout.directDependenciesOf(module));
        while (!next.isEmpty()) {
            String dep = next.poll();
            if (!dep.equals(module) && seen.add(dep)) {
                next.addAll(layout.directDependenciesOf(dep));
            }
        }
        return seen;
    }

    /** The module a source file is in: the longest module directory it lies under. */
    static String moduleOf(String file, List<String> modules) {
        String path = file == null ? "" : file.replace('\\', '/');
        String best = null;
        for (String module : modules) {
            boolean holds = module.isEmpty() || path.startsWith(module + "/");
            if (holds && (best == null || module.length() > best.length())) {
                best = module;
            }
        }
        if (best != null) {
            return best;
        }
        int src = path.indexOf("src/main/");
        return src <= 0 ? "" : path.substring(0, src - 1);
    }

    /**
     * The folder, relative to the module, its build copies its dependencies to by a phase up to
     * {@code package}; null when it copies none or the folder is not one this can name.
     */
    static String copiedDependencies(Path tree, String module) {
        if (tree == null) {
            return null;
        }
        Document pom = parse(tree.resolve(module.isEmpty() ? "pom.xml" : module + "/pom.xml"));
        if (pom == null) {
            return null;
        }
        Element build = child(pom.getDocumentElement(), "build");
        Element plugins = build == null ? null : child(build, "plugins");
        if (plugins == null) {
            return null;
        }
        for (Element plugin : children(plugins, "plugin")) {
            if (!"maven-dependency-plugin".equals(text(child(plugin, "artifactId")))) {
                continue;
            }
            String pluginDir = outputDirectory(child(plugin, "configuration"));
            Element executions = child(plugin, "executions");
            if (executions == null) {
                continue;
            }
            for (Element execution : children(executions, "execution")) {
                Element goals = child(execution, "goals");
                boolean copies = goals != null && children(goals, "goal").stream()
                    .anyMatch(goal -> "copy-dependencies".equals(text(goal)));
                String phase = text(child(execution, "phase"));
                if (!copies || (phase != null && !UP_TO_PACKAGE.contains(phase))) {
                    continue;
                }
                String dir = outputDirectory(child(execution, "configuration"));
                String folder = moduleRelative(dir != null ? dir : pluginDir != null ? pluginDir
                    : "${project.build.directory}/dependency");
                if (folder != null) {
                    return folder;
                }
            }
        }
        return null;
    }

    private static String outputDirectory(Element configuration) {
        return configuration == null ? null : text(child(configuration, "outputDirectory"));
    }

    /** A build-file folder made relative to its module; null when it names something else. */
    private static String moduleRelative(String dir) {
        String folder = dir.strip().replace('\\', '/')
            .replace("${project.build.directory}", "target")
            .replace("${project.basedir}/", "").replace("${basedir}/", "");
        while (folder.startsWith("./")) {
            folder = folder.substring(2);
        }
        folder = folder.replaceAll("/+$", "");
        if (folder.isEmpty() || folder.contains("${") || folder.startsWith("/")
                || folder.contains("..") || folder.contains("\"") || folder.contains("$")
                || folder.contains(" ")) {
            return null;
        }
        return folder;
    }

    /** The highest version of the dependency plugin whose jar the repository holds; else null. */
    static String dependencyPluginVersion(Path repository) {
        if (repository == null) {
            return null;
        }
        Path dir = repository.resolve("org/apache/maven/plugins/maven-dependency-plugin");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> versions = Files.list(dir)) {
            return versions.filter(Files::isDirectory)
                .map(v -> v.getFileName().toString())
                .filter(v -> v.matches("[0-9][0-9A-Za-z.-]*") && !v.endsWith("-SNAPSHOT"))
                .filter(v -> Files.isRegularFile(dir.resolve(v)
                    .resolve("maven-dependency-plugin-" + v + ".jar")))
                .max(ServeFromTheCode::compareVersions).orElse(null);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    static int compareVersions(String a, String b) {
        String[] left = a.split("[.-]");
        String[] right = b.split("[.-]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            int compared = l.matches("\\d+") && r.matches("\\d+")
                ? Long.compare(Long.parseLong(l), Long.parseLong(r)) : l.compareTo(r);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- XML

    private static Document parse(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 512 * 1024) {
                return null;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                Files.readString(file).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {                                             // noqa
            return null;
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && name.equals(element.getTagName())) {
                found.add(element);
            }
        }
        return found;
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        return text == null || text.isBlank() ? null : text.strip();
    }
}
