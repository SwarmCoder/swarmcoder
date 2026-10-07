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
package com.swarmcoder.swarm;

import com.swarmcoder.knowledge.JavaSourceFacts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A story does not take away public code that existed before it, unless a check the operator
 * agreed says something is to be removed.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 75, 2026-10-03. The story's one check was "the logbook offers no import/export, no
 * uploads, no award counts, no contact transcript/debrief, and no audio listening". The start
 * tree's shared data type had three fields for the later transcript feature, with their getters
 * and setters, and the edit screen and two commands used them. The architect read "offers no
 * transcript" as "delete the fields", the plan made a task of it, and the delivery removed 47
 * lines of working code from the model, two commands and a screen, so that a test asserting the
 * members are absent would pass. Every check passed: nothing in the product treats a deletion
 * differently from an addition.
 *
 * <h2>The decision</h2>
 *
 * <p>Removing existing public code is the one kind of change a later story cannot simply build
 * on: whatever used it is gone or rewritten with it. Whether a check of the form "X is not
 * offered" means "do not add X" or "delete what is there" is a reading, and a reading that
 * destroys is not a model's to make alone. So the product reads only what the operator agreed:
 *
 * <ul>
 *   <li>a candidate that removes a public or protected member, or a public type, from a main
 *       source file that existed when the run started does not pass verification;</li>
 *   <li>unless one of the story's agreed checks says, in so many words, that something is to be
 *       removed ({@link #asksForRemoval}). Then removals are the story's business and nothing is
 *       checked here.</li>
 * </ul>
 *
 * <p>The verdict names the members and the way out: keep them and meet the check without removing
 * anything, or say in the report that the check cannot be met while they exist, which is a
 * question for the operator, who can agree a check that says what goes.
 *
 * <h2>What is and is not read</h2>
 *
 * <p>Compared by name, from source text, with the project's own outline scanner; no compiler. A
 * member whose signature changed keeps its name and is not a removal. A type that the same change
 * declares in another file was moved, not removed. Test trees are not read. Code this run itself
 * added (an earlier wave's) is not "existing": the comparison is with the run's start commit. A
 * file that cannot be read establishes nothing. A run with no story, or a story with no agreed
 * check, is not checked, because there is nothing agreed to read. The words that count as asking
 * for a removal are English; a check in another language that asks for one fails closed, and the
 * operator switch below is the way out.
 *
 * <p>{@code -Dswarmcoder.verify.removedExistingApi=off} switches the check off.
 */
final class RemovedExistingApi {

    static final String SWITCH = "swarmcoder.verify.removedExistingApi";

    private static final int MAX_NAMED = 12;

    private static final Pattern ASKS_FOR_REMOVAL = Pattern.compile(
        "\\b(remov\\w*|delet\\w*|drop(?:s|ped|ping)?|retir\\w*|eliminat\\w*|discard\\w*"
            + "|strip(?:s|ped|ping)?|no\\s+longer|get(?:s|ting)?\\s+rid\\s+of|taken?\\s+out"
            + "|takes\\s+out|taken\\s+away)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PRE_IMAGE = Pattern.compile("(?m)^--- a/(\\S.*?)\\s*$");
    private static final Pattern POST_IMAGE = Pattern.compile("(?m)^\\+\\+\\+ b/(\\S.*?)\\s*$");

    private RemovedExistingApi() {
    }

    static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on").strip());
    }

    /** Whether one of the agreed checks says something is to be removed. */
    static boolean asksForRemoval(Collection<String> agreedChecks) {
        if (agreedChecks == null) {
            return false;
        }
        return agreedChecks.stream()
            .anyMatch(check -> check != null && ASKS_FOR_REMOVAL.matcher(check).find());
    }

    /**
     * The public types and members the change removes from main source files that existed at the
     * run's start, as {@code Type} or {@code Type.member}, in the order found.
     *
     * @param diffUnified the candidate's change
     * @param atRunStart  a repo-relative path to that file's text at the run's start commit;
     *                    null when it did not exist then
     * @param now         a repo-relative path to that file's text in the candidate's tree; null
     *                    when it is gone
     */
    static List<String> removed(String diffUnified, Function<String, String> atRunStart,
                                Function<String, String> now) {
        if (diffUnified == null || diffUnified.isBlank()) {
            return List.of();
        }
        Set<String> before = paths(PRE_IMAGE, diffUnified);
        Set<String> after = paths(POST_IMAGE, diffUnified);
        Set<String> removed = new LinkedHashSet<>();
        for (String path : before) {
            if (!isMainJava(path)) {
                continue;
            }
            String was = atRunStart.apply(path);
            if (was == null || was.isBlank()) {
                continue; // not there when the run started: this run's own code
            }
            JavaSourceFacts old;
            JavaSourceFacts current;
            String is = now.apply(path);
            try {
                old = JavaSourceFacts.of(was);
                current = is == null || is.isBlank() ? null : JavaSourceFacts.of(is);
            } catch (RuntimeException unreadable) {
                continue;
            }
            for (String type : old.declaredTypes()) {
                if (current == null || !current.declares(type)) {
                    if (declaredPublic(was, type) && !declaredElsewhere(type, path, after, now)) {
                        removed.add(type);
                    }
                    continue;
                }
                if (!old.bodyWasRead(type) || !current.bodyWasRead(type)) {
                    continue;
                }
                Set<String> still = current.exposedMembers(type).stream()
                    .map(JavaSourceFacts.Exposed::name).collect(Collectors.toSet());
                for (JavaSourceFacts.Exposed member : old.exposedMembers(type)) {
                    if (!still.contains(member.name())) {
                        removed.add(type + "." + member.name());
                    }
                }
            }
        }
        return List.copyOf(removed);
    }

    /** The verdict sentence, or null when nothing was removed. */
    static String objection(List<String> removed, Collection<String> agreedChecks) {
        if (removed == null || removed.isEmpty()) {
            return null;
        }
        StringBuilder named = new StringBuilder(String.join(", ",
            removed.stream().limit(MAX_NAMED).toList()));
        if (removed.size() > MAX_NAMED) {
            named.append(" and ").append(removed.size() - MAX_NAMED).append(" more");
        }
        return "the candidate removes public code that existed before this story started: " + named
            + ". None of the story's agreed checks says anything is to be removed ("
            + String.join(" | ", agreedChecks) + "), and a story takes existing public members "
            + "away only when a check says so: a check that something is NOT offered is met by "
            + "not offering it, never by deleting code other work depends on. Put "
            + (removed.size() == 1 ? "it" : "them") + " back and make the task's change without "
            + "removing anything; if the task cannot be done while "
            + (removed.size() == 1 ? "it exists" : "they exist") + ", say so in your report - "
            + "that is a question for the operator, not a change to make";
    }

    private static Set<String> paths(Pattern header, String diff) {
        Set<String> paths = new LinkedHashSet<>();
        var matcher = header.matcher(diff);
        while (matcher.find()) {
            paths.add(matcher.group(1).strip().replace('\\', '/'));
        }
        return paths;
    }

    private static boolean isMainJava(String path) {
        String p = "/" + path;
        return path.endsWith(".java") && !p.contains("/src/test/") && !p.contains("/test/java/")
            && !path.endsWith("package-info.java") && !path.endsWith("module-info.java");
    }

    private static boolean declaredPublic(String source, String simpleName) {
        return Pattern.compile("\\bpublic\\s+(?:[a-z-]+\\s+)*(?:class|interface|enum|record|"
            + "@interface)\\s+" + Pattern.quote(simpleName) + "\\b").matcher(source).find();
    }

    /** The same change declares the type in another file: moved, not removed. */
    private static boolean declaredElsewhere(String type, String ownPath, Set<String> after,
                                             Function<String, String> now) {
        for (String other : after) {
            if (other.equals(ownPath) || !other.endsWith(".java")) {
                continue;
            }
            String text = now.apply(other);
            try {
                if (text != null && JavaSourceFacts.of(text).declares(type)) {
                    return true;
                }
            } catch (RuntimeException unreadable) {
                // establishes nothing
            }
        }
        return false;
    }
}
