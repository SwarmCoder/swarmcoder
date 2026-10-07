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

public class LintResults {
    private int errors;
    private int warnings;
    private List<String> messages;

    public LintResults() {}

    public LintResults(int errors, int warnings, List<String> messages) {
        this.errors = errors;
        this.warnings = warnings;
        this.messages = messages;
    }

    public int errors() { return errors; }
    public int getErrors() { return errors; }
    public void setErrors(int errors) { this.errors = errors; }
    public int warnings() { return warnings; }
    public int getWarnings() { return warnings; }
    public void setWarnings(int warnings) { this.warnings = warnings; }
    public List<String> messages() { return messages; }
    public List<String> getMessages() { return messages; }
    public void setMessages(List<String> messages) { this.messages = messages; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LintResults that = (LintResults) o;
        return this.errors == that.errors && this.warnings == that.warnings && Objects.equals(this.messages, that.messages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(errors, warnings, messages);
    }
}

