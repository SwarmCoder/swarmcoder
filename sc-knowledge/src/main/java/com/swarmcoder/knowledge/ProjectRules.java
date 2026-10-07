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

import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.RuleScope;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One project's rules, in the store and nowhere else (author decision 2026-09-02).
 *
 * <p><b>What this replaced.</b> Rules used to be markdown files under
 * {@code <repo>/.swarmcoder/guidelines/}, reconciled into the store before every prompt: the file
 * was the persistence, the filename the identity, the store an index. It was the one place the
 * founding premise — everything the operator states is a Java object in the store, and the repo
 * holds only what the build executes — did not hold, and it failed in the open: applying one
 * technical document four times produced four differently-named copies of its rules, and deleting
 * the project deleted none of them, because the files were never the project's. This class is the
 * whole rule mechanism now. Nothing in it reads or writes a file.
 *
 * <p><b>Identity is wording.</b> A rule is the same rule when its text is the same, whitespace and
 * case aside ({@link #contentKey}). Stating a rule whose wording already exists — whatever its
 * status, whatever it was called — brings THAT rule back into force rather than adding another.
 * That is what lets a document be applied again and again and leave exactly one copy of each
 * thing it says: {@link #supersedeRulesFrom} retires the document's previous statement, and the
 * rules it still contains come straight back under their old ids.
 *
 * <p><b>Who decided a rule matters more than what it says.</b> {@link Provenance#source()} is
 * {@code stated} for a rule a person applied from a document, {@code human} for one written by
 * hand, and {@code extraction} for one a model proposed after a failed build. A machine proposal
 * arrives PROPOSED and enters no prompt until a person switches it on; it starts at half confidence
 * and decays; and its check command, if it ever has one, is ignored — a command a model wrote
 * from a worker's transcript would put the restrained party inside the policy tree (§13.1).
 *
 * <p>The rendering every agent reads comes from {@link ConstraintBrief}; this class only decides
 * WHICH rules are in force, so the planner, the test author, the workers and the judge cannot come
 * to disagree about it.
 */
public final class ProjectRules {

    private static final Logger log = LoggerFactory.getLogger(ProjectRules.class);
    private static final int DEFAULT_MAX_PREFIX_CHARS = 12_000; // approx 3K tokens (config default)

    /** A machine proposal is a suggestion, not a decision — and it decays (spec §12.4). */
    static final double MACHINE_INITIAL_CONFIDENCE = 0.5;

    /** A model proposed this after reading a failed build's transcripts. */
    public static final String MACHINE_SOURCE = "extraction";
    /** A person stated this in a document, ticked the document as technical, and applied it. */
    public static final String STATED_SOURCE = "stated";
    /** A person wrote this by hand — on the Guidelines screen, or in a rule file that was imported. */
    public static final String HUMAN_SOURCE = "human";

    private final ArtifactStore store;
    private final UUID projectId;
    /**
     * The parts of this project as its tree holds them now (section 65). Null until wired:
     * nothing is known then, every rule is recorded for the whole project and every worker is
     * sent every rule.
     */
    private volatile Supplier<ProjectParts> parts;

    /** @param projectId the project whose rules these are; never null */
    public ProjectRules(ArtifactStore store, UUID projectId) {
        this.store = Objects.requireNonNull(store, "store");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
    }

    public UUID projectId() {
        return projectId;
    }

    /**
     * Says how a rule's scope is checked: against the parts of this project, read from its tree
     * and never from a model ({@link ProjectParts}). Asked again on every use, because a run
     * adds folders and a person renames them.
     */
    public void setParts(Supplier<ProjectParts> parts) {
        this.parts = parts;
    }

    private ProjectParts partsNow() {
        Supplier<ProjectParts> supplier = parts;
        ProjectParts now = supplier == null ? null : supplier.get();
        return now;
    }

    /** The module folders a rule may be stated for; empty when no tree is connected. */
    public List<String> modules() {
        ProjectParts now = partsNow();
        return now == null ? List.of() : now.modules();
    }

    /**
     * The rules a WORKER is sent for a task that may write {@code paths}: the rules of the whole
     * project and the rules whose scope covers one of the paths, rendered as every agent reads
     * them ({@link ConstraintBrief}).
     *
     * <p>A rule whose recorded folder the tree no longer holds (renamed, deleted) is sent to
     * everyone: it must never be a rule nobody is told. With no tree connected every rule is
     * sent. This narrows what a worker is TOLD and nothing else: {@link #activeRules} and
     * {@link #activeChecks}, which the judge and verification read, are not narrowed.
     */
    public synchronized RuleScope.Briefing briefingFor(Collection<String> paths, int maxChars) {
        List<LearnedGuideline> active = activeForThisProject();
        ProjectParts now = partsNow();
        RuleScope.Selection chosen = now == null
            ? new RuleScope.Selection(active, active.size())
            : RuleScope.forPaths(active, paths, now::holds);
        int cap = maxChars > 0 ? maxChars : DEFAULT_MAX_PREFIX_CHARS;
        String text = ConstraintBrief.render(chosen.sent(), cap);
        return new RuleScope.Briefing(text.isEmpty() ? null : text, chosen.sent().size(),
            chosen.inForce());
    }

    // ---- what a person does ----

    /**
     * Records a rule a PERSON stated — the route a technical document takes into this project.
     *
     * <p><b>It arrives ACTIVE, and that is the whole point</b> (author decision 2026-08-31). By the
     * time this is called the operator has written the rule, attached the document, ticked that
     * the document says how the system must be built, read the analyst's proposal back, and ticked
     * it to apply — five deliberate acts. A sixth switch, on a screen they have not opened, between
     * all of that and any effect is a dead end, not a safeguard.
     *
     * <p><b>Stated again in the same words, it is the same rule.</b> The store is searched for this
     * wording first — every status, every provenance — and a match is brought back into force under
     * its own id rather than duplicated. This is what makes a restatement of a document idempotent
     * (see {@link #supersedeRulesFrom}): the rules the document still contains return, the ones it
     * dropped stay retired.
     *
     * @param title    a short name for the rule; becomes its heading, and its slug is derived from it
     * @param body     the rule in full, in the document's own words
     * @param document the filename the rule was read out of, recorded as its provenance; null for
     *                 a rule written by hand
     * @return "" on success, else "error: …" — the wording shown to the operator
     */
    public synchronized String stateRule(String title, String body, String document) {
        return stateRule(title, body, document, null);
    }

    /**
     * As above, and also keeps the document's OWN sentence(s) the rule was drawn from — verbatim,
     * not {@code body}'s restatement of them.
     *
     * <p><b>Why a rule's body is not enough on its own.</b> {@code body} is the analyst's
     * paraphrase of the document at intake, and a paraphrase can drop the one word — most often a
     * backticked artifact id — the document actually used: {@code dev/bookshelf-tech-requirements
     * .md} says "Persistence uses EclipseStore through {@code zerozstack-store-eclipsestore}", and
     * an analyst that states the rule as "Persistence via EclipseStore object graph" has, without
     * meaning to, thrown away the one thing {@link RulesVersusManifest} needs to catch a missing
     * dependency at PLAN. Keeping the source sentence alongside the paraphrase means a rule is
     * never ONLY a paraphrase.
     *
     * @param excerpt the document's own wording, verbatim; null when there is none to quote (a
     *                rule written by hand, or an analyst reply that carried none)
     */
    public synchronized String stateRule(String title, String body, String document,
                                         String excerpt) {
        return stateRule(title, body, document, excerpt, null, false);
    }

    /**
     * As above, and also records WHY the rule exists and whether it is HARD (harness runs 53 and
     * 55, 2026-10-01). The judge is told the purpose and flags a break only when the change causes
     * the problem the rule exists to prevent; only a hard rule's break can stop a task.
     *
     * @param purpose one line — the problem the rule prevents; null when the analyst gave none
     * @param hard    true when the document states it as a MUST, a prohibition or a fixed part of
     *                the stack; false (a preference) otherwise, and for anything unclassified
     */
    public synchronized String stateRule(String title, String body, String document,
                                         String excerpt, String purpose, boolean hard) {
        return stateRule(title, body, document, excerpt, purpose, hard, null);
    }

    /**
     * As above, and also records which part of the project the rule applies to (owner's decision
     * 2026-10-07, section 65), so a worker is sent it only when its task may write there.
     *
     * <p>Each entry is a folder from the repository root; a module is its folder. Every entry is
     * checked against the project's tree ({@link #setParts}). <b>If any entry is not a part
     * of the project, the rule is recorded for the whole project</b> and a warning names the
     * entry: see {@link RuleScope#checked} for why it is neither refused nor narrowed to the rest.
     * Stated again, a rule takes the scope of the new statement.
     *
     * @param appliesTo the folders the rule is about; null or empty is the whole project
     */
    public synchronized String stateRule(String title, String body, String document,
                                         String excerpt, String purpose, boolean hard,
                                         List<String> appliesTo) {
        ProjectParts now = partsNow();
        List<String> scope = RuleScope.checked(appliesTo, now == null ? null : now::holds);
        if (scope.isEmpty() && appliesTo != null
                && appliesTo.stream().anyMatch(a -> a != null && !a.isBlank())) {
            log.warn("Rule '{}' was stated for {}, which the project's tree does not hold{} - it "
                + "is recorded for the whole project and goes to every worker", title, appliesTo,
                now == null ? " (no tree is connected)" : "");
        }
        String why = purpose == null || purpose.isBlank() ? null : purpose.strip();
        String text = body == null ? "" : body.strip();
        String name = title == null ? "" : title.strip();
        if (text.isEmpty() && name.isEmpty()) {
            return "error: there is nothing in this rule to record";
        }
        if (text.isEmpty()) {
            text = name;
        }
        String source = document == null || document.isBlank() ? HUMAN_SOURCE : STATED_SOURCE;
        Provenance provenance = new Provenance(source, null,
            document == null || document.isBlank() ? null : document.strip(),
            excerpt == null || excerpt.isBlank() ? null : excerpt.strip());
        LearnedGuideline same = findByContent(text);
        if (same != null) {
            LearnedGuideline revived = next(same, GuidelineStatus.ACTIVE);
            revived.setProvenance(provenance);
            revived.setConfidence(1.0);
            if (!name.isEmpty()) {
                revived.setTitle(name);
            }
            if (why != null) {
                revived.setPurpose(why);
            }
            // Never downgraded by a restatement: a rule an operator kept as hard, after it stopped
            // a run, stays hard however the analyst reads the document next time.
            revived.setHard(revived.hard() || hard);
            revived.setAppliesTo(scope);
            put(revived);
            log.info("Rule '{}' was stated again in identical words and stays in force ({})",
                revived.title() == null ? revived.slug() : revived.title(), revived.id());
            return "";
        }
        String slug = slugFor(name.isEmpty() ? text : name);
        LearnedGuideline rule = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            slug, text, provenance, 1.0, Instant.now(), 0, GuidelineStatus.ACTIVE, projectId,
            null, 0);
        rule.setTitle(name.isEmpty() ? null : name);
        rule.setPurpose(why);
        rule.setHard(hard);
        rule.setAppliesTo(scope);
        put(rule);
        log.info("Rule '{}' stated from {} and in force now ({}), for {}",
            name.isEmpty() ? slug : name, document == null ? "an operator edit" : document,
            rule.id(), scope.isEmpty() ? "the whole project" : scope);
        return "";
    }

    /**
     * A document stated again REPLACES what it said before — it does not add to it.
     *
     * <p>Read a technical document twice and the analyst words its rules slightly differently each
     * time. Measured on the operator's live project: one document, applied four times over two
     * days, 44 rules in force where the document says about eleven things — and every copy went to
     * every worker, the architect, the test author and the judge. No similarity measure was going
     * to fix that. A document is one artifact: the rules in force from it are what it says NOW, so
     * this retires its previous statement, and the caller states the new one immediately after. A
     * rule whose wording did not change comes straight back — same id — through
     * {@link #stateRule}'s match on wording.
     *
     * <p><b>Retired, never deleted.</b> The Guidelines screen still lists them and any of them can
     * be switched back on, so a rule the analyst dropped this time round is one click away.
     *
     * @param document the filename whose earlier statement is superseded; null or blank does
     *                 nothing, because a rule with no document behind it belongs to nobody's
     *                 statement and must not be swept up by one
     * @return how many rules were retired
     */
    public synchronized int supersedeRulesFrom(String document) {
        if (document == null || document.isBlank()) {
            return 0;
        }
        String name = document.strip();
        int retired = 0;
        for (LearnedGuideline rule : activeForThisProject()) {
            Provenance provenance = rule.provenance();
            if (provenance != null && STATED_SOURCE.equals(provenance.source())
                    && name.equals(provenance.document())) {
                put(next(rule, GuidelineStatus.RETIRED));
                retired++;
            }
        }
        if (retired > 0) {
            log.info("'{}' is being stated again: {} rule(s) from its previous statement retired, "
                + "so the document's rules are what it says now rather than every version of it "
                + "ever read", name, retired);
        }
        return retired;
    }

    /**
     * Turns one rule on or off — the operator's act, from the Guidelines screen.
     *
     * @return "" on success, else "error: …" — the wording the Console shows the operator
     */
    public synchronized String setStatus(UUID ruleId, GuidelineStatus status) {
        if (ruleId == null || status == null) {
            return "error: which rule, and to what?";
        }
        LearnedGuideline rule = store.root().guidelines.get(ruleId);
        if (rule == null) {
            return "error: no such rule";
        }
        if (!ownedByThisProject(rule)) {
            return "error: that rule belongs to another project — open that project to change it";
        }
        if (rule.status() == status) {
            return "";
        }
        put(next(rule, status));
        log.info("Rule '{}' is now {}", rule.title() == null ? rule.slug() : rule.title(), status);
        return "";
    }

    /**
     * Gives a rule the command that proves it, or takes it away — the operator's act, from the
     * Guidelines screen.
     *
     * <p>A rule that declares a check is no longer advice: a candidate whose workspace fails the
     * command does not survive verification (§21). Only a rule a person decided may carry one —
     * see {@link #activeChecks} — so a machine proposal is refused here rather than accepted and
     * then silently ignored.
     *
     * @param command        a one-line shell command that exits 0 when the rule is obeyed; blank or
     *                       null removes the check
     * @param timeoutSeconds 0 selects {@link GuidelineCheck#DEFAULT_TIMEOUT_SECONDS}
     * @return "" on success, else "error: …"
     */
    public synchronized String setCheck(UUID ruleId, String command, int timeoutSeconds) {
        if (ruleId == null) {
            return "error: which rule?";
        }
        LearnedGuideline rule = store.root().guidelines.get(ruleId);
        if (rule == null) {
            return "error: no such rule";
        }
        if (!ownedByThisProject(rule)) {
            return "error: that rule belongs to another project — open that project to change it";
        }
        String wanted = command == null || command.isBlank() ? null : command.strip();
        if (wanted != null && !decidedByAPerson(rule)) {
            return "error: only a rule a person decided can carry a check — this one was proposed "
                + "by a model, so switch it on first";
        }
        if (wanted != null && wanted.contains("\n")) {
            return "error: a check is one line — chain commands with &&";
        }
        int timeout = Math.max(0, timeoutSeconds);
        if (Objects.equals(rule.checkCommand(), wanted) && rule.checkTimeoutSeconds() == timeout) {
            return "";
        }
        LearnedGuideline changed = next(rule, rule.status());
        changed.setCheckCommand(wanted);
        changed.setCheckTimeoutSeconds(wanted == null ? 0 : timeout);
        put(changed);
        log.info("Rule '{}' {}", rule.title() == null ? rule.slug() : rule.title(),
            wanted == null ? "no longer declares a check" : "is now checked by: " + wanted);
        return "";
    }

    // ---- answers to a question about a rule (harness runs 53 and 55, 2026-10-01) ----

    /**
     * Rewords a rule, keeping its id — the answer "reword" to a question about the rule, from the
     * operator or from the unattended policy.
     *
     * <p>The first wording is kept on the rule ({@link LearnedGuideline#statedWording()}) and
     * matched alongside the new one, so applying the same technical document again brings THIS
     * rule back in its new words rather than resurrecting the old ones beside it.
     *
     * @return "" on success (including when the rule already says exactly this), else "error: …"
     */
    public synchronized String reword(UUID ruleId, String newWording, String why) {
        LearnedGuideline rule = ownRule(ruleId);
        if (rule == null) {
            return "error: no such rule in this project";
        }
        String text = newWording == null ? "" : newWording.strip();
        if (text.isEmpty()) {
            return "error: a rule cannot be reworded to nothing";
        }
        if (contentKey(text).equals(contentKey(rule.markdownBody()))) {
            return "";
        }
        LearnedGuideline changed = next(rule, rule.status());
        changed.setMarkdownBody(text);
        if (changed.statedWording() == null) {
            changed.setStatedWording(rule.markdownBody());
        }
        put(changed);
        log.warn("Rule '{}' reworded{}: \"{}\"", nameOf(rule),
            why == null || why.isBlank() ? "" : " (" + why.strip() + ")", text);
        return "";
    }

    /**
     * Adds one exception to a rule — the answer "allow in this case". The rule keeps its words
     * and gains a line saying what is allowed and where, so the next task is told too.
     *
     * @return "" on success (including when the rule already carries this exception), else "error: …"
     */
    public synchronized String allowException(UUID ruleId, String exception) {
        LearnedGuideline rule = ownRule(ruleId);
        if (rule == null) {
            return "error: no such rule in this project";
        }
        String line = exception == null ? "" : exception.strip().replace("\n", " ");
        if (line.isEmpty()) {
            return "error: an exception has to say what it allows";
        }
        String body = rule.markdownBody() == null ? "" : rule.markdownBody().strip();
        if (body.contains(line)) {
            return "";
        }
        LearnedGuideline changed = next(rule, rule.status());
        changed.setMarkdownBody(body + "\nException: " + line);
        if (changed.statedWording() == null) {
            changed.setStatedWording(rule.markdownBody());
        }
        put(changed);
        log.warn("Rule '{}' gained an exception: {}", nameOf(rule), line);
        return "";
    }

    /**
     * The answer "keep": the rule stands as written, and is recorded as HARD — an operator who was
     * asked about it and kept it has said it is not a preference.
     *
     * @return "" on success, else "error: …"
     */
    public synchronized String keep(UUID ruleId) {
        LearnedGuideline rule = ownRule(ruleId);
        if (rule == null) {
            return "error: no such rule in this project";
        }
        if (rule.hard()) {
            return "";
        }
        LearnedGuideline changed = next(rule, rule.status());
        changed.setHard(true);
        put(changed);
        log.info("Rule '{}' kept as written, and recorded as a hard rule", nameOf(rule));
        return "";
    }

    private LearnedGuideline ownRule(UUID ruleId) {
        LearnedGuideline rule = ruleId == null ? null : store.root().guidelines.get(ruleId);
        return rule != null && ownedByThisProject(rule) ? rule : null;
    }

    private static String nameOf(LearnedGuideline rule) {
        return rule.title() == null ? rule.slug() : rule.title();
    }

    // ---- what a machine does ----

    /**
     * Records a rule a MODEL proposed after reading a failed build. Nothing is written when this
     * project already has the same wording, whatever its status.
     *
     * @param inForce true writes it ACTIVE ({@code guidelines.autoPromote}); false, the default,
     *                writes it PROPOSED, where it enters no prompt until a person decides
     * @return the rule written, or null when the wording already existed
     */
    public synchronized LearnedGuideline propose(String slug, String body, boolean inForce) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (findByContent(text) != null) {
            return null;
        }
        String name = slug == null || slug.isBlank() ? slugFor(text) : slug.strip();
        LearnedGuideline rule = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            name, text, new Provenance(MACHINE_SOURCE, null), MACHINE_INITIAL_CONFIDENCE,
            Instant.now(), 0, inForce ? GuidelineStatus.ACTIVE : GuidelineStatus.PROPOSED,
            projectId, null, 0);
        put(rule);
        return rule;
    }

    // ---- what every agent reads ----

    /**
     * The ACTIVE rules rendered for the shared prefix, or null when there are none. Deterministic,
     * so prefix-cache alignment holds; the wording is {@link ConstraintBrief}'s.
     */
    public String renderActive(int maxChars) {
        int cap = maxChars > 0 ? maxChars : DEFAULT_MAX_PREFIX_CHARS;
        String brief = ConstraintBrief.render(activeRules(), cap);
        return brief.isEmpty() ? null : brief;
    }

    /**
     * This project's ACTIVE rules as objects — the same resolved list {@link #renderActive} turns
     * into text — for the judge, whose window holds a fraction of a real rulebook and which has to
     * choose by who decided a rule and whether it bears on the diff in front of it.
     */
    public synchronized List<LearnedGuideline> activeRules() {
        return activeForThisProject();
    }

    /**
     * The proof commands the ACTIVE rules declare — what verification runs (§21).
     *
     * <p>Only a rule a person decided may carry one. A machine proposal keeps its text and loses
     * its command: the text was written by a model reading worker transcripts, and letting that
     * path end in a command the orchestrator executes would put the restrained party back inside
     * the policy tree, which is the mistake §13.1 exists to stop being made again.
     */
    public synchronized List<GuidelineCheck> activeChecks() {
        List<GuidelineCheck> checks = new ArrayList<>();
        for (LearnedGuideline rule : activeForThisProject()) {
            if (rule.checkCommand() == null || rule.checkCommand().isBlank()) {
                continue;
            }
            if (!decidedByAPerson(rule)) {
                log.warn("Rule '{}' declares a check but was machine-proposed — the check is "
                    + "IGNORED until a person takes it on", rule.slug());
                continue;
            }
            checks.add(new GuidelineCheck(rule.slug(),
                rule.scope() == null ? "PROJECT" : rule.scope().name(),
                rule.markdownBody(), rule.checkCommand().strip(), rule.checkTimeoutSeconds()));
        }
        return checks;
    }

    /** Every rule of this project, of every status, in a stable order — the Guidelines screen. */
    public synchronized List<LearnedGuideline> all() {
        List<LearnedGuideline> rules = new ArrayList<>();
        for (LearnedGuideline rule : store.root().guidelines.values()) {
            if (ownedByThisProject(rule)) {
                rules.add(rule);
            }
        }
        rules.sort(ORDER);
        return rules;
    }

    // ---- identity ----

    /**
     * The wording of a rule, reduced to what makes two rules the same rule: case and whitespace
     * do not, and neither does a trailing full stop. Nothing cleverer — a rule reworded is a
     * different rule here, and it is {@link #supersedeRulesFrom} that keeps rewordings from
     * piling up.
     */
    public static String contentKey(String body) {
        if (body == null) {
            return "";
        }
        String key = body.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        while (key.endsWith(".")) {
            key = key.substring(0, key.length() - 1).strip();
        }
        return key;
    }

    /** A short name from a rule's title: lower case, words joined by hyphens, nothing exotic. */
    static String slugFor(String title) {
        String slug = title.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "rule-" + UUID.randomUUID().toString().substring(0, 8) : slug;
    }

    /** A person decided this rule — by stating it in a document, or by writing it themselves. */
    static boolean decidedByAPerson(LearnedGuideline rule) {
        if (rule.provenance() == null) {
            return false;
        }
        String source = rule.provenance().source();
        return HUMAN_SOURCE.equals(source) || STATED_SOURCE.equals(source);
    }

    // ---- store access; the import uses these too ----

    private LearnedGuideline findByContent(String body) {
        String key = contentKey(body);
        LearnedGuideline found = null;
        for (LearnedGuideline rule : all()) {
            // A reworded rule is still the rule its first wording named — see #reword.
            if (key.equals(contentKey(rule.markdownBody()))
                    || rule.statedWording() != null && key.equals(contentKey(rule.statedWording()))) {
                // Several may match on a store that predates identity-by-wording; the stable
                // order makes the choice repeatable, and the import collapses the rest.
                if (found == null || rule.status() == GuidelineStatus.ACTIVE
                        && found.status() != GuidelineStatus.ACTIVE) {
                    found = rule;
                }
            }
        }
        return found;
    }

    /**
     * This project's ACTIVE rules in a deterministic order: most specific scope first, then the
     * surest, then by name and finally by id, so the prefix hash cannot depend on map iteration.
     */
    private List<LearnedGuideline> activeForThisProject() {
        // The one definition of "in force", shared with the Console's story planner, which cannot
        // depend on this module (harness run 39, 2026-09-25) — see ConstraintBrief#inForce.
        return ConstraintBrief.inForce(store.root().guidelines.values(), projectId);
    }

    private static final Comparator<LearnedGuideline> ORDER = ConstraintBrief.IN_FORCE_ORDER;

    private boolean ownedByThisProject(LearnedGuideline rule) {
        return projectId.equals(rule.projectId());
    }

    /** The same rule at the next revision with a new status; every other field carried over. */
    static LearnedGuideline next(LearnedGuideline g, GuidelineStatus status) {
        LearnedGuideline next = new LearnedGuideline(g.id(), g.revision() + 1, g.scope(), g.slug(),
            g.markdownBody(), g.provenance(), g.confidence(), g.lastUsed(), g.useCount(), status,
            g.projectId(), g.checkCommand(), g.checkTimeoutSeconds());
        return next.carryingMeaningFrom(g);
    }

    void put(LearnedGuideline rule) {
        try {
            store.append(() -> {
                store.root().guidelines.put(rule.id(), rule);
                return null;
            }).get();
        } catch (Exception e) {
            log.warn("Failed to persist rule {}: {}", rule.slug(), e.getMessage());
        }
    }

    void remove(UUID ruleId) {
        try {
            store.append(() -> {
                store.root().guidelines.remove(ruleId);
                return null;
            }).get();
        } catch (Exception e) {
            log.warn("Failed to remove rule {}: {}", ruleId, e.getMessage());
        }
    }
}
