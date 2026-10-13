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
package com.swarmcoder.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one project has established, kept between starts of the application: the answers of its
 * deterministic lookups by their question, and what its roles concluded from them (stored in
 * {@code StoreRoot.established}, keyed by the project's id, schema v15; section 101).
 *
 * <p>The delivered stories' findings are not here: they stay in the designs they were delivered
 * with (section 97). Only the store's writer thread changes the two collections.
 *
 * <p>A mutable POJO because EclipseStore cannot persist records.
 */
public class ProjectEstablished {
    private UUID projectId;
    private Map<String, EstablishedAnswer> answers = new HashMap<>();
    private List<EstablishedAnswer> findings = new ArrayList<>();

    public ProjectEstablished() {}

    public ProjectEstablished(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID projectId() { return projectId; }

    public Map<String, EstablishedAnswer> answers() {
        if (answers == null) {
            answers = new HashMap<>();
        }
        return answers;
    }

    public List<EstablishedAnswer> findings() {
        if (findings == null) {
            findings = new ArrayList<>();
        }
        return findings;
    }
}
