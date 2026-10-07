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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ArchDecision {
    private UUID id;
    private String decision;
    private String rationale;
    private List<String> alternatives;

    public ArchDecision() {}

    public ArchDecision(UUID id, String decision, String rationale, List<String> alternatives) {
        this.id = id;
        this.decision = decision;
        this.rationale = rationale;
        this.alternatives = alternatives;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String decision() { return decision; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String rationale() { return rationale; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public List<String> alternatives() { return alternatives; }
    public List<String> getAlternatives() { return alternatives; }
    public void setAlternatives(List<String> alternatives) { this.alternatives = alternatives; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArchDecision that = (ArchDecision) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.decision, that.decision) && Objects.equals(this.rationale, that.rationale) && Objects.equals(this.alternatives, that.alternatives);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, decision, rationale, alternatives);
    }
}

