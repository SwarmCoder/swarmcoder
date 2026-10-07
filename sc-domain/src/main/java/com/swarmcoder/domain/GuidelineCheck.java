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

import java.util.Objects;

/**
 * A house rule that carries a command proving whether it was obeyed (author decision, §21).
 *
 * <p>A guideline without one of these stays advice: it reaches the worker's prompt and the judge
 * weighs it. A guideline WITH one is enforced — a candidate whose workspace fails the command does
 * not survive verification, so it never reaches the judge and can never be merged.
 *
 * <p><b>This is a command the orchestrator executes, so where it comes from is a security
 * boundary</b>, exactly as {@code .swarmcoder/verify.yaml} is (DEVELOPER_CORRECTIONS §13.1: policy
 * must be resolved from a tree the restrained party cannot write). Two rules hold it:
 *
 * <ul>
 *   <li>The rule file is read from the OPERATOR'S checkout, never from a candidate's worktree, and
 *       {@code .swarmcoder/} is {@code PathPolicy.ALWAYS_PROTECTED} at both enforcement points —
 *       the worker's tools and the audit of the winning diff at integration.</li>
 *   <li>Only a HUMAN-authored rule may carry a command. A rule the extractor proposed
 *       ({@code source: extraction}) keeps its text and loses its command, because the text of a
 *       machine proposal is written by a model reading worker transcripts.</li>
 * </ul>
 *
 * <p>A POJO rather than a record only because everything in this package is: the store cannot
 * persist records, and the architecture test enforcing that is deliberately blanket over the whole
 * package. Nothing persists this one — it is an input to verification, resolved fresh per task.
 */
public class GuidelineCheck {

    /** Deliberately short: a rule check is meant to be cheaper than the test suite it precedes. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** The rule's file name without {@code .md}. */
    private String slug;
    /** GLOBAL / PROJECT / TASK_FAMILY, as a name. */
    private String scope;
    /** The rule's own words, for the failure message a human reads. */
    private String rule;
    /** Shell command run in the candidate's workspace; exit 0 means the rule was obeyed. */
    private String command;
    /** Per-check timeout; 0 or less selects {@link #DEFAULT_TIMEOUT_SECONDS}. */
    private int timeoutSeconds;

    public GuidelineCheck() {}

    public GuidelineCheck(String slug, String scope, String rule, String command, int timeoutSeconds) {
        this.slug = slug;
        this.scope = scope;
        this.rule = rule;
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String slug() { return slug; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String scope() { return scope; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String rule() { return rule; }
    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }
    public String command() { return command; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int effectiveTimeoutSeconds() {
        return timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    /** The rule in one line, for logs and prompts. */
    public String shortRule() {
        String text = rule == null ? "" : rule.strip().replace('\n', ' ');
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GuidelineCheck that = (GuidelineCheck) o;
        return Objects.equals(this.slug, that.slug) && Objects.equals(this.scope, that.scope)
            && Objects.equals(this.rule, that.rule) && Objects.equals(this.command, that.command)
            && this.timeoutSeconds == that.timeoutSeconds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(slug, scope, rule, command, timeoutSeconds);
    }
}
