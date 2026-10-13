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

import com.swarmcoder.domain.InstructedRepair;
import com.swarmcoder.domain.Task;
import com.swarmcoder.verify.StartFailure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which task of a build wrote what an application that did not start names in its own output
 * (DEVELOPER_CORRECTIONS section 92, live supervised build of 2026-10-13: a class one task
 * wrote kept the application from starting, every acceptance test passed because none starts
 * the application, and the build stopped with no reason and nothing to answer but
 * {@code retry}).
 *
 * <p>From facts only. The output is cut into dotted names; each is looked up in the merged
 * tree's own index of declared types ({@code ProjectTypes}, CLAUDE.md section 1), longest
 * prefix first, so a stack frame {@code a.b.C.method} and a member {@code a.b.C.field} both
 * give {@code a.b.C}; and the file that declares a type is matched against the files each
 * task's chosen change wrote. No word of the output is read for what it means, and no
 * framework's message is known here.
 */
final class StartFailureOwner {

    private StartFailureOwner() {}

    /** How much of the output's end the question itself carries; the whole is one call away. */
    static final int QUESTION_OUTPUT_CHARS = 1_500;

    /** A run of identifiers joined by dots; {@code $} is how a nested type is printed. */
    private static final Pattern DOTTED =
        Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    /** At most this many types are written out in a sentence. */
    private static final int LISTED = 6;

    /**
     * @param task       the one task that wrote what the output names; null when not exactly one
     * @param candidates when {@code task} is null: the tasks to choose from
     * @param types      the project types the output names, each with the file declaring it
     * @param why        one sentence of the facts
     */
    record Named(Task task, List<Task> candidates, Map<String, String> types, String why) {}

    /**
     * The project types named in {@code output}, in the order they first appear, each with the
     * file that declares it.
     *
     * @param fileOfType the tree's lookup: the path, relative to the tree, of the file that
     *                   declares a fully qualified type name, or null when it declares none
     */
    static Map<String, String> typesNamedIn(String output, Function<String, String> fileOfType) {
        Map<String, String> found = new LinkedHashMap<>();
        if (output == null || output.isEmpty()) {
            return found;
        }
        java.util.Set<String> asked = new java.util.HashSet<>();
        Matcher matcher = DOTTED.matcher(output);
        while (matcher.find()) {
            String name = matcher.group();
            // Longest prefix first: a.b.C.method, a.b.C.field and a.b.C$Inner.method all end
            // at a declared type before they end at the package.
            for (int end = name.length(); end > 0; end = name.lastIndexOf('.', end - 1)) {
                String prefix = name.substring(0, end);
                if (prefix.indexOf('.') < 0 || found.containsKey(prefix)) {
                    break;
                }
                if (!asked.add(prefix)) {
                    continue;
                }
                String file = fileOfType.apply(prefix);
                if (file != null) {
                    found.put(prefix, file.replace('\\', '/'));
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Names the task, or the tasks to choose from.
     *
     * @param wrote      the files a task's chosen change wrote, relative to the tree; empty for
     *                   a task with no chosen change
     * @param fileOfType as for {@link #typesNamedIn}
     */
    static Named name(String output, List<Task> tasks, Function<Task, List<String>> wrote,
                      Function<String, String> fileOfType) {
        Map<String, String> types = typesNamedIn(output, fileOfType);
        Map<Task, List<String>> writers = new LinkedHashMap<>();
        List<Task> withAChange = new ArrayList<>();
        for (Task task : tasks == null ? List.<Task>of() : tasks) {
            List<String> files = wrote.apply(task);
            if (files == null || files.isEmpty()) {
                continue;
            }
            withAChange.add(task);
            List<String> normal = files.stream().map(file -> file.replace('\\', '/')).toList();
            for (Map.Entry<String, String> type : types.entrySet()) {
                if (normal.contains(type.getValue())) {
                    writers.computeIfAbsent(task, t -> new ArrayList<>()).add(type.getKey());
                }
            }
        }
        if (writers.size() == 1) {
            Map.Entry<Task, List<String>> one = writers.entrySet().iterator().next();
            return new Named(one.getKey(), List.of(), types, "The application's output names "
                + listed(one.getValue(), "`") + ", which task '" + one.getKey().title()
                + "' wrote; no other task of this build wrote a type the output names.");
        }
        if (writers.size() > 1) {
            List<String> each = new ArrayList<>();
            writers.forEach((task, named) -> each.add("'" + task.title() + "' ("
                + listed(named, "`") + ")"));
            return new Named(null, List.copyOf(writers.keySet()), types, "The application's "
                + "output names types that " + writers.size() + " tasks of this build wrote, so "
                + "no one task can be named: " + String.join(", ", each) + ".");
        }
        return new Named(null, List.copyOf(withAChange), types, (types.isEmpty()
            ? "The application's output names no type this project declares"
            : "The application's output names " + listed(new ArrayList<>(types.keySet()), "`")
                + ", and no task of this build wrote the file of any of them")
            + ", so no task can be named from it.");
    }

    private static String listed(List<String> items, String quote) {
        List<String> shown = items.stream().limit(LISTED)
            .map(item -> quote + item + quote).toList();
        return String.join(", ", shown)
            + (items.size() > LISTED ? " and " + (items.size() - LISTED) + " more" : "");
    }

    /**
     * What the stopped build's question says after its first sentence about a failed start:
     * where the whole output is read, and the last lines of it that fit. "" when there was no
     * failed start to speak of.
     */
    static String detail(StartFailure failure) {
        if (failure == null) {
            return "";
        }
        String where = failure.whereToRead();
        String last = failure.output().lastLines(QUESTION_OUTPUT_CHARS);
        if (last.isEmpty()) {
            return " The serve command printed nothing.";
        }
        boolean whole = last.length() >= failure.output().text().stripTrailing().length();
        return (where.isEmpty() ? "" : " " + where + ".") + "\n\n"
            + (whole ? "What the application printed:" : "The last lines the application printed:")
            + "\n" + last;
    }

    /**
     * What a stop on a failed start may be answered, beside {@code retry}.
     *
     * @param repair    the task {@code repair} is offered for as it stands; null when none
     * @param choices   the tasks {@code repair} is offered for when the answer names one
     * @param paragraph what the question says about it, before the detail
     */
    record Offer(UUID repair, List<UUID> choices, String paragraph) {
        static final Offer NONE = new Offer(null, List.of(), "");

        Offer {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    /**
     * Decides the offer from what the integrator recorded and the tasks' own counts: a task is
     * offered while it may still be given a repair round on an instruction (section 84's cap).
     */
    static Offer offerFor(List<Task> tasks, FinalIntegrator.StartFailed failed) {
        if (failed == null || tasks == null) {
            return Offer.NONE;
        }
        String opening = "The application did not start in the merged tree: " + failed.fact()
            + ". " + failed.why() + " ";
        if (failed.taskId() != null) {
            Task task = byId(tasks, failed.taskId());
            if (task != null && InstructedRepair.roundLeft(task)) {
                return new Offer(task.id(), List.of(), opening + "Answer repair with text: the "
                    + "workers of '" + task.title() + "' get one repair round, told your text "
                    + "and then what the application printed. retry starts the same tree again.");
            }
            return new Offer(null, List.of(), opening + (task == null ? "That task is no "
                + "longer in the plan" : "It has had the " + InstructedRepair.limit()
                + " repair round(s) on an instruction a task is given per build")
                + ", so only sending the story back remains; retry starts the same tree again.");
        }
        List<Task> open = new ArrayList<>();
        for (UUID id : failed.candidates()) {
            Task task = byId(tasks, id);
            if (task != null && InstructedRepair.roundLeft(task)) {
                open.add(task);
            }
        }
        if (open.isEmpty()) {
            return new Offer(null, List.of(), opening + "No task of this build can be given a "
                + "repair round for it, so only sending the story back remains; retry starts "
                + "the same tree again.");
        }
        return new Offer(null, open.stream().map(Task::id).toList(), opening + "Answer repair "
            + "with task set to one of " + String.join(", ", open.stream()
                .map(task -> "'" + task.title() + "'").toList())
            + " and with text: that task's workers get one repair round, told your text and "
            + "then what the application printed. retry starts the same tree again.");
    }

    private static Task byId(List<Task> tasks, UUID id) {
        return tasks.stream().filter(task -> task.id().equals(id)).findFirst().orElse(null);
    }
}
