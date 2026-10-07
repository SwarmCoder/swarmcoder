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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses lint report XML into {@link LintResults}. Used when {@code verify.yaml} sets
 * {@code lintReports}; otherwise lint falls back to exit codes. The format is auto-detected per
 * document from its root element: {@code <BugCollection>} is SpotBugs' native format (delegated to
 * {@link SpotBugsXmlParser}), anything else is treated as checkstyle-format (emitted by Checkstyle
 * itself and, via plugins, by several other linters). No {@code verify.yaml} change is needed to
 * mix the two.
 */
public final class CheckstyleXmlParser {

    private static final Logger log = LoggerFactory.getLogger(CheckstyleXmlParser.class);
    private static final int MAX_MESSAGES = 100;

    private CheckstyleXmlParser() {}

    public static LintResults parse(List<String> xmlContents) {
        int errors = 0;
        int warnings = 0;
        List<String> messages = new ArrayList<>();

        for (String xml : xmlContents) {
            if (xml == null || xml.isBlank()) {
                continue;
            }
            try {
                Document doc = secureBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                String root = doc.getDocumentElement() == null ? "" : doc.getDocumentElement().getNodeName();
                LintResults part = "BugCollection".equals(root)
                    ? SpotBugsXmlParser.parse(doc)
                    : parseCheckstyle(doc);
                errors += part.errors();
                warnings += part.warnings();
                for (String m : part.messages()) {
                    if (messages.size() < MAX_MESSAGES) {
                        messages.add(m);
                    }
                }
            } catch (Exception e) {
                log.warn("Unparseable lint XML: {}", e.getMessage());
                errors++;
                if (messages.size() < MAX_MESSAGES) {
                    messages.add("lint-report-parse-error: " + e.getMessage());
                }
            }
        }
        return new LintResults(errors, warnings, messages);
    }

    /** Checkstyle-format: {@code <checkstyle><file name><error severity line message/>}. */
    private static LintResults parseCheckstyle(Document doc) {
        int errors = 0;
        int warnings = 0;
        List<String> messages = new ArrayList<>();
        NodeList files = doc.getElementsByTagName("file");
        for (int f = 0; f < files.getLength(); f++) {
            Element file = (Element) files.item(f);
            String name = file.getAttribute("name");
            NodeList issues = file.getElementsByTagName("error");
            for (int i = 0; i < issues.getLength(); i++) {
                Element issue = (Element) issues.item(i);
                String severity = issue.getAttribute("severity");
                if ("warning".equalsIgnoreCase(severity) || "info".equalsIgnoreCase(severity)) {
                    warnings++;
                } else {
                    errors++;
                }
                if (messages.size() < MAX_MESSAGES) {
                    messages.add(name + ":" + issue.getAttribute("line")
                        + " [" + severity + "] " + issue.getAttribute("message"));
                }
            }
        }
        return new LintResults(errors, warnings, messages);
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
