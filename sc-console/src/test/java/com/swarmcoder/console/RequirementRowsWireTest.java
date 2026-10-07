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
package com.swarmcoder.console;

import com.swarmcoder.console.api.BrdVersion;
import com.swarmcoder.console.api.CheckCountsDto;
import com.swarmcoder.console.api.RequirementPageDto;
import com.swarmcoder.console.api.RequirementQuery;
import com.swarmcoder.console.api.RequirementRowDto;
import com.zeroz4j.api.BinaryRegistry;
import com.zeroz4j.api.BinarySerializer;
import com.zeroz4j.api.GrowableBuffer;
import com.zeroz4j.api.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paged-requirements wire types survive a real round trip through the binary serializer.
 *
 * <p>{@code WireContractTest} proves these types are annotated, which is a different guarantee: it
 * catches a MISSING serializer, not a wrong one. Every type here writes and reads its fields by hand,
 * and a field appended to one method and not the other still compiles — it shifts every field after it,
 * so the symptom is a panel showing a neighbouring field's value rather than a crash. Only reading the
 * bytes back and comparing every field catches that, which is what this does.
 *
 * <p>Values below are deliberately all DIFFERENT from each other. Equal values would let a transposition
 * pass unnoticed, which is the exact defect being hunted.
 */
class RequirementRowsWireTest {

    @BeforeAll
    static void loadRegistrars() {
        BinaryRegistry.init();
    }

    @Test
    void checkCountsRoundTripWithEveryFieldDistinct() {
        CheckCountsDto counts = new CheckCountsDto();
        counts.setPassing(1);
        counts.setFailing(2);
        counts.setUnverified(3);
        counts.setStale(4);
        counts.setProposed(5);

        CheckCountsDto back = roundTrip(counts);

        assertThat(back.getPassing()).isEqualTo(1);
        assertThat(back.getFailing()).isEqualTo(2);
        assertThat(back.getUnverified()).isEqualTo(3);
        assertThat(back.getStale()).isEqualTo(4);
        assertThat(back.getProposed()).isEqualTo(5);
        assertThat(back.gating()).isEqualTo(10);
    }

    @Test
    void aRowRoundTripsWithBothNestedCountsAndEveryScalar() {
        RequirementRowDto row = new RequirementRowDto();
        row.setRequirementId("11111111-1111-1111-1111-111111111111");
        row.setHandle("R7");
        row.setTitle("Guests can check out");
        row.setKind("NON_FUNCTIONAL");
        row.setNfrCategory("PERFORMANCE");
        row.setStatus("IMPLEMENTED");
        row.setDepth(3);
        row.setParentId("22222222-2222-2222-2222-222222222222");
        CheckCountsDto own = new CheckCountsDto();
        own.setPassing(6);
        own.setFailing(7);
        row.setOwn(own);
        CheckCountsDto subtree = new CheckCountsDto();
        subtree.setPassing(8);
        subtree.setStale(9);
        row.setSubtree(subtree);
        row.setDescendants(11);
        row.setStoryKeysCsv("S3,S9");
        row.setUnclaimedChecks(12);
        row.setShapeWarning("A requirement belongs in one place.");
        row.setContextOnly(1);

        RequirementRowDto back = roundTrip(row);

        assertThat(back.getRequirementId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(back.getHandle()).isEqualTo("R7");
        assertThat(back.getTitle()).isEqualTo("Guests can check out");
        assertThat(back.getKind()).isEqualTo("NON_FUNCTIONAL");
        assertThat(back.getNfrCategory()).isEqualTo("PERFORMANCE");
        assertThat(back.getStatus()).isEqualTo("IMPLEMENTED");
        assertThat(back.getDepth()).isEqualTo(3);
        assertThat(back.getParentId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(back.getOwn().getPassing())
            .describedAs("the two nested counts must not be swapped with each other")
            .isEqualTo(6);
        assertThat(back.getOwn().getFailing()).isEqualTo(7);
        assertThat(back.getSubtree().getPassing()).isEqualTo(8);
        assertThat(back.getSubtree().getStale()).isEqualTo(9);
        assertThat(back.getDescendants()).isEqualTo(11);
        assertThat(back.getStoryKeysCsv()).isEqualTo("S3,S9");
        assertThat(back.getUnclaimedChecks()).isEqualTo(12);
        assertThat(back.getShapeWarning()).isEqualTo("A requirement belongs in one place.");
        assertThat(back.isContextOnly()).isTrue();
    }

    @Test
    void aRowWithNothingSetRoundTripsWithoutNullBlowingUp() {
        // The normal state of a freshly drafted requirement: no title, no NFR category, no parent.
        RequirementRowDto back = roundTrip(new RequirementRowDto());
        assertThat(back.getDepth()).isZero();
        assertThat(back.getOwn()).isNotNull();
        assertThat(back.getSubtree()).isNotNull();
        assertThat(back.isContextOnly()).isFalse();
        assertThat(back.hasChildren()).isFalse();
    }

    @Test
    void aPageRoundTripsItsRowsAndAllFourCounts() {
        RequirementPageDto page = new RequirementPageDto();
        List<RequirementRowDto> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RequirementRowDto row = new RequirementRowDto();
            row.setHandle("R" + (i + 1));
            row.setDepth(i);
            rows.add(row);
        }
        page.setRows(rows);
        page.setMatches(21);
        page.setContextParents(22);
        page.setExcluded(23);
        page.setTotal(24);
        page.setOffset(25);
        page.setHasMore(1);
        page.setRevision(9_000_000_001L);

        RequirementPageDto back = roundTrip(page);

        assertThat(back.getRows()).extracting(RequirementRowDto::getHandle)
            .containsExactly("R1", "R2", "R3");
        assertThat(back.getRows()).extracting(RequirementRowDto::getDepth).containsExactly(0, 1, 2);
        assertThat(back.getMatches()).isEqualTo(21);
        assertThat(back.getContextParents()).isEqualTo(22);
        assertThat(back.getExcluded()).isEqualTo(23);
        assertThat(back.getTotal()).isEqualTo(24);
        assertThat(back.getOffset()).isEqualTo(25);
        assertThat(back.moreFollow()).isTrue();
        assertThat(back.getRevision())
            .describedAs("a long, not an int - revisions outlive 2^31 edits only in theory, but the "
                + "read helper has to be readLong or the value silently truncates")
            .isEqualTo(9_000_000_001L);
    }

    @Test
    void anEmptyPageRoundTrips() {
        RequirementPageDto back = roundTrip(new RequirementPageDto());
        assertThat(back.getRows()).isEmpty();
        assertThat(back.getTotal()).isZero();
        assertThat(back.moreFollow()).isFalse();
    }

    @Test
    void aQueryRoundTripsEveryFilter() {
        RequirementQuery query = new RequirementQuery();
        query.setSearch("guest checkout");
        query.setStatusCsv("ACTIVE,IMPLEMENTED");
        query.setKindCsv("FUNCTIONAL");
        query.setNfrCategoryCsv("PERFORMANCE,SECURITY");
        query.setOnlyUnclaimed(1);
        query.setOnlyStale(1);
        query.setOnlyShapeWarnings(1);
        query.setClaimedByStory("S3");

        RequirementQuery back = roundTrip(query);

        assertThat(back.getSearch()).isEqualTo("guest checkout");
        assertThat(back.getStatusCsv()).isEqualTo("ACTIVE,IMPLEMENTED");
        assertThat(back.getKindCsv()).isEqualTo("FUNCTIONAL");
        assertThat(back.getNfrCategoryCsv()).isEqualTo("PERFORMANCE,SECURITY");
        assertThat(back.getOnlyUnclaimed()).isEqualTo(1);
        assertThat(back.getOnlyStale()).isEqualTo(1);
        assertThat(back.getOnlyShapeWarnings()).isEqualTo(1);
        assertThat(back.getClaimedByStory()).isEqualTo("S3");
        assertThat(back.isUnfiltered()).isFalse();
        assertThat(roundTrip(new RequirementQuery()).isUnfiltered())
            .describedAs("a blank query means the whole tree, and must survive the wire saying so")
            .isTrue();
    }

    @Test
    void theVersionSignalRoundTripsAndComparesByRevision() {
        BrdVersion version = new BrdVersion("33333333-3333-3333-3333-333333333333", 42);
        BrdVersion back = roundTrip(version);

        assertThat(back.getProjectId()).isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(back.getRevision()).isEqualTo(42);
        // equals matters more than usual here: the signal swallows a value equal to the one it holds,
        // so a version whose equals ignored the revision would broadcast the first change and then go
        // permanently silent.
        assertThat(back).isEqualTo(version);
        assertThat(new BrdVersion("p", 1)).isNotEqualTo(new BrdVersion("p", 2));
        assertThat(new BrdVersion("p", 1)).isNotEqualTo(new BrdVersion("q", 1));
        assertThat(new BrdVersion("p", 1)).hasSameHashCodeAs(new BrdVersion("p", 1));
    }

    /**
     * A row through its OWN {@code writeToBuffer}/{@code readFromBuffer} — the path a nested object
     * actually takes, and the only path that can catch a hand-rolled field transposition.
     *
     * <p>Discovered while mutation-checking this file: {@link #roundTrip} sends the object through
     * {@code BinarySerializer.writeValue}, which for a {@code @DataModel} type dispatches to the
     * <em>generated</em> serializer and never calls the hand-written methods at all. Transposing two
     * fields in {@code writeToBuffer} left every assertion in this class passing. The hand-written
     * methods are reached only when a parent calls them explicitly — {@code RequirementPageDto} does
     * that for each row — so they need their own test, which this is.
     */
    private static RequirementRowDto packedRoundTrip(RequirementRowDto row) {
        GrowableBuffer out = new GrowableBuffer();
        ObjectMapper mapper = new ObjectMapper();
        row.writeToBuffer(out, mapper);
        ByteBuffer in = ByteBuffer.wrap(out.toByteArray());
        RequirementRowDto back = new RequirementRowDto();
        back.readFromBuffer(in, new ObjectMapper());
        return back;
    }

    @Test
    void aRowSurvivesItsOwnHandWrittenSerializer() {
        // Every value distinct, so a transposition cannot hide behind two equal fields.
        RequirementRowDto row = new RequirementRowDto();
        row.setRequirementId("id-1");
        row.setHandle("R7");
        row.setTitle("title-2");
        row.setKind("NON_FUNCTIONAL");
        row.setNfrCategory("PERFORMANCE");
        row.setStatus("IMPLEMENTED");
        row.setDepth(3);
        row.setParentId("parent-4");
        CheckCountsDto own = new CheckCountsDto();
        own.setPassing(5);
        own.setFailing(6);
        own.setUnverified(7);
        own.setStale(8);
        own.setProposed(9);
        row.setOwn(own);
        CheckCountsDto subtree = new CheckCountsDto();
        subtree.setPassing(10);
        subtree.setFailing(11);
        subtree.setUnverified(12);
        subtree.setStale(13);
        subtree.setProposed(14);
        row.setSubtree(subtree);
        row.setDescendants(15);
        row.setStoryKeysCsv("S3,S9");
        row.setUnclaimedChecks(16);
        row.setShapeWarning("warning-17");
        row.setContextOnly(1);
        row.setRelationsCsv("waits for R4;conflicts with R6");

        RequirementRowDto back = packedRoundTrip(row);

        assertThat(back.getRequirementId()).isEqualTo("id-1");
        assertThat(back.getHandle()).isEqualTo("R7");
        assertThat(back.getTitle()).isEqualTo("title-2");
        assertThat(back.getKind()).isEqualTo("NON_FUNCTIONAL");
        assertThat(back.getNfrCategory()).isEqualTo("PERFORMANCE");
        assertThat(back.getStatus()).isEqualTo("IMPLEMENTED");
        assertThat(back.getDepth()).isEqualTo(3);
        assertThat(back.getParentId()).isEqualTo("parent-4");
        assertThat(back.getOwn().getPassing()).isEqualTo(5);
        assertThat(back.getOwn().getFailing()).isEqualTo(6);
        assertThat(back.getOwn().getUnverified()).isEqualTo(7);
        assertThat(back.getOwn().getStale()).isEqualTo(8);
        assertThat(back.getOwn().getProposed()).isEqualTo(9);
        assertThat(back.getSubtree().getPassing()).isEqualTo(10);
        assertThat(back.getSubtree().getFailing()).isEqualTo(11);
        assertThat(back.getSubtree().getUnverified()).isEqualTo(12);
        assertThat(back.getSubtree().getStale()).isEqualTo(13);
        assertThat(back.getSubtree().getProposed()).isEqualTo(14);
        assertThat(back.getDescendants())
            .describedAs("descendants and storyKeysCsv are adjacent - the classic transposition")
            .isEqualTo(15);
        assertThat(back.getStoryKeysCsv()).isEqualTo("S3,S9");
        assertThat(back.getUnclaimedChecks()).isEqualTo(16);
        assertThat(back.getShapeWarning()).isEqualTo("warning-17");
        assertThat(back.isContextOnly()).isTrue();
        assertThat(back.getRelationsCsv())
            .describedAs("appended LAST in both methods, which is the only safe place to add one")
            .isEqualTo("waits for R4;conflicts with R6");
    }

    @Test
    void aPageSurvivesItsOwnHandWrittenSerializerWithNestedRows() {
        // The page's hand-written method is what calls each row's, so this covers the composition.
        RequirementPageDto page = new RequirementPageDto();
        RequirementRowDto row = new RequirementRowDto();
        row.setHandle("R1");
        row.setDescendants(31);
        row.setStoryKeysCsv("S1");
        page.setRows(new ArrayList<>(List.of(row)));
        page.setMatches(41);
        page.setContextParents(42);
        page.setExcluded(43);
        page.setTotal(44);
        page.setOffset(45);
        page.setHasMore(1);
        page.setRevision(46L);

        GrowableBuffer out = new GrowableBuffer();
        page.writeToBuffer(out, new ObjectMapper());
        RequirementPageDto back = new RequirementPageDto();
        back.readFromBuffer(ByteBuffer.wrap(out.toByteArray()), new ObjectMapper());

        assertThat(back.getRows()).hasSize(1);
        assertThat(back.getRows().get(0).getHandle()).isEqualTo("R1");
        assertThat(back.getRows().get(0).getDescendants()).isEqualTo(31);
        assertThat(back.getRows().get(0).getStoryKeysCsv()).isEqualTo("S1");
        assertThat(back.getMatches()).isEqualTo(41);
        assertThat(back.getContextParents()).isEqualTo(42);
        assertThat(back.getExcluded()).isEqualTo(43);
        assertThat(back.getTotal()).isEqualTo(44);
        assertThat(back.getOffset()).isEqualTo(45);
        assertThat(back.moreFollow()).isTrue();
        assertThat(back.getRevision()).isEqualTo(46L);
    }

    /**
     * The top-level path: how an RMI return value travels. For a {@code @DataModel} type this uses the
     * GENERATED serializer, not the hand-written one — see {@link #packedRoundTrip}.
     */
    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) {
        GrowableBuffer out = new GrowableBuffer();
        BinarySerializer.writeValue(out, value, new ObjectMapper());
        ByteBuffer in = ByteBuffer.wrap(out.toByteArray());
        return (T) BinarySerializer.readValue(in, new ObjectMapper());
    }
}
