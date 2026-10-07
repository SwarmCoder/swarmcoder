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
 * The registry of projects as the left rail renders it, carried whole on
 * {@link ProjectSignals#CURRENT}. One value, one signal: the rail needs the list AND which entry is
 * current together, and a single carrier is what keeps the two consistent.
 *
 * <p>It also carries whether the operator has CHOSEN the project they are in
 * ({@link #isChosen()}), and which row the picker should offer first when they have not
 * ({@link #getSuggestedProjectId()}). Those two are what stop the Console opening a project nobody
 * asked for. Deleting a project used to move the operator straight into whichever project happened
 * to be next in the list, leaving a full screen that looked exactly like the project they had just
 * destroyed — so the natural next move, believing the delete had failed, was to delete again.
 *
 * <p><b>No {@code equals}.</b> This is deliberate, not an omission. {@code ValueSignal.set()} dedups
 * by {@code equals}, and a value-equal carrier would make a republish a silent no-op — the failure
 * mode documented on {@link BacklogSignals} and {@code BacklogPublisher}. Identity equality means
 * every freshly built list published by {@code ProjectPublisher} always reaches the rail. Redrawing
 * is cheap here: the rail's {@code KeyedList} leaves same-key rows untouched.
 */
@DataModel
public class ProjectList implements BinaryPackable {

    private List<ProjectDto> projects = new ArrayList<>();
    private boolean chosen;
    private String suggestedProjectId = "";
    private boolean published;

    public ProjectList() { }

    public ProjectList(List<ProjectDto> projects) {
        this.projects = projects == null ? new ArrayList<>() : projects;
    }

    /**
     * The signal's initial value — what the rail shows before the server has published.
     *
     * <p>{@code chosen} is false here, which is correct and matters: before the server has said
     * anything, the browser has no basis for showing anybody a project.
     */
    public static ProjectList empty() {
        return new ProjectList(new ArrayList<>());
    }

    public List<ProjectDto> getProjects() { return projects; }

    public void setProjects(List<ProjectDto> projects) {
        this.projects = projects == null ? new ArrayList<>() : projects;
    }

    /**
     * True once the operator has said which project they are working in — by picking one, or by
     * creating one. False on start, and false again after any project is deleted.
     *
     * <p>While it is false the Console asks; it does not decide. See the project picker.
     */
    public boolean isChosen() { return chosen; }

    /**
     * True on any value the server actually built; false on the signal's initial value.
     *
     * <p>Needed because "no projects yet" and "the server has not said anything yet" are the same
     * empty list, and one of them is a screen telling the operator to create their first project.
     * Showing that for the half-second before the retained value arrives would be a lie, and a
     * clickable one.
     */
    public boolean isPublished() { return published; }

    public void setPublished(boolean published) { this.published = published; }

    public void setChosen(boolean chosen) { this.chosen = chosen; }

    /**
     * The project to offer first while nothing is chosen — where the operator was last time — or
     * empty for no suggestion at all.
     *
     * <p>Empty after a delete, on purpose. A suggestion is a remembered choice; the project that
     * happens to be left standing after a deletion is not one, and pre-selecting it would put the
     * operator one keypress away from opening something arbitrary at exactly the moment they are
     * least sure what just happened.
     */
    public String getSuggestedProjectId() {
        return suggestedProjectId == null ? "" : suggestedProjectId;
    }

    public void setSuggestedProjectId(String suggestedProjectId) {
        this.suggestedProjectId = suggestedProjectId == null ? "" : suggestedProjectId;
    }

    @Override
    public void writeToBuffer(com.zeroz4j.api.GrowableBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        BinarySerializer.writeValue(buffer, projects.size(), mapper);
        for (ProjectDto project : projects) {
            project.writeToBuffer(buffer, mapper);
        }
        BinarySerializer.writeValue(buffer, chosen ? 1 : 0, mapper);
        BinarySerializer.writeString(buffer, getSuggestedProjectId());
        BinarySerializer.writeValue(buffer, published ? 1 : 0, mapper);
    }

    @Override
    public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper) {
        int count = SessionSummaryDto.readInt(buffer, mapper);
        this.projects = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ProjectDto project = new ProjectDto();
            project.readFromBuffer(buffer, mapper);
            projects.add(project);
        }
        this.chosen = SessionSummaryDto.readInt(buffer, mapper) != 0;
        this.suggestedProjectId = BinarySerializer.readString(buffer);
        this.published = SessionSummaryDto.readInt(buffer, mapper) != 0;
    }
}
