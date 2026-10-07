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
import java.util.UUID;

public class Risk {
    private UUID id;
    private String description;
    private Severity severity;
    private String mitigation;

    public Risk() {}

    public Risk(UUID id, String description, Severity severity, String mitigation) {
        this.id = id;
        this.description = description;
        this.severity = severity;
        this.mitigation = mitigation;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String description() { return description; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Severity severity() { return severity; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public String mitigation() { return mitigation; }
    public String getMitigation() { return mitigation; }
    public void setMitigation(String mitigation) { this.mitigation = mitigation; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Risk that = (Risk) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.description, that.description) && Objects.equals(this.severity, that.severity) && Objects.equals(this.mitigation, that.mitigation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, description, severity, mitigation);
    }
}

