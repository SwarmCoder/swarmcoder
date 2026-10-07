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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One question put to everything the product has already indexed, answered in one block: the
 * types and members the question names with their signatures, where they are really used, the
 * code that matches its words and the documentation that does (2026-10-02).
 *
 * <p><b>Why.</b> Harness run 66: the expert took 5 to 19 turns and 10 to 35 lookups per answer,
 * and 55 of its 86 lookups were {@code list_files} and {@code read_file} - it walked folders to
 * find an example, because nothing it had could be asked "where is this done?" in its own words.
 * The structural tools want an exact type name (it guessed packages and was told "nothing" 8 times
 * out of 10 for {@code find_implementations}); the documentation lookup wants one word. Meanwhile
 * the semantic index and the reference index held the answer to each of those questions before
 * the expert's first turn. This is the question asked of both at once.
 *
 * <p>Library- and project-agnostic: everything here is a name the question itself contains, looked
 * up in whatever material the project has configured. Read-only, no model call.
 *
 * <p>Used twice: before the expert's first turn, so the common question is answered from its
 * opening prompt ({@link ExpertEscalation}), and as the {@code search} tool for the questions the
 * opening did not settle ({@link ExpertTools#search}).
 */
final class ExpertSearch {

    /** How many of the types a question names are shown with their shape. */
    static final int MAX_TYPES = 5;
    /** How many places each type or method is shown used in - distinct files, not lines. */
    static final int MAX_USES = 4;
    /** How many matching source files are named, and how many of those are quoted. */
    static final int MAX_SOURCES = 6;
    static final int QUOTED_SOURCES = 2;
    /** How many documentation sections are named, at most two of one page. */
    static final int MAX_SECTIONS = 4;
    /** A type's public shape past this many lines is cut: the head of it is what names it. */
    static final int SHAPE_LINES = 40;

    static final String NOTHING = "Nothing in the index matched: ";

    private ExpertSearch() {
    }

    /**
     * @param withBodies true quotes the best matching code and documentation; false only names
     *                   where they are - for a reader that has already been handed the best of
     *                   them some other way and needs the rest as places to go
     */
    static String search(KnowledgeCurator curator, String query, int maxChars, boolean withBodies) {
        return search(curator, query, maxChars, withBodies, null);
    }

    /**
     * The same search inside one session that has searched before (2026-10-04).
     *
     * <p>Harness run 77: 290 searches in twelve expert sessions, 252 of them filled to the cap,
     * because every search quoted its two best files in full whether or not an earlier search of
     * the same session had already quoted them - and the questions of one session are about one
     * subject, so they mostly had. A file in {@code alreadyQuoted} is named with its address
     * instead of quoted again, and every file this search does quote is added to it.
     *
     * @param alreadyQuoted the files this session has been shown the text of; null is none, and
     *                      nothing is remembered
     */
    static String search(KnowledgeCurator curator, String query, int maxChars, boolean withBodies,
                         Set<String> alreadyQuoted) {
        if (curator == null || query == null || query.isBlank()) {
            return NOTHING + (query == null ? "" : query);
        }
        StringBuilder sb = new StringBuilder();
        int structuralRoom = maxChars * 45 / 100;
        Set<String> shown = alreadyQuoted != null ? alreadyQuoted : new LinkedHashSet<>();
        structural(curator, query, structuralRoom, sb, shown, withBodies);
        int left = maxChars - sb.length();
        sources(curator, query, withBodies, left * 55 / 100, sb, shown);
        docs(curator, query, withBodies, maxChars - sb.length(), sb);
        if (sb.isEmpty()) {
            return NOTHING + query;
        }
        sb.append("\nRead any address above with read_file - add :LINE for the lines around a "
            + "line, :FROM-TO for a range, or #member for one method."
            + (withBodies ? "" : " public_shape <Type> lists a type's members; a "
                + "documentation section is read with doc_section <address>#<heading>.")
            + "\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------------------------

    private static void structural(KnowledgeCurator curator, String query, int room,
                                   StringBuilder sb, Set<String> shown, boolean withBodies) {
        SemanticIndex index;
        try {
            index = curator.semanticIndex();
        } catch (Throwable e) {                                            // noqa
            return;
        }
        if (index == null || !index.available()) {
            return;
        }
        // Types the material itself declares first: their members can be shown. A type known
        // only from import lines (a class in a jar) comes after, with the code that uses it.
        Map<String, String> declaredHere = new LinkedHashMap<>();
        Map<String, String> fromAJar = new LinkedHashMap<>();
        for (String name : typeNamesIn(query, index)) {
            List<String> resolved = index.resolve(name);
            if (resolved.isEmpty()) {
                continue;
            }
            String fqn = resolved.get(0);
            (index.declarationOf(fqn) != null ? declaredHere : fromAJar).putIfAbsent(fqn, name);
        }
        Map<String, String> fqnOf = new LinkedHashMap<>(declaredHere);
        fqnOf.putAll(fromAJar);

        StringBuilder block = new StringBuilder();
        Map<String, Integer> namedTypesUsedBy = new LinkedHashMap<>();
        int types = 0;
        for (String fqn : fqnOf.keySet()) {
            if (types >= MAX_TYPES || block.length() >= room) {
                break;
            }
            types++;
            SemanticIndex.Ref declared = index.declarationOf(fqn);
            if (declared != null) {
                shown.add(address(declared, false));
                block.append("\n### `").append(simple(fqn)).append("` - ").append(declared.detail())
                    .append(", declared in ").append(address(declared, true)).append('\n')
                    .append("```java\n").append(head(index.publicShape(fqn), SHAPE_LINES))
                    .append("\n```\n");
            } else {
                block.append("\n### `").append(simple(fqn)).append("` - ").append(fqn)
                    .append(". Declared in a library jar, not in the material that can be read "
                        + "here, so its members cannot be listed; the code that uses it shows how "
                        + "it is called.\n");
            }
            List<SemanticIndex.Ref> used = index.usagesOf(fqn);
            Set<String> files = new LinkedHashSet<>();
            for (SemanticIndex.Ref ref : used) {
                if (declared == null || !ref.file().equals(declared.file())) {
                    files.add(address(ref, false));
                }
            }
            files.forEach(file -> namedTypesUsedBy.merge(file, 1, Integer::sum));
            uses(used, "Used in", block);
            List<SemanticIndex.Ref> implementations = index.implementationsOf(fqn);
            if (!implementations.isEmpty()) {
                uses(implementations, "Implemented or extended by", block);
            }
        }
        // A method the question names is looked up ON the types it names: "start" alone is every
        // start() of every class, and the question meant one of them.
        int methods = 0;
        for (String method : index.methodNamesKnownIn(query)) {
            if (methods >= 3 || block.length() >= room) {
                break;
            }
            List<SemanticIndex.Ref> calls = new ArrayList<>();
            for (String fqn : fqnOf.keySet()) {
                calls.addAll(index.usagesOf(fqn + "#" + method));
            }
            if (calls.isEmpty() && fqnOf.isEmpty()) {
                calls = index.usagesOf(method);
            }
            if (!calls.isEmpty()) {
                methods++;
                uses(calls, "\nCalls of `" + method + "(...)`", block);
            }
        }
        for (String artifact : index.artifactsNamedIn(query)) {
            List<SemanticIndex.Ref> declaring = index.dependencyDeclaring(artifact);
            if (!declaring.isEmpty() && block.length() < room) {
                uses(declaring, "\nBuild files declaring `" + artifact + "`", block);
            }
        }
        List<String> unknown = unknownNames(query, index);
        if (!unknown.isEmpty()) {
            block.append("\nNot a type the index knows (a library class outside the material, or "
                + "not the real name): ").append(String.join(", ", unknown)).append('\n');
        }
        if (block.isEmpty()) {
            return;
        }
        sb.append("## What the question names, as the code index resolves it\n")
            .append(cut(block.toString(), room * 2 / 3));
        if (withBodies) {
            workedExample(curator, query, namedTypesUsedBy, fqnOf.size(),
                room - room * 2 / 3 + 600, sb, shown);
        }
    }

    /**
     * The one file that uses the most of the types the question names, quoted where it uses
     * them - what the expert of harness run 66 walked folders for.
     */
    private static void workedExample(KnowledgeCurator curator, String query,
                                      Map<String, Integer> namedTypesUsedBy, int named, int room,
                                      StringBuilder sb, Set<String> shown) {
        if (namedTypesUsedBy.isEmpty() || room < 800) {
            return;
        }
        // A question about a test is best answered by a test; any other by code that is not one.
        boolean wantsTest = query.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\btests?\\b.*");
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> entry : namedTypesUsedBy.entrySet()) {
            if (shown.contains(entry.getKey()) || !entry.getKey().endsWith(".java")) {
                continue;
            }
            boolean isTest = entry.getKey().contains("/src/test/");
            int score = entry.getValue() * 10 + (isTest == wantsTest ? 5 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }
        if (best == null) {
            return;
        }
        String source = curator.readFile(best, 400_000);
        if (source.startsWith("error:") || source.isBlank()) {
            return;
        }
        shown.add(best);
        int uses = namedTypesUsedBy.get(best);
        sb.append("\n## Real code that uses ").append(uses == named && named > 1 ? "all " : "")
            .append(uses).append(" of the ").append(named).append(" type(s) the question names\n")
            .append(quote(best, source, query, room));
    }

    /**
     * Every type name in the question that the index knows - a dotted token as a whole (a
     * fully-qualified name), and each capitalised part of it ({@code TestServer.builder()} names
     * {@code TestServer}).
     */
    private static List<String> typeNamesIn(String query, SemanticIndex index) {
        Set<String> names = new LinkedHashSet<>();
        for (String token : query.split("[^A-Za-z0-9_.]+")) {
            if (token.length() < 3) {
                continue;
            }
            if (token.contains(".") && !index.resolve(token).isEmpty()) {
                names.add(token);
                continue;
            }
            for (String part : token.split("\\.")) {
                if (part.length() >= 3 && Character.isUpperCase(part.charAt(0))
                        && !part.equals(part.toUpperCase(java.util.Locale.ROOT))
                        && !index.resolve(part).isEmpty()) {
                    names.add(part);
                }
            }
        }
        return List.copyOf(names);
    }

    /** Names written like types - an interior capital, or dotted - that the index does not hold. */
    private static List<String> unknownNames(String query, SemanticIndex index) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String token : query.split("[^A-Za-z0-9_.]+")) {
            if (token.contains(".") && !index.resolve(token).isEmpty()) {
                continue;
            }
            for (String word : token.split("\\.")) {
                if (word.length() < 4 || !Character.isUpperCase(word.charAt(0))) {
                    continue;
                }
                boolean interiorCapital = false;
                boolean lower = false;
                for (int i = 1; i < word.length(); i++) {
                    interiorCapital |= Character.isUpperCase(word.charAt(i));
                    lower |= Character.isLowerCase(word.charAt(i));
                }
                if (interiorCapital && lower && index.resolve(word).isEmpty()) {
                    unknown.add(word);
                }
            }
        }
        return unknown.size() > 6 ? List.copyOf(unknown).subList(0, 6) : List.copyOf(unknown);
    }

    private static void uses(List<SemanticIndex.Ref> refs, String heading, StringBuilder into) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        // One line per FILE: twelve references in one file are one place to go and read.
        Map<String, SemanticIndex.Ref> byFile = new LinkedHashMap<>();
        for (SemanticIndex.Ref ref : refs) {
            byFile.putIfAbsent(ref.file(), ref);
        }
        into.append(heading).append(" (").append(byFile.size())
            .append(byFile.size() == 1 ? " file" : " files").append("):\n");
        int listed = 0;
        for (SemanticIndex.Ref ref : byFile.values()) {
            if (listed++ >= MAX_USES) {
                break;
            }
            into.append("  ").append(address(ref, true));
            if (ref.detail() != null && !ref.detail().isBlank()) {
                into.append("  ").append(ref.detail());
            }
            into.append('\n');
        }
    }

    // -------------------------------------------------------------------------------------------

    private static void sources(KnowledgeCurator curator, String query, boolean withBodies,
                                int room, StringBuilder sb, Set<String> shown) {
        List<ReferenceIndex.SrcHit> hits;
        try {
            hits = curator.sourceHits(query, MAX_SOURCES);
        } catch (Exception e) {                                            // noqa
            return;
        }
        if (hits.isEmpty() || room < 200) {
            return;
        }
        StringBuilder block = new StringBuilder();
        int quoted = 0;
        List<String> named = new ArrayList<>();
        for (ReferenceIndex.SrcHit hit : hits) {
            boolean java = hit.address().endsWith(".java");
            if (withBodies && java && quoted < QUOTED_SOURCES && !shown.contains(hit.address())) {
                int each = Math.max(1_200, room / QUOTED_SOURCES - 200);
                String source = curator.readFile(hit.address(), 400_000);
                if (!source.startsWith("error:") && !source.isBlank()) {
                    block.append(quote(hit.address(), source, query, each));
                    shown.add(hit.address());
                    quoted++;
                    continue;
                }
            }
            named.add(hit.address() + oneLine(hit.outline())
                + (withBodies && java && shown.contains(hit.address())
                    ? "  (already shown to you, above or earlier in this session)" : ""));
        }
        if (!named.isEmpty()) {
            block.append(quoted == 0 ? "" : "\nMore code that matches:\n");
            for (String line : named) {
                block.append("  ").append(line).append('\n');
            }
        }
        sb.append("\n## Code that matches the question's words, best first\n")
            .append(cut(block.toString(), room));
    }

    private static void docs(KnowledgeCurator curator, String query, boolean withBodies, int room,
                             StringBuilder sb) {
        if (room < 300) {
            return;
        }
        if (withBodies) {
            String rendered = curator.relevantDocs(query, MAX_SECTIONS, room - 80, true);
            if (!rendered.isBlank()) {
                sb.append("\n## Documentation that matches, best first\n").append(rendered);
            }
            return;
        }
        List<KnowledgeCurator.ScoredSection> sections;
        try {
            sections = curator.scoreSections(query, true);
        } catch (Exception e) {                                            // noqa
            return;
        }
        if (sections.isEmpty()) {
            return;
        }
        StringBuilder block = new StringBuilder();
        Map<String, Integer> perPage = new LinkedHashMap<>();
        int listed = 0;
        for (KnowledgeCurator.ScoredSection scored : sections) {
            KnowledgeCurator.DocSection section = scored.value();
            if (listed >= MAX_SECTIONS + 2) {
                break;
            }
            if (perPage.merge(section.address(), 1, Integer::sum) > 2) {
                continue;
            }
            block.append("  ").append(section.address()).append(" > ").append(section.heading())
                .append('\n');
            listed++;
        }
        sb.append("\n## Documentation sections that match, best first\n")
            .append(cut(block.toString(), room));
    }

    // -------------------------------------------------------------------------------------------

    /**
     * A file shaped to the question - the members that match it with their bodies, the rest as
     * signatures - under its address, with how to read one member whole. The shaper's own note
     * is written for a worker's tools and is not passed on.
     */
    private static String quote(String address, String source, String query, int room) {
        SourceShaper.Answer shaped = SourceShaper.shape(address, source, query,
            Math.max(600, room - 200));
        boolean whole = shaped.tier() == SourceShaper.Tier.WHOLE && shaped.note().isBlank();
        return "\n## Source: " + address + "\n```java\n" + shaped.code() + "\n```\n"
            + (whole ? "" : "[An excerpt shaped to the question: the members that match it, the "
                + "rest as signatures. One member whole: read_file " + address + "#memberName]\n");
    }

    private static String address(SemanticIndex.Ref ref, boolean withLine) {
        String label = ref.rootLabel() == null || ref.rootLabel().isBlank()
            ? "" : ref.rootLabel() + "/";
        return label + ref.file().replace('\\', '/')
            + (withLine && ref.line() > 0 ? ":" + ref.line() : "");
    }

    private static String simple(String fqn) {
        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
    }

    private static String simpleNames(Set<String> fqns) {
        List<String> names = new ArrayList<>();
        fqns.forEach(fqn -> names.add(simple(fqn)));
        return String.join(", ", names);
    }

    private static String head(String text, int maxLines) {
        String[] lines = text.split("\n");
        if (lines.length <= maxLines) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        // The last line of a shape is "declared in <file>:<line>" and is always kept.
        return sb + "  ... " + (lines.length - maxLines - 1) + " more members (public_shape lists "
            + "them all)\n" + lines[lines.length - 1];
    }

    private static String oneLine(String outline) {
        if (outline == null || outline.isBlank()) {
            return "";
        }
        String flat = outline.strip().replaceAll("\\s+", " ");
        return "  - " + (flat.length() > 140 ? flat.substring(0, 140) + "..." : flat);
    }

    private static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int at = text.lastIndexOf('\n', Math.max(0, max));
        String kept = text.substring(0, at > max / 2 ? at : max);
        // Never leave a code fence open: everything after it would read as code.
        int fences = kept.split("```", -1).length - 1;
        return kept + (fences % 2 == 1 ? "\n```" : "") + "\n... (cut)\n";
    }
}
