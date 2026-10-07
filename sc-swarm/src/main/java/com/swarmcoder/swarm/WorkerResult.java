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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.inference.ContextDeath;

import java.nio.file.Path;

/**
 * @param contextDeath when the worker stopped because its conversation no longer fitted the room it
 *                     had, what that looked like — the one ending {@link
 *                     com.swarmcoder.inference.AdaptiveConcurrency} counts. Null for every other
 *                     ending, and null on every result built before this existed.
 */
public record WorkerResult(CandidateSolution candidate, Path workspace, ContextDeath contextDeath) {

    public WorkerResult(CandidateSolution candidate, Path workspace) {
        this(candidate, workspace, null);
    }

    public WorkerResult withCandidate(CandidateSolution updated) {
        return new WorkerResult(updated, workspace, contextDeath);
    }
}
