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

import com.swarmcoder.domain.LintResults;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses SpotBugs' native XML ({@code <BugCollection>}) into {@link LintResults}. SpotBugs can
 * also emit checkstyle-format XML via a plugin (handled by {@link CheckstyleXmlParser}); this
 * covers the common case where the raw {@code spotbugsXml.xml} is produced instead. Priority 1
 * (High) bugs are counted as errors, everything else as warnings — lint stays advisory either way.
 */
final class SpotBugsXmlParser {

    private static final int MAX_MESSAGES = 100;

    private SpotBugsXmlParser() {}

    /** Parses an already-built (securely configured) DOM whose root is {@code <BugCollection>}. */
    static LintResults parse(Document doc) {
        int errors = 0;
        int warnings = 0;
        List<String> messages = new ArrayList<>();

        NodeList bugs = doc.getElementsByTagName("BugInstance");
        for (int i = 0; i < bugs.getLength(); i++) {
            Element bug = (Element) bugs.item(i);
            boolean high = "1".equals(bug.getAttribute("priority"));
            if (high) {
                errors++;
            } else {
                warnings++;
            }
            if (messages.size() < MAX_MESSAGES) {
                messages.add(location(bug) + " [" + severity(bug) + "] " + message(bug));
            }
        }
        return new LintResults(errors, warnings, messages);
    }

    private static String severity(Element bug) {
        return switch (bug.getAttribute("priority")) {
            case "1" -> "high";
            case "3" -> "low";
            default -> "normal";
        };
    }

    private static String message(Element bug) {
        String longMessage = firstChildText(bug, "LongMessage");
        if (!longMessage.isBlank()) {
            return longMessage;
        }
        String shortMessage = firstChildText(bug, "ShortMessage");
        return shortMessage.isBlank() ? bug.getAttribute("type") : shortMessage;
    }

    private static String location(Element bug) {
        NodeList lines = bug.getElementsByTagName("SourceLine");
        if (lines.getLength() > 0) {
            Element line = (Element) lines.item(0);
            String source = line.getAttribute("sourcepath");
            if (source.isBlank()) {
                source = line.getAttribute("classname");
            }
            String start = line.getAttribute("start");
            return start.isBlank() ? source : source + ":" + start;
        }
        return bug.getAttribute("type");
    }

    private static String firstChildText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return "";
        }
        Node node = nodes.item(0);
        String text = node.getTextContent();
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
