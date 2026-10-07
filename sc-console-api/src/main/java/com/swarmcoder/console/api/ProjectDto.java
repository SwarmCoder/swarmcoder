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
package com.swarmcoder.console.api;

import com.zeroz4j.api.DataModel;
import com.zeroz4j.api.BinaryPackable;
import com.zeroz4j.api.BinarySerializer;

import java.nio.ByteBuffer;

/** A project for the Console switcher (multi-project). Context folders are a comma-separated list. */
@DataModel
public class ProjectDto implements BinaryPackable {

    private String projectId;
    private String name;
    private String primaryPath;
    private String contextPathsCsv;
    private boolean current;

    public ProjectDto() { }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPrimaryPath() { return primaryPath; }
    public void setPrimaryPath(String primaryPath) { this.primaryPath = primaryPath; }
    public String getContextPathsCsv() { return contextPathsCsv; }
    public void setContextPathsCsv(String contextPathsCsv) { this.contextPathsCsv = contextPathsCsv; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, projectId);
        BinarySerializer.writeString(buffer, name);
        BinarySerializer.writeString(buffer, primaryPath);
        BinarySerializer.writeString(buffer, contextPathsCsv);
        BinarySerializer.writeValue(buffer, current ? 1 : 0, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.projectId = BinarySerializer.readString(buffer);
        this.name = BinarySerializer.readString(buffer);
        this.primaryPath = BinarySerializer.readString(buffer);
        this.contextPathsCsv = BinarySerializer.readString(buffer);
        this.current = SessionSummaryDto.readInt(buffer, mapper) != 0;
    }
}


