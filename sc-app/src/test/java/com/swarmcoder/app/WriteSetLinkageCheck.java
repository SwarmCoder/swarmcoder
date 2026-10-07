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
package com.swarmcoder.app;

import com.swarmcoder.verify.BuildLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The predicate behind {@code EndToEndLoopTest}'s link 8 (L_WRITESETS: "every task writes only
 * to a source root the build compiles") — extracted so it can be exercised without running the
 * live chain.
 *
 * <p>Since {@code BuildFilesInTheJob} (merged {@code 5e01c3d}), a task that writes a module's
 * sources is ALSO given that module's build file, so the swarm can declare a dependency itself
 * instead of parking on one — see that class's javadoc for the run that made the case. This
 * link's rule predates the capability: it asked every write-set entry to sit under a source root
 * the build compiles, full stop, and harness run 8 on 2026-09-03 produced exactly the intended
 * shape — a module's source root plus that module's {@code pom.xml} — and the link rejected it.
 *
 * <p>Harness run 10 on 2026-09-03 showed that fix was still too narrow: a pre-flight enabler
 * task whose whole job is declaring a dependency in a pom — write set exactly
 * {@code [bookshelf-demo-server/pom.xml]}, nothing else — is exactly the intended shape too, and
 * requiring a SAME-task sibling source entry rejected it. What actually matters is simpler: an
 * entry outside every compiled source root is still an orphan UNLESS it is the build file of a
 * module the build already compiles — i.e. that module's own compiled source root is one this
 * class knows about, regardless of whether this task, or any task, also writes to it. A
 * {@code pom.xml} for a module the build does not know about is still refused: nothing says the
 * task is working on a real module.
 *
 * <p>{@code BuildFilesInTheJob.isBuildFile} in {@code sc-workflow} says the same thing about a
 * path — {@code pom.xml}, {@code build.gradle}, {@code build.gradle.kts} by basename — but that
 * class and its methods are package-private in {@code com.swarmcoder.workflow}, and this harness
 * lives in {@code com.swarmcoder.app}, so it is not reachable from here. The basename check below
 * mirrors it rather than calling it.
 */
final class WriteSetLinkageCheck {

    private WriteSetLinkageCheck() {}

    /**
     * Every entry of {@code writeSet} that is outside a compiled source root and is not the build
     * file of a module whose own compiled source root is also in {@code writeSet}.
     */
    static List<String> orphans(Set<String> writeSet, BuildLayout.Layout layout) {
        List<String> orphans = new ArrayList<>();
        if (writeSet == null) {
            return orphans;
        }
        for (String path : writeSet) {
            if (!isAccepted(path, writeSet, layout)) {
                orphans.add(path);
            }
        }
        return orphans;
    }

    /**
     * True when {@code path} may be written: it sits under a source root the build compiles, or
     * it is the build file of a module the build compiles — {@code writeSet} is not consulted for
     * that second case, since a pom is accepted on the strength of the module it belongs to, not
     * on what else its task happens to write.
     */
    static boolean isAccepted(String path, Set<String> writeSet, BuildLayout.Layout layout) {
        if (underACompiledRoot(path, layout)) {
            return true;
        }
        if (!isBuildFile(path)) {
            return false;
        }
        return moduleOf(path, layout) != null;
    }

    private static boolean underACompiledRoot(String path, BuildLayout.Layout layout) {
        String normalised = normalise(path);
        return layout.sourceRoots().stream()
            .anyMatch(root -> normalised.startsWith(normalise(root)));
    }

    /** True for a path whose file name is a build file of any toolchain this harness knows. */
    static boolean isBuildFile(String path) {
        if (path == null) {
            return false;
        }
        String name = normalise(path);
        int slash = name.lastIndexOf('/');
        name = slash < 0 ? name : name.substring(slash + 1);
        return "pom.xml".equals(name) || "build.gradle".equals(name)
            || "build.gradle.kts".equals(name);
    }

    /** The compiling module {@code path} falls under — longest match wins — or null for none. */
    static String moduleOf(String path, BuildLayout.Layout layout) {
        String normalised = normalise(path);
        String best = null;
        for (String module : layout.compilingModules()) {
            if (module == null) {
                continue;
            }
            boolean covers = module.isEmpty()
                || normalised.equals(module) || normalised.startsWith(module + "/");
            if (covers && (best == null || module.length() > best.length())) {
                best = module;
            }
        }
        return best;
    }

    private static String normalise(String path) {
        String cleaned = path.replace('\\', '/').strip();
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }
}
