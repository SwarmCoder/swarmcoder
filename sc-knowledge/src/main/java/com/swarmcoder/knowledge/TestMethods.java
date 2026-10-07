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
 * The test methods of a Java test source, read from its syntax tree ({@link JavaOutline}), not from
 * its text. Used to keep an earlier story's acceptance test whole when a later story's test lands
 * in the same file (DEVELOPER_CORRECTIONS section 59), and to tell this story's new test methods
 * from the ones already there.
 *
 * <p>A test method is a method carrying {@code @Test}, {@code @ParameterizedTest},
 * {@code @RepeatedTest}, {@code @TestFactory} or {@code @TestTemplate}. Two sources have the same
 * method when its type path, name, parameter list and code (comments dropped, whitespace
 * collapsed) are equal.
 */
public final class TestMethods {

    private static final Pattern TEST_ANNOTATION = Pattern.compile(
        "(?:^|\\s)@(?:[\\w.]+\\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    private TestMethods() {}

    /** Address of each test method ({@code Outer.Inner#name(params)}) to its normalized code. */
    public static Map<String, String> of(String source) {
        Map<String, String> methods = new LinkedHashMap<>();
        JavaOutline outline = JavaOutline.of(source);
        for (JavaOutline.Member type : outline.types) {
            collect(type, type.name(), methods);
        }
        return methods;
    }

    private static void collect(JavaOutline.Member type, String path, Map<String, String> out) {
        for (JavaOutline.Member member : type.children()) {
            if (member.kind() == JavaOutline.Kind.TYPE) {
                collect(member, path + "." + member.name(), out);
            } else if (member.kind() == JavaOutline.Kind.METHOD
                    && TEST_ANNOTATION.matcher(member.header()).find()) {
                out.put(path + "#" + member.name() + parameters(member.header()),
                    JavaOutline.withoutComments(member.text()).replaceAll("\\s+", " ").strip());
            }
        }
    }

    private static String parameters(String header) {
        int open = header.indexOf('(');
        int close = header.lastIndexOf(')');
        return open < 0 || close < open ? "()" : header.substring(open, close + 1);
    }

    /**
     * What {@code revised} would take from {@code existing}: every test method of the old source
     * that the new one does not carry unchanged, one line each. Empty when every old test method
     * is still there as it was (methods added are not mentioned).
     */
    public static List<String> lostBy(String existing, String revised) {
        Map<String, String> before = of(existing);
        Map<String, String> after = of(revised);
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, String> method : before.entrySet()) {
            String now = after.get(method.getKey());
            if (now == null) {
                lost.add("removed: " + method.getKey());
            } else if (!now.equals(method.getValue())) {
                lost.add("changed: " + method.getKey());
            }
        }
        return lost;
    }

    /**
     * Surefire-style ids ({@code pkg.Outer$Inner#name}) of the test methods in {@code source},
     * without parameters. A reported id of a parameterized test continues the name with
     * {@code (} or {@code [}.
     */
    public static Set<String> ids(String source) {
        JavaOutline outline = JavaOutline.of(source);
        String pkg = outline.packageName == null || outline.packageName.isBlank() ? ""
            : outline.packageName + ".";
        Set<String> ids = new LinkedHashSet<>();
        for (String address : of(source).keySet()) {
            int hash = address.indexOf('#');
            String type = address.substring(0, hash).replace('.', '$');
            String name = address.substring(hash + 1);
            ids.add(pkg + type + "#" + name.substring(0, name.indexOf('(')));
        }
        return ids;
    }
}
