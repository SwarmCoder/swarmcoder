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

import com.swarmcoder.console.api.ProjectDto;
import com.swarmcoder.console.api.ProjectList;
import com.swarmcoder.console.api.ProjectSignals;
import com.swarmcoder.domain.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds and publishes the project registry onto {@link ProjectSignals#CURRENT} — the left rail's
 * only source of state.
 *
 * <p>Every {@link ProjectDto} is built fresh on each publish. That is the contract, not a habit: the
 * signal's {@code set()} dedups by {@code equals}, so republishing a value that compares equal to
 * the retained one is a silent no-op and the rail would sit there showing stale state with no error
 * anywhere. See the same note on {@code BacklogPublisher}.
 *
 * <p>A failed publish is LOGGED, never swallowed: the rail has no other source, so a silent failure
 * here is exactly the defect this class was written to remove.
 */
final class ProjectPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProjectPublisher.class);

    private ProjectPublisher() {}

    /** A fresh snapshot of the registry, with the current project flagged. */
    static ProjectList build() {
        ConsoleContext context = ConsoleContext.get();
        UUID current = context.currentProjectId();
        List<ProjectDto> dtos = new ArrayList<>();
        for (Project project : context.listProjects()) {
            ProjectDto dto = new ProjectDto();
            dto.setProjectId(project.id() == null ? null : project.id().toString());
            dto.setName(project.name());
            dto.setPrimaryPath(project.primaryPath());
            dto.setContextPathsCsv(project.contextPaths() == null
                ? "" : String.join(",", project.contextPaths()));
            dto.setCurrent(project.id() != null && project.id().equals(current));
            dtos.add(dto);
        }
        ProjectList list = new ProjectList(dtos);
        // Whether the operator has picked a project travels with the list, because the picker and
        // the rail are two views of one answer and they must not be able to disagree.
        list.setPublished(true);
        list.setChosen(context.projectChosen());
        list.setSuggestedProjectId(context.suggestedProjectId());
        return list;
    }

    /**
     * Publishes the registry. Never throws — a mutation must not fail because its notification
     * could not be sent — but a failure is logged at WARN with the cause, because an unpublished
     * registry means an empty rail.
     */
    static void publish() {
        try {
            ProjectSignals.CURRENT.set(build());
        } catch (Exception e) {
            log.warn("Could not publish the project list to the Console rail — "
                + "it will stay stale until the next change", e);
        }
    }
}
