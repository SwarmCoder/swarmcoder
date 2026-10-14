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
package com.swarmcoder.workflow;

import com.swarmcoder.knowledge.DeclarableArtifacts;
import com.swarmcoder.verify.GeneratedContract;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which of the names a test could not compile against are packages of a library, and which
 * build files of the tree declare that library (section 109).
 *
 * <p>A test that does not compile because {@code package x does not exist} names either a part
 * of the project nobody has written yet, or a library its module's build file does not declare.
 * The second is not something a task "delivers": it is a line in a build file, and the message
 * a run stops with must say which build file declares it and which does not. Read from the
 * build files on the tree and the jars of the local repository, never from the names
 * themselves: a name is a library package when the jar of an artifact a build file of the tree
 * declares, or one its dependency management pins, holds classes in that package.
 *
 * <p>Maven only. A tree with no {@code pom.xml}, an artifact with no jar on this machine, or a
 * build file that cannot be read contributes nothing, and a name nothing answers for stays what
 * it was: a type or package the plan may still have to deliver.
 */
final class LibraryPackages {

    private LibraryPackages() {}

    /**
     * A missing name that is a package of a library.
     *
     * @param name       the name as the compiler gave it: a package, or a type in one
     * @param artifact   {@code groupId:artifactId} of the library whose jar holds that package
     * @param declaredBy the build files of the tree that declare that library as a dependency,
     *                   repo-relative; empty when none does
     */
    record Found(String name, String artifact, List<String> declaredBy) {}

    /** Never null; the names that are library packages, in the order given. */
    static Map<String, Found> among(Path tree, List<String> names, Path localRepository) {
        Map<String, Found> found = new LinkedHashMap<>();
        if (tree == null || names == null || names.isEmpty() || localRepository == null
                || !Files.isDirectory(tree) || !Files.isDirectory(localRepository)) {
            return found;
        }
        List<String> poms = GeneratedContract.buildFilesIn(tree).stream()
            .filter(path -> path.equals("pom.xml") || path.endsWith("/pom.xml"))
            .toList();
        if (poms.isEmpty()) {
            return found;
        }
        // groupId:artifactId -> the version a build file names, when it names one
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, List<String>> declaredBy = new HashMap<>();
        List<String> modules = new ArrayList<>();
        for (String pom : poms) {
            modules.add(pom.contains("/") ? pom.substring(0, pom.lastIndexOf('/')) : "");
            for (String[] dependency : declaredIn(tree.resolve(pom))) {
                String key = dependency[0] + ":" + dependency[1];
                if (versions.get(key) == null) {
                    versions.put(key, dependency[2]);
                }
                declaredBy.computeIfAbsent(key, k -> new ArrayList<>()).add(pom);
            }
        }
        try {
            for (DeclarableArtifacts.Artifact managed
                    : DeclarableArtifacts.scan(tree, modules, localRepository).artifacts()) {
                String key = managed.groupId() + ":" + managed.artifactId();
                if (versions.get(key) == null) {
                    versions.put(key, managed.version());
                }
            }
        } catch (RuntimeException e) {
            // the managed versions are a help, not a requirement
        }
        for (Map.Entry<String, String> artifact : versions.entrySet()) {
            Set<String> packages = packagesOf(jarOf(localRepository, artifact.getKey(),
                artifact.getValue()));
            for (String name : names) {
                if (name == null || found.containsKey(name)) {
                    continue;
                }
                int dot = name.lastIndexOf('.');
                if (packages.contains(name) || dot > 0 && packages.contains(name.substring(0, dot))) {
                    found.put(name, new Found(name, artifact.getKey(),
                        List.copyOf(declaredBy.getOrDefault(artifact.getKey(), List.of()))));
                }
            }
        }
        Map<String, Found> ordered = new LinkedHashMap<>();
        for (String name : names) {
            if (found.containsKey(name)) {
                ordered.put(name, found.get(name));
            }
        }
        return ordered;
    }

    /** {@code [groupId, artifactId, version or null]} of each dependency the build file declares. */
    static List<String[]> declaredIn(Path pom) {
        List<String[]> out = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(pom.toFile());
            Element dependencies = child(doc.getDocumentElement(), "dependencies");
            if (dependencies == null) {
                return out;
            }
            for (Node node = dependencies.getFirstChild(); node != null;
                 node = node.getNextSibling()) {
                if (node instanceof Element dependency
                        && "dependency".equals(dependency.getTagName())) {
                    String group = text(dependency, "groupId");
                    String artifact = text(dependency, "artifactId");
                    String version = text(dependency, "version");
                    if (!group.isEmpty() && !artifact.isEmpty() && !group.contains("${")
                            && !artifact.contains("${")) {
                        out.add(new String[] {group, artifact,
                            version.isEmpty() || version.contains("${") ? null : version});
                    }
                }
            }
        } catch (Exception e) {
            // a build file that cannot be read declares nothing anybody can use
        }
        return out;
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

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().strip();
    }

    /**
     * The jar of an artifact in the local repository: at the version given when it is there,
     * otherwise at the highest version the repository holds a jar for. Null for none.
     */
    static Path jarOf(Path localRepository, String coordinate, String version) {
        int colon = coordinate.indexOf(':');
        Path dir = localRepository;
        for (String part : coordinate.substring(0, colon).split("\\.")) {
            dir = dir.resolve(part);
        }
        String artifactId = coordinate.substring(colon + 1);
        dir = dir.resolve(artifactId);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        if (version != null) {
            Path jar = dir.resolve(version).resolve(artifactId + "-" + version + ".jar");
            if (Files.isRegularFile(jar)) {
                return jar;
            }
        }
        TreeSet<String> held = new TreeSet<>(LibraryPackages::compareVersions);
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                String v = child.getFileName().toString();
                if (Files.isRegularFile(child.resolve(artifactId + "-" + v + ".jar"))) {
                    held.add(v);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return held.isEmpty() ? null
            : dir.resolve(held.last()).resolve(artifactId + "-" + held.last() + ".jar");
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean xn = x[i].matches("\\d+");
            boolean yn = y[i].matches("\\d+");
            int c = xn && yn ? Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]))
                : x[i].compareTo(y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    /** The packages a jar holds classes in; empty when it cannot be read. */
    static Set<String> packagesOf(Path jar) {
        Set<String> packages = new LinkedHashSet<>();
        if (jar == null) {
            return packages;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                int slash = entry.lastIndexOf('/');
                if (entry.endsWith(".class") && slash > 0 && !entry.startsWith("META-INF/")) {
                    packages.add(entry.substring(0, slash).replace('/', '.'));
                }
            }
        } catch (IOException | RuntimeException e) {
            // a jar that cannot be read holds nothing anybody can compile against
        }
        return packages;
    }
}
