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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reviewer's objection that an ALREADY-EXISTING type is in the wrong package is not something
 * this story's design can fix, so the architect is not asked to revise over it.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 74, 2026-10-03. Design review objected that the service implementation and a
 * screen - both written by an earlier story and sitting on the start tree - were in the package
 * root when a layout rule wants them in sub-packages. The design could only name them where they
 * are. Two architect revisions were spent on it (21 minutes), the objection came back both times,
 * and it was then carried as a warning, which is all it could ever have been.
 *
 * <h2>What is recognised, mechanically</h2>
 *
 * <p>An objection is about where existing code lives when:
 *
 * <ul>
 *   <li>its subject - the quoted text before {@code conflicts with rule} - names a type the start
 *       tree already declares, at the package the objection gives for it (its full name, or its
 *       simple name with that package elsewhere in the text), and names no type the tree does
 *       not have; and</li>
 *   <li>the objection also names a different package, one that type is not in - the place the
 *       reviewer says it should be.</li>
 * </ul>
 *
 * <p>Anything else stays an ordinary objection: a new type in the wrong place can be moved by the
 * design, and an existing type objected to for another reason is not this. Reads only the
 * objection text and the project's own type list; project-agnostic.
 */
final class WhereExistingCodeLives {

    /** What the carried warning is filed under. */
    static final String CHECK = "where code written before this story lives, against the "
        + "project's rules (reviewer model) - this story's design cannot move it";

    private static final Pattern SUBJECT =
        Pattern.compile("^[^']*'([^']*)'\\s+conflicts with rule");
    private static final Pattern QUALIFIED_TYPE = Pattern.compile(
        "(?<![A-Za-z0-9_$.])((?:[a-z_][a-z0-9_]*\\.)+)([A-Z][A-Za-z0-9_$]*)");
    private static final Pattern PACKAGE = Pattern.compile(
        "(?<![A-Za-z0-9_$.])[a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)+(?![A-Za-z0-9_$]|\\.[A-Za-z_])");
    private static final Pattern SIMPLE_TYPE = Pattern.compile(
        "(?<![A-Za-z0-9_$.])[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+(?![A-Za-z0-9_$])");

    private WhereExistingCodeLives() {
    }

    /**
     * @param forTheArchitect objections the design can answer, in their order
     * @param carried         objections about where existing code lives, in their order
     */
    record Split(List<String> forTheArchitect, List<String> carried) {
    }

    static Split split(List<String> objections, ExistingProjectTypes existing) {
        List<String> open = new ArrayList<>();
        List<String> carried = new ArrayList<>();
        for (String objection : objections == null ? List.<String>of() : objections) {
            if (isAbout(objection, existing)) {
                carried.add(objection);
            } else {
                open.add(objection);
            }
        }
        return new Split(List.copyOf(open), List.copyOf(carried));
    }

    static boolean isAbout(String objection, ExistingProjectTypes existing) {
        if (objection == null || existing == null || existing.isEmpty()) {
            return false;
        }
        Matcher subjectMatch = SUBJECT.matcher(objection);
        if (!subjectMatch.find()) {
            return false;
        }
        String subject = subjectMatch.group(1);
        Set<String> packagesNamed = new LinkedHashSet<>();
        Matcher pkg = PACKAGE.matcher(objection);
        while (pkg.find()) {
            packagesNamed.add(pkg.group());
        }
        Matcher qualified = QUALIFIED_TYPE.matcher(objection);
        while (qualified.find()) {
            String p = qualified.group(1);
            packagesNamed.add(p.substring(0, p.length() - 1));
        }

        // The types the subject names, each of which must already be there, where it says.
        List<ExistingProjectTypes.Existing> subjects = new ArrayList<>();
        String rest = subject;
        Matcher full = QUALIFIED_TYPE.matcher(subject);
        while (full.find()) {
            String fullName = full.group(1) + full.group(2);
            ExistingProjectTypes.Existing found = existing.named(full.group(2)).stream()
                .filter(t -> t.fullName().equals(fullName)).findFirst().orElse(null);
            if (found == null) {
                return false; // a type the tree does not have at that place: the design's to fix
            }
            subjects.add(found);
            rest = rest.replace(fullName, " ");
        }
        // Where the subject itself says the type is; only when it names no package at all is the
        // rest of the sentence read for one.
        Set<String> placedIn = new LinkedHashSet<>();
        Matcher subjectPackage = PACKAGE.matcher(subject);
        while (subjectPackage.find()) {
            placedIn.add(subjectPackage.group());
        }
        for (ExistingProjectTypes.Existing type : subjects) {
            placedIn.add(type.packageName());
        }
        Set<String> where = placedIn.isEmpty() ? packagesNamed : placedIn;
        Matcher simple = SIMPLE_TYPE.matcher(rest);
        while (simple.find()) {
            List<ExistingProjectTypes.Existing> named = existing.named(simple.group());
            ExistingProjectTypes.Existing found = named.stream()
                .filter(t -> where.contains(t.packageName())).findFirst().orElse(null);
            if (found == null) {
                return false; // a new type, or an existing one somewhere the objection never says
            }
            if (!subjects.contains(found)) {
                subjects.add(found);
            }
        }
        if (subjects.isEmpty()) {
            return false;
        }
        // And it must say where else the type should be: a package none of them is in.
        Set<String> theirPackages = new LinkedHashSet<>();
        for (ExistingProjectTypes.Existing type : subjects) {
            theirPackages.add(type.packageName());
        }
        for (String named : packagesNamed) {
            if (theirPackages.contains(named)) {
                continue;
            }
            boolean aParentOfOne = theirPackages.stream().anyMatch(p -> p.startsWith(named + "."));
            if (!aParentOfOne) {
                return true;
            }
        }
        return false;
    }
}
