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
 * What the model calls of one run, or of the selected project, came to per role: the figures of
 * {@code run_report} and {@code project_report}, as rows for the Console's tables. The server
 * works them out once for the text and for this, so the two cannot differ.
 *
 * <p>Table data is carried as tab/newline-delimited strings, as {@link InsightsDto} carries
 * its tables. A figure nothing recorded is the words "not recorded", never a 0.
 */
@DataModel
public class UsageTableDto implements BinaryPackable {

    /** What this is a table of, one line. */
    private String title;
    /** Said in place of the tables when there is nothing to show; "" otherwise. */
    private String note;
    /** The totals sentence: paid cloud and local apart, input and output apart. */
    private String totals;
    /** One warning a line, each beginning "WARNING:"; "" when there is none. */
    private String warnings;
    /**
     * Rows "role, server, calls, input tokens, cached input, output tokens, thinking tokens,
     * median input of a call, largest input of one call, what the largest call was for,
     * seconds in calls, calls without an answer", tab-separated, newline between rows.
     */
    private String roleRows;
    /**
     * Rows "role, kind of lookup, lookups, characters returned, repeats, characters of the
     * repeats, lookups about the reference material", tab-separated.
     */
    private String lookupRows;
    /** How much of the work was used, as sentences, one a line; "" when not known. */
    private String used;

    public UsageTableDto() { }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getTotals() { return totals; }
    public void setTotals(String v) { this.totals = v; }
    public String getWarnings() { return warnings; }
    public void setWarnings(String v) { this.warnings = v; }
    public String getRoleRows() { return roleRows; }
    public void setRoleRows(String v) { this.roleRows = v; }
    public String getLookupRows() { return lookupRows; }
    public void setLookupRows(String v) { this.lookupRows = v; }
    public String getUsed() { return used; }
    public void setUsed(String v) { this.used = v; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, title);
        BinarySerializer.writeString(buffer, note);
        BinarySerializer.writeString(buffer, totals);
        BinarySerializer.writeString(buffer, warnings);
        BinarySerializer.writeString(buffer, roleRows);
        BinarySerializer.writeString(buffer, lookupRows);
        BinarySerializer.writeString(buffer, used);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.title = BinarySerializer.readString(buffer);
        this.note = BinarySerializer.readString(buffer);
        this.totals = BinarySerializer.readString(buffer);
        this.warnings = BinarySerializer.readString(buffer);
        this.roleRows = BinarySerializer.readString(buffer);
        this.lookupRows = BinarySerializer.readString(buffer);
        this.used = BinarySerializer.readString(buffer);
    }
}
