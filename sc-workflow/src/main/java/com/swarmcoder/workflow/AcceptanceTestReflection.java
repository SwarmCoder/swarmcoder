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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An acceptance test may not locate the project's own constructors or methods by reflection. It
 * calls the members the contracts give, or it says the contracts do not say how to build
 * something.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live harness run 54, 2026-10-01, HamBook. The test author wrote
 * {@code swarm.accept.EditContactTest} for a service that needs a server-side {@code Store}. It did
 * not know how to construct one, so it wrote a helper that searched for a Store constructor by
 * reflection and threw {@code IllegalStateException("No suitable Store constructor found")}. Every
 * candidate failed inside the test's own code. The repair did the same trick again, and the run
 * parked with three of four tasks already built — lost to a test that guessed an API instead of
 * calling it.
 *
 * <p>A test that guesses at a signature cannot be right for a reason anyone can check: it fails
 * when the guess misses, and passes when it hits by luck. The compiler is the only thing that
 * reliably says "this member does not exist", and reflection removes the compiler.
 *
 * <h2>The rule, kept narrow</h2>
 *
 * <p>Read from the test source with comments and string contents stripped. Flagged:
 * <ul>
 *   <li>a constructor/method/field lookup ({@code getDeclaredConstructor(s)}, {@code getConstructor(s)},
 *       {@code getDeclaredMethod(s)}, {@code getMethod(s)}, {@code getDeclaredField(s)},
 *       {@code getField(s)}) — unless its receiver is {@code X.class} for a JDK type;</li>
 *   <li>{@code Class.forName(..)} — unless it names a JDK class;</li>
 *   <li>{@code setAccessible(..)}, which only exists to get past a member the test was not given;</li>
 *   <li>an import of {@code java.lang.reflect.Constructor}, {@code Method}, {@code Field},
 *       {@code Executable} or {@code AccessibleObject} — the types a lookup returns.</li>
 * </ul>
 * Not flagged: reflection on JDK types, and {@code java.lang.reflect} types that are not lookup
 * results ({@code Modifier}, {@code Proxy}, ...). An unreadable file concludes nothing.
 */
public final class AcceptanceTestReflection {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceTestReflection.class);

    private AcceptanceTestReflection() {}

    /** The one sentence given to the test author before it writes or repairs a line. */
    public static final String AUTHOR_RULE = "Never locate the project's constructors or methods by "
        + "reflection (getDeclaredConstructor, getMethod, Class.forName, setAccessible and the "
        + "like); call exactly the members the contracts give, and if the contracts do not say how "
        + "to build an object the test needs, say so in your reply instead of guessing.";

    /**
     * @param path     the repo-relative test file
     * @param evidence the line as written, trimmed
     */
    public record Use(String path, String evidence) {
        public String render() {
            return "in " + path + ": `" + evidence + "`";
        }
    }

    public record Check(List<Use> findings) {

        public static final Check CLEAN = new Check(List.of());

        public boolean ok() {
            return findings.isEmpty();
        }
    }

    private static final Pattern LOOKUP = Pattern.compile(
        "([\\w.]*\\w)?\\s*\\.\\s*(getDeclaredConstructors?|getConstructors?|getDeclaredMethods?|"
            + "getMethods?|getDeclaredFields?|getFields?)\\s*\\(\\s*(\\)?)");
    private static final Pattern FOR_NAME = Pattern.compile("\\bClass\\s*\\.\\s*forName\\s*\\(");
    private static final Pattern FOR_NAME_LITERAL = Pattern.compile(
        "\\bClass\\s*\\.\\s*forName\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern SET_ACCESSIBLE = Pattern.compile("\\.\\s*setAccessible\\s*\\(");
    private static final Pattern REFLECT_IMPORT = Pattern.compile(
        "(?m)^[ \\t]*import\\s+java\\.lang\\.reflect\\.(?:Constructor|Method|Field|Executable|"
            + "AccessibleObject|\\*)\\s*;");

    /** Simple names from java.lang that need no import. */
    private static final Set<String> JAVA_LANG = Set.of("Object", "String", "Integer", "Long",
        "Double", "Float", "Short", "Byte", "Boolean", "Character", "Number", "Class", "Math",
        "Thread", "System", "Runtime", "Enum", "Record", "Void", "Throwable", "Exception",
        "RuntimeException", "Error", "StringBuilder", "StringBuffer", "Iterable", "Comparable",
        "Runnable", "AutoCloseable", "CharSequence");

    /**
     * Reads the tests just written and reports every reflective reach in them.
     *
     * @param repoRoot  the tree the tests were written into
     * @param testPaths the repo-relative files the author just wrote
     */
    public static Check check(Path repoRoot, List<String> testPaths) {
        if (repoRoot == null || testPaths == null || testPaths.isEmpty()) {
            return Check.CLEAN;
        }
        List<Use> findings = new ArrayList<>();
        for (String path : testPaths) {
            String raw = read(repoRoot, path);
            if (!raw.isEmpty()) {
                findings.addAll(inSource(path, raw));
            }
        }
        return findings.isEmpty() ? Check.CLEAN : new Check(List.copyOf(findings));
    }

    /** The reflective reaches in one source; empty means it is clean. */
    static List<Use> inSource(String path, String raw) {
        String source = SelfImplementedContract.strip(raw);
        Set<String> imports = importedNames(source);
        Set<String> seen = new LinkedHashSet<>();
        List<Use> findings = new ArrayList<>();

        Matcher lookup = LOOKUP.matcher(source);
        while (lookup.find()) {
            String receiver = lookup.group(1);
            // "Store.class" is captured as the receiver "Store.class"; peel the suffix off.
            String typeName = receiver != null && receiver.endsWith(".class")
                ? receiver.substring(0, receiver.length() - ".class".length()) : null;
            if (typeName != null && isJdkType(typeName, imports)) {
                continue;
            }
            if (typeName == null) {
                // A receiver that is not a class literal: only the lookups that cannot be a
                // bean accessor (request.getMethod(), node.getFields()) count.
                String name = lookup.group(2);
                boolean singular = name.equals("getMethod") || name.equals("getDeclaredMethod")
                    || name.equals("getConstructor") || name.equals("getDeclaredConstructor");
                boolean plural = name.equals("getMethods") || name.equals("getDeclaredMethods")
                    || name.equals("getConstructors") || name.equals("getDeclaredConstructors");
                boolean hasArguments = lookup.group(3).isEmpty();
                if (!(plural || (singular && hasArguments))) {
                    continue;
                }
            }
            add(findings, seen, path, source, raw, lookup.start());
        }
        Matcher forName = FOR_NAME.matcher(source);
        while (forName.find()) {
            // Strings are blanked in `source`; read the literal, if there is one, from the same
            // line of the raw text (strip keeps line structure).
            Matcher onLine = FOR_NAME_LITERAL.matcher(
                lineAtLineNumber(raw, lineNumber(source, forName.start())));
            boolean jdk = false;
            if (onLine.find()) {
                String name = onLine.group(1);
                jdk = name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.");
            }
            if (!jdk) {
                add(findings, seen, path, source, raw, forName.start());
            }
        }
        Matcher accessible = SET_ACCESSIBLE.matcher(source);
        while (accessible.find()) {
            add(findings, seen, path, source, raw, accessible.start());
        }
        Matcher reflectImport = REFLECT_IMPORT.matcher(source);
        while (reflectImport.find()) {
            add(findings, seen, path, source, raw, reflectImport.start());
        }
        return findings;
    }

    private static void add(List<Use> into, Set<String> seen, String path, String source,
                            String raw, int at) {
        String evidence = trim(lineAtLineNumber(raw, lineNumber(source, skipBlank(source, at))));
        if (seen.add(path + "\n" + evidence)) {
            into.add(new Use(path, evidence));
        }
    }

    private static boolean isJdkType(String typeName, Set<String> imports) {
        if (typeName.startsWith("java.") || typeName.startsWith("javax.")
                || typeName.startsWith("jdk.")) {
            return true;
        }
        if (typeName.contains(".")) {
            return false;
        }
        for (String imported : imports) {
            if (imported.endsWith("." + typeName)) {
                return imported.startsWith("java.") || imported.startsWith("javax.")
                    || imported.startsWith("jdk.");
            }
        }
        return JAVA_LANG.contains(typeName);
    }

    private static final Pattern IMPORT = Pattern.compile("(?m)^[ \\t]*import\\s+([\\w.]+)\\s*;");

    private static Set<String> importedNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // ------------------------------------------------------------------ what people are told

    /** What the test author is told, once, when its test locates project members by reflection. */
    public static String reask(Check check) {
        StringBuilder message = new StringBuilder("Your test finds the project's own constructors "
            + "or methods by reflection:\n");
        for (Use use : check.findings()) {
            message.append("  - ").append(use.render()).append('\n');
        }
        message.append("\nThat is guessing at an API instead of calling it: it fails, or passes by "
            + "luck, for a reason nobody can check, and no candidate can make a guess right. "
            + AUTHOR_RULE + "\n\nReply with the same JSON object, with the corrected file(s).");
        return message.toString();
    }

    /** The brief a run parks with when the author was asked once and the test still reflects. */
    public static String brief(String taskTitle, Check check) {
        StringBuilder sb = new StringBuilder("The acceptance test(s) written for task '")
            .append(taskTitle).append("' find the project's own constructors or methods by "
                + "reflection, and the test author was asked once to call them directly and "
                + "did not:\n");
        for (Use use : check.findings()) {
            sb.append("\n  - ").append(use.render());
        }
        sb.append("\n\nA test that guesses a signature cannot be right for a reason anyone can "
            + "check, and every candidate fails inside the test's own code when the guess misses. "
            + "Usually the contracts do not say how to build an object the test needs (a store, "
            + "a connection, a configured service).\n\nDecide which side is wrong: add the "
            + "missing constructor or factory to a contract in the design, or correct the test to "
            + "use the members the contracts give. Then resume the run.");
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------- reading

    private static int lineNumber(String text, int at) {
        int n = 0;
        for (int i = 0; i < at && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String lineAtLineNumber(String text, int lineNumber) {
        String[] lines = text.split("\n", -1);
        return lineNumber < lines.length ? lines[lineNumber] : "";
    }

    private static int skipBlank(String source, int at) {
        while (at < source.length() - 1 && Character.isWhitespace(source.charAt(at))) {
            at++; // a match that starts on leading whitespace belongs to the line it reaches
        }
        return at;
    }

    private static String trim(String line) {
        String text = line.strip();
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    private static String read(Path repoRoot, String relativePath) {
        try {
            return Files.readString(repoRoot.resolve(relativePath.replace('\\', '/')));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the authored test {}: {}", relativePath, e.toString());
            return "";
        }
    }
}
