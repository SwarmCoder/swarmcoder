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
 * One line of the requirements tree — enough to render a row and nothing more
 * (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §5).
 *
 * <p><b>Why a row and not the requirement.</b> The whole {@code Brd} used to travel on every edit:
 * a deep copy, a full serialisation, and a push to every client each time one title changed. At a few
 * thousand requirements that is megabytes per keystroke-level save. A row carries no statement text, no
 * criteria, no source refs and no change journal — those are fetched when a row is opened, which is
 * rare, instead of shipped constantly, which is not.
 *
 * <p><b>Own and subtree counts are both here and are never added together.</b> A coarse requirement
 * usually has no checks of its own; showing its subtree's figure as its own would claim it is directly
 * verified when nothing verifies it.
 *
 * <p><b>Booleans travel as ints</b> (0/1), following {@code RoleEntryDto.thinking}: the buffer helpers
 * read numbers, and a boxed Boolean would be one more thing to get wrong in a hand-rolled serializer
 * for no gain.
 */
@DataModel
public class RequirementRowDto implements BinaryPackable {

    private String requirementId;
    private String handle;
    private String title;
    /** {@code FUNCTIONAL}, {@code NON_FUNCTIONAL} or {@code CONSTRAINT} — the enum name. */
    private String kind;
    /** The NFR category, or "" for a functional requirement. */
    private String nfrCategory;
    /**
     * What the operator should see, which is the status the EVIDENCE supports — so a requirement whose
     * check regressed reads ACTIVE rather than still claiming IMPLEMENTED. Derived once on the server
     * from the same place the requirement's own badge derives it.
     */
    private String status;
    /** 0 for a top-level requirement, 1 for its parts, and so on. */
    private int depth;
    /** The requirement this is part of, or "" when it is top-level. */
    private String parentId;
    private CheckCountsDto own = new CheckCountsDto();
    private CheckCountsDto subtree = new CheckCountsDto();
    /** How many requirements sit beneath this one, at any depth. */
    private int descendants;
    /** Stories claiming this requirement's checks, as keys ({@code S3,S7}); "" when none do. */
    private String storyKeysCsv;
    /** This requirement's own accepted checks that no story has claimed. */
    private int unclaimedChecks;
    /**
     * What is wrong with this requirement's place in the hierarchy, in the operator's words, or "" when
     * nothing is. Computed on the server so the row and the graph say the same sentence.
     */
    private String shapeWarning;
    /**
     * 1 when this row is present only because a descendant matched the query — a parent shown so the
     * hierarchy stays intact (UX v3 rule 1: nothing vanishes). Rendered dimmed and counted separately,
     * never presented as a match.
     */
    private int contextOnly;
    /**
     * This requirement's relations other than the hierarchy, already in the operator's words and ready
     * to render: {@code "waits for R4;constrained by R12;conflicts with R6"}, semicolon-separated, ""
     * when it has none.
     *
     * <p><b>Phrased on the server, not the client.</b> The wording is the only place a relation's
     * meaning is stated, and {@code GATES} in particular reads differently from each end — the NFR
     * *constrains*, the requirement is *constrained by*. One edge, two sentences, and getting them the
     * wrong way round inverts what the gate means. Deciding that once, where the edge direction is
     * known, is safer than handing both ends to a renderer and hoping.
     *
     * <p>Semicolons because a phrase contains spaces and a handle contains none, so a comma-separated
     * list of multi-word phrases needs a parser; this needs a split.
     */
    private String relationsCsv;

    public RequirementRowDto() { }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getNfrCategory() { return nfrCategory; }
    public void setNfrCategory(String nfrCategory) { this.nfrCategory = nfrCategory; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public CheckCountsDto getOwn() { return own; }
    public void setOwn(CheckCountsDto own) { this.own = own == null ? new CheckCountsDto() : own; }
    public CheckCountsDto getSubtree() { return subtree; }
    public void setSubtree(CheckCountsDto subtree) {
        this.subtree = subtree == null ? new CheckCountsDto() : subtree;
    }
    public int getDescendants() { return descendants; }
    public void setDescendants(int descendants) { this.descendants = descendants; }
    public String getStoryKeysCsv() { return storyKeysCsv; }
    public void setStoryKeysCsv(String storyKeysCsv) { this.storyKeysCsv = storyKeysCsv; }
    public int getUnclaimedChecks() { return unclaimedChecks; }
    public void setUnclaimedChecks(int unclaimedChecks) { this.unclaimedChecks = unclaimedChecks; }
    public String getShapeWarning() { return shapeWarning; }
    public void setShapeWarning(String shapeWarning) { this.shapeWarning = shapeWarning; }
    public int getContextOnly() { return contextOnly; }
    public void setContextOnly(int contextOnly) { this.contextOnly = contextOnly; }
    public String getRelationsCsv() { return relationsCsv; }
    public void setRelationsCsv(String relationsCsv) { this.relationsCsv = relationsCsv; }

    public boolean isContextOnly() {
        return contextOnly == 1;
    }

    public boolean hasChildren() {
        return descendants > 0;
    }

    // Both methods below append in the SAME ORDER. A field added to one and not the other does not
    // fail to compile — it silently shifts every field after it, and the symptom is a panel showing
    // another field's value. Add to both, at the end, together.
    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer,
                              com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, requirementId);
        BinarySerializer.writeString(buffer, handle);
        BinarySerializer.writeString(buffer, title);
        BinarySerializer.writeString(buffer, kind);
        BinarySerializer.writeString(buffer, nfrCategory);
        BinarySerializer.writeString(buffer, status);
        BinarySerializer.writeValue(buffer, depth, mapper);
        BinarySerializer.writeString(buffer, parentId);
        own.writeToBuffer(buffer, mapper);
        subtree.writeToBuffer(buffer, mapper);
        BinarySerializer.writeValue(buffer, descendants, mapper);
        BinarySerializer.writeString(buffer, storyKeysCsv);
        BinarySerializer.writeValue(buffer, unclaimedChecks, mapper);
        BinarySerializer.writeString(buffer, shapeWarning);
        BinarySerializer.writeValue(buffer, contextOnly, mapper);
        BinarySerializer.writeString(buffer, relationsCsv);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.requirementId = BinarySerializer.readString(buffer);
        this.handle = BinarySerializer.readString(buffer);
        this.title = BinarySerializer.readString(buffer);
        this.kind = BinarySerializer.readString(buffer);
        this.nfrCategory = BinarySerializer.readString(buffer);
        this.status = BinarySerializer.readString(buffer);
        this.depth = SessionSummaryDto.readInt(buffer, mapper);
        this.parentId = BinarySerializer.readString(buffer);
        this.own = new CheckCountsDto();
        this.own.readFromBuffer(buffer, mapper);
        this.subtree = new CheckCountsDto();
        this.subtree.readFromBuffer(buffer, mapper);
        this.descendants = SessionSummaryDto.readInt(buffer, mapper);
        this.storyKeysCsv = BinarySerializer.readString(buffer);
        this.unclaimedChecks = SessionSummaryDto.readInt(buffer, mapper);
        this.shapeWarning = BinarySerializer.readString(buffer);
        this.contextOnly = SessionSummaryDto.readInt(buffer, mapper);
        this.relationsCsv = BinarySerializer.readString(buffer);
    }
}
