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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.RebuildFrom;
import com.swarmcoder.domain.RebuildFrom.Keep;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a build of a story takes over from the build before it (section 99 of
 * {@code docs/DEVELOPER_CORRECTIONS.md}). Decided once, at INTAKE, from stored facts and the
 * repository; no model is asked.
 *
 * <h2>When is an earlier result still good</h2>
 *
 * <p>A design, a plan and a set of tests are made from two kinds of input, and both must be the
 * same:
 *
 * <ol>
 *   <li><b>What was asked</b>: the story's title and narrative, each requirement and check it
 *       answers for (handle, title, text, the test a check names), the quality gates it
 *       inherits, the project's rules as the roles are told them, and the kind of run. One
 *       fingerprint ({@link #fingerprint}), written on every run at INTAKE. All three results
 *       read all of it, so a difference anywhere makes all three again.</li>
 *   <li><b>The project's tree</b>. The same commit is the same tree. On another commit the
 *       files that differ are listed ({@code git diff --name-only}) and held against what each
 *       result refers to: the <b>design</b> refers to the types its contracts name, so it is
 *       stale when a file named after one of those types differs; the <b>plan</b> refers to
 *       the paths its tasks may write and read, so it is stale when a differing file lies
 *       under one of them; the <b>tests</b>, and every candidate chosen for a task, are
 *       commits cut from the earlier build's start commit and were measured there, so they are
 *       kept only on that same commit. A run that changes existing code (a bug fix, an
 *       enhancement) is told about the code around the change, which no list of names
 *       covers, so on another commit it keeps nothing.</li>
 * </ol>
 *
 * <p>How far the earlier build got, and what a send-back objected to, is {@link RebuildFrom}.
 * The lowest of all the limits is what is kept, and the run's record says which limit it was.
 */
final class EarlierBuild {

    private EarlierBuild() {}

    /** The files that differ between two commits; null when that cannot be read. */
    interface Changes {
        List<String> between(String from, String to);
    }

    /**
     * @param earlier the build taken over from; null when there is none
     * @param keep    what is taken over
     * @param lines   the record: one sentence for each of design, plan and tests, then the tasks
     * @param note    the note of the send-back that came before this build; null when none
     */
    record Decision(Run earlier, Keep keep, List<String> lines, String note) {

        static final Decision FIRST_BUILD = new Decision(null, Keep.NOTHING, List.of(), null);

        boolean keepsDesign() {
            return keep.ordinal() >= Keep.DESIGN.ordinal();
        }

        boolean keepsPlan() {
            return keep.ordinal() >= Keep.PLAN.ordinal();
        }

        boolean keepsTests() {
            return keep == Keep.TESTS_AND_FINISHED_TASKS;
        }
    }

    /** The build of {@code story} that came before {@code run}; null when it is the first. */
    static Run predecessor(java.util.function.Function<UUID, Run> runs, Story story, Run run) {
        if (story == null || story.runIds() == null || run == null) {
            return null;
        }
        Run latest = null;
        for (UUID id : story.runIds()) {
            Run other = id == null || id.equals(run.id()) ? null : runs.apply(id);
            if (other == null || other.startedAt() == null) {
                continue;
            }
            if (run.startedAt() != null && other.startedAt().isAfter(run.startedAt())) {
                continue;
            }
            if (latest == null || other.startedAt().isAfter(latest.startedAt())) {
                latest = other;
            }
        }
        return latest;
    }

    /**
     * The fingerprint of what was asked. Every field is written with its length in front, so
     * no two different sets of inputs can render to the same text.
     */
    static String fingerprint(Run run, StoryScope scope) {
        StringBuilder text = new StringBuilder();
        add(text, run.kind() == null ? "" : run.kind().name());
        add(text, run.report() == null ? "" : run.report().summary());
        Story story = scope == null ? null : scope.story();
        add(text, story == null ? "" : story.title());
        add(text, story == null ? "" : story.narrative());
        if (scope != null) {
            for (BrdRequirement requirement : scope.requirements()) {
                add(text, "requirement");
                add(text, String.valueOf(requirement.id()));
                add(text, requirement.handle());
                add(text, requirement.title());
                add(text, requirement.text());
            }
            for (int i = 0; i < scope.criteria().size(); i++) {
                AcceptanceCriterion criterion = scope.criteria().get(i);
                add(text, "check");
                add(text, String.valueOf(criterion.id()));
                add(text, i < scope.criterionRefs().size() ? scope.criterionRefs().get(i) : "");
                add(text, criterion.text());
                add(text, criterion.testClassOrFile());
            }
            for (BrdRequirement gate : scope.gatingNfrs()) {
                add(text, "gate");
                add(text, String.valueOf(gate.id()));
                add(text, gate.title());
                add(text, gate.text());
            }
            add(text, "rules");
            add(text, scope.constraintBrief());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void add(StringBuilder text, String field) {
        String value = field == null ? "" : field;
        text.append(value.length()).append(':').append(value).append('\n');
    }

    /**
     * @param run             the build that is starting, its fingerprint and base commit set
     * @param story           its story
     * @param earlier         the build before it; null for a first build
     * @param design          the earlier build's design; null when it has none
     * @param graph           the earlier build's plan; null when it has none
     * @param earlierIsGoing  true while a thread of this process is still in the earlier build
     * @param changesExisting true for a run that changes existing code (bug fix, enhancement)
     * @param changes         the repository
     */
    static Decision decide(Run run, Story story, Run earlier, DesignDocument design,
                           TaskGraph graph, boolean earlierIsGoing, boolean changesExisting,
                           Changes changes) {
        if (earlier == null) {
            return Decision.FIRST_BUILD;
        }
        RunState endedAt = earlier.lastStage();
        // A send-back counts for the build that follows the one it was sent back after.
        boolean sentBack = story != null && story.sentBackAfterRunId() != null
            && story.sentBackAfterRunId().equals(earlier.id());
        String note = sentBack ? story.sentBackNote() : null;

        // How far the earlier build got, whatever anybody thinks of it.
        Keep reached = RebuildFrom.afterAnInterruption(endedAt);
        if (earlier.designId() == null || design == null) {
            reached = Keep.NOTHING;
        } else if (earlier.taskGraphId() == null || graph == null) {
            reached = lower(reached, Keep.DESIGN);
        }
        Keep keep = reached;
        String why = null;

        if (sentBack) {
            Keep allowed = RebuildFrom.afterASendBack(story.sentBackFrom(), endedAt,
                story.sentBackRedoFrom());
            if (allowed.ordinal() < keep.ordinal()) {
                keep = allowed;
                why = story.sentBackRedoFrom() != null
                    ? "the story was sent back and the sender named this as what to do again"
                    : story.sentBackFrom() == com.swarmcoder.domain.StoryState.REVIEW
                        ? "the story's delivery was refused and no stage was named, so nothing "
                            + "that made the refused result is kept"
                        : "the story was sent back after its build ended at " + endedAt
                            + ", and that stage is done again";
            }
        }

        if (keep != Keep.NOTHING && (earlier.inputsFingerprint() == null
                || !earlier.inputsFingerprint().equals(run.inputsFingerprint()))) {
            keep = Keep.NOTHING;
            why = earlier.inputsFingerprint() == null
                ? "the earlier build did not record what it was made from"
                : "the story's requirements, its checks, the quality gates it inherits or the "
                    + "project's rules are not what the earlier build was made from";
        }

        if (keep != Keep.NOTHING) {
            String from = earlier.baseCommit();
            String to = run.baseCommit();
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                keep = Keep.NOTHING;
                why = "the commit one of the two builds started from is not on record, so the "
                    + "project's tree could not be compared";
            } else if (!from.equals(to)) {
                List<String> differ = changesExisting ? null : changes.between(from, to);
                if (changesExisting) {
                    keep = Keep.NOTHING;
                    why = "the project has moved from " + shortSha(from) + " to " + shortSha(to)
                        + ", and a run that changes existing code is told about the code around "
                        + "the change";
                } else if (differ == null) {
                    keep = Keep.NOTHING;
                    why = "the project has moved from " + shortSha(from) + " to " + shortSha(to)
                        + " and what differs could not be read";
                } else {
                    String designFile = firstNamedBy(design, differ);
                    String planFile = graph == null ? null : firstUnder(graph, differ);
                    if (designFile != null) {
                        keep = Keep.NOTHING;
                        why = designFile + ", which declares a type the design names, differs "
                            + "between " + shortSha(from) + " and " + shortSha(to);
                    } else if (planFile != null && keep.ordinal() > Keep.DESIGN.ordinal()) {
                        keep = Keep.DESIGN;
                        why = planFile + ", which a task of the plan may write or read, differs "
                            + "between " + shortSha(from) + " and " + shortSha(to);
                    } else if (keep == Keep.TESTS_AND_FINISHED_TASKS) {
                        keep = Keep.PLAN;
                        why = "the tests and the chosen candidates were cut from "
                            + shortSha(from) + " and measured there, and this build starts from "
                            + shortSha(to);
                    }
                }
            }
        }

        if (keep.ordinal() > Keep.DESIGN.ordinal() && earlierIsGoing) {
            keep = Keep.DESIGN;
            why = "the earlier build has not stopped yet and is still writing to its plan and "
                + "its tasks";
        }

        // A plan whose tests are written again cannot keep a task that was judged by the old
        // tests, and a task keeps its chosen candidate for as long as the plan is its plan.
        if (keep == Keep.PLAN && graph != null) {
            Task judged = graph.tasks().stream().filter(EarlierBuild::wasJudged).findFirst()
                .orElse(null);
            if (judged != null) {
                keep = Keep.DESIGN;
                why = (why == null ? "the tests are written again" : why) + "; and task '"
                    + judged.title() + "' of the earlier plan already has a result that was "
                    + "judged by the earlier tests, which a kept plan would carry over";
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("An earlier build of " + (story == null ? "this story" : story.key()) + " ("
            + shortSha(String.valueOf(earlier.id())) + ") ended at "
            + (endedAt == null ? "a stage that was not recorded" : endedAt) + "."
            + (sentBack ? " The story was then sent back"
                + (note == null || note.isBlank() ? "." : " with the note: " + note) : ""));
        lines.add(line("Design", Keep.DESIGN, keep, reached, endedAt, why));
        lines.add(line("Plan", Keep.PLAN, keep, reached, endedAt, why));
        lines.add(line("Tests", Keep.TESTS_AND_FINISHED_TASKS, keep, reached, endedAt, why));
        if (keep == Keep.TESTS_AND_FINISHED_TASKS && graph != null) {
            List<String> done = graph.tasks().stream().filter(EarlierBuild::wasJudged)
                .map(task -> "'" + task.title() + "'").toList();
            lines.add("Tasks: " + done.size() + " of " + graph.tasks().size() + " already "
                + "have a result from the earlier build and are not built again"
                + (done.isEmpty() ? "." : ": " + String.join(", ", done) + ".")
                + (done.size() < graph.tasks().size() ? " The others are built." : ""));
        } else {
            lines.add("Tasks: all are built by this build.");
        }
        return new Decision(earlier, keep, List.copyOf(lines), note);
    }

    private static String line(String what, Keep level, Keep keep, Keep reached,
                               RunState endedAt, String why) {
        if (keep.ordinal() >= level.ordinal()) {
            return what + ": reused from the earlier build. What it was made from is unchanged.";
        }
        if (reached.ordinal() < level.ordinal()) {
            return what + ": made by this build. The earlier build "
                + (endedAt == null ? "did not record how far it got"
                    : "ended at " + endedAt + " and had not finished it") + ".";
        }
        return what + ": made again, because " + why + ".";
    }

    private static Keep lower(Keep a, Keep b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    /** True for a task that has a chosen candidate or was measured as needing no change. */
    static boolean wasJudged(Task task) {
        return task.selectedCandidateId() != null || task.needsNoChange()
            || task.checksAlreadyProved() != null
            || task.state() == TaskState.SELECTED || task.state() == TaskState.INTEGRATED
            || task.state() == TaskState.DONE;
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** The first differing file named after a type the design's contracts name; else null. */
    static String firstNamedBy(DesignDocument design, List<String> differ) {
        if (design == null || design.contracts() == null) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (ApiContract contract : design.contracts()) {
            identifiers(contract.name(), names);
            identifiers(contract.typeName(), names);
            identifiers(contract.signatureSketch(), names);
            if (contract.members() != null) {
                contract.members().forEach(member -> identifiers(member, names));
            }
        }
        for (String path : differ) {
            String file = path.substring(path.lastIndexOf('/') + 1);
            int dot = file.indexOf('.');
            String stem = dot <= 0 ? file : file.substring(0, dot);
            if (names.contains(stem)) {
                return path;
            }
        }
        return null;
    }

    private static void identifiers(String text, Set<String> into) {
        if (text == null) {
            return;
        }
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group());
        }
    }

    /** The first differing file under a path a task of the plan may write or read; else null. */
    static String firstUnder(TaskGraph graph, List<String> differ) {
        Set<String> entries = new HashSet<>();
        for (Task task : graph.tasks()) {
            if (task.writeSet() != null) {
                task.writeSet().forEach(entry -> entries.add(normalize(entry)));
            }
            if (task.readSet() != null) {
                task.readSet().forEach(entry -> entries.add(normalize(entry)));
            }
        }
        entries.remove("");
        for (String path : differ) {
            for (String entry : entries) {
                if (path.equals(entry) || path.startsWith(entry + "/")) {
                    return path;
                }
            }
        }
        return null;
    }

    private static String normalize(String path) {
        String p = Objects.requireNonNullElse(path, "").strip().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
    }
}
