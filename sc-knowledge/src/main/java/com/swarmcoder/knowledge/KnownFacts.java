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

import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Story;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.StoreRoot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * What a project already knows about itself and about what it builds on: the findings the
 * architects of its earlier stories kept (section 73), offered to every later role as a lookup
 * ({@code known_about}) and, by title only, in its opening (section 97).
 *
 * <p><b>Why.</b> The second story of a three-module project spent 59 paid calls and 2.9 million
 * tokens in design without writing a draft. Of the characters its lookups returned, 44% - and
 * 67% of those about the reference material - were about a type, file or document the first
 * story's architect had looked up a few hours earlier, and the project held 22 findings from
 * that work which nothing showed to the second architect. A project learns what it builds on
 * once.
 *
 * <p><b>What a fact is.</b> A finding exactly as it was recorded: what it is about, the lookup
 * it was copied from, the architect's sentence and the lines the tool copied; with the story
 * it was established for and the commit that story was built on. Nothing is selected by a
 * search and nothing is typed again by a model. The opening carries titles only - what each
 * subject's facts came from - like the project map; the text of a fact is a lookup.
 *
 * <p><b>A fact whose source changed is not served as true.</b> The lines a finding carries were
 * copied from its source, so the question "is this still so" has an exact answer with no model:
 * are those lines still in that file. When they are not, {@code known_about} says so, withholds
 * the lines and names the lookup to make again.
 */
public final class KnownFacts {

    /**
     * One fact.
     *
     * @param about   the type or contract it is about; null for the whole project
     * @param source  the lookup it was copied from: the tool, a space, its argument
     * @param note    the architect's sentence
     * @param snippet the lines the tool copied from that lookup's result; null for none
     * @param story   the story it was established for, as the backlog names it; may be null
     * @param commit  the commit that story was built on or delivered as; may be null
     */
    public record Fact(String about, String source, String note, String snippet, String story,
                       String commit) {}

    /** What became of a fact's source since it was recorded. */
    public enum Source {
        /** Every line the fact carries is still in its source. */
        UNCHANGED,
        /** At least one line the fact carries is no longer in its source. */
        CHANGED,
        /** The source is no longer where the lookup found it. */
        GONE,
        /** The fact carries no lines, so there is nothing to hold against its source. */
        NO_LINES
    }

    /** The most characters one {@code known_about} answer carries before it turns to titles. */
    static final int ANSWER_CHARS = 12_000;

    private static final KnownFacts NONE = new KnownFacts(List.of());

    private final List<Fact> facts;

    private KnownFacts(List<Fact> facts) {
        this.facts = facts;
    }

    public static KnownFacts none() {
        return NONE;
    }

    /** The facts in the order given, without the ones that say the same of the same place. */
    public static KnownFacts of(List<Fact> facts) {
        if (facts == null || facts.isEmpty()) {
            return NONE;
        }
        Map<String, Fact> distinct = new LinkedHashMap<>();
        for (Fact fact : facts) {
            if (fact == null || fact.note() == null || fact.note().isBlank()
                    || fact.source() == null || fact.source().isBlank()) {
                continue;
            }
            distinct.putIfAbsent(fact.about() + "\u0000" + fact.source() + "\u0000"
                + fact.snippet() + "\u0000" + fact.note(), fact);
        }
        return distinct.isEmpty() ? NONE : new KnownFacts(List.copyOf(distinct.values()));
    }

    /**
     * The findings of the designs this project's stories were delivered with, oldest first.
     * A run that was not delivered contributes nothing: what its architect kept was never
     * proved by a build.
     */
    public static KnownFacts ofDelivered(ArtifactStore store, UUID projectId) {
        if (store == null || projectId == null) {
            return NONE;
        }
        List<Run> runs;
        Map<UUID, DesignDocument> designs;
        StoreRoot root = store.root();
        synchronized (root) {
            runs = new ArrayList<>(root.runs.values());
            designs = new LinkedHashMap<>(root.designs);
        }
        return fromDelivered(runs, designs::get, store::getStory, projectId);
    }

    /**
     * {@link #ofDelivered} over runs, designs and stories however they are held.
     *
     * @param designOf a run's design by its id; may answer null
     * @param storyOf  a run's story by its id; may answer null
     */
    public static KnownFacts fromDelivered(List<Run> allRuns, Function<UUID, DesignDocument> designOf,
                                           Function<UUID, Story> storyOf, UUID projectId) {
        List<Run> runs = new ArrayList<>(allRuns == null ? List.of() : allRuns);
        runs.removeIf(run -> run == null || !projectId.equals(run.projectId())
            || run.designId() == null || run.startedAt() == null);
        runs.sort(Comparator.comparing(Run::startedAt));
        List<Fact> facts = new ArrayList<>();
        for (Run run : runs) {
            Story story = run.storyId() == null ? null : storyOf.apply(run.storyId());
            String delivered = story == null ? null : story.deliveredCommit();
            DesignDocument design = designOf.apply(run.designId());
            if (delivered == null || delivered.isBlank() || design == null
                    || design.findings() == null) {
                continue;
            }
            for (DesignFinding finding : design.findings()) {
                facts.add(new Fact(finding.about(), finding.source(), finding.note(),
                    finding.snippet(), story.key(), delivered));
            }
        }
        return of(facts);
    }

    public boolean isEmpty() {
        return facts.isEmpty();
    }

    public int size() {
        return facts.size();
    }

    /**
     * The titles: each subject with how many facts are held about it and what they were copied
     * from. No fact's text. "" when nothing is known.
     */
    public String index(int maxChars) {
        if (facts.isEmpty()) {
            return "";
        }
        Map<String, Set<String>> sources = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Fact fact : facts) {
            String subject = subjectOf(fact);
            sources.computeIfAbsent(subject, s -> new LinkedHashSet<>()).add(nameOf(fact.source()));
            counts.merge(subject, 1, Integer::sum);
        }
        StringBuilder index = new StringBuilder("WHAT THIS PROJECT ALREADY KNOWS - " + facts.size()
            + " fact(s) the architect of an earlier, delivered story established and kept. "
            + "Titles only: known_about <subject or source> gives each fact with the lines it "
            + "was copied from, the story and commit it was true at, and whether its source has "
            + "changed since. Read a fact before you look the same thing up again.\n");
        int listed = 0;
        for (Map.Entry<String, Set<String>> subject : sources.entrySet()) {
            String line = "- " + subject.getKey() + " (" + counts.get(subject.getKey())
                + "): from " + String.join(", ", subject.getValue()) + "\n";
            if (index.length() + line.length() > Math.max(400, maxChars)) {
                index.append("- and ").append(sources.size() - listed)
                    .append(" more subject(s): known_about with an empty string lists them all\n");
                break;
            }
            index.append(line);
            listed++;
        }
        return index.toString();
    }

    /**
     * The facts about {@code what}: a type by its simple or full name, or a source by the type,
     * file or document it names. Names are compared as names, never as words of a sentence. An
     * empty question lists every title.
     *
     * @param check what became of a fact's source, asked once per fact that is shown
     */
    public String about(String what, Function<Fact, Source> check) {
        if (facts.isEmpty()) {
            return "Nothing is recorded for this project yet: no earlier story's architect kept "
                + "a finding. Look it up.";
        }
        String asked = what == null ? "" : what.strip();
        if (asked.isEmpty()) {
            return index(Integer.MAX_VALUE);
        }
        int hash = asked.indexOf('#');
        String name = (hash > 0 ? asked.substring(0, hash) : asked).toLowerCase(Locale.ROOT);
        List<Fact> found = new ArrayList<>();
        for (Fact fact : facts) {
            if (names(fact).contains(name) || names(fact).contains(asked.toLowerCase(Locale.ROOT))) {
                found.add(fact);
            }
        }
        if (found.isEmpty()) {
            return "Nothing is recorded about `" + asked + "`. Look it up. What is recorded:\n"
                + index(Integer.MAX_VALUE);
        }
        StringBuilder answer = new StringBuilder(found.size() + " fact(s) recorded about `" + asked
            + "`, each as the architect of an earlier story kept it:\n");
        int shown = 0;
        for (Fact fact : found) {
            Source source = check == null ? Source.NO_LINES : check.apply(fact);
            String text = render(fact, source == null ? Source.NO_LINES : source);
            if (shown > 0 && answer.length() + text.length() > ANSWER_CHARS) {
                answer.append("\n... and ").append(found.size() - shown).append(" more, from: ");
                Set<String> rest = new LinkedHashSet<>();
                for (Fact left : found.subList(shown, found.size())) {
                    rest.add(nameOf(left.source()));
                }
                answer.append(String.join(", ", rest))
                    .append(". Ask known_about for one of those sources to read them.\n");
                break;
            }
            answer.append('\n').append(text);
            shown++;
        }
        return answer.toString();
    }

    private static String render(Fact fact, Source source) {
        StringBuilder text = new StringBuilder("[").append(subjectOf(fact)).append("] ")
            .append(fact.note().strip()).append('\n');
        text.append("  from: ").append(fact.source().strip());
        if (fact.story() != null || fact.commit() != null) {
            text.append("; true for story ").append(fact.story() == null ? "?" : fact.story())
                .append(fact.commit() == null ? "" : " at commit "
                    + fact.commit().substring(0, Math.min(10, fact.commit().length())));
        }
        text.append('\n');
        boolean lines = fact.snippet() != null && !fact.snippet().isBlank();
        switch (source) {
            case UNCHANGED -> text.append("  source: unchanged since - every line below is "
                + "still in it.\n");
            case CHANGED -> text.append("  source: CHANGED SINCE. The lines kept from it are no "
                + "longer all in it, so they are not shown and the sentence above may no longer "
                + "be true. Make that lookup again before you rely on it.\n");
            case GONE -> text.append("  source: NO LONGER THERE. The sentence above may no "
                + "longer be true; look up what replaced it before you rely on it.\n");
            default -> text.append("  source: kept as a sentence, with no lines to hold "
                + "against it.\n");
        }
        if (lines && source == Source.UNCHANGED) {
            text.append("```\n").append(fact.snippet().stripTrailing()).append("\n```\n");
        }
        return text.toString();
    }

    /** What the opening and an answer call a fact's subject. */
    static String subjectOf(Fact fact) {
        String about = fact.about();
        if (about == null || about.isBlank()) {
            return "the whole project";
        }
        String name = about.strip();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** A source as a title: the file or type it names, with its section or member. */
    static String nameOf(String source) {
        String argument = argumentOf(source);
        int hash = argument.indexOf('#');
        String section = hash >= 0 ? argument.substring(hash) : "";
        String path = (hash >= 0 ? argument.substring(0, hash) : argument)
            .replaceAll(":\\d+(-\\d+)?$", "");
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        }
        return name + (section.length() > 1 ? section : "");
    }

    /** The argument of the lookup a source names, without the tool. */
    static String argumentOf(String source) {
        String text = source == null ? "" : source.strip();
        int space = text.indexOf(' ');
        String argument = space < 0 ? text : text.substring(space + 1).strip();
        int note = argument.indexOf(" [");
        return note > 0 ? argument.substring(0, note) : argument;
    }

    /** Every name a fact answers to, in lower case. */
    private static Set<String> names(Fact fact) {
        Set<String> names = new LinkedHashSet<>();
        if (fact.about() != null && !fact.about().isBlank()) {
            names.add(fact.about().strip().toLowerCase(Locale.ROOT));
            names.add(subjectOf(fact).toLowerCase(Locale.ROOT));
        }
        String argument = argumentOf(fact.source());
        names.add(argument.toLowerCase(Locale.ROOT));
        String title = nameOf(fact.source());
        names.add(title.toLowerCase(Locale.ROOT));
        int hash = argument.indexOf('#');
        String path = (hash >= 0 ? argument.substring(0, hash) : argument)
            .replaceAll(":\\d+(-\\d+)?$", "");
        names.add(path.toLowerCase(Locale.ROOT));
        String file = path.substring(path.lastIndexOf('/') + 1);
        names.add(file.toLowerCase(Locale.ROOT));
        int dot = file.lastIndexOf('.');
        names.add((dot > 0 ? file.substring(0, dot) : file).toLowerCase(Locale.ROOT));
        int member = title.indexOf('#');
        if (member > 0) {
            names.add(title.substring(0, member).toLowerCase(Locale.ROOT));
        }
        // A qualified type name answers to its simple name too.
        if (!path.contains("/") && path.contains(".")) {
            names.add(path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        }
        names.remove("");
        return names;
    }

    /**
     * Whether the lines a fact carries are still in the text of its source. A kept line that
     * names the source's own address is the tool's heading, not a line of the source.
     *
     * @param sourceText the source as it is now; null when it is no longer there
     * @param address    the address the lookup tools print for that source; may be null
     */
    static Source held(Fact fact, String sourceText, String address) {
        if (fact.snippet() == null || fact.snippet().isBlank()) {
            return Source.NO_LINES;
        }
        if (sourceText == null) {
            return Source.GONE;
        }
        Set<String> now = new java.util.HashSet<>();
        for (String line : sourceText.split("\\R")) {
            now.add(line.strip());
        }
        for (String line : fact.snippet().split("\\R")) {
            String kept = line.strip();
            if (kept.isEmpty() || now.contains(kept)
                    || (address != null && !address.isBlank() && kept.contains(address))) {
                continue;
            }
            return Source.CHANGED;
        }
        return Source.UNCHANGED;
    }
}
