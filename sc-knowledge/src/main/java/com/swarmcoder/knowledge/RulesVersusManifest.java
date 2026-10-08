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

import com.swarmcoder.domain.LibraryDoc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches the contradiction a run cannot survive: the project's stated rules name a library the
 * build does not declare.
 *
 * <p><b>Why this exists.</b> {@code dev/bookshelf-tech-requirements.md} says "Persistence uses
 * EclipseStore through {@code zerozstack-store-eclipsestore}", and for a day
 * {@code bookshelf-demo-server/pom.xml} declared no such dependency. Workers may only write under
 * {@code src/main/java} — none of them can add a dependency — so every worker of run
 * {@code ede2068b} spent 15-22 turns discovering the same contradiction by hand before being killed
 * with nothing written. Nothing in the pipeline compared what the rules SAY the stack is with what
 * the build SAYS it is, and that comparison costs milliseconds. This is that comparison.
 *
 * <p><b>Lives next to {@link ManifestParser}</b> on purpose: this is the one place in sc-knowledge
 * that already turns a build's poms into {@link LibraryDoc}s with {@code ${…}} properties resolved,
 * so it is the natural home for the one other thing that needs that list — comparing it against
 * what a document merely SAYS.
 *
 * <p><b>Deliberately narrow.</b> A false positive parks a real run over nothing, so the extractor
 * looks only where a rule is naming a thing on purpose — a Maven coordinate, a backticked token, or
 * a bare artifact id straight after a word that introduces a dependency's name — never at ordinary
 * hyphenated prose ("end-to-end", "read-only", "single-user"). Missing a vague mention is the safe
 * failure; flagging one is not.
 *
 * <p><b>A finding is a NAME, not yet a dependency</b> (live run 98, 2026-10-08). The extractor
 * reads wording, and wording can only suggest: a rule saying the client "only uses
 * TeaVM-compilable classes" yields the token after "uses", an adjective, exactly as "uses
 * some-store-artifact" yields a library. Whether a name is something a build can declare is a
 * fact about the build and is decided by the caller from facts ({@code BuildFilesInTheJob}): an
 * inherited BOM or parent pom manages an artifact of that name, or the text gave its group
 * ({@link Finding#group}). A name neither resolves is noted in the run and never stops it.
 */
public final class RulesVersusManifest {

    private RulesVersusManifest() {}

    /**
     * One rule — or one technical document — that names an artifact no module of the build
     * declares.
     *
     * @param source the filename of the technical document this finding came from, or null when
     *               it came from a stated rule; carried so the finding can name where it was
     *               found rather than always attributing it to "the rules"
     * @param group  the group id the text gave with the name ({@code group:artifact}), or null
     *               when it gave the bare name only
     */
    public record Finding(String ruleExcerpt, String artifact, List<String> inspectedPoms,
                          String source, String group) {

        /** A finding whose text gave no group - the shape before live run 98. */
        public Finding(String ruleExcerpt, String artifact, List<String> inspectedPoms,
                       String source) {
            this(ruleExcerpt, artifact, inspectedPoms, source, null);
        }

        /** A finding from a stated rule — the shape this class always had before documents were
         * scanned too. */
        public Finding(String ruleExcerpt, String artifact, List<String> inspectedPoms) {
            this(ruleExcerpt, artifact, inspectedPoms, null, null);
        }

        /**
         * True when the text wrote the artifact with its group, {@code group:artifact}: the one
         * wording that is a build coordinate by itself, whatever any repository holds.
         */
        public boolean statedAsCoordinate() {
            return group != null && !group.isBlank();
        }

        /** {@code group:artifact} when the group was given, otherwise the bare name. */
        public String named() {
            return statedAsCoordinate() ? group + ":" + artifact : artifact;
        }

        /** "the project's rules" or "the technical document \"…\"" — for a message the operator reads. */
        public String sourceLabel() {
            return source == null ? "the project's rules" : "the technical document \"" + source + "\"";
        }
    }

    /**
     * One technical document's full text, as ingested — the paper a stated rule may only have
     * paraphrased. {@code name} is the filename shown in a finding.
     */
    public record NamedDocument(String name, String text) {}

    /**
     * A Maven coordinate, {@code group:artifact} — the group conventionally reverse-DNS (at least
     * one dot), so this does not fire on ordinary "word: word" prose or a ratio like "3:2".
     */
    private static final Pattern COORDINATE = Pattern.compile(
        "\\b([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+):([a-z][a-z0-9]*(?:-[a-z0-9]+)*)\\b");

    /**
     * The build's own identity, never anything it must add. {@code moduleArtifactIds} is every
     * reactor module's own {@code artifactId} PLUS the root aggregator's, so a rule that merely
     * names the module it is talking about ("`bookshelf-demo-server` -- everything the server
     * runs") is not mistaken for the dependency a nearby sentence about EclipseStore genuinely is.
     * {@code groupIds} is the same, for a full {@code group:artifact} mention of one of them.
     * {@code repoRootEntries} names files and directories at the repository root, so a backticked
     * path is recognised even when its shape alone does not say "path".
     *
     * @see ManifestParser#ownCoordinate for where a caller reads this without a second parse
     */
    public record OwnBuild(Set<String> moduleArtifactIds, Set<String> groupIds,
                           Set<String> repoRootEntries) {

        public OwnBuild {
            moduleArtifactIds = moduleArtifactIds == null ? Set.of() : Set.copyOf(moduleArtifactIds);
            groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
            repoRootEntries = repoRootEntries == null ? Set.of() : Set.copyOf(repoRootEntries);
        }

        /** No build read at all -- every existing three- and four-argument caller's behaviour. */
        public static final OwnBuild NONE = new OwnBuild(Set.of(), Set.of(), Set.of());
    }

    /** A bare artifact id the author chose to set off in code formatting. */
    private static final Pattern BACKTICKED =
        Pattern.compile("`([a-z0-9]+(?:-[a-z0-9]+)+)`");

    /**
     * A bare artifact id straight after one of the words a rule uses to introduce a dependency's
     * name. Deliberately a short, fixed list — widening it is how prose starts getting flagged.
     */
    private static final Pattern AFTER_CUE = Pattern.compile(
        "\\b(?:through|module|dependency|uses|via)\\s+([a-z0-9]+(?:-[a-z0-9]+)+)\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * The artifact-looking tokens named in one piece of rule text — see the class javadoc for what
     * counts and why the extractor stops there.
     */
    static Set<String> artifactsNamedIn(String ruleText) {
        return artifactsNamedIn(ruleText, OwnBuild.NONE);
    }

    /**
     * As above, and never yields a token that names the build itself — one of its own reactor
     * modules (or the root aggregator), a full coordinate under its own group, or a path into its
     * own repository. Excluded here, at the source, rather than filtered out of {@code found}
     * afterwards: the exact wording of a run parked over nothing is why this exists (see the class
     * javadoc), so the tokens that never should have looked like a dependency's name never become
     * one.
     */
    static Set<String> artifactsNamedIn(String ruleText, OwnBuild ownBuild) {
        return namesIn(ruleText, ownBuild).keySet();
    }

    /**
     * The names {@link #artifactsNamedIn(String, OwnBuild)} yields, each with the group the text
     * gave it, or null for a name written bare. A name written both ways keeps its group.
     */
    static Map<String, String> namesIn(String ruleText, OwnBuild ownBuild) {
        Map<String, String> found = new LinkedHashMap<>();
        if (ruleText == null || ruleText.isBlank()) {
            return found;
        }
        OwnBuild own = ownBuild == null ? OwnBuild.NONE : ownBuild;
        Matcher coordinates = COORDINATE.matcher(ruleText);
        while (coordinates.find()) {
            if (!own.groupIds().contains(coordinates.group(1))) {
                addUnlessOwn(found, coordinates.group(2), coordinates.group(1), own);
            }
        }
        Matcher backticked = BACKTICKED.matcher(ruleText);
        while (backticked.find()) {
            addUnlessOwn(found, backticked.group(1), null, own);
        }
        Matcher afterCue = AFTER_CUE.matcher(ruleText);
        while (afterCue.find()) {
            addUnlessOwn(found, afterCue.group(1), null, own);
        }
        return found;
    }

    /** Adds {@code token} unless it names the build itself: one of its own module ids, an entry at
     * its own repository root, or something shaped like a path or a tracked file rather than a
     * dependency's name. */
    private static void addUnlessOwn(Map<String, String> found, String token, String group,
                                     OwnBuild own) {
        if (own.moduleArtifactIds().contains(token) || own.repoRootEntries().contains(token)
                || looksLikeRepoPath(token)) {
            return;
        }
        if (group != null || !found.containsKey(token)) {
            found.put(token, group);
        }
    }

    /**
     * A token shaped like a path into the repository or a tracked file, never a dependency's name.
     * Today's three patterns above cannot actually produce one — their character class has no
     * {@code .} or {@code /} — so this is a guard for a future widening of those patterns, kept
     * explicit rather than assumed, since a false positive here parks a run over nothing.
     */
    private static boolean looksLikeRepoPath(String token) {
        if (token.contains("/")) {
            return true;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xml") || lower.endsWith(".md") || lower.endsWith(".java")
            || lower.endsWith(".yaml");
    }

    /**
     * Splits a rendered rules brief back into one chunk per rule, so a finding can quote the ONE
     * rule that named the artifact rather than the whole document.
     *
     * <p>{@link com.swarmcoder.domain.ConstraintBrief} renders every rule as its own bullet, each
     * starting a new line with {@code "- "}; a chunk runs from one such line to the line before the
     * next one. Text with no such bullets — raw prose, as a caller may hand this class directly in
     * a test — is treated as a single rule.
     */
    static List<String> splitRules(String ruleText) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean sawBullet = false;
        for (String line : ruleText.split("\n", -1)) {
            if (line.startsWith("- ")) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                }
                current = new StringBuilder(line);
                sawBullet = true;
            } else if (sawBullet) {
                current.append('\n').append(line);
            }
            // A line before the first bullet is the briefing's own header, not a rule; dropped.
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return sawBullet ? chunks : List.of(ruleText);
    }

    /** Splits after a sentence-ending punctuation mark, so a finding from a document can quote
     * the ONE sentence that named the artifact rather than the whole document. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String piece : SENTENCE_END.split(text)) {
            String trimmed = piece.strip();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    /**
     * Compares what the rules say this project is built of against what its build actually
     * declares.
     *
     * @param ruleText      the project's stated rules, in full — the same rendering every agent is
     *                      briefed with; "" or null yields no findings
     * @param declared      every library every module of the build declares directly, gathered by
     *                      calling {@link ManifestParser#parse} once per module ({@code ${…}}
     *                      already resolved); transitive dependencies are the caller's business,
     *                      not this method's
     * @param inspectedPoms the module poms that were read, repo-relative — carried onto every
     *                      finding so the operator knows exactly what was checked
     * @return one finding per artifact the rules name that no module declares, in the order the
     *         rules mention them; empty when the rules and the build agree
     */
    public static List<Finding> check(String ruleText, List<LibraryDoc> declared,
                                       List<String> inspectedPoms) {
        return check(ruleText, List.of(), declared, inspectedPoms);
    }

    /** As above, plus {@code ownBuild} — see the four-argument overload for why. */
    public static List<Finding> check(String ruleText, List<LibraryDoc> declared,
                                       List<String> inspectedPoms, OwnBuild ownBuild) {
        return check(ruleText, List.of(), declared, inspectedPoms, ownBuild);
    }

    /**
     * As above, and ALSO scans the full text of every technical document the project has
     * ingested — not only the rules an analyst distilled from them.
     *
     * <p><b>Why the documents too.</b> A stated {@link com.swarmcoder.domain.LearnedGuideline} is
     * the analyst's RESTATEMENT of a document, and an analyst restating "persistence uses
     * EclipseStore through {@code zerozstack-store-eclipsestore}" as "Persistence via EclipseStore
     * object graph" has paraphrased away the one backticked token this whole comparison exists to
     * catch. The one place the artifact was actually named — the document itself — was never
     * scanned before this. The extractor's precision rules are unchanged: a document sentence is
     * flagged on exactly the same terms a rule is (a Maven coordinate, a backticked token, or an
     * "through/module/dependency/uses/via NAME" cue), never on ordinary prose.
     *
     * <p>An artifact already found missing from the rules is not reported again from a document
     * that also names it — one finding per missing artifact, whichever source is checked first.
     *
     * @param technicalDocuments the project's ingested technical documents, in full; empty or
     *                           null scans none, exactly as before this overload existed
     */
    public static List<Finding> check(String ruleText, List<NamedDocument> technicalDocuments,
                                       List<LibraryDoc> declared, List<String> inspectedPoms) {
        return check(ruleText, technicalDocuments, declared, inspectedPoms, OwnBuild.NONE);
    }

    /**
     * As above, and never reports an artifact that names the build's OWN reactor — a module it
     * already contains, or the root aggregator (see {@link OwnBuild}). Read {@code
     * dev/bookshelf-tech-requirements.md}'s own sentence, "{@code `bookshelf-demo-server}` —
     * everything the server runs", against this project's real reactor: without {@code ownBuild}
     * that backticked module name is indistinguishable from the sentence two paragraphs later
     * naming {@code zerozstack-store-eclipsestore}, and both get reported as a dependency no pom
     * declares — one of them correctly, one of them not.
     *
     * @param ownBuild what the build IS, gathered the same run {@code declared} was — pass {@link
     *                 OwnBuild#NONE} to get exactly the four-argument overload's behaviour
     */
    public static List<Finding> check(String ruleText, List<NamedDocument> technicalDocuments,
                                       List<LibraryDoc> declared, List<String> inspectedPoms,
                                       OwnBuild ownBuild) {
        List<Finding> findings = new ArrayList<>();
        Set<String> declaredIds = declaredArtifactIds(declared);
        List<String> poms = inspectedPoms == null ? List.of() : List.copyOf(inspectedPoms);
        OwnBuild own = ownBuild == null ? OwnBuild.NONE : ownBuild;
        Set<String> alreadyFound = new LinkedHashSet<>();
        if (ruleText != null && !ruleText.isBlank()) {
            for (String rule : splitRules(ruleText)) {
                for (Map.Entry<String, String> named : namesIn(rule, own).entrySet()) {
                    String artifact = named.getKey();
                    if (!declaredIds.contains(artifact) && alreadyFound.add(artifact)) {
                        findings.add(new Finding(excerpt(rule), artifact, poms, null,
                            named.getValue()));
                    }
                }
            }
        }
        if (technicalDocuments != null) {
            for (NamedDocument document : technicalDocuments) {
                if (document == null || document.text() == null || document.text().isBlank()) {
                    continue;
                }
                for (String sentence : splitSentences(document.text())) {
                    for (Map.Entry<String, String> named : namesIn(sentence, own).entrySet()) {
                        String artifact = named.getKey();
                        if (!declaredIds.contains(artifact) && alreadyFound.add(artifact)) {
                            findings.add(new Finding(excerpt(sentence), artifact, poms,
                                document.name(), named.getValue()));
                        }
                    }
                }
            }
        }
        return findings;
    }

    /**
     * Every artifact-looking token the whole rules document names, rule by rule.
     *
     * <p>The same extractor {@link #check} uses, exposed because the worker's "libraries you may
     * add" list has to offer an artifact the rules name even when its group is not one the build
     * already uses — which is precisely the case that produced this class.
     */
    public static Set<String> artifactsNamedInRules(String ruleText) {
        Set<String> named = new LinkedHashSet<>();
        if (ruleText == null || ruleText.isBlank()) {
            return named;
        }
        for (String rule : splitRules(ruleText)) {
            named.addAll(artifactsNamedIn(rule));
        }
        return named;
    }

    private static Set<String> declaredArtifactIds(List<LibraryDoc> declared) {
        Set<String> ids = new LinkedHashSet<>();
        if (declared == null) {
            return ids;
        }
        for (LibraryDoc library : declared) {
            String coordinate = library.coordinate() == null ? "" : library.coordinate();
            int colon = coordinate.indexOf(':');
            String artifact = colon >= 0 ? coordinate.substring(colon + 1) : coordinate;
            if (!artifact.isBlank()) {
                ids.add(artifact);
            }
        }
        return ids;
    }

    /** The first 120 characters of the rule that named the artifact — the caller's own wording. */
    private static String excerpt(String rule) {
        String trimmed = rule.strip();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
    }
}
