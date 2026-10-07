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

@DataModel
public class GuidelineDto implements BinaryPackable {

    private String id;
    private String scope;
    private String slug;
    /** A short human name for the rule; empty on one that never had one. */
    private String title;
    private String body;
    private String status;
    private String source;
    /** The document this rule was stated in; empty for a learned or hand-written one. */
    private String document;
    private double confidence;
    /** The one-line command that proves the rule; empty when it is advice only. */
    private String checkCommand;
    /** The check's timeout; 0 means the default. */
    private int checkTimeoutSeconds;

    public GuidelineDto() { }

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getScope() { return scope; }
    public void setScope(String v) { this.scope = v; }
    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getDocument() { return document; }
    public void setDocument(String v) { this.document = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { this.confidence = v; }
    public String getCheckCommand() { return checkCommand; }
    public void setCheckCommand(String v) { this.checkCommand = v; }
    public int getCheckTimeoutSeconds() { return checkTimeoutSeconds; }
    public void setCheckTimeoutSeconds(int v) { this.checkTimeoutSeconds = v; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, id);
        BinarySerializer.writeString(buffer, scope);
        BinarySerializer.writeString(buffer, slug);
        BinarySerializer.writeString(buffer, title);
        BinarySerializer.writeString(buffer, body);
        BinarySerializer.writeString(buffer, status);
        BinarySerializer.writeString(buffer, source);
        BinarySerializer.writeString(buffer, document);
        BinarySerializer.writeValue(buffer, confidence, mapper);
        BinarySerializer.writeString(buffer, checkCommand);
        BinarySerializer.writeValue(buffer, checkTimeoutSeconds, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.id = BinarySerializer.readString(buffer);
        this.scope = BinarySerializer.readString(buffer);
        this.slug = BinarySerializer.readString(buffer);
        this.title = BinarySerializer.readString(buffer);
        this.body = BinarySerializer.readString(buffer);
        this.status = BinarySerializer.readString(buffer);
        this.source = BinarySerializer.readString(buffer);
        this.document = BinarySerializer.readString(buffer);
        Object c = BinarySerializer.readValue(buffer, mapper);
        this.confidence = c instanceof Number ? ((Number) c).doubleValue() : 0.0;
        this.checkCommand = BinarySerializer.readString(buffer);
        Object t = BinarySerializer.readValue(buffer, mapper);
        this.checkTimeoutSeconds = t instanceof Number ? ((Number) t).intValue() : 0;
    }
}


