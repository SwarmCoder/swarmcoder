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

public class FixSummary {
    private UUID id;
    private UUID taskId;
    private String rootCause;
    private String changeMade;
    private String guidelineCandidate;
    private UUID debugSessionId;

    public FixSummary() {}

    public FixSummary(UUID id, UUID taskId, String rootCause, String changeMade, String guidelineCandidate, UUID debugSessionId) {
        this.id = id;
        this.taskId = taskId;
        this.rootCause = rootCause;
        this.changeMade = changeMade;
        this.guidelineCandidate = guidelineCandidate;
        this.debugSessionId = debugSessionId;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID taskId() { return taskId; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public String rootCause() { return rootCause; }
    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }
    public String changeMade() { return changeMade; }
    public String getChangeMade() { return changeMade; }
    public void setChangeMade(String changeMade) { this.changeMade = changeMade; }
    public String guidelineCandidate() { return guidelineCandidate; }
    public String getGuidelineCandidate() { return guidelineCandidate; }
    public void setGuidelineCandidate(String guidelineCandidate) { this.guidelineCandidate = guidelineCandidate; }
    public UUID debugSessionId() { return debugSessionId; }
    public UUID getDebugSessionId() { return debugSessionId; }
    public void setDebugSessionId(UUID debugSessionId) { this.debugSessionId = debugSessionId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FixSummary that = (FixSummary) o;
        return Objects.equals(this.id, that.id) && Objects.equals(this.taskId, that.taskId) && Objects.equals(this.rootCause, that.rootCause) && Objects.equals(this.changeMade, that.changeMade) && Objects.equals(this.guidelineCandidate, that.guidelineCandidate) && Objects.equals(this.debugSessionId, that.debugSessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, taskId, rootCause, changeMade, guidelineCandidate, debugSessionId);
    }
}

