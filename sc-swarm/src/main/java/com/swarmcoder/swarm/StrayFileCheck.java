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

import com.swarmcoder.verify.BuildLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a path outside a task's write set is a STRAY file — a helper script, a scratch note, a
 * generated artefact, or anything else a worker left behind that no build in this repository
 * would ever compile, package or read — as opposed to a neighbouring source or resource file the
 * task genuinely needed (see {@code JudgeClient#writeSetLine} for why an out-of-write-set path is
 * not automatically wrong).
 *
 * <p><b>Why this exists</b> (harness run 11, 2026-09-03). A worker wrote a Python script,
 * {@code insert_dep.py}, at the repository root to edit a pom, left it there, and it rode along
 * in the winning diff onto the run's progress branch. The judge scored the candidate 1.00 and
 * called it "minimal, clean" — it was never told the file was there, and nothing would have
 * stopped the merge even if it had objected. This class is the one mechanical fact that closes
 * both gaps: a path is flagged here on geometry alone, the same way {@link JudgeClient} answers
 * "did this diff change anything" in {@code deliveredNothing} — not on whether the file was
 * actually useful, which stays a judgement call for a human or the judge reading the diff.
 *
 * <p>Deliberately narrow and deliberately generous to the candidate. A path under a conventional
 * JVM source or resource directory, or a module's own build file, is never flagged, however far
 * outside the write set it sits — the operator's own run has shown that a neighbouring class is
 * often exactly what a multi-module task needs, and killing that case again in different clothing
 * would be the same defect this was built to remove.
 *
 * <p>The judge has no live checkout to read an actual {@link BuildLayout.Layout} from — the
 * candidate's sandbox is gone by the time a diff reaches it — so this checks a path against the
 * same convention {@link BuildLayout} itself falls back to, string on string, via
 * {@link BuildLayout#conventionalSourceRootSegments()}. {@code WaveIntegrator} and
 * {@code FinalIntegrator} run on a real merged worktree and could read the live layout instead,
 * but they call this too, so a file is judged and stripped by the identical rule wherever it is
 * checked.
 */
public final class StrayFileCheck {

    private StrayFileCheck() {}

    /** The basenames of a build file, by toolchain — never stray, whatever module it names. */
    private static boolean isBuildFile(String normalizedPath) {
        int slash = normalizedPath.lastIndexOf('/');
        String name = slash < 0 ? normalizedPath : normalizedPath.substring(slash + 1);
        return "pom.xml".equals(name) || "build.gradle".equals(name)
            || "build.gradle.kts".equals(name);
    }

    /**
     * True when {@code path} falls under one of the conventional JVM source or resource
     * directories, at the repository root or under any module prefix.
     */
    private static boolean underConventionalSourceRoot(String normalizedPath) {
        for (String convention : BuildLayout.conventionalSourceRootSegments()) {
            if (normalizedPath.equals(convention) || normalizedPath.startsWith(convention + "/")
                    || normalizedPath.contains("/" + convention + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code path} is a stray non-source file: not a build file, and not under any
     * conventional source or resource directory. Null or blank is never stray — there is nothing
     * to strip.
     */
    public static boolean isStray(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = BuildLayout.normalize(path);
        if (normalized == null || normalized.isEmpty()) {
            normalized = path.replace('\\', '/').strip();
        }
        return !isBuildFile(normalized) && !underConventionalSourceRoot(normalized);
    }

    /** Every entry of {@code paths} that {@link #isStray} — order preserved, never null. */
    public static List<String> strayPathsIn(List<String> paths) {
        List<String> stray = new ArrayList<>();
        if (paths == null) {
            return stray;
        }
        for (String path : paths) {
            if (isStray(path)) {
                stray.add(path);
            }
        }
        return stray;
    }
}
