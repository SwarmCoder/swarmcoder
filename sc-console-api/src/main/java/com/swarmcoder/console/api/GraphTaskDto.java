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
import java.util.ArrayList;
import java.util.List;

/** One task node in the run graph (design §6.2). Dependencies are CSV of upstream task ids. */
@DataModel
public class GraphTaskDto implements BinaryPackable {

    /** Nothing is known about this task's tests: the stage has not reached it, or the run predates the record. */
    public static final String TESTS_UNKNOWN = "";
    /** The test author has this task right now. */
    public static final String TESTS_WRITING = "WRITING";
    /** The task's tests are on disk and were read back - see {@link #getTestsWritten}. */
    public static final String TESTS_WRITTEN = "WRITTEN";

    private String taskId;
    private String title;
    private String state;
    private String writeSetCsv;
    private String dependsOnCsv;
    private int criteria;
    private String requirementIdsCsv; // CSV of requirement ids this task satisfies
    /**
     * The stable label of the {@code Story} this task belongs to - S1, S2, ... - or "" when it
     * belongs to none.
     *
     * <p>Everything else a task node needs was already on this frame; the story was the one thing
     * that was not, and it is the piece that says what the task is FOR. A task title is written for
     * a worker ("Add shared Book model"); the story is the work item a person recognises, and the
     * one that survives a retry. Two short strings on a frame that is already a few KB, so a hover
     * reads them off the frame it already holds rather than costing a fetch per node.
     */
    private String storyKey = "";
    /** The title of that story; "" when the task belongs to none. */
    private String storyTitle = "";
    /**
     * Where the test author is with this task: one of {@link #TESTS_UNKNOWN},
     * {@link #TESTS_WRITING}, {@link #TESTS_WRITTEN}.
     *
     * <p>This and the five fields after it are what put a "3 tests, 2 checks" badge on a task box
     * while the test-authoring stage is still running. They ride on the frame like everything
     * else here: the counts and the names cost no request, and a badge that appears the moment a
     * task's tests land is the proof of life the stage never had. The file's source is the one
     * thing NOT here — it is fetched when the badge is clicked.
     */
    private String testsPhase = TESTS_UNKNOWN;
    /** How many checks the author was handed for this task; 0 for a task that claims none. */
    private int checksClaimed;
    /** How many tests were read back out of the files the author wrote. */
    private int testsWritten;
    /** How many distinct checks one of those tests proves. */
    private int checksProved;
    /** How many checks point at a test nobody wrote - the findings that park the run. */
    private int testProblems;
    /** Repo-relative paths of the files the author wrote, CSV; "" when none. */
    private String testFilesCsv = "";
    /** The tests themselves, one per test method, in file order. */
    private List<GraphTestDto> tests = new ArrayList<>();

    public GraphTaskDto() { }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getWriteSetCsv() { return writeSetCsv; }
    public void setWriteSetCsv(String writeSetCsv) { this.writeSetCsv = writeSetCsv; }
    public String getDependsOnCsv() { return dependsOnCsv; }
    public void setDependsOnCsv(String dependsOnCsv) { this.dependsOnCsv = dependsOnCsv; }
    public int getCriteria() { return criteria; }
    public void setCriteria(int criteria) { this.criteria = criteria; }
    public String getRequirementIdsCsv() { return requirementIdsCsv; }
    public void setRequirementIdsCsv(String requirementIdsCsv) { this.requirementIdsCsv = requirementIdsCsv; }
    public String getStoryKey() { return storyKey; }
    public void setStoryKey(String storyKey) { this.storyKey = storyKey; }
    public String getStoryTitle() { return storyTitle; }
    public void setStoryTitle(String storyTitle) { this.storyTitle = storyTitle; }
    public String getTestsPhase() { return testsPhase; }
    public void setTestsPhase(String testsPhase) { this.testsPhase = testsPhase; }
    public int getChecksClaimed() { return checksClaimed; }
    public void setChecksClaimed(int checksClaimed) { this.checksClaimed = checksClaimed; }
    public int getTestsWritten() { return testsWritten; }
    public void setTestsWritten(int testsWritten) { this.testsWritten = testsWritten; }
    public int getChecksProved() { return checksProved; }
    public void setChecksProved(int checksProved) { this.checksProved = checksProved; }
    public int getTestProblems() { return testProblems; }
    public void setTestProblems(int testProblems) { this.testProblems = testProblems; }
    public String getTestFilesCsv() { return testFilesCsv; }
    public void setTestFilesCsv(String testFilesCsv) { this.testFilesCsv = testFilesCsv; }
    public List<GraphTestDto> getTests() { return tests; }
    public void setTests(List<GraphTestDto> tests) { this.tests = tests; }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeString(buffer, taskId);
        BinarySerializer.writeString(buffer, title);
        BinarySerializer.writeString(buffer, state);
        BinarySerializer.writeString(buffer, writeSetCsv);
        BinarySerializer.writeString(buffer, dependsOnCsv);
        BinarySerializer.writeValue(buffer, criteria, mapper);
        BinarySerializer.writeString(buffer, requirementIdsCsv);
        BinarySerializer.writeString(buffer, storyKey);
        BinarySerializer.writeString(buffer, storyTitle);
        BinarySerializer.writeString(buffer, testsPhase);
        BinarySerializer.writeValue(buffer, checksClaimed, mapper);
        BinarySerializer.writeValue(buffer, testsWritten, mapper);
        BinarySerializer.writeValue(buffer, checksProved, mapper);
        BinarySerializer.writeValue(buffer, testProblems, mapper);
        BinarySerializer.writeString(buffer, testFilesCsv);
        List<GraphTestDto> written = tests == null ? List.of() : tests;
        BinarySerializer.writeValue(buffer, written.size(), mapper);
        for (GraphTestDto test : written) {
            test.writeToBuffer(buffer, mapper);
        }
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        this.taskId = BinarySerializer.readString(buffer);
        this.title = BinarySerializer.readString(buffer);
        this.state = BinarySerializer.readString(buffer);
        this.writeSetCsv = BinarySerializer.readString(buffer);
        this.dependsOnCsv = BinarySerializer.readString(buffer);
        this.criteria = SessionSummaryDto.readInt(buffer, mapper);
        this.requirementIdsCsv = BinarySerializer.readString(buffer);
        this.storyKey = BinarySerializer.readString(buffer);
        this.storyTitle = BinarySerializer.readString(buffer);
        this.testsPhase = BinarySerializer.readString(buffer);
        this.checksClaimed = SessionSummaryDto.readInt(buffer, mapper);
        this.testsWritten = SessionSummaryDto.readInt(buffer, mapper);
        this.checksProved = SessionSummaryDto.readInt(buffer, mapper);
        this.testProblems = SessionSummaryDto.readInt(buffer, mapper);
        this.testFilesCsv = BinarySerializer.readString(buffer);
        int count = SessionSummaryDto.readInt(buffer, mapper);
        this.tests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GraphTestDto test = new GraphTestDto();
            test.readFromBuffer(buffer, mapper);
            tests.add(test);
        }
    }
}
