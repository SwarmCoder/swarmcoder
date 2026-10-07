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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a task changes what a person sees in a browser - decided from the build and the paths
 * the task may write, with no model and no reading of what the story says (section 63).
 *
 * <p>A task has a screen when it may write
 *
 * <ol>
 *   <li>shipped code of a module whose own build file says it runs only in a browser
 *       ({@link BrowserOnlyCode#survey}); or</li>
 *   <li>a file a browser loads - a page, a stylesheet, a script - in a project whose
 *       verification contract starts an application ({@code browser.serve}).</li>
 * </ol>
 *
 * <p>A build file and a test file are never a screen: every task is given its module's build
 * file, and a test is not shipped.
 *
 * <p><b>What this cannot see.</b> A screen drawn by code that runs on the server (a server-side
 * UI framework, a page built as a string in a servlet), and a screen drawn in the browser from
 * what the server returns (live run 89): its module is an ordinary JVM module and its files are
 * ordinary source files, so nothing here tells it from a service. The write set alone therefore
 * no longer decides whether a story gets a journey: see {@code JourneysOfAPlan} (section 64).
 */
public final class ScreenChange {

    /** What a browser loads, by the file types the web itself defines and their compiled-to forms. */
    private static final Set<String> PAGE_FILE_TYPES =
        Set.of("html", "htm", "css", "js", "mjs", "jsx", "ts", "tsx");

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle",
        "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "package.json",
        "package-lock.json");

    private ScreenChange() {}

    /**
     * The paths of {@code writeSet} that are a screen, as the write set spells them; empty when
     * the task changes none.
     *
     * @param survey the build's browser-only modules; null is none
     * @param served true when the project's contract starts an application for a browser
     */
    public static List<String> screenPaths(Collection<String> writeSet,
                                           BrowserOnlyCode.Survey survey, boolean served) {
        List<String> found = new ArrayList<>();
        if (writeSet == null) {
            return found;
        }
        for (String entry : writeSet) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String path = entry.replace('\\', '/').strip();
            while (path.startsWith("./")) {
                path = path.substring(2);
            }
            if (isBuildFile(path) || isTestPath(path)) {
                continue;
            }
            if (inBrowserOnlyModule(path, survey) || (served && isPageFile(path))) {
                found.add(entry);
            }
        }
        return found;
    }

    /** The browser-only module directories {@code writeSet} reaches into, for a sentence. */
    public static List<String> browserOnlyModulesOf(Collection<String> writeSet,
                                                    BrowserOnlyCode.Survey survey) {
        List<String> modules = new ArrayList<>();
        if (survey == null) {
            return modules;
        }
        for (String path : screenPaths(writeSet, survey, false)) {
            String module = moduleOf(path.replace('\\', '/').strip(), survey);
            if (module != null && !modules.contains(module)) {
                modules.add(module);
            }
        }
        return modules;
    }

    /** True when {@code path} is inside a module the build survey says runs only in a browser. */
    public static boolean inBrowserOnlyModule(String path, BrowserOnlyCode.Survey survey) {
        return path != null && survey != null
            && moduleOf(path.replace('\\', '/').strip(), survey) != null;
    }

    /**
     * The paths of {@code writeSet} that are shipped code or content: every entry but build
     * files and paths under a test folder, as the write set spells them.
     */
    public static List<String> shippedPaths(Collection<String> writeSet) {
        List<String> shipped = new ArrayList<>();
        if (writeSet == null) {
            return shipped;
        }
        for (String entry : writeSet) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String path = entry.replace('\\', '/').strip();
            if (!isBuildFile(path) && !isTestPath(path)) {
                shipped.add(entry);
            }
        }
        return shipped;
    }

    /** The browser-only module holding {@code path}: the longest module directory above it. */
    private static String moduleOf(String path, BrowserOnlyCode.Survey survey) {
        String best = null;
        for (String dir : survey.browserOnlyDirs()) {
            String module = dir == null ? "" : dir.replace('\\', '/');
            boolean inside = module.isEmpty() || path.equals(module) || path.startsWith(module + "/");
            if (inside && (best == null || module.length() > best.length())) {
                best = module;
            }
        }
        if (best == null) {
            return null;
        }
        // A module nested in a browser-only root module, and not itself browser-only, is a JVM
        // module: the longest directory above the path decides, among ALL modules.
        for (String jvm : survey.jvmModules()) {
            String module = jvm == null ? "" : jvm.replace('\\', '/');
            if (module.length() > best.length()
                    && (path.equals(module) || path.startsWith(module + "/"))) {
                return null;
            }
        }
        return best;
    }

    private static boolean isPageFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0
            && PAGE_FILE_TYPES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isBuildFile(String path) {
        return BUILD_FILES.contains(path.substring(path.lastIndexOf('/') + 1));
    }

    /** A path under a folder that holds tests, by the names build tools give such folders. */
    private static boolean isTestPath(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals("test") || segment.equals("tests") || segment.equals("it")
                    || segment.equals("__tests__") || segment.equals("e2e")) {
                return true;
            }
        }
        return false;
    }
}
