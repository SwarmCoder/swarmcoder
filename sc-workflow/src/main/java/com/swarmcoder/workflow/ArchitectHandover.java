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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Which of the architect's findings travel with which task (owner's decision, 2026-10-08;
 * section 73). No model, and nothing read from a task's wording.
 *
 * <h2>Why</h2>
 *
 * <p>Nothing of the architect's design reached a worker directly. The task planner looked the
 * framework up a second time (run 98: 78 lookups, 31 of its 69 distinct ones a repeat of the
 * architect's) and wrote what it found into each task as about 600 tokens of prose; the worker,
 * the weakest model of the run, started from that. The findings are the architect's own lookups,
 * kept as it made them ({@code keep_for_workers}); here they are given to the tasks they concern,
 * word for word.
 *
 * <h2>Which task gets which finding</h2>
 *
 * <p>In this order, and within each step in the order the architect kept them:
 *
 * <ol>
 *   <li>findings about a contract the task delivers;</li>
 *   <li>findings about a type whose file the task's write set names;</li>
 *   <li>findings about the whole project;</li>
 *   <li>findings about another contract of the design that a member of one of the task's own
 *       contracts names as a type - what the task's code will have to use;</li>
 *   <li>findings about a contract delivered by a task this one waits for, the nearest such
 *       task first - what the task is built on.</li>
 * </ol>
 *
 * <p><b>The fifth step, after live run 100.</b> The architect kept three findings on how a
 * screen is written against a service - the generated client class it calls, a form, and that
 * the screen must be mounted on a thread of its own because building it calls the server - and
 * said they were about the service's contract, the only contract they concern: a screen was no
 * contract of that design. The first four steps gave them to the tasks that wrote the interface
 * and the server, and none to the task that wrote the screen, which delivered no contract and
 * so matched nothing. Its four workers took 128 of the run's 189 worker calls, and the first
 * screen they delivered failed in the browser on exactly the thread fact. A task that waits
 * for another is built on what that one delivers; the plan's edges say so with no wording read.
 *
 * <h2>The bound</h2>
 *
 * <p>{@value #DEFAULT_MAX_CHARS} characters a task, about 6,000 tokens
 * ({@code swarmcoder.handover.maxChars} replaces it). Input tokens are cheap on the workers'
 * server and a finding saves a worker the lookups and output tokens it would spend finding the
 * same thing, so the bound is generous; it is there for a project where the architect kept forty.
 * A finding is never cut: one that does not fit whole is given without its lines - the sentence
 * and the lookup that reads the rest - and one that does not fit even so is left out and
 * counted. So what is dropped first is the least relevant, never the end of a snippet.
 */
final class ArchitectHandover {

    static final int DEFAULT_MAX_CHARS = 24_000;

    private ArchitectHandover() {
    }

    static int maxChars() {
        return Math.max(0, Integer.getInteger("swarmcoder.handover.maxChars", DEFAULT_MAX_CHARS));
    }

    /**
     * @param findings    what the task is given, most relevant first
     * @param withoutCode how many of them are given as the sentence alone, for room
     * @param leftOut     how many relevant findings did not fit at all
     */
    record Given(List<DesignFinding> findings, int withoutCode, int leftOut) {
    }

    /** As {@link #forTask(Task, TaskGraph, DesignDocument, int)} for a task taken alone. */
    static Given forTask(Task task, DesignDocument design, int maxChars) {
        return forTask(task, null, design, maxChars);
    }

    /**
     * The findings of {@code design} that concern {@code task}, as many as fit.
     *
     * @param graph the plan the task is in, for the tasks it waits for; null for none
     */
    static Given forTask(Task task, TaskGraph graph, DesignDocument design, int maxChars) {
        if (task == null || design == null || design.findings().isEmpty()) {
            return new Given(List.of(), 0, 0);
        }
        Set<DesignFinding> relevant = new LinkedHashSet<>();
        List<ApiContract> delivers = task.deliveredContracts();
        for (DesignFinding finding : design.findings()) {
            if (finding != null && delivers.stream().anyMatch(finding::isAbout)) {
                relevant.add(finding);
            }
        }
        Set<String> files = fileStems(task.writeSet());
        for (DesignFinding finding : design.findings()) {
            if (finding != null && !finding.wholeProject()
                    && files.contains(finding.aboutSimpleName().toLowerCase(java.util.Locale.ROOT))) {
                relevant.add(finding);
            }
        }
        for (DesignFinding finding : design.findings()) {
            if (finding != null && finding.wholeProject()) {
                relevant.add(finding);
            }
        }
        List<ApiContract> used = new ArrayList<>();
        for (ApiContract other : design.contracts()) {
            if (other != null && other.namesAType() && !delivers.contains(other)
                    && delivers.stream().anyMatch(own -> names(own, other.simpleTypeName()))) {
                used.add(other);
            }
        }
        for (DesignFinding finding : design.findings()) {
            if (finding != null && used.stream().anyMatch(finding::isAbout)) {
                relevant.add(finding);
            }
        }
        for (Task before : waitedFor(task, graph)) {
            List<ApiContract> builtOn = before.deliveredContracts();
            for (DesignFinding finding : design.findings()) {
                if (finding != null && builtOn.stream().anyMatch(finding::isAbout)) {
                    relevant.add(finding);
                }
            }
        }
        List<DesignFinding> given = new ArrayList<>();
        int left = maxChars;
        int withoutCode = 0;
        int leftOut = 0;
        for (DesignFinding finding : relevant) {
            if (finding.size() <= left) {
                given.add(finding);
                left -= finding.size();
            } else if (finding.hasSnippet() && finding.withoutSnippet().size() <= left) {
                DesignFinding brief = finding.withoutSnippet();
                given.add(brief);
                left -= brief.size();
                withoutCode++;
            } else {
                leftOut++;
            }
        }
        return new Given(List.copyOf(given), withoutCode, leftOut);
    }

    /**
     * Gives every task of an accepted plan its findings and says, one line a task, what it was
     * given. Tasks are changed in place.
     */
    static List<String> attach(TaskGraph graph, DesignDocument design) {
        List<String> lines = new ArrayList<>();
        if (graph == null || graph.tasks() == null || design == null) {
            return lines;
        }
        int all = design.findings().size();
        if (all == 0) {
            lines.add("the architect kept no findings for the workers; every task starts from "
                + "the librarian's brief");
            return lines;
        }
        Set<DesignFinding> givenToSomeone = new LinkedHashSet<>();
        int bound = maxChars();
        for (Task task : graph.tasks()) {
            if (task == null) {
                continue;
            }
            Given given = forTask(task, graph, design, bound);
            task.setArchitectFindings(given.findings().isEmpty() ? null
                : new ArrayList<>(given.findings()));
            for (DesignFinding finding : design.findings()) {
                if (given.findings().stream().anyMatch(g -> g.id() != null
                        && g.id().equals(finding.id()))) {
                    givenToSomeone.add(finding);
                }
            }
            lines.add("'" + task.title() + "' is given " + given.findings().size() + " of the "
                + "architect's " + all + " finding(s), " + DesignFinding.sizeOf(given.findings())
                + " characters" + (given.withoutCode() == 0 ? "" : "; " + given.withoutCode()
                    + " without their lines, for room")
                + (given.leftOut() == 0 ? "" : "; " + given.leftOut() + " did not fit"));
        }
        List<String> nobody = design.findings().stream()
            .filter(f -> f != null && !givenToSomeone.contains(f))
            .map(f -> f.wholeProject() ? "the whole project" : f.about().strip()).distinct()
            .toList();
        if (!nobody.isEmpty()) {
            lines.add(nobody.size() + " subject(s) of the architect's findings are built or "
                + "changed by no task of this plan, so their findings went to no worker: "
                + nobody);
        }
        return lines;
    }

    /**
     * True when {@link #attach} would give some task of the plan other findings than it
     * carries: a plan saved before a rule here changed (a run resumed from an earlier stage).
     */
    static boolean stale(TaskGraph graph, DesignDocument design) {
        if (graph == null || graph.tasks() == null || design == null
                || design.findings().isEmpty()) {
            return false;
        }
        int bound = maxChars();
        for (Task task : graph.tasks()) {
            if (task != null && !DesignFinding.renderAll(forTask(task, graph, design, bound)
                    .findings()).equals(DesignFinding.renderAll(task.architectFindings()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tasks {@code task} waits for, by the plan's edges: the ones it waits for directly
     * first, then the ones those wait for. Empty without a plan.
     */
    private static List<Task> waitedFor(Task task, TaskGraph graph) {
        List<Task> found = new ArrayList<>();
        if (graph == null || graph.tasks() == null || graph.dependencies() == null
                || task.id() == null) {
            return found;
        }
        Map<UUID, Task> byId = new LinkedHashMap<>();
        for (Task one : graph.tasks()) {
            if (one != null && one.id() != null) {
                byId.put(one.id(), one);
            }
        }
        Set<UUID> seen = new LinkedHashSet<>();
        seen.add(task.id());
        Deque<UUID> next = new ArrayDeque<>();
        next.add(task.id());
        while (!next.isEmpty()) {
            UUID current = next.poll();
            for (TaskEdge edge : graph.dependencies()) {
                if (edge != null && current.equals(edge.to()) && edge.from() != null
                        && byId.containsKey(edge.from()) && seen.add(edge.from())) {
                    found.add(byId.get(edge.from()));
                    next.add(edge.from());
                }
            }
        }
        return found;
    }

    /** The names, without folder and extension and in lower case, of the files a set names. */
    private static Set<String> fileStems(Set<String> paths) {
        Set<String> stems = new LinkedHashSet<>();
        for (String path : paths == null ? Set.<String>of() : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String clean = path.strip().replace('\\', '/');
            String name = clean.substring(clean.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                stems.add(name.substring(0, dot).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return stems;
    }

    /** True when a member or the signature of {@code contract} names the type {@code simple}. */
    private static boolean names(ApiContract contract, String simple) {
        if (simple == null || simple.isBlank()) {
            return false;
        }
        Pattern word = Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(simple)
            + "(?![A-Za-z0-9_$])");
        for (String member : contract.members()) {
            if (member != null && word.matcher(member).find()) {
                return true;
            }
        }
        return contract.signatureSketch() != null
            && word.matcher(contract.signatureSketch()).find();
    }
}
