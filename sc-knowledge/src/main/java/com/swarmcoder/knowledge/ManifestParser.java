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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.LibraryDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the target repo's dependency coordinates (spec §13): Maven {@code pom.xml} and
 * npm {@code package.json} for now; Gradle/Cargo/pyproject follow with their toolchains.
 */
public final class ManifestParser {

    private static final Logger log = LoggerFactory.getLogger(ManifestParser.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ManifestParser() {}

    public static List<LibraryDoc> parse(Path repoRoot) {
        List<LibraryDoc> libraries = new ArrayList<>();
        parsePom(repoRoot.resolve("pom.xml"), libraries);
        parsePackageJson(repoRoot.resolve("package.json"), libraries);
        return libraries;
    }

    /**
     * A module's own Maven coordinate — {@code groupId} and {@code artifactId} of the pom itself,
     * never of anything it depends on. {@code groupId} falls back to the nearest {@code <parent>}'s
     * when the module omits its own, exactly as Maven itself would resolve it.
     *
     * <p>Exists so a caller that already reads a module's pom for its dependencies (see
     * {@link #parse}) can also learn what the module IS — the fact {@link
     * com.swarmcoder.knowledge.RulesVersusManifest} needs to stop mistaking one of the build's own
     * modules for a dependency it must declare.
     *
     * @return null when {@code moduleDir/pom.xml} is missing, unparseable, or declares no
     *         artifactId at all
     */
    public static Coordinate ownCoordinate(Path moduleDir) {
        Path pom = moduleDir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(pom.toFile());
            Element project = doc.getDocumentElement();
            String artifactId = directChildText(project, "artifactId");
            if (artifactId == null || artifactId.isBlank()) {
                return null;
            }
            String groupId = directChildText(project, "groupId");
            if (groupId == null || groupId.isBlank()) {
                Element parent = directChild(project, "parent");
                groupId = parent == null ? "" : directChildText(parent, "groupId");
            }
            return new Coordinate(groupId == null ? "" : groupId.trim(), artifactId.trim());
        } catch (Exception e) {
            log.warn("Unparseable pom.xml at {}: {}", pom, e.getMessage());
            return null;
        }
    }

    /** A Maven coordinate naming the module ITSELF, not one of its dependencies. */
    public record Coordinate(String groupId, String artifactId) {}

    /** The immediate {@code <tag>} child of {@code parent}, ignoring same-named elements nested
     * deeper (a {@code <parent>}'s own {@code <artifactId>}, a dependency's) — unlike {@link
     * #text}, which is scoped by the caller instead. */
    private static Element directChild(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node instanceof Element element && tag.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String directChildText(Element parent, String tag) {
        Element element = directChild(parent, tag);
        return element == null ? null : element.getTextContent().trim();
    }

    /**
     * The version a project actually uses of an artifact, or "" when it does not name it. Matched
     * on the artifact id alone, since a reference folder is known by its module name.
     */
    public static String versionOf(List<LibraryDoc> libraries, String artifactId) {
        for (LibraryDoc library : libraries) {
            String coordinate = library.coordinate() == null ? "" : library.coordinate();
            String name = coordinate.substring(coordinate.indexOf(':') + 1);
            if (name.equals(artifactId) && library.version() != null && !library.version().isBlank()
                && !library.version().startsWith("${")) {
                return library.version();
            }
        }
        return "";
    }

    private static void parsePom(Path pom, List<LibraryDoc> out) {
        if (!Files.isRegularFile(pom)) {
            return;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(pom.toFile());
            Map<String, String> properties = propertiesOf(pom, doc, factory, 0);
            NodeList dependencies = doc.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dependency = (Element) dependencies.item(i);
                String groupId = resolve(text(dependency, "groupId"), properties);
                String artifactId = resolve(text(dependency, "artifactId"), properties);
                String version = resolve(text(dependency, "version"), properties);
                if (!groupId.isEmpty() && !artifactId.isEmpty()) {
                    out.add(new LibraryDoc(groupId + ":" + artifactId, version, ""));
                }
            }
        } catch (Exception e) {
            log.warn("Unparseable pom.xml at {}: {}", pom, e.getMessage());
        }
    }

    /** How deep a parent chain is followed before we accept a property cannot be resolved. */
    private static final int MAX_PARENT_DEPTH = 5;
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * A pom own {@code <properties>} block, plus its parents within the repository.
     *
     * <p><b>Why this matters more than it looks.</b> Every worker brief opens with "Available
     * libraries (exact versions, use these APIs, do not invent)", and on the owner own project
     * that list said {@code com.zeroz4j:zerozstack-bom ${zeroz4j.version}} — the literal text of
     * the placeholder. The one line in the prompt whose whole purpose is to pin a version pinned
     * nothing, and no comparison against the reference folder version was possible either.
     */
    private static Map<String, String> propertiesOf(Path pom, Document doc,
                                                    DocumentBuilderFactory factory, int depth) {
        Map<String, String> properties = new LinkedHashMap<>();
        NodeList blocks = doc.getElementsByTagName("properties");
        for (int i = 0; i < blocks.getLength(); i++) {
            NodeList children = blocks.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (children.item(j) instanceof Element property) {
                    properties.putIfAbsent(property.getTagName(), property.getTextContent().strip());
                }
            }
        }
        NodeList projectVersion = doc.getElementsByTagName("version");
        if (projectVersion.getLength() > 0) {
            properties.putIfAbsent("project.version",
                projectVersion.item(0).getTextContent().strip());
        }
        if (depth >= MAX_PARENT_DEPTH) {
            return properties;
        }
        NodeList parents = doc.getElementsByTagName("parent");
        if (parents.getLength() > 0 && parents.item(0) instanceof Element parent) {
            String relative = text(parent, "relativePath");
            Path parentPom = pom.getParent()
                .resolve(relative.isEmpty() ? "../pom.xml" : relative).normalize();
            if (Files.isDirectory(parentPom)) {
                parentPom = parentPom.resolve("pom.xml");
            }
            if (Files.isRegularFile(parentPom)) {
                try {
                    Document parentDoc = factory.newDocumentBuilder().parse(parentPom.toFile());
                    propertiesOf(parentPom, parentDoc, factory, depth + 1)
                        .forEach(properties::putIfAbsent);
                } catch (Exception e) {
                    log.debug("Unreadable parent pom {}: {}", parentPom, e.getMessage());
                }
            }
        }
        return properties;
    }

    /** Substitutes {@code ${name}} placeholders, leaving anything unknown exactly as written. */
    private static String resolve(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value == null ? "" : value;
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

    private static void parsePackageJson(Path packageJson, List<LibraryDoc> out) {
        if (!Files.isRegularFile(packageJson)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(packageJson.toFile());
            for (String section : List.of("dependencies", "devDependencies")) {
                Iterator<Map.Entry<String, JsonNode>> fields = root.path(section).fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    out.add(new LibraryDoc(entry.getKey(), entry.getValue().asText(""), ""));
                }
            }
        } catch (Exception e) {
            log.warn("Unparseable package.json at {}: {}", packageJson, e.getMessage());
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
