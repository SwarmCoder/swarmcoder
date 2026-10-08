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
package com.swarmcoder.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What this build could declare TODAY, without a network and without anyone choosing a version.
 *
 * <p><b>Why this exists.</b> A worker may now add a dependency to a module's build file, and the
 * only honest way to let it do that is to tell it exactly which artifacts it is allowed to name.
 * The sandbox runs with {@code network=none} and resolves against the operator's {@code ~/.m2}
 * mounted read-only, so "a library that exists" and "a library this build can use" are completely
 * different sets. A worker that declares {@code org.apache.commons:commons-lang3} because it has
 * heard of it produces a build that cannot resolve, and Maven's offline failure looks nothing like
 * a mistake in the code.
 *
 * <p>An artifact is <b>declarable offline</b> when both halves are true:
 *
 * <ol>
 *   <li><b>Its version is already decided</b> — it appears in a {@code <dependencyManagement>}
 *       block this build inherits: either an imported BOM ({@code <type>pom</type>
 *       <scope>import</scope>}) or a parent pom. Then the declaration is three lines with no
 *       {@code <version>} in them, and there is no version for a model to invent.</li>
 *   <li><b>Its files are on the disk the sandbox mounts</b> — a {@code .pom} and (unless it is
 *       itself a pom) a {@code .jar} under the local repository at that exact version.</li>
 * </ol>
 *
 * <p>Both halves are cheap file reads. Neither asks Maven anything, because asking Maven means
 * running Maven, and this has to be affordable at PLAN time and again for every task's brief.
 *
 * <p><b>What it deliberately does not do.</b> It does not resolve transitive dependencies, so
 * "declarable" means the artifact itself is present, not that everything it drags in is. A missing
 * transitive dependency still fails the sandbox build — and
 * {@code CompileFailureAttribution} names it when it does, which is where that belongs: it is
 * cheap to say afterwards and expensive to prove beforehand.
 */
public final class DeclarableArtifacts {

    private static final Logger log = LoggerFactory.getLogger(DeclarableArtifacts.class);

    /** How deep a parent chain or a chain of imported BOMs is followed. */
    private static final int MAX_DEPTH = 6;

    /** A guard against a pathological reactor; far beyond any real one. */
    private static final int MAX_MODULES = 200;

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private DeclarableArtifacts() {}

    /**
     * One artifact this build can declare without deciding a version and without a network.
     *
     * @param managedBy plain English about WHERE the version comes from, for the sentence a worker
     *                  or an operator reads — "the zerozstack-bom BOM", "the parent pom"
     */
    public record Artifact(String groupId, String artifactId, String version, String managedBy) {
        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    /**
     * Everything {@link #scan} found, plus where it looked — the path is carried because when an
     * artifact is NOT here that path is the one fact the operator needs.
     *
     * @param repositoryPresent false when the local repository directory does not exist at all, so
     *                          "nothing is declarable" can be told apart from "nothing was checked"
     * @param managedNotOnDisk  the artifacts this build's inherited dependency management pins
     *                          whose files the local repository does NOT hold: real artifacts a
     *                          person can install, which nothing inside a run can obtain
     */
    public record Catalog(List<Artifact> artifacts, Path localRepository, boolean repositoryPresent,
                          List<Artifact> managedNotOnDisk) {

        public Catalog {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            managedNotOnDisk = managedNotOnDisk == null ? List.of() : List.copyOf(managedNotOnDisk);
        }

        public Catalog(List<Artifact> artifacts, Path localRepository, boolean repositoryPresent) {
            this(artifacts, localRepository, repositoryPresent, List.of());
        }

        /** A managed artifact of that id whose files are not on the disk, when there is one. */
        public Optional<Artifact> managedNotOnDisk(String artifactId) {
            for (Artifact artifact : managedNotOnDisk) {
                if (artifact.artifactId().equals(artifactId)) {
                    return Optional.of(artifact);
                }
            }
            return Optional.empty();
        }

        public static Catalog empty(Path localRepository) {
            return new Catalog(List.of(), localRepository, false);
        }

        public boolean isEmpty() {
            return artifacts.isEmpty();
        }

        /** Matched on the artifact id alone: that is what a rule names when it names a library. */
        public Optional<Artifact> byArtifactId(String artifactId) {
            for (Artifact artifact : artifacts) {
                if (artifact.artifactId().equals(artifactId)) {
                    return Optional.of(artifact);
                }
            }
            return Optional.empty();
        }

        /** The artifacts whose group is one of {@code groups}, in the order they were found. */
        public List<Artifact> inGroups(Set<String> groups) {
            List<Artifact> kept = new ArrayList<>();
            for (Artifact artifact : artifacts) {
                if (groups.contains(artifact.groupId())) {
                    kept.add(artifact);
                }
            }
            return kept;
        }
    }

    /**
     * The local Maven repository the sandbox mounts read-only.
     *
     * <p>The SAME system property the application hands {@code DockerSandboxManager} as the
     * repository to bind at {@code /opt/m2-ro}, so what this class reads on the host and what a
     * candidate's build resolves from in the sandbox cannot be two different directories. Read
     * here rather than passed down through five constructors, and overridable in a test.
     */
    public static Path defaultLocalRepository() {
        String configured = System.getProperty("swarmcoder.sandbox.m2");
        Path m2 = configured != null && !configured.isBlank()
            ? Paths.get(configured)
            : Paths.get(System.getProperty("user.home"), ".m2");
        return m2.resolve("repository");
    }

    /**
     * Every artifact this build's inherited dependency management pins AND the local repository
     * actually holds.
     *
     * @param repoRoot        the target repository's root
     * @param moduleDirs      the compiling modules, repo-relative ({@code ""} is the root); the
     *                        root pom is always read as well, because in an aggregator build that
     *                        is where the BOM is imported and the root is not a compiling module
     * @param localRepository the local Maven repository — see {@link #defaultLocalRepository()}
     */
    public static Catalog scan(Path repoRoot, List<String> moduleDirs, Path localRepository) {
        if (repoRoot == null || localRepository == null) {
            return Catalog.empty(localRepository);
        }
        boolean repoPresent = Files.isDirectory(localRepository);
        List<Path> poms = new ArrayList<>();
        Path rootPom = repoRoot.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            poms.add(rootPom);
        }
        int seen = 0;
        for (String dir : moduleDirs == null ? List.<String>of() : moduleDirs) {
            if (dir == null || ++seen > MAX_MODULES) {
                break;
            }
            Path pom = (dir.isEmpty() ? repoRoot : repoRoot.resolve(dir)).resolve("pom.xml");
            if (Files.isRegularFile(pom) && !poms.contains(pom)) {
                poms.add(pom);
            }
        }
        if (poms.isEmpty()) {
            return Catalog.empty(localRepository);
        }

        Map<String, Artifact> found = new LinkedHashMap<>();
        for (Path pom : poms) {
            PomFacts facts = read(pom, repoRoot, localRepository, 0);
            if (facts == null) {
                continue;
            }
            collect(facts.managed, facts.properties, localRepository, repoRoot, found, 0);
        }
        if (!repoPresent) {
            return new Catalog(List.of(), localRepository, false);
        }
        List<Artifact> present = new ArrayList<>();
        List<Artifact> absent = new ArrayList<>();
        for (Artifact artifact : found.values()) {
            (onDisk(localRepository, artifact) ? present : absent).add(artifact);
        }
        return new Catalog(present, localRepository, true, absent);
    }

    // --------------------------------------------------------------------------- managed entries

    /** One {@code <dependency>} inside a {@code <dependencyManagement>} block, versions resolved. */
    private record Managed(String groupId, String artifactId, String version, String type,
                           String scope, String source) {}

    /**
     * Walks the managed entries, expanding every imported BOM into the entries it manages.
     *
     * <p>An import is followed only into the LOCAL REPOSITORY: a BOM this build imports but the
     * repository does not hold pins nothing that could be resolved offline anyway, so there is
     * nothing to lose by not finding it.
     */
    private static void collect(List<Managed> managed, Map<String, String> properties,
                                Path localRepository, Path repoRoot,
                                Map<String, Artifact> out, int depth) {
        for (Managed entry : managed) {
            String version = resolve(entry.version(), properties);
            if (entry.groupId().isEmpty() || entry.artifactId().isEmpty()
                    || version.isEmpty() || version.contains("${")) {
                continue;   // a version nothing could resolve is not a version
            }
            boolean isImport = "pom".equals(entry.type()) && "import".equals(entry.scope());
            if (isImport) {
                if (depth >= MAX_DEPTH) {
                    continue;
                }
                Path bom = pomInRepository(localRepository, entry.groupId(), entry.artifactId(),
                    version);
                if (bom == null) {
                    continue;
                }
                PomFacts facts = read(bom, repoRoot, localRepository, 0);
                if (facts != null) {
                    collect(facts.managed, facts.properties, localRepository, repoRoot, out,
                        depth + 1);
                }
                continue;
            }
            String key = entry.groupId() + ":" + entry.artifactId();
            out.putIfAbsent(key, new Artifact(entry.groupId(), entry.artifactId(), version,
                entry.source()));
        }
    }

    // ------------------------------------------------------------------------------ on the disk

    /**
     * True when the local repository holds this exact version's {@code .pom} and its {@code .jar}.
     *
     * <p>The jar is required because a dependency with no jar is not something a compile can use;
     * the exception is an artifact whose own packaging is {@code pom}, which the pom itself says,
     * and which is checked by looking rather than by guessing from the name.
     */
    static boolean onDisk(Path localRepository, Artifact artifact) {
        Path dir = versionDir(localRepository, artifact.groupId(), artifact.artifactId(),
            artifact.version());
        if (dir == null) {
            return false;
        }
        Path pom = firstMatching(dir, artifact.artifactId(), artifact.version(), ".pom");
        if (pom == null) {
            return false;
        }
        if (firstMatching(dir, artifact.artifactId(), artifact.version(), ".jar") != null) {
            return true;
        }
        return "pom".equals(packagingOf(pom));
    }

    /**
     * The {@code .jar} of an artifact in the local repository, or null when there is none.
     *
     * <p>The same lookup {@link #onDisk} already does, said out loud so a caller that needs the
     * FILE rather than a yes/no can have it. {@link SemanticIndex} needs it: a type-attributed
     * parse of a module's sources needs that module's dependencies as real jars on a classpath,
     * and the only place they exist offline is here.
     */
    public static Path jarOf(Path localRepository, Artifact artifact) {
        if (artifact == null) {
            return null;
        }
        Path dir = versionDir(localRepository, artifact.groupId(), artifact.artifactId(),
            artifact.version());
        return dir == null ? null
            : firstMatching(dir, artifact.artifactId(), artifact.version(), ".jar");
    }

    /** The version directory, or null when it is not there. */
    private static Path versionDir(Path localRepository, String groupId, String artifactId,
                                   String version) {
        if (localRepository == null || groupId.isEmpty() || artifactId.isEmpty()) {
            return null;
        }
        Path dir = localRepository;
        for (String segment : groupId.split("\\.")) {
            dir = dir.resolve(segment);
        }
        dir = dir.resolve(artifactId).resolve(version);
        return Files.isDirectory(dir) ? dir : null;
    }

    /**
     * {@code artifact-version.ext}, or — for a snapshot that was downloaded rather than installed,
     * whose files carry a build timestamp instead — the first {@code artifact-*.ext} in the
     * directory. Never a checksum or a signature: those are matched away by the extension.
     */
    private static Path firstMatching(Path versionDir, String artifactId, String version,
                                      String extension) {
        Path exact = versionDir.resolve(artifactId + "-" + version + extension);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        if (!version.endsWith("-SNAPSHOT")) {
            return null;
        }
        try (DirectoryStream<Path> files =
                 Files.newDirectoryStream(versionDir, artifactId + "-*" + extension)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                // A classified artifact (…-sources.jar, …-javadoc.jar) is not the artifact.
                if (Files.isRegularFile(file) && !name.contains("-sources")
                        && !name.contains("-javadoc")) {
                    return file;
                }
            }
        } catch (Exception e) {
            log.debug("Could not list {}: {}", versionDir, e.getMessage());
        }
        return null;
    }

    private static String packagingOf(Path pom) {
        try {
            Document doc = parse(pom);
            Element packaging = childElement(doc.getDocumentElement(), "packaging");
            return packaging == null ? "jar" : packaging.getTextContent().strip();
        } catch (Exception e) {
            return "jar";
        }
    }

    // ------------------------------------------------------------------------------ pom reading

    /** A pom's properties (its own and its ancestors') and every managed entry it inherits. */
    private static final class PomFacts {
        final Map<String, String> properties = new LinkedHashMap<>();
        final List<Managed> managed = new ArrayList<>();
    }

    /**
     * Reads one pom, following its parent chain — inside the repository first, then into the local
     * repository, which is where a framework's own parent lives.
     *
     * <p>Own properties win over inherited ones, and own managed entries come first, so a module
     * that overrides a version the parent pins is read the way Maven reads it.
     */
    private static PomFacts read(Path pom, Path repoRoot, Path localRepository, int depth) {
        Document doc;
        try {
            doc = parse(pom);
        } catch (Exception e) {
            log.debug("Unreadable pom {}: {}", pom, e.getMessage());
            return null;
        }
        Element project = doc.getDocumentElement();
        PomFacts facts = new PomFacts();

        Element properties = childElement(project, "properties");
        if (properties != null) {
            for (Element property : childElements(properties)) {
                facts.properties.putIfAbsent(property.getTagName(),
                    property.getTextContent().strip());
            }
        }
        Element parent = childElement(project, "parent");
        String version = textOf(childElement(project, "version"));
        if (version.isEmpty() && parent != null) {
            version = textOf(childElement(parent, "version"));
        }
        String groupId = textOf(childElement(project, "groupId"));
        if (groupId.isEmpty() && parent != null) {
            groupId = textOf(childElement(parent, "groupId"));
        }
        if (!version.isEmpty()) {
            facts.properties.putIfAbsent("project.version", version);
        }
        if (!groupId.isEmpty()) {
            facts.properties.putIfAbsent("project.groupId", groupId);
        }

        String label = label(pom, repoRoot);
        Element management = childElement(project, "dependencyManagement");
        Element dependencies = management == null ? null : childElement(management, "dependencies");
        if (dependencies != null) {
            for (Element dependency : childElements(dependencies)) {
                if (!"dependency".equals(dependency.getTagName())) {
                    continue;
                }
                facts.managed.add(new Managed(
                    textOf(childElement(dependency, "groupId")),
                    textOf(childElement(dependency, "artifactId")),
                    textOf(childElement(dependency, "version")),
                    orDefault(textOf(childElement(dependency, "type")), "jar"),
                    orDefault(textOf(childElement(dependency, "scope")), "compile"),
                    label));
            }
        }

        if (parent != null && depth < MAX_DEPTH) {
            Path parentPom = parentPom(pom, parent, repoRoot, localRepository);
            if (parentPom != null) {
                PomFacts inherited = read(parentPom, repoRoot, localRepository, depth + 1);
                if (inherited != null) {
                    inherited.properties.forEach(facts.properties::putIfAbsent);
                    facts.managed.addAll(inherited.managed);
                }
            }
        }
        return facts;
    }

    /**
     * The parent's pom: beside this one in the checkout if it is there, else in the repository.
     *
     * <p>An explicitly EMPTY {@code <relativePath/>} is Maven's way of saying "the parent is not on
     * disk beside me, go to the repository", and it is what every framework parent uses. It is
     * honoured, which is why the element's presence is checked rather than only its text.
     */
    private static Path parentPom(Path pom, Element parent, Path repoRoot, Path localRepository) {
        Element relativePath = childElement(parent, "relativePath");
        String relative = textOf(relativePath);
        boolean lookOnDisk = relativePath == null || !relative.isEmpty();
        if (lookOnDisk) {
            Path candidate = pom.getParent()
                .resolve(relative.isEmpty() ? "../pom.xml" : relative).normalize();
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve("pom.xml");
            }
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return pomInRepository(localRepository, textOf(childElement(parent, "groupId")),
            textOf(childElement(parent, "artifactId")), textOf(childElement(parent, "version")));
    }

    /** {@code group/path/artifact/version/artifact-version.pom} in the local repository, or null. */
    private static Path pomInRepository(Path localRepository, String groupId, String artifactId,
                                        String version) {
        if (groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()
                || version.contains("${")) {
            return null;
        }
        Path dir = versionDir(localRepository, groupId, artifactId, version);
        return dir == null ? null : firstMatching(dir, artifactId, version, ".pom");
    }

    /**
     * How the version's origin is described to a reader. A pom inside the checkout is named by its
     * repo-relative path; anything else is a BOM or a parent the build imports by coordinate, and
     * is named by its artifact id, which is the word the operator recognises.
     */
    private static String label(Path pom, Path repoRoot) {
        try {
            if (repoRoot != null && pom.toAbsolutePath().startsWith(repoRoot.toAbsolutePath())) {
                return repoRoot.toAbsolutePath().relativize(pom.toAbsolutePath()).toString()
                    .replace('\\', '/');
            }
        } catch (Exception ignored) {
            // fall through to the coordinate-shaped label
        }
        Path version = pom.getParent();
        Path artifact = version == null ? null : version.getParent();
        return artifact == null ? pom.getFileName().toString()
            : "the " + artifact.getFileName() + " BOM";
    }

    // ------------------------------------------------------------------------------- XML helpers

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    /**
     * The FIRST DIRECT child element of that name.
     *
     * <p>Direct on purpose. {@code getElementsByTagName} searches the whole subtree, so on a pom
     * with profiles it happily returns a dependency block that is only active under a profile, and
     * on a {@code <dependency>} it returns the wrong {@code <version>} when an exclusion carries
     * one. Both were real risks here; walking the children is the only correct reading.
     */
    private static Element childElement(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        if (parent == null) {
            return elements;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String textOf(Element element) {
        return element == null ? "" : element.getTextContent().strip();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value.toLowerCase(Locale.ROOT);
    }

    /** Substitutes {@code ${name}} placeholders, leaving anything unknown exactly as written. */
    private static String resolve(String value, Map<String, String> properties) {
        if (value == null) {
            return "";
        }
        String out = value;
        for (int pass = 0; pass < 3 && out.contains("${"); pass++) {
            Matcher matcher = PROPERTY.matcher(out);
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    replacement == null ? matcher.group(0) : replacement));
                replaced |= replacement != null;
            }
            matcher.appendTail(sb);
            out = sb.toString();
            if (!replaced) {
                break;
            }
        }
        return out;
    }

    /** The groups of a set of {@code group:artifact} coordinates — the declarable list's filter. */
    public static Set<String> groupsOf(List<String> coordinates) {
        Set<String> groups = new LinkedHashSet<>();
        for (String coordinate : coordinates == null ? List.<String>of() : coordinates) {
            if (coordinate == null) {
                continue;
            }
            int colon = coordinate.indexOf(':');
            if (colon > 0) {
                groups.add(coordinate.substring(0, colon));
            }
        }
        return groups;
    }
}
