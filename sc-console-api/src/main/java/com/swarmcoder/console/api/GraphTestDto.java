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

import com.zeroz4j.api.BinaryPackable;
import com.zeroz4j.api.BinarySerializer;
import com.zeroz4j.api.DataModel;

import java.nio.ByteBuffer;

/**
 * One acceptance test written for a task, as the run graph carries it on every frame.
 *
 * <p>Its id, the file it is in, and the check it proves - the handle and the requirement's own
 * words, blank when no check names it. These four strings ride on the frame beside the task so
 * that the badge and the panel behind it cost no request; the file's source does not, and is
 * fetched only when the panel is opened ({@link GraphService#testSource}).
 */
@DataModel
public class GraphTestDto implements BinaryPackable {

    private String testRef = "";
    private String path = "";
    private String provesRef = "";
    private String provesText = "";

    public GraphTestDto() { }

    public String getTestRef() { return testRef; }
    public void setTestRef(String testRef) { this.testRef = testRef; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getProvesRef() { return provesRef; }
    public void setProvesRef(String provesRef) { this.provesRef = provesRef; }
    public String getProvesText() { return provesText; }
    public void setProvesText(String provesText) { this.provesText = provesText; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, testRef);
        BinarySerializer.writeString(buffer, path);
        BinarySerializer.writeString(buffer, provesRef);
        BinarySerializer.writeString(buffer, provesText);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.testRef = BinarySerializer.readString(buffer);
        this.path = BinarySerializer.readString(buffer);
        this.provesRef = BinarySerializer.readString(buffer);
        this.provesText = BinarySerializer.readString(buffer);
    }
}
