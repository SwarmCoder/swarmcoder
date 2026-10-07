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
 * What the operator is looking for in the requirements tree
 * (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §3.6).
 *
 * <p><b>An object rather than a parameter list</b> so that adding a filter later does not change the
 * {@code BrdService} signature — a signature change means a wire change, a client rebuild, and a
 * version skew window where an old client calls a method that no longer exists.
 *
 * <p><b>Multi-value filters are CSV, not collections.</b> Originally because the serializer had no
 * {@code Set} support; it has had one since ZeroZ Stack 0.4.0, so the remaining reason is the
 * smaller one — this class hand-writes its own {@code BinaryPackable} and a {@code List<String>} in
 * a hand-rolled serializer is more machinery than two commas deserve. Blank means "any", which is
 * the default for every filter — an empty query returns the whole tree.
 */
@DataModel
public class RequirementQuery implements BinaryPackable {

    /**
     * Free text matched against handle, title, statement text and check text, case-insensitively.
     * Blank matches everything.
     */
    private String search;
    /** {@code DRAFT,ACTIVE,IMPLEMENTED,DEPRECATED} — any subset. Blank means any. */
    private String statusCsv;
    /** {@code FUNCTIONAL,NON_FUNCTIONAL}. Blank means any. */
    private String kindCsv;
    /** NFR categories ({@code PERFORMANCE,SECURITY,…}). Blank means any. */
    private String nfrCategoryCsv;
    /** 1 to keep only requirements with accepted checks no story has claimed. */
    private int onlyUnclaimed;
    /** 1 to keep only requirements carrying evidence that went stale when the wording changed. */
    private int onlyStale;
    /** 1 to keep only requirements whose place in the hierarchy is malformed. */
    private int onlyShapeWarnings;
    /** A story key ({@code S3}); keeps only requirements that story claims checks of. Blank = any. */
    private String claimedByStory;
    /**
     * 1 to include requirements that have been retired — taken out of scope, kept on the record.
     *
     * <p>Zero by default, and that default is the whole point of retiring rather than deleting: a
     * retired requirement leaves the normal view and stops counting, but it is still there and one
     * click brings it back on screen. Asking for {@code DEPRECATED} in {@link #statusCsv} also shows
     * them, because a filter that named them and then hid them would be nonsense.
     */
    private int includeRetired;

    public RequirementQuery() { }

    public String getSearch() { return search; }
    public void setSearch(String search) { this.search = search; }
    public String getStatusCsv() { return statusCsv; }
    public void setStatusCsv(String statusCsv) { this.statusCsv = statusCsv; }
    public String getKindCsv() { return kindCsv; }
    public void setKindCsv(String kindCsv) { this.kindCsv = kindCsv; }
    public String getNfrCategoryCsv() { return nfrCategoryCsv; }
    public void setNfrCategoryCsv(String nfrCategoryCsv) { this.nfrCategoryCsv = nfrCategoryCsv; }
    public int getOnlyUnclaimed() { return onlyUnclaimed; }
    public void setOnlyUnclaimed(int onlyUnclaimed) { this.onlyUnclaimed = onlyUnclaimed; }
    public int getOnlyStale() { return onlyStale; }
    public void setOnlyStale(int onlyStale) { this.onlyStale = onlyStale; }
    public int getOnlyShapeWarnings() { return onlyShapeWarnings; }
    public void setOnlyShapeWarnings(int onlyShapeWarnings) {
        this.onlyShapeWarnings = onlyShapeWarnings;
    }
    public String getClaimedByStory() { return claimedByStory; }
    public void setClaimedByStory(String claimedByStory) { this.claimedByStory = claimedByStory; }
    public int getIncludeRetired() { return includeRetired; }
    public void setIncludeRetired(int includeRetired) { this.includeRetired = includeRetired; }

    /** True when nothing is being filtered, so the answer is the whole tree. */
    public boolean isUnfiltered() {
        return blank(search) && blank(statusCsv) && blank(kindCsv) && blank(nfrCategoryCsv)
            && onlyUnclaimed != 1 && onlyStale != 1 && onlyShapeWarnings != 1
            && blank(claimedByStory) && includeRetired != 1;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // Append to BOTH methods, in the same order, at the end.
    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer,
                              com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, search);
        BinarySerializer.writeString(buffer, statusCsv);
        BinarySerializer.writeString(buffer, kindCsv);
        BinarySerializer.writeString(buffer, nfrCategoryCsv);
        BinarySerializer.writeValue(buffer, onlyUnclaimed, mapper);
        BinarySerializer.writeValue(buffer, onlyStale, mapper);
        BinarySerializer.writeValue(buffer, onlyShapeWarnings, mapper);
        BinarySerializer.writeString(buffer, claimedByStory);
        BinarySerializer.writeValue(buffer, includeRetired, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.search = BinarySerializer.readString(buffer);
        this.statusCsv = BinarySerializer.readString(buffer);
        this.kindCsv = BinarySerializer.readString(buffer);
        this.nfrCategoryCsv = BinarySerializer.readString(buffer);
        this.onlyUnclaimed = SessionSummaryDto.readInt(buffer, mapper);
        this.onlyStale = SessionSummaryDto.readInt(buffer, mapper);
        this.onlyShapeWarnings = SessionSummaryDto.readInt(buffer, mapper);
        this.claimedByStory = BinarySerializer.readString(buffer);
        this.includeRetired = SessionSummaryDto.readInt(buffer, mapper);
    }
}
