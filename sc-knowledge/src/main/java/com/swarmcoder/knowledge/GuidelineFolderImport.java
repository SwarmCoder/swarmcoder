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

import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads the rule files a checkout may still carry under {@code .swarmcoder/guidelines/} into the
 * store — ONCE — and never looks at the folder again (author decision 2026-09-02).
 *
 * <p>This is the only code left that reads a rule file, and it exists for the projects that were
 * created while rules were files. The operator's own project has 81 of them, 80 in his git
 * history, and they are four rewordings of about eleven rules: every time the technical document
 * was applied, the analyst worded its rules a little differently, the filename differed, and the
 * previous set stayed on disk RETIRED. So the import does not merely copy files into objects — it
 * collapses what it reads, twice:
 *
 * <ol>
 *   <li>rules with the same wording (case and whitespace aside) are one rule — the store's own
 *       notion of identity, {@link ProjectRules#contentKey};</li>
 *   <li>rules that say the same thing in different words are one rule, judged by how many content
 *       words they share — the measure the extractor's backstop uses, at the threshold measured on
 *       exactly this folder (0.5 flagged 21 pairs, every one a genuine repeat), applied
 *       transitively so a chain of rewordings becomes one group.</li>
 * </ol>
 *
 * <p>Of each group one rule survives: an ACTIVE one over a PROPOSED one over a RETIRED one, a rule
 * the store already holds over one from a file (so its id survives), and the longest wording over
 * a shorter one, because the longest is the one most likely to carry the detail the others
 * dropped. Its status is the best status the group had, so a rule that is in force stays in force
 * and a rule every version of which was retired stays retired — as history, once, not 44 times.
 *
 * <p>What the store already holds for the project goes into the same grouping. A store written
 * while rules were files holds exactly what the folder holds, four rewordings included; importing
 * the files without collapsing the index would leave the 81 in place under a different name.
 *
 * <p><b>The files are not touched.</b> They are in the operator's git history, and that is theirs
 * to clean. Every collapse is logged by name so the folder can be checked against what survived.
 */
public final class GuidelineFolderImport {

    private static final Logger log = LoggerFactory.getLogger(GuidelineFolderImport.class);

    /** Shared content words over the smaller set; see the class javadoc for where 0.5 comes from. */
    static final double SAME_LESSON_OVERLAP = 0.5;
    private static final int MIN_CONTENT_TOKENS = 5;

    private GuidelineFolderImport() {}

    /**
     * What one import did.
     *
     * @param filesRead  rule files found under the folder
     * @param inStore    rules the store already held for the project before the import
     * @param kept       rules the project has afterwards
     * @param collapsed  rules folded into another as the same lesson
     * @param lines      one line per collapse, survivor first — the receipt
     */
    public record Result(int filesRead, int inStore, int kept, int collapsed, List<String> lines) {}

    /**
     * Imports the folder if this project has never imported it, and marks the project so it never
     * does again. Returns null when the project was already marked.
     *
     * @param guidelinesDir {@code <repo>/.swarmcoder/guidelines}; null or absent imports nothing but
     *                      still marks the project, because the folder is not consulted again
     */
    public static Result importOnce(ArtifactStore store, Project project, ProjectRules rules,
                                    Path guidelinesDir) {
        if (project.guidelineFilesImported()) {
            return null;
        }
        Result result;
        try {
            result = run(rules, guidelinesDir);
        } catch (IOException e) {
            // Left unmarked, so the next open tries again; the rules are the operator's decisions
            // and losing them to an unreadable folder would be worse than reading it twice.
            log.warn("Could not read the rule files under {} — they will be tried again next time: {}",
                guidelinesDir, e.getMessage());
            return new Result(0, 0, rules.all().size(), 0, List.of());
        }
        project.setGuidelineFilesImported(true);
        store.saveProject(project);
        if (result.filesRead() == 0 && result.collapsed() == 0) {
            return result;
        }
        log.info("Imported {} rule file(s) from {} into the store: the project had {} rule(s) in "
            + "the store, has {} now, and {} were folded into another as the same lesson said "
            + "differently. The folder is not read again; the files are yours to remove.",
            result.filesRead(), guidelinesDir, result.inStore(), result.kept(), result.collapsed());
        for (String line : result.lines()) {
            log.info("  {}", line);
        }
        return result;
    }

    /** The import itself, without the once-only mark — for a caller that has its own. */
    static Result run(ProjectRules rules, Path guidelinesDir) throws IOException {
        List<LearnedGuideline> before = rules.all();
        List<Candidate> candidates = new ArrayList<>();
        for (LearnedGuideline rule : before) {
            candidates.add(new Candidate(rule, true));
        }
        int filesRead = 0;
        if (guidelinesDir != null && Files.isDirectory(guidelinesDir)) {
            try (Stream<Path> walk = Files.walk(guidelinesDir)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                    LearnedGuideline parsed = parse(file, rules.projectId());
                    if (parsed != null) {
                        candidates.add(new Candidate(parsed, false));
                        filesRead++;
                    }
                }
            }
        }
        List<List<Candidate>> groups = group(candidates);
        List<String> lines = new ArrayList<>();
        int collapsed = 0;
        int kept = 0;
        for (List<Candidate> group : groups) {
            group.sort(SURVIVOR_FIRST);
            Candidate survivor = group.get(0);
            LearnedGuideline rule = merged(survivor, group);
            boolean unchanged = survivor.inStore && group.size() == 1
                && rule.equals(survivor.rule);
            if (!unchanged) {
                rules.put(rule);
            }
            kept++;
            if (group.size() > 1) {
                List<String> folded = new ArrayList<>();
                for (Candidate other : group.subList(1, group.size())) {
                    if (other.inStore) {
                        rules.remove(other.rule.id());
                    }
                    folded.add(other.rule.slug() + " (" + other.rule.status() + ")");
                    collapsed++;
                }
                lines.add(rule.slug() + " (" + rule.status() + ") <- " + String.join(", ", folded));
            }
        }
        return new Result(filesRead, before.size(), kept, collapsed, lines);
    }

    /** One rule going into the grouping, and whether the store already holds it under its id. */
    private record Candidate(LearnedGuideline rule, boolean inStore) {}

    /** ACTIVE before PROPOSED before RETIRED; then already in the store; then the longer wording. */
    private static final Comparator<Candidate> SURVIVOR_FIRST = Comparator
        .comparingInt((Candidate c) -> statusRank(c.rule.status()))
        .thenComparing(c -> !c.inStore)
        .thenComparing(Comparator.comparingInt(
            (Candidate c) -> c.rule.markdownBody() == null ? 0 : c.rule.markdownBody().length())
            .reversed())
        .thenComparing(c -> c.rule.slug() == null ? "" : c.rule.slug());

    private static int statusRank(GuidelineStatus status) {
        if (status == GuidelineStatus.ACTIVE) {
            return 0;
        }
        return status == GuidelineStatus.PROPOSED ? 1 : 2;
    }

    /**
     * The survivor with what the rest of its group had that it lacked: the group's best status, a
     * title when it had none, and a check command when a rule a person decided declared one.
     */
    private static LearnedGuideline merged(Candidate survivor, List<Candidate> group) {
        LearnedGuideline s = survivor.rule;
        GuidelineStatus status = group.get(0).rule.status(); // sorted: best status first
        LearnedGuideline rule = new LearnedGuideline(s.id(), survivor.inStore ? s.revision() + 1 : 1,
            s.scope() == null ? GuidelineScope.PROJECT : s.scope(), s.slug(), s.markdownBody(),
            s.provenance(), s.confidence(), s.lastUsed() == null ? Instant.now() : s.lastUsed(),
            s.useCount(), status == null ? GuidelineStatus.ACTIVE : status, s.projectId(),
            s.checkCommand(), s.checkTimeoutSeconds());
        rule.carryingMeaningFrom(s);
        for (Candidate other : group) {
            LearnedGuideline o = other.rule;
            if (rule.title() == null && o.title() != null) {
                rule.setTitle(o.title());
            }
            if (rule.checkCommand() == null && o.checkCommand() != null
                    && ProjectRules.decidedByAPerson(o) && ProjectRules.decidedByAPerson(rule)) {
                rule.setCheckCommand(o.checkCommand());
                rule.setCheckTimeoutSeconds(o.checkTimeoutSeconds());
            }
        }
        return rule;
    }

    /**
     * Same wording first, then same lesson by shared content words, transitively. Groups come out
     * in the order their first member was seen, so a run is repeatable.
     */
    private static List<List<Candidate>> group(List<Candidate> candidates) {
        Map<Candidate, Candidate> parent = new LinkedHashMap<>();
        Map<String, Candidate> byWording = new LinkedHashMap<>();
        List<Set<String>> tokens = new ArrayList<>();
        for (Candidate candidate : candidates) {
            parent.put(candidate, candidate);
            String key = ProjectRules.contentKey(candidate.rule.markdownBody());
            Candidate same = byWording.putIfAbsent(key, candidate);
            if (same != null) {
                union(parent, same, candidate);
            }
            tokens.add(GuidelineExtractor.contentTokens(
                candidate.rule.markdownBody() == null ? "" : candidate.rule.markdownBody()));
        }
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                if (overlap(tokens.get(i), tokens.get(j)) >= SAME_LESSON_OVERLAP) {
                    union(parent, candidates.get(i), candidates.get(j));
                }
            }
        }
        Map<Candidate, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            groups.computeIfAbsent(find(parent, candidate), k -> new ArrayList<>()).add(candidate);
        }
        return new ArrayList<>(groups.values());
    }

    private static double overlap(Set<String> a, Set<String> b) {
        if (a.size() < MIN_CONTENT_TOKENS || b.size() < MIN_CONTENT_TOKENS) {
            return 0;
        }
        Set<String> shared = new LinkedHashSet<>(a);
        shared.retainAll(b);
        return (double) shared.size() / Math.min(a.size(), b.size());
    }

    private static void union(Map<Candidate, Candidate> parent, Candidate a, Candidate b) {
        Candidate ra = find(parent, a);
        Candidate rb = find(parent, b);
        if (ra != rb) {
            parent.put(rb, ra);
        }
    }

    private static Candidate find(Map<Candidate, Candidate> parent, Candidate c) {
        Candidate cursor = c;
        while (parent.get(cursor) != cursor) {
            cursor = parent.get(cursor);
        }
        return cursor;
    }

    // ---- reading one file, exactly as the old reconcile did ----

    /**
     * One rule file as the object it describes. Scope comes from the parent folder, the slug from
     * the filename; the front matter may carry {@code status}, {@code scope}, {@code source},
     * {@code title}, {@code document}, {@code check} and {@code checkTimeoutSeconds}. A file with
     * no {@code source} was written by hand.
     */
    static LearnedGuideline parse(Path file, UUID projectId) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            log.warn("Unreadable rule file {}: {}", file, e.getMessage());
            return null;
        }
        String slug = file.getFileName().toString().replaceFirst("\\.md$", "");
        GuidelineScope scope = scopeFromPath(file);
        GuidelineStatus status = GuidelineStatus.ACTIVE;
        String source = null;
        String title = null;
        String document = null;
        String check = null;
        int checkTimeout = 0;
        String body = content;

        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) {
                body = content.substring(end + 4);
                for (String rawLine : content.substring(3, end).split("\n")) {
                    String line = rawLine.strip();
                    if (line.startsWith("status:")) {
                        GuidelineStatus parsed = parseStatus(line.substring("status:".length()));
                        status = parsed == null ? status : parsed;
                    } else if (line.startsWith("scope:")) {
                        GuidelineScope parsed = parseScope(line.substring("scope:".length()));
                        scope = parsed == null ? scope : parsed;
                    } else if (line.startsWith("source:")) {
                        source = line.substring("source:".length()).strip().toLowerCase(Locale.ROOT);
                    } else if (line.startsWith("title:")) {
                        title = unquote(line.substring("title:".length()));
                    } else if (line.startsWith("document:")) {
                        document = unquote(line.substring("document:".length()));
                    } else if (line.startsWith("checkTimeoutSeconds:")) {
                        checkTimeout = parseInt(line.substring("checkTimeoutSeconds:".length()));
                    } else if (line.startsWith("check:")) {
                        check = parseCheck(file, line.substring("check:".length()).strip());
                    }
                }
            }
        }
        body = body.strip();
        if (body.isEmpty()) {
            return null;
        }
        boolean machine = ProjectRules.MACHINE_SOURCE.equals(source);
        String provenance = machine ? ProjectRules.MACHINE_SOURCE
            : ProjectRules.STATED_SOURCE.equals(source) ? ProjectRules.STATED_SOURCE
            : ProjectRules.HUMAN_SOURCE;
        LearnedGuideline rule = new LearnedGuideline(UUID.randomUUID(), 1, scope, slug, body,
            new Provenance(provenance, null, document),
            machine ? ProjectRules.MACHINE_INITIAL_CONFIDENCE : 1.0,
            Instant.now(), 0, status, projectId, machine ? null : check, machine ? 0 : checkTimeout);
        rule.setTitle(title);
        return rule;
    }

    private static String unquote(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isBlank() ? null : value;
    }

    private static String parseCheck(Path file, String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.startsWith("|") || raw.startsWith(">")) {
            log.warn("Rule file {} declares a multi-line 'check:' block, which is not supported — "
                + "the rule is imported without its check", file);
            return null;
        }
        return unquote(raw);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static GuidelineScope scopeFromPath(Path file) {
        Path parent = file.getParent();
        if (parent != null) {
            GuidelineScope scope = parseScope(parent.getFileName().toString());
            if (scope != null) {
                return scope;
            }
        }
        return GuidelineScope.PROJECT;
    }

    private static GuidelineScope parseScope(String value) {
        try {
            return GuidelineScope.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static GuidelineStatus parseStatus(String value) {
        try {
            return GuidelineStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
