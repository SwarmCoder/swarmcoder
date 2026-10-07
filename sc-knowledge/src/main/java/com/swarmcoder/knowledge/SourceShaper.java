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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shapes one Java source file to the size of the question asked about it.
 *
 * <p>A worker gets about 51,200 tokens of working context, its fixed prefix takes a share, and
 * every tool result stays in the conversation until compaction. So a {@code lookup_api} answer
 * that returns the first 2,400 characters of a 600-line class — a license header, the imports and
 * a constructor — spends hundreds of tokens and answers nothing. The worker that reverse-engineered
 * a jar with {@code javap -p} wanted six method signatures; this class gives it those.
 *
 * <p>Three tiers, chosen by what the question names:
 * <ul>
 *   <li><b>{@link Tier#WHOLE}</b> — the file as written (minus a license header, which is never
 *       the answer). Only for a file small enough that shaping would cost more than it saves, or
 *       when a worker asks for a file by path, optionally with a line range.</li>
 *   <li><b>{@link Tier#REGION}</b> — the question names something inside the file: a method, a
 *       field, a word that appears in a body. The members that mention it are returned WITH their
 *       bodies (a long body as the lines around the mention), and the rest of the public surface
 *       as signatures if there is room. "What does save() throw" gets save()'s throws clause and
 *       its body; "how does ChatView connect to the service" gets the field holding the service
 *       and the methods that call it.</li>
 *   <li><b>{@link Tier#SURFACE}</b> — the question names the type and nothing inside it: the
 *       public and protected declarations, signatures only, with the first sentence of their
 *       javadoc. What {@code javap -p} gave the spelunking worker in one step.</li>
 * </ul>
 *
 * <p>Every shaped answer ends with one line saying what was left out and how to ask for it: a
 * worker that does not know it is looking at a summary assumes the method has no body. When in
 * doubt the shaping returns more, not less — a slightly fat answer costs tokens; a wrong answer
 * costs a whole worker.
 */
public final class SourceShaper {

    public enum Tier { WHOLE, REGION, SURFACE }

    /** A shaped file: the code to show and the one-line note about what is not shown. */
    public record Answer(Tier tier, String code, String note) {

        /** As it goes into a tool result, under a source heading the worker can quote back. */
        public String render(String address) {
            return "\n## Source: " + address + "\n```java\n" + code + "\n```\n"
                + (note.isBlank() ? "" : note + "\n");
        }
    }

    /**
     * Below this size (license header excluded) a file goes whole. Shaping costs a note line and
     * the risk of leaving something out; on a file this small it cannot save enough to pay for
     * either. {@code ClickEvent.java} — a constructor and nothing else — is 250 characters.
     */
    static final int WHOLE_FILE_CHARS = 1_200;
    /** A matched member shown in full up to this size; beyond it, the lines around the mention. */
    static final int MEMBER_CHARS = 1_400;
    /** Lines kept on either side of a mention when a long member is excerpted. */
    static final int CONTEXT_LINES = 4;
    /** Room kept back from the budget for the closing brace, the code fence and the note. */
    static final int NOTE_CHARS = 450;

    /**
     * Query words that describe the QUESTION, not anything in the file. "class" would otherwise
     * match every call to {@code addClassName}, and "example" every example.
     */
    static final Set<String> NOISE = Set.of(
        "class", "classes", "interface", "implementation", "import", "imports", "package",
        "declaration", "example", "examples", "code", "method", "methods", "usage", "using",
        "java", "with", "from", "what", "does", "should", "have", "this", "that", "type",
        "public", "void", "static", "final", "which", "where", "when", "into", "about", "them",
        "their", "there", "call", "calls", "instance", "obtain", "sample", "snippet", "file",
        "source", "lookup", "please", "show", "give", "need", "want", "return", "returns");

    private SourceShaper() {
    }

    /** Shapes {@code source} (addressed as {@code address}) to {@code query}, within {@code maxChars}. */
    public static Answer shape(String address, String source, String query, int maxChars) {
        return shape(address, source, query, maxChars, Set.of());
    }

    /**
     * @param otherTypes simple names of the other types the same answer covers — a question that
     *                   names five components is not asking whether {@code Button} is declared
     *                   inside {@code FormLayout}
     */
    public static Answer shape(String address, String source, String query, int maxChars,
                               Set<String> otherTypes) {
        JavaOutline outline = JavaOutline.of(source);
        String body = outline.withoutLicense.strip();
        JavaOutline.Member type = outline.primaryType();
        if (type == null) {
            return whole(address, outline, 1, 0, maxChars);
        }
        if (body.length() <= Math.min(WHOLE_FILE_CHARS, maxChars)) {
            return new Answer(Tier.WHOLE, body, "");
        }
        Map<String, String> terms = selectorTerms(query, type.name());
        for (String other : otherTypes) {
            terms.remove(other.toLowerCase(Locale.ROOT));
        }
        // "imports" in the question means the worker wants to see them; nothing else does.
        boolean withImports = query != null && query.toLowerCase(Locale.ROOT).matches("(?s).*\\bimports?\\b.*");
        List<JavaOutline.Member> byHeader = new ArrayList<>();
        List<JavaOutline.Member> byBody = new ArrayList<>();
        for (JavaOutline.Member member : type.descendants()) {
            String header = (member.header() + " " + member.name()).toLowerCase(Locale.ROOT);
            if (mentionsAny(header, terms.keySet())) {
                byHeader.add(member);
            } else if (member.kind() != JavaOutline.Kind.TYPE
                       && mentionsAny(member.text().toLowerCase(Locale.ROOT), terms.keySet())) {
                byBody.add(member);
            }
        }
        // Only a word that looks like a member name — getValue, onAuthenticated, listBooks — is
        // worth saying "not declared here" about; a plain word like "signup" is not. And "not
        // declared" means no DECLARATION: TextField's constructor calls the DOM input's getValue(),
        // but the getValue() a worker asks about is declared on AbstractField, and that is the
        // pointer it needs.
        List<String> missing = new ArrayList<>();
        StringBuilder declarations = new StringBuilder();
        for (JavaOutline.Member member : type.descendants()) {
            declarations.append(member.header()).append(' ').append(member.name()).append('\n');
        }
        String declared = declarations.toString().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> term : terms.entrySet()) {
            if (!declared.contains(term.getKey()) && looksLikeIdentifier(term.getValue())) {
                missing.add(term.getValue());
            }
        }
        int room = maxChars - NOTE_CHARS;
        Answer answer = byHeader.isEmpty() && byBody.isEmpty()
            ? surface(outline, type, room, withImports)
            : region(outline, type, terms, byHeader, byBody, room, withImports);
        // The "not declared here" hint only when this file is the whole answer: a question that
        // names five types is not asking whether each is declared inside the others.
        String hint = otherTypes.isEmpty() ? missingHint(type, missing) : "";
        return bounded(hint.isEmpty() ? answer
            : new Answer(answer.tier(), answer.code(), answer.note() + "\n" + hint), maxChars);
    }

    /** Code plus note never exceed the budget: the hint goes first, then the code is cut. */
    private static Answer bounded(Answer answer, int maxChars) {
        if (answer.code().length() + answer.note().length() <= maxChars) {
            return answer;
        }
        String note = answer.note();
        int hint = note.indexOf("\n[Not declared");
        if (hint > 0) {
            note = note.substring(0, hint);
        }
        int room = maxChars - note.length() - 12;
        String code = answer.code();
        if (code.length() > room) {
            int cut = code.lastIndexOf('\n', Math.max(0, room));
            code = code.substring(0, Math.max(0, cut)).stripTrailing() + "\n… (cut)";
        }
        return new Answer(answer.tier(), code, note);
    }

    /**
     * The whole file from line {@code fromLine} to {@code toLine} (1-based, inclusive; 0 means to
     * the end; a request from line 1 starts after the license header), as much as fits, with a
     * note naming the next range to ask for.
     */
    public static Answer whole(String address, JavaOutline outline, int fromLine, int toLine,
                               int maxChars) {
        int total = outline.lineCount;
        int start = Math.max(fromLine, 1);
        if (start == 1) {
            start = outline.firstCodeLine();
        }
        int stop = toLine <= 0 ? total : Math.min(total, toLine);
        String[] lines = outline.source().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int last = start - 1;
        for (int i = start - 1; i < stop; i++) {
            if (sb.length() + lines[i].length() + 1 > maxChars - NOTE_CHARS) {
                break;
            }
            sb.append(lines[i]).append('\n');
            last = i + 1;
        }
        boolean complete = last >= total || (last == total - 1 && lines[total - 1].isBlank());
        String note = complete ? ""
            : "[Lines " + start + "-" + last + " of " + total + ". For the next part ask lookup_api \""
              + address + " lines " + (last + 1) + "-"
              + Math.min(total, last + Math.max(40, last - start + 1)) + "\".]";
        return new Answer(Tier.WHOLE, sb.toString().stripTrailing(), note);
    }

    // --- the surface -------------------------------------------------------------------------

    private static Answer surface(JavaOutline outline, JavaOutline.Member type, int maxChars,
                                  boolean withImports) {
        StringBuilder sb = new StringBuilder();
        appendHead(sb, outline, withImports);
        int[] counts = new int[3]; // [bodies left out, hidden members, members that did not fit]
        appendTypeSurface(sb, type, "", counts, maxChars);
        String note = "[Signatures only. Left out: " + counts[0] + " method bodies, " + counts[1]
            + " private members (" + outline.lineCount + " lines in the file)"
            + (counts[2] > 0 ? ", " + counts[2] + " more public members that did not fit" : "")
            + ". Ask lookup_api \"" + type.name() + " " + exampleMember(type)
            + "\" for one member with its body, or give the path above for the whole file.]";
        return new Answer(Tier.SURFACE, sb.toString().stripTrailing(), note);
    }

    private static void appendTypeSurface(StringBuilder sb, JavaOutline.Member type, String indent,
                                          int[] counts, int maxChars) {
        appendSummary(sb, type, indent);
        sb.append(indent).append(type.header()).append(" {\n");
        for (JavaOutline.Member member : type.children()) {
            if (!member.exposed()) {
                counts[1] += 1 + member.descendants().size();
                continue;
            }
            if (member.kind() == JavaOutline.Kind.INITIALIZER) {
                continue;
            }
            if (sb.length() > maxChars - 200) {
                counts[2]++;
                continue;
            }
            if (member.kind() == JavaOutline.Kind.TYPE) {
                appendTypeSurface(sb, member, indent + "    ", counts, maxChars);
                continue;
            }
            if (member.hasBody() && member.kind() != JavaOutline.Kind.FIELD) {
                counts[0]++;
            }
            appendSummary(sb, member, indent + "    ");
            sb.append(indent).append("    ").append(signature(member)).append('\n');
        }
        if (counts[2] > 0 && indent.isEmpty()) {
            sb.append("    … (").append(counts[2]).append(" more public members)\n");
        }
        sb.append(indent).append("}\n");
    }

    /** The package line, and the imports only when the question asked for them. */
    private static void appendHead(StringBuilder sb, JavaOutline outline, boolean withImports) {
        if (!outline.packageName.isEmpty()) {
            sb.append("package ").append(outline.packageName).append(";\n\n");
        }
        if (withImports && !outline.imports.isEmpty()) {
            for (String imported : outline.imports) {
                sb.append("import ").append(imported).append(";\n");
            }
            sb.append('\n');
        }
    }

    /** camelCase or Dotted.Name or ALL_CAPS — something a Java program could declare. */
    static boolean looksLikeIdentifier(String word) {
        for (int i = 1; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isUpperCase(c) || c == '_') {
                return true;
            }
        }
        return false;
    }

    private static void appendSummary(StringBuilder sb, JavaOutline.Member member, String indent) {
        if (!member.summary().isEmpty()) {
            sb.append(indent).append("/** ").append(member.summary()).append(" */\n");
        }
    }

    /** One declaration as a line: a body becomes {@code { … }}, an initializer {@code = …}. */
    static String signature(JavaOutline.Member member) {
        return switch (member.kind()) {
            case METHOD, CONSTRUCTOR -> member.header() + (member.hasBody() ? " { … }" : ";");
            case FIELD -> member.header() + (member.text().contains("=") ? " = …;" : ";");
            case ENUM_CONSTANTS -> member.header() + ";";
            case TYPE -> member.header() + " { … }";
            case INITIALIZER -> "";
        };
    }

    /** A member the note can name as the example of asking for one — the type's own first method. */
    private static String exampleMember(JavaOutline.Member type) {
        for (JavaOutline.Member member : type.children()) {
            if (member.exposed() && member.kind() == JavaOutline.Kind.METHOD) {
                return member.name();
            }
        }
        for (JavaOutline.Member member : type.descendants()) {
            if (member.kind() == JavaOutline.Kind.METHOD) {
                return member.name();
            }
        }
        return type.name();
    }

    // --- the region ------------------------------------------------------------------------------

    private static Answer region(JavaOutline outline, JavaOutline.Member type,
                                 Map<String, String> terms, List<JavaOutline.Member> byHeader,
                                 List<JavaOutline.Member> byBody, int maxChars, boolean withImports) {
        StringBuilder sb = new StringBuilder();
        appendHead(sb, outline, withImports);
        appendSummary(sb, type, "");
        sb.append(type.header()).append(" {\n");
        List<JavaOutline.Member> shown = new ArrayList<>();
        List<JavaOutline.Member> squeezed = new ArrayList<>();
        // Direct answers first: the members whose declaration names what was asked. Then the
        // members that mention it in a body. A block that does not fit is named in the note.
        List<JavaOutline.Member> matched = new ArrayList<>(byHeader);
        matched.addAll(byBody);
        for (JavaOutline.Member member : matched) {
            if (member.kind() == JavaOutline.Kind.TYPE) {
                continue; // a nested type is shown through its own matching members
            }
            boolean direct = byHeader.contains(member);
            String block = direct && member.text().length() <= MEMBER_CHARS
                ? indent(dedent(member.text()), "    ")
                : indent(excerpt(dedent(member.text()), terms.keySet(), CONTEXT_LINES, MEMBER_CHARS),
                    "    ");
            String withSummary = (member.summary().isEmpty() ? ""
                : "    /** " + member.summary() + " */\n") + block + "\n\n";
            if (sb.length() + withSummary.length() <= maxChars) {
                sb.append(withSummary);
                shown.add(member);
            } else {
                squeezed.add(member);
            }
        }
        // Then the rest of the surface, signatures only, while there is room.
        int restLeftOut = 0;
        boolean restHeading = false;
        for (JavaOutline.Member member : type.descendants()) {
            if (shown.contains(member) || !member.exposed()
                || member.kind() == JavaOutline.Kind.INITIALIZER
                || member.kind() == JavaOutline.Kind.TYPE) {
                continue;
            }
            String line = "    " + signature(member) + "\n";
            if (sb.length() + line.length() > maxChars) {
                restLeftOut++;
                continue;
            }
            if (!restHeading) {
                sb.append("    // the rest of the public surface, signatures only:\n");
                restHeading = true;
            }
            sb.append(line);
        }
        if (restLeftOut > 0) {
            sb.append("    … (").append(restLeftOut).append(" more public members)\n");
        }
        sb.append("}\n");
        StringBuilder note = new StringBuilder("[Shown with bodies: the ").append(shown.size())
            .append(shown.size() == 1 ? " member of " : " members of ").append(type.name())
            .append(" that mention ").append(String.join(", ", terms.values()));
        if (restHeading) {
            note.append("; the rest as signatures");
        }
        note.append(". Left out: the other bodies");
        if (!squeezed.isEmpty()) {
            note.append(" and ").append(squeezed.size()).append(" more matching member")
                .append(squeezed.size() == 1 ? "" : "s").append(" (").append(names(squeezed))
                .append(")");
        }
        note.append(". Ask lookup_api \"").append(type.name()).append(' ')
            .append(squeezed.isEmpty() ? exampleMember(type) : squeezed.get(0).name())
            .append("\" for one member, or give the path above for the whole file.]");
        return new Answer(Tier.REGION, sb.toString().stripTrailing(), note.toString());
    }

    private static String names(List<JavaOutline.Member> members) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JavaOutline.Member member : members) {
            if (n++ == 4) {
                sb.append(", …");
                break;
            }
            sb.append(n > 1 ? ", " : "").append(member.name());
        }
        return sb.toString();
    }

    // --- the hint for what is not here ------------------------------------------------------------

    private static final Pattern EXTENDS = Pattern.compile("\\bextends\\s+([\\w.]+)");
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bimplements\\s+(.+)$");

    /**
     * "Not declared in TextField: getValue, setValue. It extends AbstractField — try lookup_api
     * "AbstractField getValue"." A question about a text field's value is answered by the
     * superclass, and a worker told only "not found" would go and look in the jar.
     */
    static String missingHint(JavaOutline.Member type, List<String> missing) {
        if (missing.isEmpty()) {
            return "";
        }
        List<String> supertypes = supertypes(type.header());
        String hint = "[Not declared in " + type.name() + ": " + String.join(", ", missing) + ".";
        if (!supertypes.isEmpty()) {
            hint += " It extends " + supertypes.get(0)
                + (supertypes.size() > 1
                    ? " (and implements " + String.join(", ", supertypes.subList(1, supertypes.size())) + ")"
                    : "")
                + " — try lookup_api \"" + supertypes.get(0) + " " + missing.get(0) + "\".";
        }
        return hint + "]";
    }

    /** The extended type first, then the implemented ones, simple names, at most four. */
    static List<String> supertypes(String typeHeader) {
        String header = JavaOutline.stripAnnotations(typeHeader);
        List<String> names = new ArrayList<>();
        Matcher ext = EXTENDS.matcher(header);
        if (ext.find()) {
            names.add(simpleName(ext.group(1)));
        }
        Matcher impl = IMPLEMENTS.matcher(header);
        if (impl.find()) {
            for (String part : splitTopLevel(impl.group(1))) {
                String name = simpleName(part.strip());
                if (!name.isEmpty() && names.size() < 4 && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : list.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String simpleName(String qualified) {
        String name = qualified.replaceAll("<.*", "").strip();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    // --- words and windows ------------------------------------------------------------------------

    /**
     * The query's words that could name something INSIDE the type — not noise, not the type
     * itself — lower-cased for matching, mapped to how the question spelled them.
     */
    static Map<String, String> selectorTerms(String query, String typeName) {
        Map<String, String> terms = new LinkedHashMap<>();
        String typeLower = typeName.toLowerCase(Locale.ROOT);
        for (String token : (query == null ? "" : query).split("[^A-Za-z0-9_]+")) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.length() > 3 && !NOISE.contains(lower) && !typeLower.contains(lower)) {
                terms.putIfAbsent(lower, token);
            }
        }
        return terms;
    }

    private static boolean mentionsAny(String lower, Set<String> terms) {
        for (String term : terms) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The lines of {@code text} that mention a term, each with {@code context} lines around it,
     * the first and last lines always, gaps marked. Shrinks the context, then cuts, to stay
     * within {@code maxChars}.
     */
    static String excerpt(String text, Set<String> terms, int context, int maxChars) {
        String[] lines = text.split("\n", -1);
        for (int ctx = context; ctx >= 0; ctx--) {
            boolean[] keep = new boolean[lines.length];
            keep[0] = true;
            keep[lines.length - 1] = true;
            for (int i = 0; i < lines.length; i++) {
                if (mentionsAny(lines[i].toLowerCase(Locale.ROOT), terms)) {
                    for (int j = Math.max(0, i - ctx); j <= Math.min(lines.length - 1, i + ctx); j++) {
                        keep[j] = true;
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            int skipped = 0;
            for (int i = 0; i < lines.length; i++) {
                if (keep[i]) {
                    if (skipped > 0) {
                        sb.append("    … (").append(skipped).append(" lines)\n");
                        skipped = 0;
                    }
                    sb.append(lines[i]).append('\n');
                } else {
                    skipped++;
                }
            }
            String result = sb.toString().stripTrailing();
            if (result.length() <= maxChars) {
                return result;
            }
            if (ctx == 0) {
                return result.substring(0, Math.max(0, maxChars - 12)).stripTrailing() + "\n    … (cut)";
            }
        }
        return text;
    }

    /** Removes the indentation the member had inside its type, so it can be re-indented cleanly. */
    static String dedent(String text) {
        String[] lines = text.split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < lines[i].length() && lines[i].charAt(indent) == ' ') {
                indent++;
            }
            common = Math.min(common, indent);
        }
        if (common == Integer.MAX_VALUE || common == 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            sb.append('\n').append(lines[i].length() >= common ? lines[i].substring(common) : lines[i].strip());
        }
        return sb.toString();
    }

    private static String indent(String text, String indent) {
        return indent + text.replace("\n", "\n" + indent);
    }
}
