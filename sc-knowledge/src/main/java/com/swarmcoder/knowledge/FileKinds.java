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

import java.util.List;
import java.util.Locale;

/**
 * What a file IS, so retrieval can prefer the thing that answers a worker's question over the
 * thing that merely repeats its words.
 *
 * <p><b>Why this exists.</b> Measured on 2026-09-03: a worker asked {@code lookup_api} twelve
 * variations of one question — how do I reach the store and its root object, and how do I save —
 * and got {@code docs/AGENT_PROMPTS.md › Task 3} four times. That file is a set of prompts for an
 * agent that was told to BUILD the example the worker wanted to read; it names every class,
 * module and concept in the answer and contains none of the answer. The guide that answers,
 * {@code docs/guides/persistence.md}, was never returned once. Word overlap cannot tell those two
 * apart, because on word overlap the prompt file wins. Only knowing what each file is can.
 *
 * <p><b>Where the classification lives.</b> Here, in one ordered table of path rules, as the
 * default for every project; and per project in the store, as
 * {@link com.swarmcoder.domain.Project#fileKinds()} — a list of {@code kind: path-fragment} lines
 * that are consulted BEFORE the defaults. No new file mechanism: project settings are store
 * objects (author decision 2026-09-02), and this is a project setting.
 *
 * <p>The rules are ordered and the first match wins, so a test inside an example is a test and a
 * fixture inside a test tree is a fixture.
 */
public final class FileKinds {

    /**
     * What a file is for.
     *
     * <p>The ranking a worker's question is answered in: {@link #GUIDE} first, then
     * {@link #FRAMEWORK}, then {@link #EXAMPLE}, then {@link #TEST}. {@link #META} and
     * {@link #FIXTURE} are not answers at all — see {@link #isAnswerable()}.
     */
    public enum Kind {
        /** Written to be read: a guide, a reference page, a quickstart, a README. */
        GUIDE(1.00),
        /** The framework's own {@code src/main} code — the API a worker calls. */
        FRAMEWORK(0.90),
        /** An application built with the framework: {@code examples/}, demos, samples. */
        EXAMPLE(0.82),
        /** A test. Real code, but it exercises an API rather than showing how to use one. */
        TEST(0.30),
        /**
         * About the project rather than the software: prompts for agents, release history, how to
         * contribute, design notes, documentation plans, style guides.
         */
        META(0.0),
        /**
         * A template or a smoke fixture: archetype resources, {@code __rootArtifactId__} skeletons.
         * It compiles and it teaches nothing — the archetype's {@code ServerApp.java} has no store
         * in it at all, and it was returned to a worker asking how to set one up.
         */
        FIXTURE(0.0);

        private final double weight;

        Kind(double weight) {
            this.weight = weight;
        }

        /** The multiplier applied to a hit's raw relevance. */
        public double weight() {
            return weight;
        }

        /**
         * Whether a worker's question may be answered with this at all. {@link #META} and
         * {@link #FIXTURE} may not — except as a last resort when nothing else in the whole
         * reference folder matched the question, which is how a worker can still ask what changed
         * in a release, and always by exact path.
         */
        public boolean isAnswerable() {
            return weight > 0;
        }
    }

    private FileKinds() {
    }

    /** Path fragments that mean "a template or a smoke fixture", checked first. */
    private static final List<String> FIXTURE_MARKS = List.of(
        "/archetype-resources/", "__rootartifactid__", "/smoke/fixtures/", "/archetype/smoke/",
        "/src/test/resources/", "/testfixtures/", "/test-fixtures/", "/fixtures/");

    /** Path fragments that mean "a test". */
    private static final List<String> TEST_MARKS = List.of(
        "/src/test/java/", "/src/it/java/", "/test/java/", "/src/test/kotlin/");

    /** Path fragments that mean "an application built with the framework". */
    private static final List<String> EXAMPLE_MARKS = List.of(
        "-examples/", "/examples/", "/example/", "/samples/", "/sample/", "/demo/", "/demos/",
        "-demo/", "-example/");

    /** Folders under a documentation tree that hold process material, not reference material. */
    private static final List<String> META_DOC_DIRS = List.of(
        "/contribute/", "/contributing/", "/design/", "/decide/", "/internal/", "/plans/",
        "/adr/", "/rfc/", "/meeting-notes/", "/proposals/");

    /** Document base names (without extension) that are process, not reference. */
    private static final List<String> META_DOC_NAMES = List.of(
        "changelog", "changes", "history", "releasing", "release_notes", "release-notes",
        "contributing", "code_of_conduct", "code-of-conduct", "security", "license", "licence",
        "notice", "authors", "maintainers", "roadmap", "agents", "agent_prompts", "agent-prompts",
        "developer_corrections", "developer-corrections", "docs-style-guide", "documentation-plan",
        "style-guide", "styleguide", "todo", "backlog");

    /**
     * Classifies {@code address} — {@code <root label>/<relative path>}, forward slashes.
     *
     * @param overrides per-project lines of the form {@code kind: path-fragment}
     *                  (e.g. {@code guide: /handbook/}), consulted before the defaults; nullable
     */
    public static Kind of(String address, List<String> overrides) {
        if (address == null || address.isBlank()) {
            return Kind.FRAMEWORK;
        }
        String path = address.replace('\\', '/').toLowerCase(Locale.ROOT);
        Kind override = fromOverrides(path, overrides);
        if (override != null) {
            return override;
        }
        if (containsAny(path, FIXTURE_MARKS)) {
            return Kind.FIXTURE;
        }
        return path.endsWith(".md") || path.endsWith(".markdown") || path.endsWith(".adoc")
            ? documentKind(path) : sourceKind(path);
    }

    public static Kind of(String address) {
        return of(address, null);
    }

    /**
     * A document is reference material unless its name or its folder says it is about running the
     * project. A README is reference: it is usually the only page that says what the thing is.
     */
    private static Kind documentKind(String path) {
        if (containsAny(path, META_DOC_DIRS)) {
            return Kind.META;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        return META_DOC_NAMES.contains(base) ? Kind.META : Kind.GUIDE;
    }

    private static Kind sourceKind(String path) {
        if (containsAny(path, TEST_MARKS) || path.endsWith("test.java") || path.endsWith("tests.java")
            || path.endsWith("it.java")) {
            return Kind.TEST;
        }
        return containsAny(path, EXAMPLE_MARKS) ? Kind.EXAMPLE : Kind.FRAMEWORK;
    }

    private static Kind fromOverrides(String path, List<String> overrides) {
        if (overrides == null) {
            return null;
        }
        for (String line : overrides) {
            if (line == null) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String fragment = line.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
            if (fragment.isEmpty() || !path.contains(fragment)) {
                continue;
            }
            try {
                return Kind.valueOf(line.substring(0, colon).strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException notAKind) {
                // an unknown kind name is ignored, not fatal: the operator typed it by hand
            }
        }
        return null;
    }

    private static boolean containsAny(String path, List<String> fragments) {
        for (String fragment : fragments) {
            if (path.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How useful a document is likely to be, for the ORDER of the catalogue in a worker's brief.
     * Lower sorts first. A guide beats a topical page beats a README beats everything deeper: the
     * worker's brief used to open with README, AGENTS, CHANGELOG, CONTRIBUTING and RELEASING, and
     * the guide that answered its question was inside "… and 23 more documents".
     */
    public static int listingRank(String address, Kind kind) {
        if (kind == Kind.META) {
            return 90;
        }
        String path = address.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (path.contains("/guides/") || path.contains("/guide/")) {
            return 0;
        }
        if (path.contains("/reference/")) {
            return 1;
        }
        if (path.contains("/start/") || path.contains("getting_started") || path.contains("quickstart")) {
            return 2;
        }
        String relative = path.substring(path.indexOf('/') + 1);
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        if (name.startsWith("readme")) {
            return 4;
        }
        // A topical page one level inside the documentation folder: docs/UI_COMPONENTS.md.
        return relative.chars().filter(c -> c == '/').count() <= 1 ? 3 : 5;
    }
}
