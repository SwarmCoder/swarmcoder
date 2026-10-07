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

import java.util.Objects;

/**
 * What one guideline's proof command did in one candidate's workspace.
 *
 * <p>Persisted inside the {@link VerificationReport}, so the operator can see months later WHICH
 * rule a candidate broke and what the command said — not merely that "verification failed".
 * A report written before this existed has no results at all, which reads as "no rule declared a
 * check", never as "a rule was broken".
 */
@JsonTypeName("GuidelineCheckResult")
public class GuidelineCheckResult {

    private String slug;
    private String scope;
    private String rule;
    private String command;
    /** False when the command could not be run at all (exec target unreachable). */
    private boolean ran;
    private boolean passed;
    private int exitCode;
    private boolean timedOut;
    /** Last few lines the command printed — the actionable part of a failure. */
    private String outputTail;

    public GuidelineCheckResult() {}

    public GuidelineCheckResult(String slug, String scope, String rule, String command,
                                boolean ran, boolean passed, int exitCode, boolean timedOut,
                                String outputTail) {
        this.slug = slug;
        this.scope = scope;
        this.rule = rule;
        this.command = command;
        this.ran = ran;
        this.passed = passed;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.outputTail = outputTail;
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
    public boolean ran() { return ran; }
    public boolean getRan() { return ran; }
    public void setRan(boolean ran) { this.ran = ran; }
    public boolean passed() { return passed; }
    public boolean getPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
    public int exitCode() { return exitCode; }
    public int getExitCode() { return exitCode; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }
    public boolean timedOut() { return timedOut; }
    public boolean getTimedOut() { return timedOut; }
    public void setTimedOut(boolean timedOut) { this.timedOut = timedOut; }
    public String outputTail() { return outputTail; }
    public String getOutputTail() { return outputTail; }
    public void setOutputTail(String outputTail) { this.outputTail = outputTail; }

    /** One line an operator can read without opening anything else. */
    public String describe() {
        StringBuilder sb = new StringBuilder(passed ? "obeyed" : "BROKEN").append(": ")
            .append(scope == null ? "" : scope.toLowerCase()).append('/').append(slug);
        if (rule != null && !rule.isBlank()) {
            String text = rule.strip().replace('\n', ' ');
            sb.append(" — \"").append(text.length() <= 160 ? text : text.substring(0, 160) + "…")
              .append('"');
        }
        if (!passed) {
            sb.append("\n    proved by: ").append(command);
            if (!ran) {
                sb.append("\n    the check could not be run at all");
            } else if (timedOut) {
                sb.append("\n    the check timed out");
            } else {
                sb.append("\n    exit ").append(exitCode);
            }
            if (outputTail != null && !outputTail.isBlank()) {
                sb.append("\n    ").append(outputTail.strip().replace("\n", "\n    "));
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GuidelineCheckResult that = (GuidelineCheckResult) o;
        return Objects.equals(this.slug, that.slug) && Objects.equals(this.scope, that.scope)
            && Objects.equals(this.rule, that.rule) && Objects.equals(this.command, that.command)
            && this.ran == that.ran && this.passed == that.passed && this.exitCode == that.exitCode
            && this.timedOut == that.timedOut && Objects.equals(this.outputTail, that.outputTail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slug, scope, rule, command, ran, passed, exitCode, timedOut, outputTail);
    }
}
