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

import com.zeroz4j.api.RmiService;
import com.zeroz4j.api.Secured;
import java.util.List;

/**
 * The run graph surface (design §6): {@code snapshot} assembles the current graph from the
 * store + live TraceHub; {@code watch} starts a server-side publisher that pushes fresh
 * snapshots on the {@code run-graph} topic whenever the run changes, until it parks.
 */
@RmiService
@Secured
public interface GraphService {

    RunGraphDto snapshot(String runId);

    /** Idempotent; a run that is already terminal publishes one final snapshot. */
    void watch(String runId);

    /** The run's PERSISTED sessions (replay lanes, design §6.5), ordered by openedAt. */
    List<SessionSummaryDto> runSessions(String runId);

    /**
     * The source of one acceptance test file the test author wrote for a task.
     *
     * <p>Fetched only when the operator opens a task's tests - the names and the checks they prove
     * ride on the frame ({@link GraphTaskDto#getTests}), the text does not. Only a path the task's
     * own record names is served, read from the project's checkout; anything else, and any file
     * that cannot be read, comes back as a sentence saying so rather than as an error.
     */
    String testSource(String runId, String taskId, String path);
}
