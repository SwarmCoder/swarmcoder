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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether the code running a resumed walk differs from the code that saved its snapshot, in the
 * files that decide what the snapshot holds.
 *
 * <h2>Why a warning and not a refusal (2026-09-25)</h2>
 *
 * <p>A snapshot holds what the front half of the chain produced — requirements, stories, the
 * design, the plan, the acceptance tests — and a resumed walk measures only the workers against
 * it. That is the whole point: the workers are what is being changed between runs, so a snapshot
 * must survive commits to the worker side. But a change to the planner, the test author, the
 * intake wizard or the stored shapes means today's product would NOT have produced that plan or
 * those tests, and a green resumed walk then says nothing about the front half as it now is. That
 * is worth shouting about, and not worth refusing over: the operator may be deliberately holding
 * the plan still while changing something the list below counts as front half.
 *
 * <h2>The list is generous on purpose</h2>
 *
 * <p>{@link #FRONT_HALF} names whole source trees rather than guessing which classes each link
 * touches, because a missed file would make the warning lie by omission. {@code sc-workflow} is in
 * it although it also drives EXECUTING: design, review, planning and test authoring all live there.
 * The warning names every file it counted, so a reader can see at once whether a hit is really
 * about the front half. The worker side — {@code sc-swarm}, {@code sc-runtime}, {@code sc-sandbox},
 * {@code sc-git}, {@code sc-lsp}, {@code sc-syntax} — is not in it.
 */
final class SnapshotStaleness {

    /** Path prefixes, repository-relative with forward slashes, that shape links 1 to 9. */
    static final List<String> FRONT_HALF = List.of(
        // intake and planning wizards, the requirements service, the backlog, document ingest
        "sc-console/src/main/",
        // design, design review, plan, test authoring and the red check (and EXECUTING's driver)
        "sc-workflow/src/main/",
        // project rules, the guideline import, the knowledge briefs attached at planning
        "sc-knowledge/src/main/",
        // build layout, where acceptance tests live, the red check's test runs
        "sc-verify/src/main/",
        // what the snapshot's store holds, and how it is read back
        "sc-domain/src/main/",
        "sc-store/src/main/",
        // how every role's request is made
        "sc-inference/src/main/",
        // the harness's own front half
        "sc-app/src/test/java/com/swarmcoder/app/EndToEndLoopTest.java",
        "sc-app/src/test/java/com/swarmcoder/app/BookshelfFixture.java",
        "sc-app/src/test/java/com/swarmcoder/app/HarnessReferenceRoot.java",
        "sc-app/src/test/java/com/swarmcoder/app/PlanTaskLinkageCheck.java",
        "sc-app/src/test/java/com/swarmcoder/app/WriteSetLinkageCheck.java",
        // the two documents the journey starts from
        "dev/bookshelf-requirements.md",
        "dev/bookshelf-tech-requirements.md");

    private SnapshotStaleness() {
    }

    /**
     * @param comparable     false when the saved commit is unknown to this checkout, so nothing
     *                       could be compared
     * @param currentCommit  HEAD now
     * @param frontHalf      the front-half files that differ, committed or not
     */
    record Finding(boolean comparable, String currentCommit, List<String> frontHalf, String note) {

        /** A loud banner when there is something to say; null when the code is the same. */
        String warning(String savedCommit) {
            if (!comparable) {
                return banner("the snapshot was saved by SwarmCoder " + savedCommit + ", which this "
                    + "checkout does not know (" + note + "). Whether the front half of the chain "
                    + "has changed since cannot be told.");
            }
            if (frontHalf.isEmpty()) {
                return null;
            }
            return banner("the code that decides links 1 to 9 has changed since this snapshot was "
                + "saved (SwarmCoder " + EndToEndLoopTest.shortSha(savedCommit) + " then, "
                + EndToEndLoopTest.shortSha(currentCommit) + " now). Today's product might not "
                + "have produced the plan and tests this walk starts from, and a green result "
                + "says nothing about them. " + frontHalf.size() + " file(s): "
                + String.join(", ", frontHalf) + ". Save a fresh snapshot with -D"
                + HarnessSnapshot.SAVE_PROPERTY + " when that matters.");
        }

        private static String banner(String text) {
            return "\n[E2E] !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n"
                + "[E2E] !!! STALE SNAPSHOT WARNING: " + text + "\n"
                + "[E2E] !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n";
        }
    }

    /** True when a changed file can change what links 1 to 9 produce. */
    static boolean affectsFrontHalf(String path) {
        String normal = path.replace('\\', '/').strip();
        return FRONT_HALF.stream().anyMatch(prefix -> prefix.endsWith("/")
            ? normal.startsWith(prefix) : normal.equals(prefix));
    }

    /**
     * Compares the checkout at {@code swarmcoderRoot} with the commit that saved the snapshot:
     * what was committed since, what is uncommitted now, and what was uncommitted THEN (the code
     * that saved the snapshot was its commit plus those files, so they differ from the commit too).
     */
    static Finding compare(Path swarmcoderRoot, String savedCommit, List<String> savedUncommitted) {
        String current;
        try {
            current = BookshelfFixture.git(swarmcoderRoot, "rev-parse HEAD").strip();
        } catch (Exception e) {
            return new Finding(false, "(unknown)", List.of(), "no git checkout here: "
                + e.getMessage().strip());
        }
        if (savedCommit == null || savedCommit.isBlank()) {
            return new Finding(false, current, List.of(), "the manifest names no commit");
        }
        Set<String> changed = new LinkedHashSet<>();
        try {
            // Not "<sha>^{commit}": every git call here goes through cmd.exe on Windows, and cmd
            // eats a caret.
            if (!"commit".equals(BookshelfFixture.git(swarmcoderRoot,
                    "cat-file -t " + savedCommit).strip())) {
                return new Finding(false, current, List.of(), "it does not name a commit");
            }
            changed.addAll(lines(BookshelfFixture.git(swarmcoderRoot,
                "diff --name-only " + savedCommit + " HEAD")));
        } catch (Exception e) {
            return new Finding(false, current, List.of(), "git cannot resolve it");
        }
        changed.addAll(uncommitted(swarmcoderRoot));
        if (savedUncommitted != null) {
            changed.addAll(savedUncommitted);
        }
        List<String> frontHalf = changed.stream().filter(SnapshotStaleness::affectsFrontHalf)
            .sorted().toList();
        return new Finding(true, current, frontHalf, null);
    }

    /** Files changed and not committed in a checkout, repository-relative. */
    static List<String> uncommitted(Path checkout) {
        List<String> files = new ArrayList<>();
        try {
            for (String line : BookshelfFixture.git(checkout, "status --porcelain").split("\\R")) {
                if (line.length() < 4) {
                    continue;
                }
                String path = line.substring(3).strip();
                int arrow = path.indexOf(" -> ");
                files.add(arrow < 0 ? unquote(path) : unquote(path.substring(arrow + 4)));
            }
        } catch (Exception e) {
            // no checkout, nothing uncommitted to speak of
        }
        return files;
    }

    /** The SwarmCoder checkout this JVM runs from, or the working directory when git says none. */
    static Path swarmcoderRoot() {
        Path here = Path.of("").toAbsolutePath();
        try {
            return Path.of(BookshelfFixture.git(here, "rev-parse --show-toplevel").strip());
        } catch (Exception e) {
            return here;
        }
    }

    private static List<String> lines(String output) {
        return output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static String unquote(String path) {
        return path.length() > 1 && path.startsWith("\"") && path.endsWith("\"")
            ? path.substring(1, path.length() - 1) : path;
    }
}
