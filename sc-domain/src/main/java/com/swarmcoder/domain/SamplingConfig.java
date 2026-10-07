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

public class SamplingConfig {
    private String modelProfileId;
    private double temperature;
    private long seed;
    private String personaId;
    private String contextSliceId;

    public SamplingConfig() {}

    public SamplingConfig(String modelProfileId, double temperature, long seed, String personaId, String contextSliceId) {
        this.modelProfileId = modelProfileId;
        this.temperature = temperature;
        this.seed = seed;
        this.personaId = personaId;
        this.contextSliceId = contextSliceId;
    }

    public String modelProfileId() { return modelProfileId; }
    public String getModelProfileId() { return modelProfileId; }
    public void setModelProfileId(String modelProfileId) { this.modelProfileId = modelProfileId; }
    public double temperature() { return temperature; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public long seed() { return seed; }
    public long getSeed() { return seed; }
    public void setSeed(long seed) { this.seed = seed; }
    public String personaId() { return personaId; }
    public String getPersonaId() { return personaId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public String contextSliceId() { return contextSliceId; }
    public String getContextSliceId() { return contextSliceId; }
    public void setContextSliceId(String contextSliceId) { this.contextSliceId = contextSliceId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SamplingConfig that = (SamplingConfig) o;
        return Objects.equals(this.modelProfileId, that.modelProfileId) && this.temperature == that.temperature && this.seed == that.seed && Objects.equals(this.personaId, that.personaId) && Objects.equals(this.contextSliceId, that.contextSliceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelProfileId, temperature, seed, personaId, contextSliceId);
    }
}

