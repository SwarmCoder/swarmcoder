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

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One rule a project is held to — a standing statement about HOW it must be built, as opposed to a
 * {@link BrdRequirement}, which says WHAT must be built.
 *
 * <p><b>It is a store object, and only a store object</b> (author decision 2026-09-02). Until then
 * a rule was a markdown file under {@code <repo>/.swarmcoder/guidelines/} and this class was an
 * index entry for it: the file was the persistence, the filename was the identity, and the store
 * was reconciled from the folder before every prompt. That was the one place SwarmCoder's founding
 * premise — everything the operator states is a Java object in the store, and the repository holds
 * only what the build executes — did not hold. It broke visibly: a project deleted and recreated
 * four times against the same folder kept every file through every deletion, and the folder held
 * 81 files for eleven rules. Now a rule is created, agreed, retired and DELETED WITH ITS PROJECT
 * exactly as a requirement is, its identity is its id, and two rules are the same rule when their
 * wording is the same — see {@code ProjectRules} for that comparison.
 *
 * <p><b>The class name is older than the design.</b> A rule here may have been LEARNED by a model
 * reading a failed build, STATED by a person in a technical document, or written by hand — the
 * {@link Provenance} says which, and nothing else about the object differs. The name stays because
 * EclipseStore records class names in its type dictionary and there is no legacy-type mapping in
 * this repository; renaming it would refuse to open every store that already holds one.
 */
@JsonTypeName("LearnedGuideline")
public class LearnedGuideline {
    private UUID id;
    private long revision;
    private GuidelineScope scope;
    /**
     * A short lower-case name ({@code the-stack-is-fixed}) derived from the title when the rule was
     * recorded. It NAMES the rule in log lines, check results and the judge's brief; it does not
     * identify it — two rules may share one, and the id is the identity.
     */
    private String slug;
    /**
     * A short human name for the rule, shown on the Guidelines screen and above the rule in every
     * prompt. Null on a rule that never had one — the slug is then the only name it has.
     */
    private String title;
    private String markdownBody;
    private Provenance provenance;
    private double confidence;
    private Instant lastUsed;
    private int useCount;
    private GuidelineStatus status;
    /**
     * The project this rule belongs to. A rule is deleted with its project, and one project never
     * sees another's. Null only on an entry written before ownership existed (before §21), which
     * no project can reach any more and which the store sweeps at start-up.
     */
    private UUID projectId;
    /**
     * The shell command that proves this rule was obeyed, or null when it is advice only.
     * A candidate whose workspace fails this command does NOT survive verification.
     * Honoured only for a rule a person decided — see {@code ProjectRules.activeChecks}.
     */
    private String checkCommand;
    /** Per-check timeout; 0 selects {@link GuidelineCheck#DEFAULT_TIMEOUT_SECONDS}. */
    private int checkTimeoutSeconds;
    /**
     * Why this rule exists, in one line — the problem it is there to prevent. Null when nobody
     * said (every rule recorded before 2026-10-01, and one written by hand without one).
     *
     * <p><b>Why a rule needs one</b> (harness runs 53 and 55, 2026-10-01). The judge held every
     * candidate to each rule's literal words. "Every type that crosses the wire is a
     * {@code @DataModel}" exists so the wire can serialize what is sent; the stack serializes enums
     * natively, so an unannotated enum causes no such problem — yet every candidate that left one
     * unannotated was marked as breaking the rule. Told the purpose, the judge flags a break only
     * when the change causes the problem the rule exists to prevent.
     */
    private String purpose;
    /**
     * True for a HARD rule — one the document states as a MUST, a prohibition or a fixed part of
     * the stack — whose break may stop a task. False, the default for anything nobody classified,
     * is a PREFERENCE: breaking it lowers the judge's score and never removes or parks anything.
     */
    private boolean hard;
    /**
     * The rule's wording as it was first stated, kept when an operator's answer (or the unattended
     * policy) reworded it. Null for a rule never reworded. Matched as well as the current wording
     * when the same document is applied again, so a reworded rule is not resurrected in its old
     * words alongside the new ones — the answer is remembered for the project.
     */
    private String statedWording;
    /**
     * The parts of the project this rule applies to, as folders from the repository root
     * ({@code client}, {@code server/src/main/java/app/store}); null or empty means the whole
     * project (owner's decision 2026-10-07, section 65). Null on every rule recorded before that
     * day. A worker is sent a rule only when one of these covers a path it may write; whoever
     * plans or checks work reads every rule. See {@link RuleScope} for what "covers" means and
     * {@code ProjectRules} for how an entry is checked against the project's tree before it is
     * kept. A mutable list, so the store reads it back with its own handler.
     */
    private ArrayList<String> appliesTo;

    public LearnedGuideline() {}

    public LearnedGuideline(UUID id, long revision, GuidelineScope scope, String slug, String markdownBody, Provenance provenance, double confidence, Instant lastUsed, int useCount, GuidelineStatus status) {
        this(id, revision, scope, slug, markdownBody, provenance, confidence, lastUsed, useCount, status, null, null, 0);
    }

    public LearnedGuideline(UUID id, long revision, GuidelineScope scope, String slug, String markdownBody, Provenance provenance, double confidence, Instant lastUsed, int useCount, GuidelineStatus status, UUID projectId, String checkCommand, int checkTimeoutSeconds) {
        this.projectId = projectId;
        this.checkCommand = checkCommand;
        this.checkTimeoutSeconds = checkTimeoutSeconds;
        this.id = id;
        this.revision = revision;
        this.scope = scope;
        this.slug = slug;
        this.markdownBody = markdownBody;
        this.provenance = provenance;
        this.confidence = confidence;
        this.lastUsed = lastUsed;
        this.useCount = useCount;
        this.status = status;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long revision() { return revision; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public GuidelineScope scope() { return scope; }
    public GuidelineScope getScope() { return scope; }
    public void setScope(GuidelineScope scope) { this.scope = scope; }
    public String slug() { return slug; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String markdownBody() { return markdownBody; }
    public String getMarkdownBody() { return markdownBody; }
    public void setMarkdownBody(String markdownBody) { this.markdownBody = markdownBody; }
    public Provenance provenance() { return provenance; }
    public Provenance getProvenance() { return provenance; }
    public void setProvenance(Provenance provenance) { this.provenance = provenance; }
    public double confidence() { return confidence; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public Instant lastUsed() { return lastUsed; }
    public Instant getLastUsed() { return lastUsed; }
    public void setLastUsed(Instant lastUsed) { this.lastUsed = lastUsed; }
    public int useCount() { return useCount; }
    public int getUseCount() { return useCount; }
    public void setUseCount(int useCount) { this.useCount = useCount; }
    public GuidelineStatus status() { return status; }
    public GuidelineStatus getStatus() { return status; }
    public void setStatus(GuidelineStatus status) { this.status = status; }
    public UUID projectId() { return projectId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String checkCommand() { return checkCommand; }
    public String getCheckCommand() { return checkCommand; }
    public void setCheckCommand(String checkCommand) { this.checkCommand = checkCommand; }
    public int checkTimeoutSeconds() { return checkTimeoutSeconds; }
    public int getCheckTimeoutSeconds() { return checkTimeoutSeconds; }
    public void setCheckTimeoutSeconds(int checkTimeoutSeconds) { this.checkTimeoutSeconds = checkTimeoutSeconds; }

    public String purpose() { return purpose; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public boolean hard() { return hard; }
    public boolean isHard() { return hard; }
    public void setHard(boolean hard) { this.hard = hard; }
    public String statedWording() { return statedWording; }
    public String getStatedWording() { return statedWording; }
    public void setStatedWording(String statedWording) { this.statedWording = statedWording; }
    /** Never null; empty for a rule of the whole project. */
    public List<String> appliesTo() { return appliesTo == null ? List.of() : List.copyOf(appliesTo); }
    public List<String> getAppliesTo() { return appliesTo(); }
    public void setAppliesTo(List<String> appliesTo) {
        this.appliesTo = appliesTo == null || appliesTo.isEmpty() ? null : new ArrayList<>(appliesTo);
    }

    /**
     * Copies what a rule MEANS — its title, purpose, strength, first wording and the parts of the
     * project it applies to — from an earlier
     * revision of it. Every place that rebuilds a rule through the constructor calls this, for the
     * same reason {@code CandidateSolution.carryingAuditFrom} exists: a field outside the argument
     * list is silently dropped by whoever forgets it.
     */
    public LearnedGuideline carryingMeaningFrom(LearnedGuideline earlier) {
        if (earlier != null) {
            this.title = earlier.title();
            this.purpose = earlier.purpose();
            this.hard = earlier.hard();
            this.statedWording = earlier.statedWording();
            setAppliesTo(earlier.appliesTo());
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LearnedGuideline that = (LearnedGuideline) o;
        return Objects.equals(this.id, that.id) && this.revision == that.revision && Objects.equals(this.scope, that.scope) && Objects.equals(this.slug, that.slug) && Objects.equals(this.title, that.title) && Objects.equals(this.markdownBody, that.markdownBody) && Objects.equals(this.provenance, that.provenance) && this.confidence == that.confidence && Objects.equals(this.lastUsed, that.lastUsed) && this.useCount == that.useCount && Objects.equals(this.status, that.status) && Objects.equals(this.projectId, that.projectId) && Objects.equals(this.checkCommand, that.checkCommand) && this.checkTimeoutSeconds == that.checkTimeoutSeconds && Objects.equals(this.purpose, that.purpose) && this.hard == that.hard && Objects.equals(this.statedWording, that.statedWording) && Objects.equals(appliesTo(), that.appliesTo());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision, scope, slug, title, markdownBody, provenance, confidence, lastUsed, useCount, status, projectId, checkCommand, checkTimeoutSeconds, purpose, hard, statedWording, appliesTo());
    }
}

