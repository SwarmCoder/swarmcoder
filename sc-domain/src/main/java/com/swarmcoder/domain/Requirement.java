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

public class Requirement {
    private UUID id;
    private String text;
    private Priority priority;

    public Requirement() {}

    public Requirement(UUID id, String text, Priority priority) {
        this.id = id;
        this.text = text;
        this.priority = priority;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String text() { return text; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Priority priority() { return priority; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Requirement that = (Requirement) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.text, that.text) && Objects.equals(this.priority, that.priority);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, text, priority);
    }
}

