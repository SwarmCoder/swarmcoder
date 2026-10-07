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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a repository's build actually looks for code — read out of the candidate's OWN workspace,
 * not out of a convention.
 *
 * <p>This answers the only question {@link BuildReachabilityCheck} needs: given this repository as
 * the candidate left it, which directories will a build compile or package? It is deliberately
 * separate from {@link ToolchainDetector}, which answers a different question (how do I build a
 * repository I have never seen) and proposes commands for an operator to correct. Nothing here
 * proposes anything; it reads what is there, and says plainly when it cannot.
 *
 * <p><b>It reads the candidate's workspace on purpose.</b> A candidate is allowed to add a module:
 * a new {@code pom.xml} plus a {@code <module>} entry in the aggregator is a legitimate change, and
 * reading the layout from the pre-change tree would fail exactly that candidate for doing the right
 * thing. Reading it from the post-change tree makes the new module a source root like any other.
 *
 * <p><b>Only Maven and Gradle are read.</b> Their source roots are convention, declared in files
 * this class can parse. Node, Cargo and Python place compiled or bundled sources wherever a config
 * file says, and there is no honest way to derive the set from the repository alone — so those
 * report undetermined and gate nothing.
 */
public final class BuildLayout {

    private static final Logger log = LoggerFactory.getLogger(BuildLayout.class);

    /** Enough for any real reactor; a guard against a pom cycle or a pathological checkout. */
    private static final int MAX_BUILD_FILES = 400;
    private static final int MAX_BUILD_FILE_BYTES = 512 * 1024;

    /** The convention source roots of a JVM module, relative to the module directory. */
    private static final List<String> JVM_SOURCE_ROOTS = List.of(
        "src/main/java", "src/main/kotlin", "src/main/scala", "src/main/groovy",
        "src/test/java", "src/test/kotlin", "src/test/scala", "src/test/groovy",
        "src/main/resources", "src/test/resources");

    /**
     * One compiling module and the sibling modules of the same build that it depends on.
     *
     * <p>Direct dependencies only; anything transitive is the caller's arithmetic. This is what
     * makes it possible to ask "whose classpath can see the rest of this project?" — the question
     * {@link AcceptanceTestLocation} must answer before a story-level test can be placed anywhere
     * at all.
     *
     * @param dir       the module's directory, repo-relative; {@code ""} is the repository root
     * @param dependsOn directories of sibling modules this one depends on, in declaration order
     */
    public record ModuleNode(String dir, List<String> dependsOn) {}

    /**
     * @param toolchain        maven | gradle, or null when nothing readable was found
     * @param sourceRoots      every directory the build compiles or packages, repo-relative,
     *                         forward slashes, no trailing slash
     * @param compilingModules directories of modules the build actually builds. {@code ""} means
     *                         the repository root itself is one — which an aggregator pom is NOT
     * @param moduleGraph      one entry per compiling module, naming the siblings it depends on;
     *                         empty when the build files did not say
     * @param note             plain English about what was read, or why nothing could be
     */
    public record Layout(String toolchain, List<String> sourceRoots, List<String> compilingModules,
                         List<ModuleNode> moduleGraph, String note) {

        public boolean determined() {
            return !sourceRoots.isEmpty();
        }

        static Layout undetermined(String note) {
            return new Layout(null, List.of(), List.of(), List.of(), note);
        }

        /** The siblings {@code dir} depends on directly; empty when nothing was read about it. */
        public List<String> directDependenciesOf(String dir) {
            for (ModuleNode node : moduleGraph) {
                if (node.dir().equals(dir)) {
                    return node.dependsOn();
                }
            }
            return List.of();
        }
    }

    private BuildLayout() {}

    /**
     * The convention source and resource directories of a JVM module, relative to the module
     * directory — exposed for a caller with no live checkout to read an actual {@link Layout}
     * from. The judge is the reason this exists: a candidate's sandbox is gone by the time its
     * diff reaches judging, so it cannot read the real reactor the way {@link #read} does, and
     * this lets it still ask whether a path plausibly sits somewhere a JVM build would compile
     * or package from, using the same convention this class itself falls back to.
     */
    public static List<String> conventionalSourceRootSegments() {
        return JVM_SOURCE_ROOTS;
    }

    /**
     * Reads the layout of the workspace behind {@code target}.
     *
     * @param toolchain the verification contract's declared toolchain; used to decide whether the
     *                  layout is readable at all. Null falls back to whichever build file is found.
     */
    public static Layout read(ExecTarget target, String toolchain) {
        String tc = toolchain == null ? "" : toolchain.toLowerCase(Locale.ROOT);
        if (!tc.isEmpty() && !"maven".equals(tc) && !"gradle".equals(tc)) {
            return Layout.undetermined("The verification contract declares the '" + tc
                + "' toolchain. Its source roots are configuration rather than convention, so where "
                + "this repository compiles from cannot be read from the repository itself. Nothing "
                + "is concluded about where these files should have gone.");
        }
        if (tc.isEmpty() || "maven".equals(tc)) {
            String rootPom = readFile(target, "pom.xml");
            if (rootPom != null) {
                return maven(target, rootPom);
            }
            if ("maven".equals(tc)) {
                return Layout.undetermined("The verification contract declares the maven toolchain, "
                    + "but there is no pom.xml at the repository root, so the reactor's module "
                    + "layout could not be read.");
            }
        }
        if (tc.isEmpty() || "gradle".equals(tc)) {
            Layout gradle = gradle(target);
            if (gradle != null) {
                return gradle;
            }
        }
        return Layout.undetermined("No Maven or Gradle build file could be read at the repository "
            + "root, so which directories this build compiles is unknown.");
    }

    /**
     * Convenience for callers holding a directory rather than an exec target — the planner, which
     * has to be told where this repository's code actually lives before it invents a path.
     */
    public static Layout read(java.nio.file.Path root, String toolchain) {
        if (root == null) {
            return Layout.undetermined("No target repository is configured, so its module layout "
                + "could not be read.");
        }
        return read(new LocalProcessExecTarget(root), toolchain);
    }

    // ---------------------------------------------------------------- Maven

    /**
     * Walks the reactor from the root pom. A pom with {@code <packaging>pom</packaging>} is an
     * aggregator: it compiles NOTHING itself, it only orders its modules — which is the whole
     * mechanism the orphan-path defect slipped through. Every other packaging is a module that
     * compiles, and contributes its source roots.
     */
    private static Layout maven(ExecTarget target, String rootPom) {
        Set<String> roots = new LinkedHashSet<>();
        Set<String> compiling = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        // artifactId -> module directory, and module directory -> the artifactIds it depends on.
        // Resolved into directories at the end, because a module can be declared before the
        // module it depends on and a single pass would miss the edge.
        java.util.Map<String, String> dirOfArtifact = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<String>> declaredDeps = new java.util.LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add("");                 // "" = the repository root module
        seen.add("");
        String pomText = rootPom;
        int read = 0;
        List<String> unreadable = new ArrayList<>();

        while (!queue.isEmpty() && read < MAX_BUILD_FILES) {
            String dir = queue.poll();
            read++;
            if (!dir.isEmpty()) {
                pomText = readFile(target, join(dir, "pom.xml"));
            }
            if (pomText == null) {
                unreadable.add(join(dir, "pom.xml"));
                continue;
            }
            Document doc = parse(pomText);
            pomText = null;
            if (doc == null) {
                unreadable.add(join(dir, "pom.xml"));
                continue;
            }
            String packaging = childText(doc.getDocumentElement(), "packaging");
            boolean aggregator = "pom".equalsIgnoreCase(packaging == null ? "" : packaging.trim());
            if (!aggregator) {
                compiling.add(dir);
                roots.addAll(mavenRootsOf(dir, doc, packaging));
                String artifactId = childText(doc.getDocumentElement(), "artifactId");
                if (artifactId != null && !artifactId.isBlank()) {
                    dirOfArtifact.putIfAbsent(artifactId.trim(), dir);
                }
                declaredDeps.put(dir, mavenDependencyArtifactIds(doc));
            }
            // <module> anywhere in the document, profiles included: a module a profile activates
            // is still a module, and missing it would strand its sources on a false alarm.
            NodeList modules = doc.getElementsByTagName("module");
            for (int i = 0; i < modules.getLength(); i++) {
                String child = modules.item(i).getTextContent();
                if (child == null || child.isBlank()) {
                    continue;
                }
                String childDir = normalize(join(dir, child.trim()));
                if (childDir != null && !childDir.isEmpty() && seen.add(childDir)) {
                    queue.add(childDir);
                }
            }
        }

        if (roots.isEmpty()) {
            return Layout.undetermined("The Maven reactor was read from pom.xml but not one module "
                + "in it compiles anything (every pom found is a <packaging>pom</packaging> "
                + "aggregator, or its pom could not be read), so there is no source root to compare "
                + "against.");
        }
        StringBuilder note = new StringBuilder("Read from the Maven reactor: ")
            .append(compiling.size()).append(compiling.size() == 1 ? " module compiles" : " modules compile")
            .append(" (").append(String.join(", ", compiling.stream()
                .map(d -> d.isEmpty() ? "the repository root" : d).toList())).append(").");
        if (!unreadable.isEmpty()) {
            note.append(" These declared poms could not be read and were left out: ")
                .append(String.join(", ", unreadable)).append('.');
        }
        List<ModuleNode> graph = new ArrayList<>();
        for (String dir : compiling) {
            List<String> siblings = new ArrayList<>();
            for (String artifact : declaredDeps.getOrDefault(dir, List.of())) {
                String siblingDir = dirOfArtifact.get(artifact);
                if (siblingDir != null && !siblingDir.equals(dir) && !siblings.contains(siblingDir)) {
                    siblings.add(siblingDir);
                }
            }
            graph.add(new ModuleNode(dir, List.copyOf(siblings)));
        }
        return new Layout("maven", List.copyOf(roots), List.copyOf(compiling), List.copyOf(graph),
            note.toString());
    }

    /**
     * The artifactIds this pom depends on — its own {@code <dependencies>} only.
     *
     * <p>Read from the direct child element on purpose: {@code <dependencyManagement>} and a
     * plugin's own {@code <dependencies>} both contain the same tag names, and counting those as
     * real dependencies would make a parent pom that merely PINS a sibling's version look like it
     * used the sibling. Every Maven scope — compile, runtime, provided, test — puts the dependency
     * on the module's test classpath, so no scope is filtered out.
     */
    private static List<String> mavenDependencyArtifactIds(Document doc) {
        Element dependencies = child(doc.getDocumentElement(), "dependencies");
        if (dependencies == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        NodeList children = dependencies.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && "dependency".equals(element.getTagName())) {
                String artifactId = childText(element, "artifactId");
                if (artifactId != null && !artifactId.isBlank()) {
                    ids.add(artifactId.trim());
                }
            }
        }
        return List.copyOf(ids);
    }

    /** Convention roots plus anything the pom overrides or adds explicitly. */
    private static Set<String> mavenRootsOf(String dir, Document doc, String packaging) {
        Set<String> roots = new LinkedHashSet<>();
        Element build = child(doc.getDocumentElement(), "build");
        String sourceDir = build == null ? null : childText(build, "sourceDirectory");
        String testSourceDir = build == null ? null : childText(build, "testSourceDirectory");

        for (String convention : JVM_SOURCE_ROOTS) {
            if (sourceDir != null && "src/main/java".equals(convention)) {
                continue;   // overridden below
            }
            if (testSourceDir != null && "src/test/java".equals(convention)) {
                continue;
            }
            roots.add(normalize(join(dir, convention)));
        }
        if (sourceDir != null) {
            roots.add(normalize(join(dir, stripProperties(sourceDir))));
        }
        if (testSourceDir != null) {
            roots.add(normalize(join(dir, stripProperties(testSourceDir))));
        }
        if (build != null) {
            addResourceDirs(roots, dir, child(build, "resources"));
            addResourceDirs(roots, dir, child(build, "testResources"));
        }
        if ("war".equalsIgnoreCase(packaging == null ? "" : packaging.trim())) {
            roots.add(normalize(join(dir, "src/main/webapp")));
        }
        roots.remove(null);
        return roots;
    }

    private static void addResourceDirs(Set<String> roots, String dir, Element container) {
        if (container == null) {
            return;
        }
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                String directory = childText(element, "directory");
                if (directory != null && !directory.isBlank()) {
                    String resolved = normalize(join(dir, stripProperties(directory)));
                    if (resolved != null && !resolved.isEmpty()) {
                        roots.add(resolved);
                    }
                }
            }
        }
    }

    /**
     * Strips a leading {@code ${project.basedir}} / {@code ${basedir}} so a declared directory
     * resolves against the module. Any other property is left alone — the path then simply does not
     * match anything, which costs a noted file, never a false failure.
     */
    private static String stripProperties(String raw) {
        String value = raw.trim().replace('\\', '/');
        for (String prefix : List.of("${project.basedir}/", "${basedir}/", "${project.build.directory}/")) {
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }

    // --------------------------------------------------------------- Gradle

    private static final Pattern GRADLE_INCLUDE = Pattern.compile("(?m)^\\s*include\\s*\\(?\\s*(.+)$");
    private static final Pattern GRADLE_PROJECT_PATH = Pattern.compile("[\"']([^\"']+)[\"']");

    /** Null when this is not a Gradle repository at all. */
    private static Layout gradle(ExecTarget target) {
        String settings = firstPresent(target, "settings.gradle", "settings.gradle.kts");
        String rootBuild = firstPresent(target, "build.gradle", "build.gradle.kts");
        if (settings == null && rootBuild == null) {
            return null;
        }
        Set<String> compiling = new LinkedHashSet<>();
        if (rootBuild != null) {
            compiling.add("");
        }
        if (settings != null) {
            Matcher matcher = GRADLE_INCLUDE.matcher(settings);
            while (matcher.find() && compiling.size() < MAX_BUILD_FILES) {
                Matcher paths = GRADLE_PROJECT_PATH.matcher(matcher.group(1));
                while (paths.find()) {
                    String dir = normalize(paths.group(1).replace(':', '/'));
                    if (dir != null && !dir.isEmpty()) {
                        compiling.add(dir);
                    }
                }
            }
        }
        if (compiling.isEmpty()) {
            return Layout.undetermined("A Gradle settings file was found but no included project "
                + "could be read out of it, so which directories this build compiles is unknown.");
        }
        Set<String> roots = new LinkedHashSet<>();
        for (String dir : compiling) {
            for (String convention : JVM_SOURCE_ROOTS) {
                roots.add(normalize(join(dir, convention)));
            }
        }
        roots.remove(null);
        List<ModuleNode> graph = new ArrayList<>();
        for (String dir : compiling) {
            String script = dir.isEmpty() ? rootBuild
                : firstPresent(target, join(dir, "build.gradle"), join(dir, "build.gradle.kts"));
            graph.add(new ModuleNode(dir, gradleProjectDependencies(script, compiling)));
        }
        return new Layout("gradle", List.copyOf(roots), List.copyOf(compiling), List.copyOf(graph),
            "Read from the Gradle build: " + compiling.size()
                + (compiling.size() == 1 ? " project" : " projects") + " ("
                + String.join(", ", compiling.stream()
                    .map(d -> d.isEmpty() ? "the repository root" : d).toList())
                + "). Source sets a build script redefines by hand are not read, so a file outside "
                + "the conventional directories is reported but never failed on that alone.");
    }

    /** {@code project(":a:b")} references in a build script, as sibling project directories. */
    private static final Pattern GRADLE_PROJECT_DEP =
        Pattern.compile("project\\s*\\(\\s*[\"']([^\"']+)[\"']");

    private static List<String> gradleProjectDependencies(String script, Set<String> known) {
        if (script == null) {
            return List.of();
        }
        List<String> dirs = new ArrayList<>();
        Matcher matcher = GRADLE_PROJECT_DEP.matcher(script);
        while (matcher.find()) {
            String dir = normalize(matcher.group(1).replace(':', '/'));
            if (dir != null && !dir.isEmpty() && known.contains(dir) && !dirs.contains(dir)) {
                dirs.add(dir);
            }
        }
        return List.copyOf(dirs);
    }

    private static String firstPresent(ExecTarget target, String... candidates) {
        for (String candidate : candidates) {
            String content = readFile(target, candidate);
            if (content != null) {
                return content;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Utility

    private static String readFile(ExecTarget target, String path) {
        try {
            return target.readFile(path, MAX_BUILD_FILE_BYTES);
        } catch (Exception e) {
            log.debug("Build layout: could not read {}: {}", path, e.getMessage());
            return null;
        }
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.debug("Build layout: unparseable build file: {}", e.getMessage());
            return null;
        }
    }

    /** A direct child element by name — never a same-named element buried in plugin config. */
    static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    static String childText(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent();
    }

    private static String join(String dir, String child) {
        return dir == null || dir.isEmpty() ? child : dir + "/" + child;
    }

    /** Repo-relative, forward slashes, no {@code .} or trailing slash. Null if it escapes the repo. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace('\\', '/').trim();
        List<String> parts = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (parts.isEmpty()) {
                    return null;    // outside the repository; not our business
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(segment);
        }
        return String.join("/", parts);
    }
}
