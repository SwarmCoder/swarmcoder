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

import java.util.Objects;

/** Nested inside {@link Task}, which travels on the wire, so this has to be serializable too. */
@DataModel
public class TokenBudget {
    /**
     * <b>INERT. Written by configuration, read by nothing, and still not a limit</b> (§14.4, and
     * re-checked §24 — this is stated here so the field cannot be mistaken for a control again).
     *
     * <p>It stays rather than being removed because it is persisted inside {@link Task} and stores
     * written by older builds carry it. Enforcing it needs a token count over an assembled prompt,
     * and the only two things that could then be done at this boundary both cost something real: to
     * TRIM a worker's prompt would break the byte-identical prefix every worker of a group shares,
     * and so throw away the prefix cache the swarm's economics rest on; to REFUSE the dispatch
     * would stop runs that work today.
     *
     * <p>What IS enforced, and where, so this is not mistaken for the answer: the requirements
     * analyst refuses to start when its documents exceed the operator's character budget — a stated
     * number, with both ways out offered — and the requirement graph it is sent is capped at a fixed
     * number of requirements however large the project grows. Those were the two places an
     * unbounded prompt was actually being built.
     *
     * <p>Two of the other three fields are real: {@code maxTotalTokens} and {@code maxToolTurns} are
     * read by the worker loop.
     */
    private long maxPromptTokens;
    private long maxCompletionTokensPerTurn;
    private long maxTotalTokens;
    private int maxToolTurns;

    public TokenBudget() {}

    public TokenBudget(long maxPromptTokens, long maxCompletionTokensPerTurn, long maxTotalTokens, int maxToolTurns) {
        this.maxPromptTokens = maxPromptTokens;
        this.maxCompletionTokensPerTurn = maxCompletionTokensPerTurn;
        this.maxTotalTokens = maxTotalTokens;
        this.maxToolTurns = maxToolTurns;
    }

    public long maxPromptTokens() { return maxPromptTokens; }
    public long getMaxPromptTokens() { return maxPromptTokens; }
    public void setMaxPromptTokens(long maxPromptTokens) { this.maxPromptTokens = maxPromptTokens; }
    public long maxCompletionTokensPerTurn() { return maxCompletionTokensPerTurn; }
    public long getMaxCompletionTokensPerTurn() { return maxCompletionTokensPerTurn; }
    public void setMaxCompletionTokensPerTurn(long maxCompletionTokensPerTurn) { this.maxCompletionTokensPerTurn = maxCompletionTokensPerTurn; }
    public long maxTotalTokens() { return maxTotalTokens; }
    public long getMaxTotalTokens() { return maxTotalTokens; }
    public void setMaxTotalTokens(long maxTotalTokens) { this.maxTotalTokens = maxTotalTokens; }
    public int maxToolTurns() { return maxToolTurns; }
    public int getMaxToolTurns() { return maxToolTurns; }
    public void setMaxToolTurns(int maxToolTurns) { this.maxToolTurns = maxToolTurns; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TokenBudget that = (TokenBudget) o;
        return this.maxPromptTokens == that.maxPromptTokens && this.maxCompletionTokensPerTurn == that.maxCompletionTokensPerTurn && this.maxTotalTokens == that.maxTotalTokens && this.maxToolTurns == that.maxToolTurns;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxPromptTokens, maxCompletionTokensPerTurn, maxTotalTokens, maxToolTurns);
    }
}

