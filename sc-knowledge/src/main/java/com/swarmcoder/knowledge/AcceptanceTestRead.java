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
import java.util.regex.Pattern;

/**
 * What a worker is shown of the acceptance test its task must satisfy (owner's decision,
 * 2026-10-05): the test methods the task claims, and the helpers of the test class they use -
 * not the whole file. No model: the file is read as the compiler's outline reads it.
 *
 * <p>A helper is a member of the same type (a method, a field, a constructor, a nested type)
 * whose name is used by a member already shown, followed through, plus the type's lifecycle
 * methods ({@code @BeforeEach} and the like), which run before and after every test. Matching is
 * on identifiers of the code, never on words of any language.
 */
public final class AcceptanceTestRead {

    private static final Pattern LIFECYCLE =
        Pattern.compile("@(BeforeEach|BeforeAll|AfterEach|AfterAll)\\b");
    private static final Pattern TEST =
        Pattern.compile("@(Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");

    private AcceptanceTestRead() {}

    /** Why nothing was shown, when {@link #of} has nothing to show. */
    public static final String NO_SUCH_METHOD = "declares none of the claimed test methods";

    /**
     * @param address  the file's repository-relative path, for the heading
     * @param source   the test file's text
     * @param methods  the claimed methods by simple name; empty means every test method of the file
     * @return the claimed methods and the helpers they use, per type, as code; one line saying
     *         so when the file declares none of them
     */
    public static String of(String address, String source, Set<String> methods) {
        JavaOutline outline;
        try {
            outline = JavaOutline.of(source.replace("\r\n", "\n"));
        } catch (RuntimeException unreadable) {                                 // noqa
            return "`" + address + "` could not be read as Java.\n";
        }
        StringBuilder out = new StringBuilder();
        List<JavaOutline.Member> types = new ArrayList<>();
        for (JavaOutline.Member top : outline.types) {
            types.add(top);
            for (JavaOutline.Member nested : top.descendants()) {
                if (nested.kind() == JavaOutline.Kind.TYPE) {
                    types.add(nested);
                }
            }
        }
        for (JavaOutline.Member type : types) {
            List<JavaOutline.Member> claimed = new ArrayList<>();
            for (JavaOutline.Member child : type.children()) {
                if (child.kind() != JavaOutline.Kind.METHOD) {
                    continue;
                }
                boolean wanted = methods == null || methods.isEmpty()
                    ? TEST.matcher(child.header() + " " + child.text()).find()
                    : methods.contains(child.name());
                if (wanted) {
                    claimed.add(child);
                }
            }
            if (claimed.isEmpty()) {
                continue;
            }
            Set<JavaOutline.Member> shown = new LinkedHashSet<>(claimed);
            for (JavaOutline.Member child : type.children()) {
                if (LIFECYCLE.matcher(child.header() + " " + child.text()).find()) {
                    shown.add(child);
                }
            }
            boolean grew = true;
            while (grew) {
                grew = false;
                Set<String> used = new LinkedHashSet<>();
                for (JavaOutline.Member member : shown) {
                    identifiersIn(JavaOutline.withoutComments(member.text()), used);
                }
                for (JavaOutline.Member child : type.children()) {
                    if (!shown.contains(child) && child.kind() != JavaOutline.Kind.INITIALIZER
                            && used.contains(child.name()) && shown.add(child)) {
                        grew = true;
                    }
                }
            }
            if (out.length() == 0) {
                out.append("// ").append(address).append(" - the claimed test method(s) and the "
                    + "helpers they use, as committed for this run; read-only\n");
                for (String line : outline.imports) {
                    out.append("import ").append(line.replaceAll(";\\s*$", "")).append(";\n");
                }
            }
            out.append(type.header()).append(" {\n");
            for (JavaOutline.Member child : type.children()) {
                if (shown.contains(child)) {
                    out.append("  // :").append(child.startLine()).append('\n')
                        .append(child.text()).append("\n\n");
                }
            }
            out.append("}\n");
        }
        return out.length() == 0 ? "`" + address + "` " + NO_SUCH_METHOD + ".\n" : out.toString();
    }

    /** The simple method name and the simple class name of a test ref such as {@code a.b.C#m}. */
    public static Map.Entry<String, String> classAndMethod(String testRef) {
        String ref = testRef == null ? "" : testRef.strip();
        int hash = ref.indexOf('#');
        String type = hash < 0 ? ref : ref.substring(0, hash);
        String method = hash < 0 ? "" : ref.substring(hash + 1).replaceAll("\\(.*", "").strip();
        return Map.entry(type.substring(type.lastIndexOf('.') + 1), method);
    }

    private static void identifiersIn(String code, Set<String> into) {
        var matcher = Pattern.compile("[A-Za-z_$][\\w$]*").matcher(code);
        while (matcher.find()) {
            into.add(matcher.group());
        }
    }

    /** Claimed method names per file path, in the order given. */
    public static Map<String, Set<String>> methodsByFile(List<String[]> pathAndRef) {
        Map<String, Set<String>> byFile = new LinkedHashMap<>();
        for (String[] one : pathAndRef) {
            byFile.computeIfAbsent(one[0], k -> new LinkedHashSet<>())
                .add(classAndMethod(one[1]).getValue());
        }
        return byFile;
    }
}
