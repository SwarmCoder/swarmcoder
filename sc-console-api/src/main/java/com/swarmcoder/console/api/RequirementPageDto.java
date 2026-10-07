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
import java.util.ArrayList;
import java.util.List;

/**
 * A window onto the requirements tree, plus the counts that keep the window honest
 * (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §3.7).
 *
 * <p><b>Why three counts and not one.</b> A filtered tree contains two kinds of row: requirements that
 * matched, and their ancestors, which are present so the hierarchy is not broken on screen. Reporting
 * their sum as "matches" would overstate the result; omitting the ancestors would break UX v3 rule 1.
 * And {@link #excluded} exists because a filter that matches nothing must not look like an empty
 * project — the operator is told how much is being hidden and can open it.
 *
 * <p>{@link #revision} is the {@code Brd} revision this page was built from. A client that receives a
 * higher one on {@link BrdSignals#VERSION} knows its rows are stale without being sent a new document.
 */
@DataModel
public class RequirementPageDto implements BinaryPackable {

    private List<RequirementRowDto> rows = new ArrayList<>();
    /** Requirements that matched the query. */
    private int matches;
    /** Ancestors included only to keep matches attached to their place in the tree. */
    private int contextParents;
    /** Requirements the query left out entirely. */
    private int excluded;
    /** Every requirement in the project, matched or not. */
    private int total;
    /** Where this window starts within the matched-plus-context sequence. */
    private int offset;
    /** 1 when more rows follow this window. */
    private int hasMore;
    /** The BRD revision these rows were built from. */
    private long revision;
    /**
     * Requirements held back because they are retired — out of scope, kept on the record.
     *
     * <p>Counted apart from {@link #excluded} because it answers a different question. Excluded means
     * "your filter did not match this"; retired means "this is not part of the project any more". A
     * list that silently dropped them would make retiring look exactly like deleting, which is the
     * one thing it is not (author decision 2026-08-28). Zero when the operator asked to see them.
     */
    private int retiredHidden;

    public RequirementPageDto() { }

    public List<RequirementRowDto> getRows() { return rows; }
    public void setRows(List<RequirementRowDto> rows) {
        this.rows = rows == null ? new ArrayList<>() : rows;
    }
    public int getMatches() { return matches; }
    public void setMatches(int matches) { this.matches = matches; }
    public int getContextParents() { return contextParents; }
    public void setContextParents(int contextParents) { this.contextParents = contextParents; }
    public int getExcluded() { return excluded; }
    public void setExcluded(int excluded) { this.excluded = excluded; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public int getHasMore() { return hasMore; }
    public void setHasMore(int hasMore) { this.hasMore = hasMore; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public int getRetiredHidden() { return retiredHidden; }
    public void setRetiredHidden(int retiredHidden) { this.retiredHidden = retiredHidden; }

    public boolean moreFollow() {
        return hasMore == 1;
    }

    // Append to BOTH methods, in the same order, at the end.
    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer,
                              com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeValue(buffer, rows.size(), mapper);
        for (RequirementRowDto row : rows) {
            row.writeToBuffer(buffer, mapper);
        }
        BinarySerializer.writeValue(buffer, matches, mapper);
        BinarySerializer.writeValue(buffer, contextParents, mapper);
        BinarySerializer.writeValue(buffer, excluded, mapper);
        BinarySerializer.writeValue(buffer, total, mapper);
        BinarySerializer.writeValue(buffer, offset, mapper);
        BinarySerializer.writeValue(buffer, hasMore, mapper);
        BinarySerializer.writeValue(buffer, revision, mapper);
        BinarySerializer.writeValue(buffer, retiredHidden, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        int rowCount = SessionSummaryDto.readInt(buffer, mapper);
        this.rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            RequirementRowDto row = new RequirementRowDto();
            row.readFromBuffer(buffer, mapper);
            this.rows.add(row);
        }
        this.matches = SessionSummaryDto.readInt(buffer, mapper);
        this.contextParents = SessionSummaryDto.readInt(buffer, mapper);
        this.excluded = SessionSummaryDto.readInt(buffer, mapper);
        this.total = SessionSummaryDto.readInt(buffer, mapper);
        this.offset = SessionSummaryDto.readInt(buffer, mapper);
        this.hasMore = SessionSummaryDto.readInt(buffer, mapper);
        this.revision = SessionSummaryDto.readLong(buffer, mapper);
        this.retiredHidden = SessionSummaryDto.readInt(buffer, mapper);
    }
}
