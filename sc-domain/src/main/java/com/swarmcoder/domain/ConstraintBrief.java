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
package com.swarmcoder.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The project's standing rules, and the one wording of them that every agent is shown.
 *
 * <p><b>Why it exists.</b> Until 2026-08-31 a project could not state how it must be built.
 * Requirements were business-only, so the architect planned knowing nothing about the technology
 * and every worker rediscovered the stack alone. Against {@code dev/bookshelf-demo} — pure Java, a
 * TeaVM-compiled browser client, binary WebSocket calls, an EclipseStore object graph, and not one
 * line of Spring, JPA, SQL, REST or JavaScript anywhere in it — the test author, told nothing,
 * wrote acceptance tests asserting a {@code @SpringBootApplication} class, a Flyway migration
 * creating a USERS table, and a Spring Data {@code JpaRepository} over a {@code @Entity}. None of
 * those exist or ever will, so those tests could never pass and every candidate would fail for
 * ever. The model had not misunderstood anything: with no technical context it fell back on the
 * most statistically common Java web stack, which is exactly what a model does when it is told
 * nothing.
 *
 * <p><b>What it renders, and why that changed.</b> This used to render a third kind of BRD
 * requirement. It renders {@link LearnedGuideline}s now (author decision 2026-08-31, the same day):
 * SwarmCoder already had a mechanism for "a rule this project must follow", with a scope, a status,
 * a provenance and a command that can prove one was obeyed. Building a second one inside the
 * requirements model needed nine separate exemptions to keep rules out of machinery that assumes
 * everything in it gets delivered — and every one of those exemptions was the system saying the
 * rules did not belong there. (The rules were files then; since 2026-09-02 they are store objects.
 * Nothing here changed for that — this class renders objects and never knew about the files.)
 *
 * <p><b>One rendering, used everywhere.</b> The architect at DESIGN and PLAN, the test author, and
 * every worker in every swarm are handed the same text from here — {@code ProjectRules.renderActive}
 * calls it too, so the worker's copy cannot drift from the planner's. Three separately worded
 * briefings would drift, and a rule the planner honoured and the worker never saw is the same
 * defect with extra steps.
 *
 * <p>The text is deliberately blunt about what a rule is NOT: it is not a feature, nothing delivers
 * it, and there is no test that finishes it. That sentence is what stops a planner dutifully
 * inventing a task called "make the project pure Java".
 */
public final class ConstraintBrief {

    /** Enough for a real technical document; a longer set is truncated rather than silently cut. */
    public static final int DEFAULT_MAX_CHARS = 12_000;

    private static final String HEADER =
        "HOW THIS PROJECT MUST BE BUILT — read this before you decide anything.\n\n"
        + "These are the project's standing rules. They are NOT features. Nothing delivers them, "
        + "no task is assigned one, and there is no test that finishes one. They apply to every "
        + "piece of work on this project, every time, and work that breaks one is wrong even when "
        + "it passes its own tests.\n\n"
        + "This project is very unlikely to be the usual one. Where a rule below contradicts what "
        + "a project of this kind normally does, the rule wins and the habit is the mistake. If a "
        + "piece of work seems to need something a rule forbids, say so rather than doing it.\n\n";

    /**
     * The line a HARD rule's entry carries, and the only one. Whoever is handed the rendered text
     * rather than the rules themselves (the workflow is) reads a rule's strength off this line, so
     * the wording lives here once.
     */
    public static final String HARD_RULE_LINE = "A HARD rule: breaking it stops the work.";

    private ConstraintBrief() {}

    /**
     * The order rules in force are rendered in: most specific scope first, then the surest, then
     * by name and finally by id, so a prefix hash cannot depend on map iteration.
     */
    public static final Comparator<LearnedGuideline> IN_FORCE_ORDER = Comparator
        .comparingInt((LearnedGuideline g) -> scopeRank(g.scope())).reversed()
        .thenComparing(Comparator.comparingDouble(LearnedGuideline::confidence).reversed())
        .thenComparing(g -> g.slug() == null ? "" : g.slug())
        .thenComparing(g -> g.id() == null ? "" : g.id().toString());

    /**
     * One project's ACTIVE rules out of every rule in the store, in {@link #IN_FORCE_ORDER} — THE
     * definition of "in force". {@code ProjectRules} (sc-knowledge) answers with this for every
     * agent it briefs, and the Console's story planner, which cannot depend on sc-knowledge, calls
     * it directly over the same store (harness run 39, 2026-09-25: the story planner was the one
     * agent never shown the rules, and wrote "save to localStorage" into a story for a project
     * whose rules mandate server-side EclipseStore). One definition, so the story planner and the
     * workers can never be shown different sets.
     */
    public static List<LearnedGuideline> inForce(Collection<LearnedGuideline> all, UUID projectId) {
        List<LearnedGuideline> active = new ArrayList<>();
        if (all == null || projectId == null) {
            return active;
        }
        for (LearnedGuideline rule : all) {
            if (rule != null && rule.status() == GuidelineStatus.ACTIVE
                    && projectId.equals(rule.projectId())) {
                active.add(rule);
            }
        }
        active.sort(IN_FORCE_ORDER);
        return active;
    }

    private static int scopeRank(GuidelineScope scope) {
        if (scope == null) {
            return 1;
        }
        return switch (scope) {
            case TASK_FAMILY -> 2;
            case PROJECT -> 1;
            case GLOBAL -> 0;
        };
    }

    /** The briefing for a set of live rules, at the default size. */
    public static String render(List<LearnedGuideline> rules) {
        return render(rules, DEFAULT_MAX_CHARS);
    }

    /**
     * The briefing for a set of live rules; empty when there are none.
     *
     * <p>Empty means empty: a prompt with no rules is byte-for-byte the prompt it was before this
     * feature existed. Nothing is ever handed a heading with nothing under it, and no agent is told
     * "there are no rules", which reads as permission.
     *
     * <p>The caller decides which rules are live. It is handed a resolved list rather than a store
     * because ownership, scope shadowing and status are {@code ProjectRules}'s business, and two
     * places deciding what "in force" means is how the planner and the worker come to disagree.
     */
    public static String render(List<LearnedGuideline> rules, int maxChars) {
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        int cap = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        StringBuilder sb = new StringBuilder(HEADER);
        int written = 0;
        for (LearnedGuideline rule : rules) {
            if (rule == null) {
                continue;
            }
            String entry = entryFor(rule);
            if (entry.isEmpty()) {
                continue;
            }
            if (sb.length() + entry.length() > cap) {
                sb.append("  … and ").append(rules.size() - written)
                    .append(" further rule(s) not shown — the rule set is over ")
                    .append(cap).append(" characters.\n");
                break;
            }
            sb.append(entry);
            written++;
        }
        return written == 0 ? "" : sb.toString();
    }

    /**
     * One rule as the agent reads it: its name, its wording, and the command that will decide it.
     *
     * <p>A rule that declares a proof command says so. Telling an agent that a command will decide
     * the matter is not a courtesy — an unstated check is a trap, and the agent can run the command
     * itself before reporting done.
     */
    private static String entryFor(LearnedGuideline rule) {
        String name = rule.title() != null && !rule.title().isBlank()
            ? rule.title().strip()
            : rule.slug() == null ? "" : rule.slug().strip();
        String body = rule.markdownBody() == null ? "" : rule.markdownBody().strip();
        if (name.isEmpty() && body.isEmpty()) {
            return "";
        }
        StringBuilder entry = new StringBuilder("- ").append(name.isEmpty() ? body : name)
            .append('\n');
        if (!name.isEmpty() && !body.isEmpty()) {
            entry.append("  ").append(body.replace("\n", "\n  ")).append('\n');
        }
        // Why the rule exists, and how hard it is (harness runs 53 and 55, 2026-10-01): a rule
        // held to its literal words parked two runs over behaviour that caused none of the harm it
        // exists to prevent. Written only for a rule somebody classified, so a rule recorded
        // before this existed renders exactly as it did.
        if (rule.purpose() != null && !rule.purpose().isBlank()) {
            entry.append("  Why it exists: ").append(rule.purpose().strip().replace("\n", " "))
                .append('\n');
        }
        if (rule.hard()) {
            entry.append("  ").append(HARD_RULE_LINE).append('\n');
        } else if (rule.purpose() != null && !rule.purpose().isBlank()) {
            entry.append("  A preference: follow it where it fits; breaking it costs quality, "
                + "never the work.\n");
        }
        if (rule.checkCommand() != null && !rule.checkCommand().isBlank()) {
            entry.append("  This rule is CHECKED. A candidate that fails this command does not "
                + "pass verification: ").append(rule.checkCommand().strip()).append('\n');
        }
        return entry.toString();
    }
}
