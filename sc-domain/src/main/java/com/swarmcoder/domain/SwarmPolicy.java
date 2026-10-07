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

import com.zeroz4j.api.DataModel;

import java.util.List;
import java.util.Objects;

/** Nested inside {@link Task}, which travels on the wire, so this has to be serializable too. */
@DataModel
public class SwarmPolicy {
    private int n;
    private boolean splitAcrossFamilies;
    private double tempMin;
    private double tempMax;
    private List<String> personaIds;
    /**
     * Why this task has THIS many workers, in the operator's own words — "story S3 asks for 6",
     * "this project's own settings ask for 4", "the settings file asks for 4".
     *
     * <p>It rides on the policy rather than being logged and forgotten because the task graph IS
     * the run record: opening a finished run has to answer "why did this get four attempts?"
     * without anybody having kept the console output. Null on a policy built before sizing had
     * layers, and on the hand-built policies in tests. See {@link SwarmSizing}.
     */
    private String nSource;
    /**
     * How many tool turns each of this task's workers gets, resolved over the same three layers
     * as {@link #n} (story, then project, then the settings file's {@code budgets:} block). See
     * {@link TurnAllowance}. Zero means "nobody said", and the worker loop falls back to
     * {@link TurnAllowance#BUILT_IN_MAX_TOOL_TURNS}.
     *
     * <p>It rides here rather than on {@link TokenBudget} because the resolution is the SAME
     * resolution the worker count already goes through, at the same moments, and a second
     * mechanism for one more number is how a setting ends up reading as configurable while
     * nothing constructs the object that carries it — which is exactly what happened to
     * {@code TokenBudget.maxToolTurns} until 2026-09-01.
     */
    private int maxToolTurns;
    /** Why this task's workers get THIS many turns, in the operator's own words. Nullable. */
    private String turnSource;

    public SwarmPolicy() {}

    public SwarmPolicy(int n, boolean splitAcrossFamilies, double tempMin, double tempMax, List<String> personaIds) {
        this(n, splitAcrossFamilies, tempMin, tempMax, personaIds, null);
    }

    public SwarmPolicy(int n, boolean splitAcrossFamilies, double tempMin, double tempMax, List<String> personaIds, String nSource) {
        this.n = n;
        this.splitAcrossFamilies = splitAcrossFamilies;
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.personaIds = personaIds;
        this.nSource = nSource;
    }

    /** The same policy with a different worker count and the reason for it. */
    public SwarmPolicy withWorkers(SwarmSizing sizing) {
        SwarmPolicy copy = new SwarmPolicy(sizing.workersPerTask(), splitAcrossFamilies, tempMin,
            tempMax, personaIds, sizing.label());
        copy.maxToolTurns = maxToolTurns;
        copy.turnSource = turnSource;
        return copy;
    }

    /** The same policy with a different per-worker turn allowance and the reason for it. */
    public SwarmPolicy withTurns(TurnAllowance allowance) {
        SwarmPolicy copy = new SwarmPolicy(n, splitAcrossFamilies, tempMin, tempMax, personaIds,
            nSource);
        copy.maxToolTurns = allowance.maxToolTurns();
        copy.turnSource = allowance.label();
        return copy;
    }

    public int n() { return n; }
    public int getN() { return n; }
    public void setN(int n) { this.n = n; }
    public boolean splitAcrossFamilies() { return splitAcrossFamilies; }
    public boolean getSplitAcrossFamilies() { return splitAcrossFamilies; }
    public void setSplitAcrossFamilies(boolean splitAcrossFamilies) { this.splitAcrossFamilies = splitAcrossFamilies; }
    public double tempMin() { return tempMin; }
    public double getTempMin() { return tempMin; }
    public void setTempMin(double tempMin) { this.tempMin = tempMin; }
    public double tempMax() { return tempMax; }
    public double getTempMax() { return tempMax; }
    public void setTempMax(double tempMax) { this.tempMax = tempMax; }
    public List<String> personaIds() { return personaIds; }
    public List<String> getPersonaIds() { return personaIds; }
    public void setPersonaIds(List<String> personaIds) { this.personaIds = personaIds; }
    public int maxToolTurns() { return maxToolTurns; }
    public int getMaxToolTurns() { return maxToolTurns; }
    public void setMaxToolTurns(int maxToolTurns) { this.maxToolTurns = maxToolTurns; }
    public String turnSource() { return turnSource; }
    public String getTurnSource() { return turnSource; }
    public void setTurnSource(String turnSource) { this.turnSource = turnSource; }
    public String nSource() { return nSource; }
    public String getNSource() { return nSource; }
    public void setNSource(String nSource) { this.nSource = nSource; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SwarmPolicy that = (SwarmPolicy) o;
        return this.n == that.n && this.splitAcrossFamilies == that.splitAcrossFamilies && this.tempMin == that.tempMin && this.tempMax == that.tempMax && Objects.equals(this.personaIds, that.personaIds) && Objects.equals(this.nSource, that.nSource) && this.maxToolTurns == that.maxToolTurns && Objects.equals(this.turnSource, that.turnSource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(n, splitAcrossFamilies, tempMin, tempMax, personaIds, nSource,
            maxToolTurns, turnSource);
    }
}

