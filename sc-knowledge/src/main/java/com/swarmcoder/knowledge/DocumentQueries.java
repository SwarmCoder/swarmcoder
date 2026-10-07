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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The questions about a project's written documents that are answered with no model: which
 * documents there are and what each one's sections are, ONE section by its number or heading,
 * and which sections are about a subject (live run 82, 2026-10-04; CLAUDE.md section 1).
 *
 * <p>The counterpart of {@link TreeQueries} for what the syntax tree does not hold. In run 82
 * the architect read seven documents whole - the framework primer alone was 30,531 characters,
 * and 200 of its lines a second time - because a document had no access but {@code read_file}:
 * 119,578 characters from whole files against 16,208 from the tree, sent again on each of its
 * 25 calls.
 *
 * <p>Documents are the ones the reference index already catalogues ({@link
 * KnowledgeCurator#documents()}): the project's own and every reference root's. The search is
 * the index's own section ranking, answered with places instead of text. Addresses are written
 * the way the asker's read tool takes them, as {@link TreeQueries} writes them.
 */
public final class DocumentQueries {

    /** A section longer than this that has subsections is answered with its lead and their list. */
    static final int SECTION_QUOTED_CHARS = TreeQueries.OVERLOADS_QUOTED_CHARS;
    /** How many places one search names. */
    static final int MAX_HITS = 20;
    /** A folder with more documents than this is listed without each one's sections. */
    static final int OUTLINED_TOGETHER = 20;

    private final KnowledgeCurator curator;
    /** The label of the root a worker's plain paths are relative to; null for a role. */
    private final String ownRoot;

    public DocumentQueries(KnowledgeCurator curator) {
        this(curator, null);
    }

    private DocumentQueries(KnowledgeCurator curator, String ownRoot) {
        this.curator = curator;
        this.ownRoot = ownRoot;
    }

    /** The same queries for a worker in a checkout of {@code project}; see {@link TreeQueries}. */
    public static DocumentQueries forAWorkerOf(KnowledgeCurator curator, Path project) {
        String label = "";
        if (curator != null && project != null) {
            Path wanted = project.toAbsolutePath().normalize();
            for (KnowledgeCurator.Root root : curator.roots()) {
                if (root.path() != null && root.path().toAbsolutePath().normalize().equals(wanted)) {
                    label = root.label();
                }
            }
        }
        return new DocumentQueries(curator, label);
    }

    // --- addresses -------------------------------------------------------------------------------

    /** {@code <root>/<relative>} as the asker's read tool takes it. */
    private String shown(String address) {
        if (ownRoot == null) {
            return address;
        }
        int slash = address.indexOf('/');
        String label = slash < 0 ? address : address.substring(0, slash);
        String rest = slash < 0 ? "" : address.substring(slash + 1);
        return label.equals(ownRoot) ? rest : "/reference/" + address;
    }

    /** What the asker wrote, as the {@code <root>/<relative>} forms it can mean, likeliest first. */
    private List<String> candidates(String asked) {
        String cleaned = asked == null ? "" : asked.replace('\\', '/').strip();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        List<String> forms = new ArrayList<>();
        if (cleaned.isEmpty() || ("/" + cleaned + "/").contains("/../")) {
            return forms;
        }
        if (cleaned.startsWith("/reference/")) {
            forms.add(cleaned.substring("/reference/".length()));
            return forms;
        }
        // An absolute path inside a root - a compiler or a log prints those.
        for (KnowledgeCurator.Root root : curator.roots()) {
            String base = root.path() == null ? null
                : root.path().toAbsolutePath().normalize().toString().replace('\\', '/');
            if (base != null && cleaned.startsWith(base + "/")) {
                forms.add(root.label() + cleaned.substring(base.length()));
                return forms;
            }
        }
        if (cleaned.startsWith("/")) {
            return forms;
        }
        if (ownRoot != null && !ownRoot.isEmpty()) {
            forms.add(ownRoot + "/" + cleaned);
        }
        forms.add(cleaned);
        for (KnowledgeCurator.Root root : curator.roots()) {
            forms.add(root.label() + "/" + cleaned);
        }
        return forms;
    }

    private record Read(String address, String text) {}

    /** The document {@code asked} names, or null. */
    private Read read(String asked) {
        for (String address : candidates(asked)) {
            int slash = address.indexOf('/');
            String label = slash < 0 ? address : address.substring(0, slash);
            if (slash < 0 || curator.roots().stream().noneMatch(r -> r.label().equals(label))
                    || curator.isFolder(address)) {
                continue;
            }
            String text = curator.readFile(address, 2_000_000);
            if (!text.startsWith("error:")) {
                return new Read(address, text);
            }
        }
        return null;
    }

    private static String summary(DocumentOutline outline) {
        return outline.chars() + " chars, " + outline.lineCount() + " lines, "
            + outline.sections().size() + " section(s)";
    }

    // --- the queries -----------------------------------------------------------------------------

    /**
     * A document's outline: every heading with its number, its lines and its size. Blank: the
     * project's documents, each with its outermost sections, and how many documents each
     * reference root holds. A folder or a root: the documents in it.
     */
    public String outlineOf(String where) {
        if (curator == null) {
            return "No document index is configured here.";
        }
        String asked = where == null ? "" : where.strip();
        if (!asked.isEmpty()) {
            Read document = read(asked);
            if (document != null) {
                return outline(document);
            }
        }
        List<ReferenceIndex.DocEntry> all = curator.documents().stream()
            .filter(d -> DocumentOutline.isDocument(d.address())).toList();
        if (asked.isEmpty()) {
            return documentMap(all);
        }
        for (String prefix : candidates(asked)) {
            List<ReferenceIndex.DocEntry> inside = all.stream()
                .filter(d -> d.address().startsWith(prefix + "/")).toList();
            if (!inside.isEmpty()) {
                return listed("Documents in `" + shown(prefix) + "`", inside,
                    inside.size() <= OUTLINED_TOGETHER);
            }
        }
        return "No document `" + where + "` in this project's material. doc_outline with an "
            + "empty string lists the documents there are.";
    }

    private String outline(Read document) {
        DocumentOutline outline = DocumentOutline.of(document.address(), document.text());
        String address = shown(document.address());
        StringBuilder sb = new StringBuilder("`" + address + "` - " + summary(outline)
            + ". ONE section: doc_section " + address + "#<number or heading>.\n");
        for (DocumentOutline.Section section : outline.sections()) {
            sb.append("  ".repeat(Math.max(0, section.level() - 1)))
                .append(DocumentOutline.line(section)).append('\n');
        }
        return sb.toString();
    }

    private String documentMap(List<ReferenceIndex.DocEntry> all) {
        Map<String, List<ReferenceIndex.DocEntry>> byRoot = new LinkedHashMap<>();
        for (ReferenceIndex.DocEntry document : all) {
            int slash = document.address().indexOf('/');
            byRoot.computeIfAbsent(slash < 0 ? "" : document.address().substring(0, slash),
                r -> new ArrayList<>()).add(document);
        }
        if (byRoot.isEmpty()) {
            return "This project's material holds no documents.";
        }
        String project = ownRoot != null && !ownRoot.isEmpty() ? ownRoot : "project";
        StringBuilder sb = new StringBuilder("DOCUMENT MAP - every document with its sections "
            + "(number, heading, lines). Read ONE section with doc_section <document>#<number or "
            + "heading>; doc_outline <document> lists a document's every heading with its "
            + "size.\n");
        List<ReferenceIndex.DocEntry> own = byRoot.remove(project);
        if (own != null) {
            sb.append(listed("The project's documents", own, true));
        }
        for (Map.Entry<String, List<ReferenceIndex.DocEntry>> root : byRoot.entrySet()) {
            String label = shown(root.getKey() + "/x");
            label = label.substring(0, label.length() - 2);
            if (own == null && byRoot.size() == 1) {
                sb.append(listed("Documents in `" + label + "`", root.getValue(),
                    root.getValue().size() <= OUTLINED_TOGETHER));
            } else {
                sb.append("Reference material `").append(label).append("`: ")
                    .append(root.getValue().size()).append(" document(s) - doc_outline ")
                    .append(label).append(" lists them.\n");
            }
        }
        return sb.toString();
    }

    private String listed(String heading, List<ReferenceIndex.DocEntry> documents,
                          boolean withSections) {
        StringBuilder sb = new StringBuilder(heading + " (" + documents.size() + "):\n");
        List<ReferenceIndex.DocEntry> sorted = new ArrayList<>(documents);
        sorted.sort(java.util.Comparator.comparing(ReferenceIndex.DocEntry::address));
        for (ReferenceIndex.DocEntry document : sorted) {
            sb.append(shown(document.address()));
            if (!withSections) {
                sb.append(document.title() == null || document.title().isBlank() ? ""
                    : " - " + document.title()).append('\n');
                continue;
            }
            String text = curator.readFile(document.address(), 2_000_000);
            if (text.startsWith("error:")) {
                sb.append('\n');
                continue;
            }
            DocumentOutline outline = DocumentOutline.of(document.address(), text);
            sb.append(" - ").append(summary(outline)).append('\n');
            List<String> top = new ArrayList<>();
            for (DocumentOutline.Section section : outline.outermost()) {
                top.add(section.number() + " " + section.title() + " (" + section.startLine()
                    + "-" + section.endLine() + ")");
            }
            if (!top.isEmpty()) {
                sb.append("    ").append(String.join("; ", top)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * ONE section of a document, as {@code <document>#<number or heading>}: its text with its
     * file and lines. A long section that has subsections is answered with its own lead text and
     * the list of them.
     */
    public String sectionOf(String what) {
        if (curator == null) {
            return "No document index is configured here.";
        }
        String asked = what == null ? "" : what.strip();
        int hash = asked.indexOf('#');
        String key = hash < 0 ? "" : asked.substring(hash + 1).strip();
        Read document = read(hash < 0 ? asked : asked.substring(0, hash));
        if (document == null) {
            return "No document `" + (hash < 0 ? asked : asked.substring(0, hash)) + "` in this "
                + "project's material. Give <document>#<number or heading>; doc_outline with an "
                + "empty string lists the documents.";
        }
        DocumentOutline outline = DocumentOutline.of(document.address(), document.text());
        String address = shown(document.address());
        List<DocumentOutline.Section> found = outline.find(key);
        if (found.isEmpty()) {
            return (key.isEmpty() ? "Say which section, as " + address + "#<number or heading>. "
                : "`" + address + "` has no section `" + key + "`. ") + "Its sections:\n"
                + outline(document);
        }
        if (found.size() > 1) {
            StringBuilder sb = new StringBuilder("`" + key + "` names " + found.size()
                + " sections of `" + address + "`. Ask for one by its number:\n");
            found.forEach(s -> sb.append("  ").append(DocumentOutline.line(s)).append('\n'));
            return sb.toString();
        }
        DocumentOutline.Section section = found.get(0);
        String place = "[" + address + ":" + section.startLine() + "-" + section.endLine()
            + ", section " + section.number() + "]\n";
        List<DocumentOutline.Section> children = outline.childrenOf(section);
        if (section.chars() <= SECTION_QUOTED_CHARS || children.isEmpty()) {
            return place + outline.textOf(section);
        }
        StringBuilder sb = new StringBuilder(place)
            .append(outline.lines(section.startLine(), children.get(0).startLine() - 1))
            .append("[This section is ").append(section.chars()).append(" characters, so its ")
            .append(children.size()).append(" subsections are listed. Ask for one as ")
            .append(address).append("#<number>; all of it is read_file ").append(address)
            .append(':').append(section.startLine()).append('-').append(section.endLine())
            .append(".]\n");
        children.forEach(c -> sb.append("  ").append(DocumentOutline.line(c)).append('\n'));
        return sb.toString();
    }

    /**
     * Which sections of which documents are about {@code words}: each as its document, line,
     * section number and heading with its size - places, not text.
     */
    public String search(String words) {
        if (curator == null) {
            return "No document index is configured here.";
        }
        String asked = words == null ? "" : words.strip();
        List<KnowledgeCurator.ScoredSection> scored =
            asked.isEmpty() ? List.of() : curator.scoreSections(asked);
        if (scored.isEmpty()) {
            return "No section of this project's documents matches `" + asked + "`. doc_outline "
                + "with an empty string lists the documents and their sections.";
        }
        Map<String, DocumentOutline> outlines = new HashMap<>();
        List<String> places = new ArrayList<>();
        for (KnowledgeCurator.ScoredSection hit : scored) {
            if (places.size() >= MAX_HITS) {
                break;
            }
            String address = hit.value().address();
            if (!DocumentOutline.isDocument(address)) {
                continue;   // the index ranks a source file's javadoc too; that is the tree's
            }
            DocumentOutline outline = outlines.computeIfAbsent(address, a -> {
                String text = curator.readFile(a, 2_000_000);
                return text.startsWith("error:") ? null : DocumentOutline.of(a, text);
            });
            DocumentOutline.Section section = outline == null ? null
                : outline.titled(hit.value().heading());
            String place = section == null
                ? shown(address) + "  " + hit.value().heading()
                : shown(address) + ":" + section.startLine() + "  section " + section.number()
                    + "  " + section.title() + "  (" + section.chars() + " chars)";
            if (!places.contains(place)) {
                places.add(place);
            }
        }
        if (places.isEmpty()) {
            return "No section of this project's documents matches `" + asked + "`.";
        }
        return "Sections about `" + asked + "`, best first (" + places.size() + "):\n  "
            + String.join("\n  ", places)
            + "\nRead one with doc_section <document>#<section number>.\n";
    }

    /**
     * What a whole-file read of a document is answered with besides the file: the query that
     * returns one section of it. "" for a file that is not a document, has fewer than two
     * sections, or is so short that the note would be a tenth of it or more.
     */
    public String wholeFileNote(String address, String text) {
        if (text == null || !DocumentOutline.isDocument(address)) {
            return "";
        }
        DocumentOutline outline = DocumentOutline.of(address, text);
        if (outline.sections().size() < 2) {
            return "";
        }
        String note = "\n[You read this whole document: " + outline.chars() + " characters in "
            + outline.sections().size() + " sections, sent again with every later call. ONE "
            + "section is doc_section " + address + "#<number or heading>; doc_outline "
            + address + " lists the sections with their sizes; doc_search finds the sections "
            + "on a subject.]";
        return note.length() * 10 <= text.length() ? note : "";
    }
}
