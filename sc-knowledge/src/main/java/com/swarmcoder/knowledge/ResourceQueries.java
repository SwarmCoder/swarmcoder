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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A module's resource files - {@code beans.xml}, persistence descriptors, property files - answered
 * without reading them whole (DEVELOPER_CORRECTIONS section 59). A role used to read the file to
 * learn one setting: run 86's test author read poms and {@code beans.xml} 13 times, whole.
 *
 * <p>Three answers: a module's resource files with their sizes; one resource's content when it is
 * small; and, when it is large, its outline (an XML file's elements, a property file's keys, a
 * YAML file's top-level keys, any other file's line count) with {@code file#<part>} to get one part
 * of it - an element, a key, or a line range such as {@code L10-40}.
 */
final class ResourceQueries {

    /** A resource up to this size is answered whole. */
    static final int WHOLE_UP_TO_CHARS = 6_000;

    private static final List<String> RESOURCE_DIRS =
        List.of("src/main/resources", "src/test/resources");

    private ResourceQueries() {}

    /** The resource files under a module or folder, one per line with its size. */
    static String listing(String address, Path dir) {
        List<Path> roots = new ArrayList<>();
        for (String relative : RESOURCE_DIRS) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) {
                roots.add(candidate);
            }
        }
        if (roots.isEmpty() && inResourceTree(dir)) {
            roots.add(dir); // a resources folder itself, or a folder inside one
        }
        if (roots.isEmpty()) {
            return "`" + address + "` has no src/main/resources or src/test/resources.\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Path root : roots) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            sb.append(address.isEmpty() ? "" : address + "/")
                .append(dir.equals(root) ? "" : dir.relativize(root).toString().replace('\\', '/') + "/")
                .append(" - ").append(files.size()).append(" file(s)\n");
            for (Path file : files) {
                sb.append("  ").append(root.relativize(file).toString().replace('\\', '/'))
                    .append(" (").append(sizeOf(file)).append(")\n");
            }
        }
        sb.append("resources_of <module>/<path under the resources folder> returns one file when "
            + "it is small, and its outline when it is large; add #<element, key or L10-40> for "
            + "one part.\n");
        return sb.toString();
    }

    private static boolean inResourceTree(Path dir) {
        for (Path part : dir.normalize()) {
            if (part.toString().equals("resources")) {
                return true;
            }
        }
        return false;
    }

    private static String sizeOf(Path file) {
        try {
            long bytes = Files.size(file);
            return bytes < 1024 ? bytes + " B" : (bytes + 512) / 1024 + " KB";
        } catch (IOException e) {
            return "?";
        }
    }

    /** One resource: whole when small, its outline when large, or the part {@code part} names. */
    static String file(String address, Path file, String part) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return "`" + address + "` could not be read.\n";
        }
        for (int i = 0; i < Math.min(bytes.length, 2048); i++) {
            if (bytes[i] == 0) {
                return "`" + address + "` is a binary file of " + bytes.length + " bytes.\n";
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String name = file.getFileName().toString().toLowerCase();
        if (part != null && !part.isBlank()) {
            return partOf(address, text, name, part.strip());
        }
        if (text.length() <= WHOLE_UP_TO_CHARS) {
            return address + " (" + text.length() + " characters)\n" + text
                + (text.endsWith("\n") ? "" : "\n");
        }
        return address + " is " + text.length() + " characters, so its outline:\n"
            + outline(text, name) + "Ask for " + address + "#<part> for one part.\n";
    }

    private static String outline(String text, String name) {
        if (isXml(name, text)) {
            String xml = xmlOutline(text);
            if (xml != null) {
                return xml;
            }
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        if (name.endsWith(".properties")) {
            for (int i = 0; i < lines.length; i++) {
                String key = propertyKey(lines[i]);
                if (key != null) {
                    sb.append("  ").append(key).append(" (line ").append(i + 1).append(")\n");
                }
            }
            return sb.toString();
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            for (int i = 0; i < lines.length; i++) {
                String key = yamlTopKey(lines[i]);
                if (key != null) {
                    sb.append("  ").append(key).append(" (line ").append(i + 1).append(")\n");
                }
            }
            return sb.toString();
        }
        return "  " + lines.length + " lines; ask for #L<from>-<to> for a range.\n";
    }

    private static boolean isXml(String name, String text) {
        return name.endsWith(".xml") || name.endsWith(".xsd") || name.endsWith(".xhtml")
            || text.stripLeading().startsWith("<?xml");
    }

    private static String xmlOutline(String text) {
        Element root;
        try {
            root = parse(text).getDocumentElement();
        } catch (Exception unreadable) {                                   // noqa
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  <").append(root.getTagName()).append(attributes(root)).append(">\n");
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                sb.append("    <").append(child.getTagName()).append(attributes(child)).append("> ")
                    .append(count(child)).append(" element(s) inside\n");
            }
        }
        return sb.toString();
    }

    private static int count(Element element) {
        return element.getElementsByTagName("*").getLength();
    }

    private static String attributes(Element element) {
        StringBuilder sb = new StringBuilder();
        NamedNodeMap all = element.getAttributes();
        for (int i = 0; i < all.getLength(); i++) {
            sb.append(' ').append(all.item(i).getNodeName()).append("=\"")
                .append(all.item(i).getNodeValue()).append('"');
        }
        return sb.toString();
    }

    private static Document parse(String text) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(
            new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String partOf(String address, String text, String name, String part) {
        String[] lines = text.split("\n", -1);
        java.util.regex.Matcher range =
            java.util.regex.Pattern.compile("(?i)L?(\\d+)\\s*-\\s*L?(\\d+)").matcher(part);
        if (range.matches()) {
            int from = Math.max(1, Integer.parseInt(range.group(1)));
            int to = Math.min(lines.length, Integer.parseInt(range.group(2)));
            if (from > to) {
                return address + " has " + lines.length + " lines.\n";
            }
            return address + " lines " + from + "-" + to + "\n"
                + String.join("\n", java.util.Arrays.copyOfRange(lines, from - 1, to)) + "\n";
        }
        StringBuilder sb = new StringBuilder();
        if (isXml(name, text)) {
            try {
                Document document = parse(text);
                org.w3c.dom.NodeList all = document.getElementsByTagName("*");
                for (int i = 0; i < all.getLength(); i++) {
                    Element element = (Element) all.item(i);
                    if (element.getTagName().equals(part) || part.equals(element.getAttribute("id"))
                            || part.equals(element.getAttribute("name"))) {
                        sb.append(serialize(element));
                    }
                }
            } catch (Exception unreadable) {                               // noqa
                return address + " could not be read as XML; ask for #L<from>-<to>.\n";
            }
        } else if (name.endsWith(".properties")) {
            for (String line : lines) {
                String key = propertyKey(line);
                if (key != null && key.startsWith(part)) {
                    sb.append(line).append('\n');
                }
            }
        } else if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            boolean inside = false;
            for (String line : lines) {
                String key = yamlTopKey(line);
                if (key != null) {
                    inside = key.equals(part);
                }
                if (inside) {
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.length() == 0 ? address + " has no part `" + part + "`; its outline lists them.\n"
            : address + "#" + part + "\n" + sb;
    }

    private static String serialize(Element element) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(out));
        return out.toString().strip() + "\n";
    }

    private static String propertyKey(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
            return null;
        }
        int end = 0;
        while (end < stripped.length() && "=: \t".indexOf(stripped.charAt(end)) < 0) {
            end++;
        }
        return stripped.substring(0, end);
    }

    private static String yamlTopKey(String line) {
        if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '#'
                || line.charAt(0) == '-') {
            return null;
        }
        int colon = line.indexOf(':');
        return colon > 0 ? line.substring(0, colon).strip() : null;
    }
}
