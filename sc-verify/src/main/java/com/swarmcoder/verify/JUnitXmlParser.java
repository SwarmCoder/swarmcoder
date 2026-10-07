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
import com.swarmcoder.domain.TestResults;
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
import java.util.regex.Pattern;

/**
 * Parses JUnit-format XML test reports (Gradle {@code build/test-results}, Maven Surefire
 * {@code target/surefire-reports} — same schema). This is what makes verification structural
 * rather than exit-code guessing: counts and per-test failure details feed the
 * VerificationReport, the repair round, and the judge.
 */
public final class JUnitXmlParser {

    private static final Logger log = LoggerFactory.getLogger(JUnitXmlParser.class);
    /** Safety net for a pathological trace with one giant line and no frames to cap it by. */
    private static final int MAX_TRACE_CHARS = 8000;
    /**
     * How many {@code at ...} frames a filtered trace keeps. An acceptance test throws at most a
     * few levels into the candidate's own code; anything past a dozen frames is noise nobody
     * reading the verdict, the judge block or the repair prompt needed.
     */
    private static final int MAX_FRAME_LINES = 12;
    /**
     * A frame that is never the candidate's fault: JUnit's own runner, or the JDK reflecting into
     * the test method. Everything else — including the candidate's own code and any library it
     * calls — is kept, because "the project's packages" cannot be named in advance here: a task's
     * repository has whatever package structure that task happens to use.
     */
    private static final Pattern NOISE_FRAME =
        Pattern.compile("^\\s*at\\s+(java\\.|jdk\\.|org\\.junit\\.).*$");

    private JUnitXmlParser() {}

    /** Merges any number of report-file contents into one TestResults. Unparseable files count as one error. */
    public static TestResults parse(List<String> xmlContents) {
        int passed = 0;
        int failed = 0;
        int errored = 0;
        int skipped = 0;
        List<TestFailure> failures = new ArrayList<>();
        // The ids of the tests that actually ran, and of those the runner skipped. Counts alone
        // cannot answer "did MY test run?", and a consumer that cannot answer that has no choice
        // but to read silence as a pass — which is how a requirement gets certified by a test that
        // was never written. See CriterionEvidence.
        List<String> passedIds = new ArrayList<>();
        List<String> skippedIds = new ArrayList<>();
        boolean truncated = false;

        for (String xml : xmlContents) {
            if (xml == null || xml.isBlank()) {
                continue;
            }
            try {
                Document doc = secureBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                NodeList suites = doc.getElementsByTagName("testsuite");
                for (int s = 0; s < suites.getLength(); s++) {
                    Element suite = (Element) suites.item(s);
                    NodeList cases = suite.getElementsByTagName("testcase");
                    for (int c = 0; c < cases.getLength(); c++) {
                        Element testCase = (Element) cases.item(c);
                        String testId = testCase.getAttribute("classname") + "#" + testCase.getAttribute("name");
                        Element failure = firstChild(testCase, "failure");
                        Element error = firstChild(testCase, "error");
                        Element skip = firstChild(testCase, "skipped");
                        if (failure != null) {
                            failed++;
                            failures.add(toFailure(testId, failure));
                        } else if (error != null) {
                            errored++;
                            failures.add(toFailure(testId, error));
                        } else if (skip != null) {
                            skipped++;
                            truncated |= !add(skippedIds, testId);
                        } else {
                            passed++;
                            truncated |= !add(passedIds, testId);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Unparseable JUnit XML report: {}", e.getMessage());
                errored++;
                failures.add(new TestFailure("report-parse-error", e.getMessage(), ""));
            }
        }
        if (truncated) {
            log.warn("More than {} test ids in one stage — the id list is truncated, so criteria "
                + "whose test is not in it will read as UNKNOWN rather than passed",
                TestResults.MAX_IDS);
        }
        return new TestResults(passed, failed, errored, skipped, failures, passedIds, skippedIds,
            truncated);
    }

    /** @return false once the cap is reached, so the caller can record that ids are incomplete */
    private static boolean add(List<String> ids, String testId) {
        if (ids.size() >= TestResults.MAX_IDS) {
            return false;
        }
        ids.add(testId);
        return true;
    }

    private static TestFailure toFailure(String testId, Element failureElement) {
        String message = failureElement.getAttribute("message");
        String trace = filterTrace(failureElement.getTextContent());
        return new TestFailure(testId, message == null ? "" : message, trace == null ? "" : trace);
    }

    /**
     * Keeps the trace's non-frame lines (the exception header, a "Caused by:" line) unconditionally,
     * and its {@code at ...} frame lines up to {@link #MAX_FRAME_LINES}, dropping any frame that
     * matches {@link #NOISE_FRAME} before it counts against that cap. This is what turns "0
     * acceptance test(s) failed and 1 errored" into a stack a person — or a repair worker — can
     * actually read: the exception, its message, and where in the candidate's own call path it blew
     * up, with the JUnit and JDK plumbing around it cut away.
     */
    private static String filterTrace(String rawTrace) {
        if (rawTrace == null || rawTrace.isBlank()) {
            return rawTrace == null ? "" : rawTrace;
        }
        StringBuilder out = new StringBuilder();
        int frameLines = 0;
        for (String line : rawTrace.split("\r?\n")) {
            boolean isFrame = line.stripLeading().startsWith("at ");
            if (isFrame) {
                if (NOISE_FRAME.matcher(line).matches()) {
                    continue; // JUnit/JDK plumbing — never the candidate's fault, never kept
                }
                if (frameLines >= MAX_FRAME_LINES) {
                    continue; // cap reached; a later non-frame line (e.g. "Caused by:") still gets through
                }
                frameLines++;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        String filtered = out.toString();
        if (filtered.length() > MAX_TRACE_CHARS) {
            filtered = filtered.substring(0, MAX_TRACE_CHARS) + "\n[trace truncated]";
        }
        return filtered;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getParentNode() == parent) {
                return (Element) children.item(i);
            }
        }
        return null;
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
